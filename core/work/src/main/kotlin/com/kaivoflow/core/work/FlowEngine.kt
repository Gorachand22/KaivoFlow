package com.kaivoflow.core.work

import android.content.Context
import com.kaivoflow.core.data.CredentialRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.RunRepository
import com.kaivoflow.core.data.WorkflowVariableRepository
import com.kaivoflow.core.engine.ActiveRunRegistry
import com.kaivoflow.core.engine.ExecutionMode
import com.kaivoflow.core.engine.FlowInvoker
import com.kaivoflow.core.engine.FlowRunner
import com.kaivoflow.core.engine.KaivoException
import com.kaivoflow.core.engine.NodeRegistry
import com.kaivoflow.core.engine.RunOptions
import com.kaivoflow.core.engine.ValidationReport
import com.kaivoflow.core.expressions.KaivoExpressionEvaluator
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
import com.kaivoflow.core.engine.PerFlowEventQueue
import com.kaivoflow.core.engine.RunRetention
import com.kaivoflow.core.model.LogLevel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
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
    private val variableRepository: WorkflowVariableRepository,
) {
    private val expressionEvaluator: com.kaivoflow.core.engine.ExpressionEvaluator by lazy {
        // KaivoFlow's own pure-Kotlin expression engine (safe navigation, whitelisted library, limits).
        KaivoExpressionEvaluator()
    }

    private val registry: NodeRegistry by lazy { NodeCatalog.registry(deviceGateway) }

    suspend fun resolveEffectiveFlow(flow: Flow): Flow {
        val scopedVars = runCatching { variableRepository.resolveVariables(flow.groupId) }.getOrNull().orEmpty()
        return if (scopedVars.isNotEmpty()) {
            flow.copy(vars = (scopedVars + flow.vars).toImmutableMap())
        } else {
            flow
        }
    }

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
            val effective = resolveEffectiveFlow(flow)
            val record = runner.run(
                flow = effective,
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

    private val eventQueue = PerFlowEventQueue<RunTrigger>(PerFlowEventQueue.DEFAULT_MAX_SIZE)

    /** Manual, test and Sub-Flow starts are never queued; only event-driven triggers are. */
    private fun isQueueable(trigger: RunTrigger) = trigger.kind.lowercase() !in setOf("manual", "test", "subflow")

    /**
     * Runs a Flow and stores the result. If the Flow is busy and the trigger is an event, the event
     * is queued (bounded FIFO) and runs after the current Run, in order.
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
        val first = runOnce(flow, trigger, mode, onEvent, null)
        if (first.status == RunStatus.Queued) return first
        var last = first
        while (currentCoroutineContext().isActive) {
            val next = eventQueue.poll(flow.id) ?: break
            val latest = flowRepository.flow(flow.id) ?: break
            if (!latest.isActive) { eventQueue.clear(flow.id); break }
            val dropped = eventQueue.takeDroppedCount(flow.id)
            val notice = if (dropped > 0) "$dropped older queued event(s) were dropped because the queue was full." else null
            val record = runOnce(latest, next, mode, {}, notice)
            if (record.status == RunStatus.Queued) { // another Run took over and will drain the queue
                eventQueue.offer(flow.id, next)
                break
            }
            last = record
        }
        // Report the Run the caller asked for, not a later queued one.
        return first
    }

    private suspend fun runOnce(
        flow: Flow,
        trigger: RunTrigger,
        mode: ExecutionMode,
        onEvent: (RunEvent) -> Unit,
        notice: String?,
    ): RunRecord {
        // Own the actual calling job atomically; do not use a detached placeholder job.
        val job = currentCoroutineContext()[Job] ?: error("A Run requires a coroutine Job")
        if (!activeRuns.tryAcquire(flow.id, job)) {
            val now = System.currentTimeMillis()
            if (isQueueable(trigger)) {
                eventQueue.offer(flow.id, trigger)
                return RunRecord(
                    id = "queued-$now", flowId = flow.id, flowName = flow.name,
                    status = RunStatus.Queued, startedAt = now, finishedAt = now,
                    error = "This Flow is already running. The event was queued (${eventQueue.size(flow.id)} waiting).",
                    trigger = trigger.kind,
                )
            }
            return RunRecord(
                id = "skipped-$now",
                flowId = flow.id,
                flowName = flow.name,
                status = RunStatus.Canceled,
                startedAt = now,
                finishedAt = now,
                error = "This Flow is already running.",
                trigger = trigger.kind,
            )
        }

        var startedId: String? = null
        var startedAt = 0L
        var finalSaved = false
        try {
            val effectiveFlow = resolveEffectiveFlow(flow)
            val wrapped: (RunEvent) -> Unit = { event ->
                _events.value = event
                notifications.onRunEvent(effectiveFlow, event)
                onEvent(event)
                if (event is RunEvent.RunStarted && notice != null) {
                    val log = RunEvent.Log(event.runId, notice, LogLevel.Warn)
                    _events.value = log
                    onEvent(log)
                }
            }
            val effectiveTrigger = if (notice == null) trigger else
                trigger.copy(meta = (trigger.meta + ("droppedEvents" to notice)).toImmutableMap())
            val record = try {
                runner.run(effectiveFlow, effectiveTrigger, RunOptions(mode = mode), wrapped) { started ->
                    startedId = started.id
                    startedAt = started.startedAt
                    persistStart(effectiveFlow, started)
                }
            } catch (e: CancellationException) {
                // Killed before the runner could return a record: still leave a Canceled one behind.
                val id = startedId
                if (id != null) {
                    withContext(NonCancellable) {
                        persist(flow, RunRecord(id = id, flowId = flow.id, flowName = flow.name,
                            status = RunStatus.Canceled, startedAt = startedAt, finishedAt = System.currentTimeMillis(),
                            trigger = trigger.kind, error = "Run canceled.", triggerData = trigger.meta))
                    }
                    finalSaved = true
                }
                throw e
            }
            // The runner returns a Canceled record on cancellation; save it even though we are cancelled.
            withContext(NonCancellable) { persist(effectiveFlow, record) }
            finalSaved = true
            return record
        } finally {
            activeRuns.release(flow.id, job)
            notifications.clear(flow.id)
            if (!finalSaved) startedId?.let { id ->
                // Crash path: never leave a Running record behind in this process.
                withContext(NonCancellable) {
                    runCatching {
                        persist(flow, RunRecord(id = id, flowId = flow.id, flowName = flow.name,
                            status = RunStatus.Canceled, startedAt = startedAt, finishedAt = System.currentTimeMillis(),
                            trigger = trigger.kind, error = RunRetention.INTERRUPTED_MESSAGE, triggerData = trigger.meta))
                    }
                }
            }
        }
    }

    private suspend fun persistStart(flow: Flow, record: RunRecord) {
        // If either history setting is off, a Running row could never be cleaned up. Skip it then.
        if (!(flow.settings.saveSuccessfulRuns && flow.settings.saveFailedRuns)) return
        runLock.withLock { runRepository.save(record) }
    }

    /**
     * Housekeeping for app start: Runs still marked Running belong to a dead process. Mark them
     * Canceled (the model has no separate Interrupted status) with an explanatory error, then prune.
     */
    suspend fun recoverAndPrune() {
        runCatching {
            val now = System.currentTimeMillis()
            runRepository.observeRecent(RECOVERY_SCAN_LIMIT).first()
                .filter { (it.status == RunStatus.Running || it.status == RunStatus.Queued) && it.startedAt < processStartMs }
                .forEach {
                    runRepository.save(it.copy(status = RunStatus.Canceled, finishedAt = now, error = RunRetention.INTERRUPTED_MESSAGE))
                }
        }
        pruneHistory()
    }

    /** Age limit via the repository; the per-Flow count cap is applied by [pruneHistory]. */
    suspend fun pruneHistory() {
        runCatching { runRepository.pruneOlderThan(RunRetention.MAX_AGE_DAYS) }
    }

    /** "Test Step" from the inspector: runs exactly one Step, persists nothing. */
    suspend fun testStep(flow: Flow, stepId: String, input: List<com.kaivoflow.core.model.FlowData>): StepRun {
        val effectiveFlow = resolveEffectiveFlow(flow)
        return runner.testStep(effectiveFlow, stepId, input) { _events.value = it }
    }

    fun cancel(flowId: String) {
        eventQueue.clear(flowId) // a user stop also discards events waiting behind the Run
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
        pruneHistory()
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

    /** Runs that started before this singleton existed belong to a previous process. */
    private val processStartMs = System.currentTimeMillis()

    private companion object { const val RECOVERY_SCAN_LIMIT = 2000 }
}
