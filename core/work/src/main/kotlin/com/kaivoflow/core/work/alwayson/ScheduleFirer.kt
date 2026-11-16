package com.kaivoflow.core.work.alwayson

import com.kaivoflow.core.data.ExpectedRunState
import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.ScheduleRepository
import com.kaivoflow.core.engine.alwayson.DedupeKeys
import com.kaivoflow.core.engine.alwayson.EnqueueResult
import com.kaivoflow.core.engine.alwayson.Lateness
import com.kaivoflow.core.engine.alwayson.NewSpark
import com.kaivoflow.core.engine.alwayson.PrecisionPolicy
import com.kaivoflow.core.engine.alwayson.SlotMath
import com.kaivoflow.core.engine.alwayson.SparkInbox
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FireResult {
    /** The slot's Inbox row was written. [duplicate] means it already existed (alarm plus watchdog both fired). */
    data class Enqueued(val duplicate: Boolean, val nextSlotMs: Long?) : FireResult
    /** The Flow or Spark is gone, paused or disabled; its schedule was disarmed. */
    data object Ignored : FireResult
}

/**
 * The fire path of a schedule slot (spec 3.9), shared by the alarm receiver and the Flexible worker:
 *
 * 1. compute the next slot with pure `SlotMath`, **arm it first** and persist `nextDueAt`;
 * 2. insert the Inbox row with key `schedule:<flowId>:<slotEpochMs>` (a duplicate is a no-op);
 * 3. mark `expected_run` Fired or Late with the real time and lateness;
 * 4. wake the consumer.
 *
 * Steps 1 to 3 run under the schedule lock, so a concurrent Reconciler pass cannot interleave.
 */
@Singleton
class ScheduleFirer @Inject constructor(
    private val flows: FlowRepository,
    private val lifecycle: FlowLifecycleRepository,
    private val scheduleRepo: ScheduleRepository,
    private val sync: ScheduleSync,
    private val alarms: AlarmScheduler,
    private val inbox: SparkInbox,
    private val controller: EngineController,
) {
    suspend fun fire(flowId: String, sparkId: String, slotMs: Long?, source: IntakeSource): FireResult {
        val now = sync.now()
        val flow = flows.flow(flowId)
        val live = flow != null && (lifecycle.snapshot(flowId)?.state?.isLive ?: flow.isActive)
        val spark = flow?.let { f -> sync.scheduleSparks(f).firstOrNull { it.id == sparkId } }
        if (flow == null || !live || spark == null) {
            sync.disarmFlowSpark(flowId, sparkId)
            return FireResult.Ignored
        }

        val result = sync.locked {
            val d = sync.desiredFor(flowId, spark)
            val state = scheduleRepo.state(flowId, sparkId)
            val slot = slotMs?.takeIf { it > 0 } ?: state?.nextDueAt ?: now
            val eff = PrecisionPolicy.effective(d.requested, alarms.canScheduleExact())

            // 1. Next slot first. A stale fire (the schedule was already armed past this slot) never moves it backwards.
            val alreadyAhead = state != null && state.configHash == d.configHash && (state.nextDueAt ?: 0L) > slot
            var next: Long? = state?.nextDueAt?.takeIf { alreadyAhead }
            if (!alreadyAhead) {
                val computed = SlotMath.nextSlotAfter(d.config, maxOf(now, slot), eff.mode, anchorMs = slot)
                sync.armOne(d, computed, eff.mode, eff.degradedFrom, supersedePending = false, prev = state, spark = spark, now = now)
                next = computed
            }

            // 2. Inbox row, idempotent by slot.
            val stored = inbox.enqueue(NewSpark(flowId, sparkId, "scheduled", null, DedupeKeys.schedule(flowId, slot)))

            // 3. Evidence: Fired or Late, with the actual lateness.
            val row = scheduleRepo.expected(flowId, sparkId, slot.toString())
            if (row != null && (row.state == ExpectedRunState.PENDING || row.state == ExpectedRunState.MISSED)) {
                val c = Lateness.classify(eff.mode, slot, now)
                scheduleRepo.markExpected(row.id, c.state, now, row.runId, c.latenessMs, row.reason)
            }
            scheduleRepo.state(flowId, sparkId)?.let {
                scheduleRepo.saveState(it.copy(lastFiredAt = now, lastSlotKey = slot.toString()))
            }
            FireResult.Enqueued(duplicate = stored is EnqueueResult.Duplicate, nextSlotMs = next)
        }
        // 4. Wake the consumer (no-op when the row was a duplicate of one already queued).
        if (result is FireResult.Enqueued && !result.duplicate) controller.onInboxWritten(source)
        return result
    }
}
