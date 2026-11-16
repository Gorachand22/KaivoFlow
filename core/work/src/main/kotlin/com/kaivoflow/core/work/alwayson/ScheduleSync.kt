package com.kaivoflow.core.work.alwayson

import com.kaivoflow.core.data.ExpectedRunEntity
import com.kaivoflow.core.data.ExpectedRunState
import com.kaivoflow.core.data.RegistrationState
import com.kaivoflow.core.data.ScheduleRepository
import com.kaivoflow.core.data.ScheduleStateEntity
import com.kaivoflow.core.data.TriggerRegistrationEntity
import com.kaivoflow.core.data.TriggerRegistrationRepository
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.DedupeKeys
import com.kaivoflow.core.engine.alwayson.DesiredSchedule
import com.kaivoflow.core.engine.alwayson.ElapsedSlot
import com.kaivoflow.core.engine.alwayson.Lateness
import com.kaivoflow.core.engine.alwayson.MisfirePlanner
import com.kaivoflow.core.engine.alwayson.MisfirePolicy
import com.kaivoflow.core.engine.alwayson.NewSpark
import com.kaivoflow.core.engine.alwayson.PrecisionMode
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.ScheduleSyncPlanner
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.SlotConfig
import com.kaivoflow.core.engine.alwayson.SlotMath
import com.kaivoflow.core.engine.alwayson.StoredSchedule
import com.kaivoflow.core.engine.alwayson.SparkInbox
import com.kaivoflow.core.engine.alwayson.SyncOp
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.nodes.TriggerSpecs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class SyncReport(val armed: Int = 0, val disarmed: Int = 0, val caughtUp: Int = 0, val skipped: Int = 0, val degraded: Int = 0) {
    operator fun plus(o: SyncReport) = SyncReport(armed + o.armed, disarmed + o.disarmed, caughtUp + o.caughtUp, skipped + o.skipped, degraded + o.degraded)
}

/**
 * Applies the pure [ScheduleSyncPlanner] to the real world: alarms, WorkManager, `schedule_state`, `expected_run` and the Inbox.
 * One mutex serialises the Reconciler pass and the alarm fire path, so a fire can never be overwritten by a stale plan.
 */
@Singleton
class ScheduleSync @Inject constructor(
    private val scheduleRepo: ScheduleRepository,
    private val registrations: TriggerRegistrationRepository,
    private val alarms: AlarmScheduler,
    private val flexible: FlexibleWork,
    private val inbox: SparkInbox,
    private val recorder: ReliabilityRecorder,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { block() }

    // ---- desired state ----

    fun desiredFor(flow: Flow): List<DesiredSchedule> =
        scheduleSparks(flow).map { desiredFor(flow.id, it) }

    fun scheduleSparks(flow: Flow): List<StepNode> = flow.triggers.filter { it.type == TriggerSpecs.SCHEDULE && !it.disabled }

    fun desiredFor(flowId: String, spark: StepNode): DesiredSchedule {
        val cfg = SlotConfig(
            frequency = spark.paramString("frequency", "daily"),
            time = spark.paramString("time", "08:00"),
            daysOfWeek = spark.paramString("daysOfWeek", ""),
            dayOfMonth = spark.paramNumber("dayOfMonth", 1.0).toInt(),
            intervalMinutes = spark.paramNumber("interval", 15.0),
            zone = zoneOf(spark.paramString("timezone", "")),
        )
        val requested = PrecisionMode.fromParam(spark.paramString("precision", ""))
        val misfire = MisfirePolicy.fromParam(spark.paramString("misfirePolicy", ""))
        val custom = spark.paramNumber("maxLatenessMinutes", -1.0)
        val lateness = if (custom > 0) (custom * 60_000).toLong()
        else MisfirePlanner.defaultMaxLatenessMs(cfg, SlotMath.intervalMs(cfg, requested))
        return DesiredSchedule(flowId, spark.id, cfg, requested, misfire, lateness, SlotMath.configHash(cfg, requested, misfire, lateness))
    }

    /** Blank or "device" follows the device zone (spec 3.9), which also removes the old hard-coded default. */
    internal fun zoneOf(text: String): ZoneId {
        val t = text.trim()
        if (t.isEmpty() || t.equals("device", true) || t.equals("system", true) || t.equals("follow device", true)) return ZoneId.systemDefault()
        return runCatching { ZoneId.of(t) }.getOrDefault(ZoneId.systemDefault())
    }

    // ---- passes ----

    /** The schedule half of a Reconciler pass for all live Flows. Idempotent. */
    suspend fun sync(live: List<Flow>, applyMisfire: Boolean, recompute: Boolean, now: Long): SyncReport = locked {
        val sparks = live.flatMap { f -> scheduleSparks(f).map { (f.id to it.id) to it } }.toMap()
        plan(live.flatMap { desiredFor(it) }, scheduleRepo.allStates(), sparks, now, applyMisfire, recompute)
    }

    /** Re-plans one Flow after it was edited, published or unpublished. A non-live Flow is disarmed. */
    suspend fun syncFlow(flow: Flow, live: Boolean, now: Long): SyncReport = locked {
        val desired = if (live) desiredFor(flow) else emptyList()
        val sparks = scheduleSparks(flow).associateBy { flow.id to it.id }
        val report = plan(desired, scheduleRepo.statesForFlow(flow.id), sparks, now, applyMisfire = false, recompute = false)
        if (!live) { flexible.cancelAllForFlow(flow.id) }
        report
    }

    /** Disarms one Spark whose Flow vanished, was paused or had the Spark removed. */
    suspend fun disarmFlowSpark(flowId: String, sparkId: String) = locked { disarm(flowId, sparkId) }

    suspend fun disarmFlow(flowId: String) = locked {
        flexible.cancelAllForFlow(flowId)
        for (s in scheduleRepo.statesForFlow(flowId)) disarm(s.flowId, s.sparkId)
    }

    private suspend fun plan(
        desired: List<DesiredSchedule>,
        states: List<ScheduleStateEntity>,
        sparks: Map<Pair<String, String>, StepNode>,
        now: Long,
        applyMisfire: Boolean,
        recompute: Boolean,
    ): SyncReport {
        val byKey = states.associateBy { it.flowId to it.sparkId }
        val stored = states.map { StoredSchedule(it.flowId, it.sparkId, it.configHash, it.nextDueAt, it.lastSlotKey?.toLongOrNull()) }
        val cutoff = now - Lateness.elapsedGraceMs(PrecisionMode.OnTime)
        val elapsed = scheduleRepo.pendingDueBefore(cutoff).mapNotNull { row ->
            val st = byKey[row.flowId to row.sparkId] ?: return@mapNotNull null
            val mode = PrecisionMode.fromDb(st.armedMode?.substringBefore(':'))
            if (row.dueAt + Lateness.elapsedGraceMs(mode) < now) ElapsedSlot(row.flowId, row.sparkId, row.dueAt) else null
        }
        val exact = alarms.canScheduleExact()
        // Stored Flexible schedules that wanted exact alarms can return to them once the permission is back: the planner
        // resolves the effective mode from `requested` and `exact`, so nothing extra is needed here.
        val ops = ScheduleSyncPlanner.plan(desired, stored, elapsed, now, exact, applyMisfire, recompute)
        var report = SyncReport()
        for (op in ops) report += when (op) {
            is SyncOp.Disarm -> { disarm(op.flowId, op.sparkId); SyncReport(disarmed = 1) }
            is SyncOp.Arm -> {
                val degraded = armOne(op.desired, op.slotMs, op.mode, op.degradedFrom, op.supersedePending, byKey[op.desired.flowId to op.desired.sparkId], sparks[op.desired.flowId to op.desired.sparkId], now)
                SyncReport(armed = 1, degraded = if (degraded) 1 else 0)
            }
            is SyncOp.CatchUp -> applyCatchUp(op, byKey[op.flowId to op.sparkId], now)
        }
        return report
    }

    // ---- operations ----

    /**
     * Arms one slot: records the expected run first (spec 6.3), sets the alarm or WorkManager job, then persists `schedule_state`.
     * Public for the fire path, which already holds the lock via [locked]. Returns true when the effective mode is degraded.
     */
    suspend fun armOne(
        d: DesiredSchedule,
        slotMs: Long,
        mode: PrecisionMode,
        degradedFrom: PrecisionMode?,
        supersedePending: Boolean,
        prev: ScheduleStateEntity?,
        spark: StepNode?,
        now: Long,
    ): Boolean {
        if (supersedePending) scheduleRepo.supersedePendingForSpark(d.flowId, d.sparkId, "Reconfigured")
        scheduleRepo.expect(
            ExpectedRunEntity(
                flowId = d.flowId, sparkId = d.sparkId, slotKey = slotMs.toString(), dueAt = slotMs, armedAt = now,
                state = ExpectedRunState.PENDING, firedAt = null, runId = null, latenessMs = null, reason = null,
            ),
        )
        var effective = mode
        var degraded = degradedFrom
        if (effective.usesAlarm) {
            when (val r = alarms.armSchedule(d.flowId, d.sparkId, slotMs, effective)) {
                ArmResult.Armed -> if (prev?.armedMode?.startsWith("FLEXIBLE") == true) flexible.cancel(d.flowId, d.sparkId)
                is ArmResult.Denied -> {
                    degraded = degraded ?: effective
                    effective = PrecisionMode.Flexible
                    recorder.record(ReliabilityKinds.EXACT_ALARM_DENIED, Severity.WARN, d.flowId, r.detail)
                }
            }
        }
        var armedMode = effective.db
        if (effective == PrecisionMode.Flexible) {
            alarms.disarmSchedule(d.flowId, d.sparkId)
            val prevFlex = prev?.armedMode?.takeIf { it.startsWith("FLEXIBLE") }
            val prevLetter = prevFlex?.substringAfter(':', "")?.ifBlank { null }
            val letter = flexible.arm(
                flowId = d.flowId, sparkId = d.sparkId, slotMs = slotMs, nowMs = now, currentLetter = prevLetter,
                sameSlotAlreadyArmed = prevFlex != null && prev?.nextDueAt == slotMs,
                wifiOnly = spark?.paramBoolean("runOnlyOnWifi") == true,
                batteryNotLow = spark?.paramBoolean("requireBatteryNotLow") == true,
            )
            armedMode = "${effective.db}:$letter"
        }
        scheduleRepo.saveState(
            ScheduleStateEntity(
                flowId = d.flowId, sparkId = d.sparkId, configHash = d.configHash, precision = d.requested.db,
                nextDueAt = slotMs, lastFiredAt = prev?.lastFiredAt, lastSlotKey = prev?.lastSlotKey, armedAt = now,
                armedMode = armedMode, missedPolicy = d.misfire.db,
                lastStatus = if (degraded != null) "DegradedExactAlarmDenied" else "Armed",
            ),
        )
        registrations.upsert(
            TriggerRegistrationEntity(
                flowId = d.flowId, sparkId = d.sparkId, kind = TriggerSpecs.SCHEDULE, state = RegistrationState.ARMED,
                detail = if (degraded != null) "Flexible (exact alarms not allowed)" else effective.db,
                since = prevSince(d.flowId, d.sparkId, now), retryCount = 0, nextRetryAt = null,
            ),
        )
        return degraded != null
    }

    private suspend fun prevSince(flowId: String, sparkId: String, now: Long): Long =
        registrations.forFlow(flowId).firstOrNull { it.sparkId == sparkId && it.state == RegistrationState.ARMED }?.since ?: now

    private suspend fun disarm(flowId: String, sparkId: String) {
        alarms.disarmSchedule(flowId, sparkId)
        flexible.cancel(flowId, sparkId)
        scheduleRepo.supersedePendingForSpark(flowId, sparkId, "Disarmed")
        scheduleRepo.removeState(flowId, sparkId)
        registrations.remove(flowId, sparkId)
    }

    private suspend fun applyCatchUp(op: SyncOp.CatchUp, state: ScheduleStateEntity?, now: Long): SyncReport {
        for (slot in op.run) {
            val row = ensureExpected(op.flowId, op.sparkId, slot, now)
            scheduleRepo.markExpected(row.id, ExpectedRunState.MISSED, null, null, null, "CaughtUp")
            inbox.enqueue(NewSpark(op.flowId, op.sparkId, "scheduled", null, DedupeKeys.schedule(op.flowId, slot)))
        }
        for (sk in op.skipped) {
            val row = ensureExpected(op.flowId, op.sparkId, sk.slot, now)
            scheduleRepo.markExpected(row.id, ExpectedRunState.SKIPPED, null, null, null, sk.reason)
        }
        val last = (op.run + op.skipped.map { it.slot }).maxOrNull()
        if (state != null && last != null) {
            scheduleRepo.saveState(state.copy(lastSlotKey = last.toString(), lastStatus = if (op.run.isNotEmpty()) "CaughtUp" else "Skipped"))
        }
        if (op.run.isNotEmpty()) {
            recorder.record(ReliabilityKinds.SCHEDULE_CAUGHT_UP, Severity.INFO, op.flowId, "slots=${op.run.size}")
        }
        if (op.skipped.isNotEmpty() || op.truncated > 0) {
            val reasons = op.skipped.groupingBy { it.reason }.eachCount()
            recorder.record(ReliabilityKinds.SCHEDULE_SKIPPED, Severity.WARN, op.flowId, "$reasons olderNotListed=${op.truncated}")
        }
        return SyncReport(caughtUp = op.run.size, skipped = op.skipped.size)
    }

    private suspend fun ensureExpected(flowId: String, sparkId: String, slot: Long, now: Long): ExpectedRunEntity {
        scheduleRepo.expected(flowId, sparkId, slot.toString())?.let { return it }
        scheduleRepo.expect(
            ExpectedRunEntity(
                flowId = flowId, sparkId = sparkId, slotKey = slot.toString(), dueAt = slot, armedAt = now,
                state = ExpectedRunState.PENDING, firedAt = null, runId = null, latenessMs = null, reason = null,
            ),
        )
        return scheduleRepo.expected(flowId, sparkId, slot.toString())!!
    }

    /** Wall clock for the fire path, which shares this class's lock and persistence. */
    fun now(): Long = clock.now()
}
