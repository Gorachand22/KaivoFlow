package com.kaivoflow.core.work.alwayson

import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.ExpectedRunState
import com.kaivoflow.core.data.ScheduleRepository
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
        return RunOutcome.Completed(result.id, result.status.name)
    }
}

/** Keeps `expected_run` and spilled payload files in step with what the dispatcher does. */
@Singleton
class DispatchEvidence @Inject constructor(
    private val schedules: ScheduleRepository,
    private val inbox: SparkInbox,
    private val recorder: ReliabilityRecorder,
    private val clock: Clock,
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
        if (outcome != RunOutcome.Busy) inbox.discardPayload(record)
    }

    override suspend fun onDropped(record: InboxRecord, reason: String) {
        recorder.record("InboxRowDropped", Severity.INFO, record.flowId, reason)
    }

    override suspend fun onInterrupted(count: Int) {
        recorder.record(ReliabilityKinds.INBOX_RUN_INTERRUPTED, Severity.WARN, detail = "runs=$count")
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
