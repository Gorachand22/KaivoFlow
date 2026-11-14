package com.kaivoflow.core.work

import android.content.Context
import com.kaivoflow.core.data.CredentialRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.RunRepository
import com.kaivoflow.core.engine.ActiveRunRegistry
import com.kaivoflow.core.engine.ExecutionMode
import com.kaivoflow.core.engine.FlowInvoker
import com.kaivoflow.core.engine.FlowRunner
import com.kaivoflow.core.engine.KaivoException
import com.kaivoflow.core.engine.NodeRegistry
import com.kaivoflow.core.engine.RunOptions
import com.kaivoflow.core.engine.ValidationReport
import com.kaivoflow.core.expressions.FallbackExpressionEvaluator
import com.kaivoflow.core.expressions.QuickJsExpressionEvaluator
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.RunEvent
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunTrigger
import com.kaivoflow.core.model.StepRun
import com.kaivoflow.core.network.KtorHttpGateway
import com.kaivoflow.core.nodes.NodeCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext

/**
 * The app's single entry point for executing a Flow.
 *
 * Responsibilities: wire the engine to real Android dependencies, keep one Run per Flow at a time,
 * persist the RunRecord, and expose live events so the canvas can animate the Golden Current.
 * Everything else (routing, retries, item flow) belongs to `:core:engine`.
 */
@Singleton
class FlowEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flowRepository: FlowRepository,
    private val runRepository: RunRepository,
    private val credentials: CredentialRepository,
    private val http: KtorHttpGateway,
    private val deviceGateway: AndroidDeviceGateway,
    private val notifications: RunNotifications,
) {
    private val expressionEvaluator: com.kaivoflow.core.engine.ExpressionEvaluator by lazy {
        // QuickJS if it loads, built-in evaluator if the device refuses the native library.
        runCatching { QuickJsExpressionEvaluator() }
            .getOrElse { FallbackExpressionEvaluator() }
    }

    private val registry: NodeRegistry by lazy { NodeCatalog.registry(deviceGateway) }

    /**
     * Original Sub-Flow composition: a Step can run another saved Flow and receive its terminal
     * items. Nested Runs are deliberately not persisted as top-level history or notifications.
     */
    private val subFlowInvoker: FlowInvoker = object : FlowInvoker {
        private val maxDepth = 8

        override suspend fun invoke(flowId: String, items: List<FlowData>, depth: Int): List<FlowData> {
            if (depth > maxDepth) {
                throw KaivoException("Sub-Flows are nested too deeply. Break the loop before running again.")
            }
            val flow = flowRepository.flow(flowId)
                ?: throw KaivoException("The Flow this Step points to no longer exists ($flowId).")
            val record = runner.run(
                flow = flow,
                trigger = RunTrigger(kind = "subflow", items = items.toImmutableList()),
                options = RunOptions(mode = ExecutionMode.Production, ignoreFrozen = true, subFlowDepth = depth),
            )
            if (record.status == RunStatus.Failed || record.status == RunStatus.Canceled) {
                throw KaivoException("Sub-Flow \"${flow.name}\" failed: ${record.error ?: "the Run was stopped"}")
            }
            return record.stepRuns
                .lastOrNull { it.status == StepRunStatus.Succeeded && it.output.isNotEmpty() }
                ?.output
                ?.toList()
                ?: emptyList()
        }
    }

    val runner: FlowRunner by lazy {
        FlowRunner(
            registry = registry,
            evaluator = expressionEvaluator,
            http = http,
            credentials = credentials,
            flowInvoker = subFlowInvoker,
        )
    }

    private val activeRuns = ActiveRunRegistry()
    private val runLock = Mutex()

    private val _events = MutableStateFlow<RunEvent?>(null)
    val events: StateFlow<RunEvent?> = _events.asStateFlow()

    fun validate(flow: Flow): ValidationReport = runner.validate(flow)

    fun isRunning(flowId: String): Boolean = activeRuns.isOwned(flowId)

    /**
     * Runs a Flow and stores the result.
     *
     * @param onEvent receives engine events (used by the canvas for live animation, and by the
     *                foreground notification to show the current Step).
     */
    suspend fun run(
        flow: Flow,
        trigger: RunTrigger = RunTrigger(),
        mode: ExecutionMode = ExecutionMode.Production,
        onEvent: (RunEvent) -> Unit = {},
    ): RunRecord {
        // Own the actual calling job atomically; do not use a detached placeholder job.
        val job = currentCoroutineContext()[Job] ?: error("A Run requires a coroutine Job")
        if (!activeRuns.tryAcquire(flow.id, job)) {
            return RunRecord(
                id = "skipped-${System.currentTimeMillis()}",
                flowId = flow.id,
                flowName = flow.name,
                status = com.kaivoflow.core.model.RunStatus.Canceled,
                startedAt = System.currentTimeMillis(),
                finishedAt = System.currentTimeMillis(),
                error = "This Flow is already running.",
                trigger = trigger.kind,
            )
        }


        return try {
            val wrapped: (RunEvent) -> Unit = { event ->
                _events.value = event
                notifications.onRunEvent(flow, event)
                onEvent(event)
            }
            val record = runner.run(flow, trigger, RunOptions(mode = mode), wrapped)
            persist(flow, record)
            record
        } catch (e: CancellationException) {
            throw e
        } finally {
            activeRuns.release(flow.id, job)
            notifications.clear(flow.id)
        }
    }

    /** "Test Step" from the inspector: runs exactly one Step, persists nothing. */
    suspend fun testStep(flow: Flow, stepId: String, input: List<com.kaivoflow.core.model.FlowData>): StepRun =
        runner.testStep(flow, stepId, input) { _events.value = it }

    fun cancel(flowId: String) {
        activeRuns.cancel(flowId)
    }

    private suspend fun persist(flow: Flow, record: RunRecord) {
        runLock.withLock {
            val shouldSave = when (record.status) {
                com.kaivoflow.core.model.RunStatus.Succeeded, com.kaivoflow.core.model.RunStatus.PartialSuccess ->
                    flow.settings.saveSuccessfulRuns
                else -> flow.settings.saveFailedRuns
            }
            if (shouldSave) runRepository.save(record)
            flowRepository.recordRun(flow.id, record.status, record.startedAt)
        }
    }

    /** Loads a Flow and runs it — the entry point used by WorkManager and by the webhook server. */
    suspend fun runById(
        flowId: String,
        trigger: RunTrigger = RunTrigger(kind = "scheduled"),
        mode: ExecutionMode = ExecutionMode.Production,
        onEvent: (RunEvent) -> Unit = {},
    ): RunRecord? {
        val flow = flowRepository.flow(flowId) ?: return null
        return run(flow, trigger, mode, onEvent = onEvent)
    }
}
