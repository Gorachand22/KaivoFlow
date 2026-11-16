package com.kaivoflow.core.engine.reliability

import com.kaivoflow.core.engine.alwayson.PrecisionMode

/*
 * AO-19 (spec 6.3 and 5.5): lateness, missed rule, daily aggregation and retention. Pure Kotlin on plain numbers, so the
 * storage layer only moves rows in and out.
 */

/** One expected run as the maths sees it. [state] uses the `ExpectedRunState` spellings. */
data class ExpectedSample(
    val flowId: String,
    val dueAt: Long,
    val state: String,
    val latenessMs: Long? = null,
    val intervalMs: Long = 24 * 3_600_000L,
    val mode: PrecisionMode = PrecisionMode.Exact,
)

object MissedRule {
    /** Spec 5.5: missed when no Run started by `dueAt + max(5 min, 10% of the interval)`. */
    fun missedAfterMs(intervalMs: Long): Long = maxOf(5 * 60_000L, intervalMs / 10)

    fun isMissed(dueAt: Long, intervalMs: Long, now: Long): Boolean = now > dueAt + missedAfterMs(intervalMs)

    /** Pending rows past their missed threshold, to be marked MISSED by the Reconciler or the Pulse. */
    fun sweep(pending: List<ExpectedSample>, now: Long): List<ExpectedSample> =
        pending.filter { it.state == "PENDING" && isMissed(it.dueAt, it.intervalMs, now) }
}

/** Counts and lateness percentiles for one Flow and one UTC-or-local day (the caller picks the day key). */
data class DailyAggregate(
    val flowId: String,
    val dayStart: Long,
    val fired: Int,
    val late: Int,
    val missed: Int,
    val skipped: Int,
    val paused: Int,
    val p50Ms: Long,
    val p95Ms: Long,
    val maxMs: Long,
) {
    /** Rows that count toward on-time: Paused and Skipped are policy, not failures. */
    val measured: Int get() = fired + late + missed
}

object RunAggregation {
    const val DAY_MS = 24 * 3_600_000L

    /** Nearest-rank percentile, 0 for an empty list. [p] in 1..100. */
    fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return 0L
        val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun aggregate(flowId: String, dayStart: Long, rows: List<ExpectedSample>): DailyAggregate {
        val lateness = rows.filter { it.state == "FIRED" || it.state == "LATE" }.mapNotNull { it.latenessMs }.sorted()
        return DailyAggregate(
            flowId, dayStart,
            fired = rows.count { it.state == "FIRED" }, late = rows.count { it.state == "LATE" },
            missed = rows.count { it.state == "MISSED" }, skipped = rows.count { it.state == "SKIPPED" },
            paused = rows.count { it.state == "PAUSED" },
            p50Ms = percentile(lateness, 50), p95Ms = percentile(lateness, 95), maxMs = lateness.lastOrNull() ?: 0L,
        )
    }

    /**
     * Rows older than [cutoff] grouped per Flow and day. PENDING and SUPERSEDED rows are dropped, not counted: the first is
     * unresolved, the second never ran by design.
     */
    fun compact(rows: List<ExpectedSample>, cutoff: Long, zoneOffsetMs: Long = 0L): List<DailyAggregate> =
        rows.filter { it.dueAt < cutoff && it.state != "PENDING" && it.state != "SUPERSEDED" }
            .groupBy { it.flowId to dayStart(it.dueAt, zoneOffsetMs) }
            .map { (k, v) -> aggregate(k.first, k.second, v) }
            .sortedWith(compareBy({ it.flowId }, { it.dayStart }))

    fun dayStart(at: Long, zoneOffsetMs: Long = 0L): Long = Math.floorDiv(at + zoneOffsetMs, DAY_MS) * DAY_MS - zoneOffsetMs
}

/** Per day outcome used by the 14-day strip. */
enum class DayOutcome { OnTime, Late, Missed, Idle }

data class FlowReliability(val onTimeRatio: Double?, val measured: Int, val strip: List<DayOutcome>, val lastLateOrMissed: Long?)

object FlowReliabilityCalc {
    /**
     * [aggregates] (compacted days) and [recent] (raw rows newer than the compaction cutoff) together cover [days] days ending at
     * [now]. A day is Missed if any slot was missed, else Late if any was late, else OnTime if something fired, else Idle
     * (grey: skipped, paused or nothing due).
     */
    fun compute(flowId: String, aggregates: List<DailyAggregate>, recent: List<ExpectedSample>, now: Long, days: Int = 14, zoneOffsetMs: Long = 0L): FlowReliability {
        val today = RunAggregation.dayStart(now, zoneOffsetMs)
        val perDay = HashMap<Long, DailyAggregate>()
        aggregates.filter { it.flowId == flowId }.forEach { perDay[it.dayStart] = it }
        recent.filter { it.flowId == flowId }.groupBy { RunAggregation.dayStart(it.dueAt, zoneOffsetMs) }.forEach { (d, rows) ->
            if (d !in perDay) perDay[d] = RunAggregation.aggregate(flowId, d, rows.filter { it.state != "PENDING" && it.state != "SUPERSEDED" })
        }
        val strip = (days - 1 downTo 0).map { back ->
            val a = perDay[today - back * RunAggregation.DAY_MS]
            when {
                a == null -> DayOutcome.Idle
                a.missed > 0 -> DayOutcome.Missed
                a.late > 0 -> DayOutcome.Late
                a.fired > 0 -> DayOutcome.OnTime
                else -> DayOutcome.Idle
            }
        }
        val window = perDay.filterKeys { it >= today - (days - 1) * RunAggregation.DAY_MS }.values
        val measured = window.sumOf { it.measured }
        val onTime = window.sumOf { it.fired }
        val lastBad = (recent.filter { it.flowId == flowId && (it.state == "LATE" || it.state == "MISSED") }.maxOfOrNull { it.dueAt })
            ?: window.filter { it.late + it.missed > 0 }.maxOfOrNull { it.dayStart }
        return FlowReliability(if (measured == 0) null else onTime.toDouble() / measured, measured, strip, lastBad)
    }
}

/** Spec 5.5 and 6.3 retention windows. */
object RetentionPolicy {
    const val EXPECTED_RUN_DAYS = 30
    const val AGGREGATE_DAYS = 180
    const val EVENT_DAYS = 90
    const val EVENT_MAX_ROWS = 5_000
    const val SESSION_DAYS = 90

    fun expectedCutoff(now: Long) = now - EXPECTED_RUN_DAYS * RunAggregation.DAY_MS
    fun aggregateCutoff(now: Long) = now - AGGREGATE_DAYS * RunAggregation.DAY_MS
    fun eventCutoff(now: Long) = now - EVENT_DAYS * RunAggregation.DAY_MS

    /** Event rows to delete: older than 90 days, or beyond the newest 5000. [ats] are all event timestamps, any order. */
    fun eventCutoffForRowCap(ats: List<Long>, now: Long): Long {
        val byAge = eventCutoff(now)
        if (ats.size <= EVENT_MAX_ROWS) return byAge
        val fifthThousandNewest = ats.sortedDescending()[EVENT_MAX_ROWS - 1]
        return maxOf(byAge, fifthThousandNewest) // delete strictly older than this
    }
}
