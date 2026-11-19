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
import com.kaivoflow.core.engine.RunRetention
import com.kaivoflow.core.engine.stepdata.ChannelStepExecutionRecorder
import com.kaivoflow.core.engine.stepdata.PinStore
import com.kaivoflow.core.engine.stepdata.StepDataPolicy
import com.kaivoflow.core.engine.stepdata.StepDataRedactor
import com.kaivoflow.core.engine.stepdata.StepDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val stepData: StepDataStore,
    private val pinStore: PinStore,
) {
    // Step data capture: redacted, limited and written off the Run's thread (bounded channel).
    private val captureRecorder: ChannelStepExecutionRecorder by lazy {
        ChannelStepExecutionRecorder(
            sink = stepData,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            policy = StepDataPolicy(redactor = StepDataRedactor()),
        )
    }

    private val expressionEvaluator: com.kaivoflow.core.engine.ExpressionEvaluator by lazy {
        // KaivoFlow's own pure-Kotlin expression engine (safe navigation, whitelisted library, limits).
        KaivoExpressionEvaluator()
    }

    private val registry: NodeRegistry by lazy { NodeCatalog.registry(deviceGateway) }

    suspend fun resolveEffectiveFlow(flow: Flow): Flow {
        val scopedVars = runCatching { variableRepository.resolveVariables(flow.groupId) }.getOrNull().orEmpty()
        val merged: Map<String, Any?> = if (scopedVars.isNotEmpty()) scopedVars + flow.vars else flow.vars
        // Spec P3: variable values may be expressions; they are evaluated once per Run, here, against the global scope.
        val resolved = com.kaivoflow.core.engine.VariableResolution.resolve(
            raw = merged,
            evaluator = expressionEvaluator,
            flowId = flow.id,
            flowName = flow.name,
            timezone = flow.settings.timezone,
        )
        return if (scopedVars.isNotEmpty() || resolved.vars !== merged) {
            flow.copy(vars = resolved.vars.toImmutableMap())
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
            // Spec P3: non-secret credential fields may be expressions; they are evaluated per call, in the vault wrapper.
            credentials = com.kaivoflow.core.engine.ExprCredentialProvider(
                delegate = credentials,
                evaluator = expressionEvaluator,
                vars = { variableRepository.resolveVariables(null) },
            ),
            flowInvoker = subFlowInvoker,
            recorder = captureRecorder,
            pins = pinStore,
        )
    }

    private val activeRuns = ActiveRunRegistry()
    private val runLock = Mutex()

    private val _events = MutableStateFlow<RunEvent?>(null)
    val events: StateFlow<RunEvent?> = _events.asStateFlow()

    fun validate(flow: Flow): ValidationReport = runner.validate(flow)

    fun isRunning(flowId: String): Boolean = activeRuns.isOwned(flowId)

    /** Manual, test and Sub-Flow starts are never deferred; every other trigger kind comes from the Spark Inbox. */
    private fun isInboxTrigger(trigger: RunTrigger) = trigger.kind.lowercase() !in setOf("manual", "test", "subflow")

    /**
     * Runs a Flow and stores the result.
     *
     * Event ordering is the Spark Inbox's job (`RunDispatcher`): it starts at most one Run per Flow, in arrival order, so this
     * method no longer keeps an in-memory queue. If the Flow is busy and the trigger came from the Inbox, a `Queued` record is
     * returned and nothing is stored; the dispatcher then releases the row and retries after a back-off.
     *
     * @param onStarted called with the Run id once the Run exists and its Running record is persisted, before any Step runs.
     *                  The Inbox records it so a Run that began is never replayed after a crash.
     * @param onEvent last, so a trailing lambda still means "events" (used by the canvas for live animation).
     */
    suspend fun run(
        flow: Flow,
        trigger: RunTrigger = RunTrigger(),
        mode: ExecutionMode = ExecutionMode.Production,
        onStarted: suspend (runId: String) -> Unit = {},
        onEvent: (RunEvent) -> Unit = {},
    ): RunRecord = runOnce(flow, trigger, mode, onEvent, null, onStarted)

    private suspend fun runOnce(
        flow: Flow,
        trigger: RunTrigger,
        mode: ExecutionMode,
        onEvent: (RunEvent) -> Unit,
        notice: String?,
        onStarted: suspend (String) -> Unit,
    ): RunRecord {
        // Own the actual calling job atomically; do not use a detached placeholder job.
        val job = currentCoroutineContext()[Job] ?: error("A Run requires a coroutine Job")
        if (!activeRuns.tryAcquire(flow.id, job)) {
            val now = System.currentTimeMillis()
            if (isInboxTrigger(trigger)) {
                return RunRecord(
                    id = "busy-$now", flowId = flow.id, flowName = flow.name,
                    status = RunStatus.Queued, startedAt = now, finishedAt = now,
                    error = "This Flow is already running. The event stays in the Inbox and runs after the current Run.",
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
                    onStarted(started.id)
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
        runCatching { RunRetention.pruneStepData(stepData) }
    }

    /**
     * "Test Step" from the inspector: runs exactly one Step on [input]. Never runs another Step, never writes a Run record,
     * never notifies and never touches the Always-On engine; the only thing stored is the Step's data, marked as a test.
     * Cancelling the calling coroutine cancels the Step.
     */
    suspend fun testStep(
        flow: Flow,
        stepId: String,
        input: List<com.kaivoflow.core.model.FlowData>,
        upstream: Map<String, Map<Int, List<com.kaivoflow.core.model.FlowData>>> = emptyMap(),
    ): StepRun {
        val effectiveFlow = resolveEffectiveFlow(flow)
        return runner.testStep(effectiveFlow, stepId, input, upstream) { _events.value = it }
    }

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
        pruneHistory()
    }

    /** Loads a Flow and runs it — the entry point used by WorkManager and by the webhook server. */
    suspend fun runById(
        flowId: String,
        trigger: RunTrigger = RunTrigger(kind = "scheduled"),
        mode: ExecutionMode = ExecutionMode.Production,
        onStarted: suspend (runId: String) -> Unit = {},
        onEvent: (RunEvent) -> Unit = {},
    ): RunRecord? {
        val flow = flowRepository.flow(flowId) ?: return null
        return run(flow, trigger, mode, onEvent = onEvent, onStarted = onStarted)
    }

    /** Runs that started before this singleton existed belong to a previous process. */
    private val processStartMs = System.currentTimeMillis()

    private companion object { const val RECOVERY_SCAN_LIMIT = 2000 }
}
