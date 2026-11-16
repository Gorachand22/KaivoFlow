package com.kaivoflow.core.work.alwayson

import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.ExpectedRunState
import com.kaivoflow.core.data.RunRepository
import com.kaivoflow.core.data.ScheduleRepository
import com.kaivoflow.core.engine.RunRetention
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.engine.alwayson.CrashedClaim
import com.kaivoflow.core.engine.alwayson.GateResult
import com.kaivoflow.core.engine.alwayson.ResponseGate
import com.kaivoflow.core.engine.ExecutionMode
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.DedupeKeys
import com.kaivoflow.core.engine.alwayson.DispatchListener
import com.kaivoflow.core.engine.alwayson.DispatcherConfig
import com.kaivoflow.core.engine.alwayson.InboxRecord
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.RunDispatcher
import com.kaivoflow.core.engine.alwayson.RunExecutor
import com.kaivoflow.core.engine.alwayson.RunOutcome
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.SparkInbox
import com.kaivoflow.core.engine.alwayson.Lateness
import com.kaivoflow.core.engine.alwayson.PrecisionMode
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.RunTrigger
import com.kaivoflow.core.work.FlowEngine
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Runs an Inbox row through [FlowEngine] under a Run wake lock. The dispatcher decides when; this decides how. */
@Singleton
class AndroidRunExecutor @Inject constructor(
    private val flowEngine: FlowEngine,
    private val flows: FlowRepository,
    private val lifecycle: FlowLifecycleRepository,
    private val inbox: SparkInbox,
    private val wakeLocks: WakeLockManager,
) : RunExecutor {

    override suspend fun execute(record: InboxRecord, onStarted: suspend (String) -> Unit): RunOutcome {
        val flow = flows.flow(record.flowId) ?: return RunOutcome.Dropped("FlowMissing")
        val live = lifecycle.snapshot(flow.id)?.state?.isLive ?: flow.isActive
        if (!live) return RunOutcome.Dropped("FlowNotLive")
        if (flowEngine.isRunning(flow.id)) return RunOutcome.Busy

        val decoded = InboxPayloads.decode(inbox.payloadOf(record))
        val trigger = RunTrigger(
            kind = record.kind,
            startAtStepId = record.sparkId.ifBlank { null },
            items = decoded.items.toImmutableList(),
            meta = decoded.meta.toImmutableMap(),
        )
        val result = wakeLocks.withRunLock("${flow.id}:${record.id}", flow.settings.executionTimeoutMs) {
            flowEngine.run(flow, trigger, ExecutionMode.Production, onStarted = onStarted)
        }
        // The engine reports Queued when another Run took the Flow between the check above and the start.
        if (result.status == RunStatus.Queued) return RunOutcome.Busy
        // Only a webhook that asked to wait for the result gets its output serialised; nothing else ever leaves the Run record.
        val response = if (decoded.meta["respond"] == RESPOND_WAIT) responseJson(result) else null
        return RunOutcome.Completed(result.id, result.status.name, response)
    }

    /** The last Step that produced items, as the caller of a wait-for-run webhook receives it: one item is an object, several an array. */
    private fun responseJson(record: com.kaivoflow.core.model.RunRecord): String? {
        val items = record.stepRuns.lastOrNull { it.status == com.kaivoflow.core.model.StepRunStatus.Succeeded && it.output.isNotEmpty() }
            ?.output ?: return null
        return Values.stringify(if (items.size == 1) items[0].json else items.map { it.json })
    }

    companion object { const val RESPOND_WAIT = "wait" }
}

/**
 * Keeps `expected_run`, spilled payload files, webhook callers that wait for a result, and the record of crashed Runs in step with
 * what the dispatcher does.
 */
@Singleton
class DispatchEvidence @Inject constructor(
    private val schedules: ScheduleRepository,
    private val inbox: SparkInbox,
    private val recorder: ReliabilityRecorder,
    private val clock: Clock,
    private val gate: ResponseGate,
    private val runs: RunRepository,
) : DispatchListener {

    override suspend fun onStarted(record: InboxRecord, runId: String) {
        val (flowId, slot) = DedupeKeys.parseSchedule(record.dedupeKey) ?: return
        val row = schedules.expected(flowId, record.sparkId, slot.toString()) ?: return
        val firedAt = row.firedAt ?: clock.now()
        val lateness = row.latenessMs ?: (firedAt - row.dueAt).coerceAtLeast(0)
        val state = when (row.state) {
            // A caught-up slot ran late by definition.
            ExpectedRunState.MISSED -> ExpectedRunState.LATE
            ExpectedRunState.PENDING -> Lateness.classify(PrecisionMode.Exact, row.dueAt, firedAt).state
            else -> row.state
        }
        schedules.markExpected(row.id, state, firedAt, runId, lateness, row.reason)
    }

    override suspend fun onFinished(record: InboxRecord, outcome: RunOutcome?) {
        if (outcome == RunOutcome.Busy) return // released for a retry: the row is not finished
        inbox.discardPayload(record)
        if (record.kind != InboxWebhookSink.KIND) return
        gate.complete(
            record.id,
            when (outcome) {
                is RunOutcome.Completed -> GateResult.Finished(outcome.status, outcome.runId, outcome.responseJson)
                is RunOutcome.Dropped -> GateResult.Dropped(outcome.reason)
                else -> GateResult.Dropped("ExecutorError")
            },
        )
    }

    override suspend fun onDropped(record: InboxRecord, reason: String) {
        recorder.record("InboxRowDropped", Severity.INFO, record.flowId, reason)
    }

    /**
     * The lease of a started Run expired with no process: the Run is recorded as Crashed and never replayed (spec 3.1 rule 5, L12).
     * The model has no Crashed status, so the Run record becomes Failed with an error that starts with `Crashed:`
     * ([RunCrash.isCrashMessage]); it also replaces the generic "Interrupted" note the app-start sweep may have written first.
     */
    override suspend fun onCrashed(claims: List<CrashedClaim>) {
        for (claim in claims) {
            val record = claim.record
            recorder.record(ReliabilityKinds.RUN_CRASHED, Severity.ERROR, record.flowId, "run=${claim.runId} row=${record.id}")
            claim.runId?.let { id -> closeRunRecord(id, claim) }
            inbox.discardPayload(record)
            if (record.kind == InboxWebhookSink.KIND) gate.complete(record.id, GateResult.Crashed(claim.runId))
        }
    }

    private suspend fun closeRunRecord(runId: String, claim: CrashedClaim) {
        runCatching {
            val run = runs.run(runId) ?: return
            val open = run.status == RunStatus.Running || run.status == RunStatus.Queued
            val sweptAsInterrupted = run.status == RunStatus.Canceled && run.error == RunRetention.INTERRUPTED_MESSAGE
            if (open || sweptAsInterrupted) {
                runs.save(run.copy(status = RunStatus.Failed, finishedAt = clock.now(), error = claim.message()))
            }
        }
    }
}

/**
 * Owns the process-wide [RunDispatcher] and its loop. The same dispatcher serves the foreground service and the fallback
 * worker, so per-Flow ordering holds whichever of them is draining (spec 3.11).
 */
@Singleton
class RunHost @Inject constructor(
    executor: AndroidRunExecutor,
    store: com.kaivoflow.core.engine.alwayson.InboxStore,
    clock: Clock,
    evidence: DispatchEvidence,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var loop: Job? = null

    val dispatcher = RunDispatcher(store, executor, clock, scope, DispatcherConfig(), evidence)

    /** Starts the dispatcher loop (idempotent). */
    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { dispatcher.runLoop() }
        dispatcher.nudge()
    }

    /** Stops scanning. Runs already in flight finish on their own. */
    @Synchronized
    fun stop() { loop?.cancel(); loop = null }

    val isLooping: Boolean get() = loop?.isActive == true

    fun nudge() = dispatcher.nudge()
}
