package com.kaivoflow.core.engine.alwayson

data class DesiredSchedule(
    val flowId: String,
    val sparkId: String,
    val config: SlotConfig,
    val requested: PrecisionMode,
    val misfire: MisfirePolicy,
    val maxLatenessMs: Long,
    val configHash: String,
)

/** What `schedule_state` holds, reduced to what planning needs. */
data class StoredSchedule(
    val flowId: String,
    val sparkId: String,
    val configHash: String,
    val nextDueAt: Long?,
    /** Last grid point (for interval anchoring): the last slot that fired or was armed. */
    val lastSlotMs: Long?,
)

/** An `expected_run` row that is still PENDING although its slot is past the grace time. */
data class ElapsedSlot(val flowId: String, val sparkId: String, val slotMs: Long)

sealed interface SyncOp {
    /** Stop and forget this Spark's schedule (Flow no longer live, Spark removed or disabled). */
    data class Disarm(val flowId: String, val sparkId: String) : SyncOp

    /**
     * Arm [slotMs] with [mode]. When [supersedePending] is set, pending slots computed under an older configuration or
     * clock are marked SUPERSEDED first.
     */
    data class Arm(
        val desired: DesiredSchedule,
        val slotMs: Long,
        val mode: PrecisionMode,
        val degradedFrom: PrecisionMode?,
        val supersedePending: Boolean,
    ) : SyncOp

    /** Apply the misfire decision for slots that passed unfired. [run] are enqueued, [skipped] are logged. */
    data class CatchUp(
        val flowId: String,
        val sparkId: String,
        val run: List<Long>,
        val skipped: List<MisfirePlanner.SkippedSlot>,
        val truncated: Int,
    ) : SyncOp
}

/**
 * The schedule half of the Reconciler as a pure function: given desired and stored state it returns the operations
 * that make them agree. Applying the result and planning again yields the same Arm operations and no CatchUp, which
 * is what "calling it twice changes nothing" means.
 */
object ScheduleSyncPlanner {
    private const val MAX_ENUMERATED_SLOTS = 100

    fun plan(
        desired: List<DesiredSchedule>,
        stored: List<StoredSchedule>,
        elapsed: List<ElapsedSlot>,
        now: Long,
        exactAllowed: Boolean,
        applyMisfire: Boolean,
        recompute: Boolean,
    ): List<SyncOp> {
        val ops = ArrayList<SyncOp>()
        val desiredKeys = desired.map { it.flowId to it.sparkId }.toSet()
        stored.filter { (it.flowId to it.sparkId) !in desiredKeys }.forEach { ops += SyncOp.Disarm(it.flowId, it.sparkId) }

        val storedByKey = stored.associateBy { it.flowId to it.sparkId }
        val elapsedByKey = elapsed.groupBy({ it.flowId to it.sparkId }, { it.slotMs })

        for (d in desired) {
            val key = d.flowId to d.sparkId
            val s = storedByKey[key]
            val eff = PrecisionPolicy.effective(d.requested, exactAllowed)
            val sameConfig = s != null && s.configHash == d.configHash
            val anchor = s?.lastSlotMs ?: s?.nextDueAt
            val grace = Lateness.elapsedGraceMs(eff.mode)

            if (sameConfig && !recompute && applyMisfire) {
                val missedSet = sortedSetOf<Long>()
                missedSet += elapsedByKey[key].orEmpty()
                if (s!!.nextDueAt != null && s.nextDueAt + grace < now) missedSet += s.nextDueAt
                if (missedSet.isNotEmpty()) {
                    val grid = SlotMath.slotsBetween(
                        d.config, eff.mode, missedSet.first() - 1, now - grace, anchor, MAX_ENUMERATED_SLOTS,
                    )
                    missedSet += grid.slots
                    val decision = MisfirePlanner.plan(d.misfire, missedSet.toList(), now, d.maxLatenessMs)
                    ops += SyncOp.CatchUp(d.flowId, d.sparkId, decision.run, decision.skipped, grid.truncated)
                }
            }

            // A slot that is past but still inside the grace time stays armed: replacing it could cancel an alarm the
            // system is about to deliver (Doze delays). Re-setting a past time fires immediately and the Inbox key dedupes.
            val keepStored = sameConfig && !recompute && s!!.nextDueAt != null && s.nextDueAt + grace >= now
            val slot = if (keepStored) s!!.nextDueAt!! else {
                SlotMath.nextSlotAfter(d.config, now, eff.mode, if (sameConfig && !recompute) anchor else null)
            }
            ops += SyncOp.Arm(d, slot, eff.mode, eff.degradedFrom, supersedePending = !sameConfig && s != null || recompute)
        }
        return ops
    }
}
