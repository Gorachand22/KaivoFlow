package com.kaivoflow.core.engine.alwayson

import com.kaivoflow.core.model.ScheduleMath
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Per-Schedule-Spark precision (spec 3.9). Stored by its [db] spelling in `schedule_state.precision`. */
enum class PrecisionMode {
    /** `setAlarmClock`: fires on time even in deep Doze. Few per app. */
    OnTime,
    /** `setExactAndAllowWhileIdle`: the default. Throttled to about once per 9 minutes per app in Doze. */
    Exact,
    /** WorkManager: deferred to maintenance windows, best for battery. Also the fallback when exact alarms are denied. */
    Flexible;

    val db: String get() = name.uppercase()
    val usesAlarm: Boolean get() = this != Flexible

    companion object {
        fun fromDb(v: String?): PrecisionMode = entries.firstOrNull { it.db == v } ?: Exact

        /** Reads the optional Spark parameter `precision` ("ontime", "exact", "flexible"). Default Exact. */
        fun fromParam(v: String?): PrecisionMode = when (v?.trim()?.lowercase()?.replace("-", "")?.replace("_", "")) {
            "ontime", "alarmclock" -> OnTime
            "flexible", "workmanager" -> Flexible
            else -> Exact
        }
    }
}

object PrecisionPolicy {
    /** Spec 3.9: soft cap of OnTime Sparks per app. */
    const val ON_TIME_SOFT_CAP = 5

    data class Effective(val mode: PrecisionMode, val degradedFrom: PrecisionMode?) {
        val degraded: Boolean get() = degradedFrom != null
    }

    /** Without the exact-alarm permission Exact and OnTime degrade to Flexible (never silently: the caller records an event). */
    fun effective(requested: PrecisionMode, exactAllowed: Boolean): Effective =
        if (requested.usesAlarm && !exactAllowed) Effective(PrecisionMode.Flexible, requested) else Effective(requested, null)

    fun onTimeOverCap(onTimeCount: Int): Boolean = onTimeCount > ON_TIME_SOFT_CAP
}

enum class MisfirePolicy {
    Skip, RunOnce, RunAll;

    val db: String get() = name.uppercase()

    companion object {
        fun fromDb(v: String?): MisfirePolicy = entries.firstOrNull { it.db == v } ?: RunOnce
        fun fromParam(v: String?): MisfirePolicy = when (v?.trim()?.lowercase()?.replace("-", "")?.replace("_", "")) {
            "skip" -> Skip
            "runall", "all" -> RunAll
            else -> RunOnce
        }
    }
}

/** The Schedule Spark's configuration in the shape the slot arithmetic needs. */
data class SlotConfig(
    /** minutes, hourly, daily, weekly or monthly. Anything else behaves as daily (same as ScheduleMath). */
    val frequency: String,
    val time: String = "08:00",
    val daysOfWeek: String = "",
    val dayOfMonth: Int = 1,
    val intervalMinutes: Double = 15.0,
    val zone: ZoneId,
) {
    val isInterval: Boolean get() = frequency == "minutes" || frequency == "hourly"
}

data class SlotWindow(val slots: List<Long>, /** Older slots that were not listed because of the limit. */ val truncated: Int)

/**
 * Pure slot arithmetic. Wall-clock schedules are computed per calendar date in the Spark's zone (never by adding
 * 24 hours): a spring-forward gap fires at the next valid instant, a fall-back overlap fires once (first occurrence).
 * Interval schedules sit on a grid anchored at the last slot, so a late or caught-up fire never shifts the grid.
 */
object SlotMath {
    /** WorkManager's floor for Flexible. Alarms honour one minute (a deep-Doze floor of about 9 minutes still applies). */
    fun minIntervalMinutes(mode: PrecisionMode): Long = if (mode == PrecisionMode.Flexible) ScheduleMath.MIN_INTERVAL_MINUTES else 1L

    fun intervalMs(cfg: SlotConfig, mode: PrecisionMode): Long {
        val minutes = if (cfg.frequency == "hourly") 60L else cfg.intervalMinutes.toLong().coerceAtLeast(minIntervalMinutes(mode))
        return minutes * 60_000L
    }

    /**
     * The first slot strictly after [afterMs]. For interval schedules [anchorMs] is a known grid point (the last slot);
     * without one the first slot is one interval after [afterMs].
     */
    fun nextSlotAfter(cfg: SlotConfig, afterMs: Long, mode: PrecisionMode, anchorMs: Long? = null): Long =
        if (cfg.isInterval) {
            val step = intervalMs(cfg, mode)
            val anchor = anchorMs ?: afterMs
            anchor + (Math.floorDiv(afterMs - anchor, step) + 1) * step
        } else {
            nextCalendarSlot(cfg, afterMs)
        }

    /** Slots in (fromExclusive, toInclusive], oldest first, at most [limit] of the most recent ones. */
    fun slotsBetween(
        cfg: SlotConfig,
        mode: PrecisionMode,
        fromExclusive: Long,
        toInclusive: Long,
        anchorMs: Long? = null,
        limit: Int = 100,
    ): SlotWindow {
        if (toInclusive <= fromExclusive) return SlotWindow(emptyList(), 0)
        if (cfg.isInterval) {
            val step = intervalMs(cfg, mode)
            val anchor = anchorMs ?: fromExclusive
            val first = anchor + (Math.floorDiv(fromExclusive - anchor, step) + 1) * step
            if (first > toInclusive) return SlotWindow(emptyList(), 0)
            val count = ((toInclusive - first) / step + 1)
            val keep = minOf(count, limit.toLong())
            val skip = count - keep
            return SlotWindow(List(keep.toInt()) { first + (skip + it) * step }, skip.toInt())
        }
        val all = ArrayList<Long>()
        var cursor = fromExclusive
        while (all.size < MAX_CALENDAR_SLOTS) {
            val next = nextCalendarSlot(cfg, cursor)
            if (next > toInclusive) break
            all += next
            cursor = next
        }
        val skip = (all.size - limit).coerceAtLeast(0)
        return SlotWindow(all.drop(skip), skip)
    }

    private fun nextCalendarSlot(cfg: SlotConfig, afterMs: Long): Long {
        val (hour, minute) = ScheduleMath.parseTime(cfg.time)
        val days = ScheduleMath.parseDays(cfg.daysOfWeek)
        val monthDay = ScheduleMath.clampDayOfMonth(cfg.dayOfMonth)
        var date: LocalDate = Instant.ofEpochMilli(afterMs).atZone(cfg.zone).toLocalDate().minusDays(1)
        repeat(SEARCH_DAYS) {
            date = date.plusDays(1)
            val matches = when (cfg.frequency) {
                "weekly" -> days.isEmpty() || date.dayOfWeek.value in days
                "monthly" -> date.dayOfMonth == monthDay
                else -> true
            }
            if (matches) {
                val slot = instantOf(date, hour, minute, cfg.zone)
                if (slot > afterMs) return slot
            }
        }
        error("no slot found within $SEARCH_DAYS days for ${cfg.frequency}")
    }

    /** Local date and time to an instant. A gap (spring forward) resolves to the transition instant, an overlap to the first occurrence. */
    internal fun instantOf(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long {
        val local = date.atTime(hour, minute)
        val rules = zone.rules
        if (rules.getValidOffsets(local).isEmpty()) {
            return rules.getTransition(local).instant.toEpochMilli()
        }
        return local.atZone(zone).toInstant().toEpochMilli()
    }

    /** Stable fingerprint of everything that changes which slots exist. A change supersedes pending slots. */
    fun configHash(cfg: SlotConfig, requested: PrecisionMode, misfire: MisfirePolicy, maxLatenessMs: Long): String {
        val canonical = listOf(
            cfg.frequency, ScheduleMath.parseTime(cfg.time).toString(), ScheduleMath.parseDays(cfg.daysOfWeek).sorted().toString(),
            ScheduleMath.clampDayOfMonth(cfg.dayOfMonth).toString(), cfg.intervalMinutes.toLong().toString(), cfg.zone.id,
            requested.db, misfire.db, maxLatenessMs.toString(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    private const val SEARCH_DAYS = 400
    private const val MAX_CALENDAR_SLOTS = 1_000
}

object Lateness {
    /** How long after a slot the fire still counts as on time. */
    fun lateAfterMs(mode: PrecisionMode): Long = if (mode == PrecisionMode.Flexible) 15 * 60_000L else 60_000L

    /** How long after a slot the Reconciler waits before it calls the slot elapsed (Doze can delay an alarm by minutes). */
    fun elapsedGraceMs(mode: PrecisionMode): Long = when (mode) {
        PrecisionMode.OnTime -> 2 * 60_000L
        PrecisionMode.Exact -> 5 * 60_000L
        PrecisionMode.Flexible -> 30 * 60_000L
    }

    data class Classified(val state: String, val latenessMs: Long)

    fun classify(mode: PrecisionMode, dueAt: Long, firedAt: Long): Classified {
        val late = (firedAt - dueAt).coerceAtLeast(0)
        return Classified(if (late > lateAfterMs(mode)) "LATE" else "FIRED", late)
    }
}

object MisfirePlanner {
    const val RUN_ALL_CAP = 5
    private const val HALF_DAY_MS = 12 * 60 * 60_000L

    data class SkippedSlot(val slot: Long, val reason: String)
    data class Decision(val run: List<Long>, val skipped: List<SkippedSlot>)

    /** 12 hours for daily and longer, one interval for shorter schedules (spec 3.9). */
    fun defaultMaxLatenessMs(cfg: SlotConfig, intervalMs: Long): Long = if (cfg.isInterval) intervalMs else HALF_DAY_MS

    /**
     * [missed] are the elapsed slots, oldest first. Slots older than [maxLatenessMs] are always skipped. Of the rest,
     * Skip drops all, RunOnce runs the most recent one, RunAll runs the most recent [RUN_ALL_CAP] in order.
     */
    fun plan(policy: MisfirePolicy, missed: List<Long>, now: Long, maxLatenessMs: Long): Decision {
        val skipped = ArrayList<SkippedSlot>()
        val fresh = ArrayList<Long>()
        for (slot in missed.sorted()) {
            if (now - slot > maxLatenessMs) skipped += SkippedSlot(slot, "TooLate") else fresh += slot
        }
        val run: List<Long> = when (policy) {
            MisfirePolicy.Skip -> { fresh.forEach { skipped += SkippedSlot(it, "PolicySkip") }; emptyList() }
            MisfirePolicy.RunOnce -> {
                fresh.dropLast(1).forEach { skipped += SkippedSlot(it, "SupersededByCatchUp") }
                fresh.takeLast(1)
            }
            MisfirePolicy.RunAll -> {
                fresh.dropLast(RUN_ALL_CAP).forEach { skipped += SkippedSlot(it, "RunAllCap") }
                fresh.takeLast(RUN_ALL_CAP)
            }
        }
        return Decision(run, skipped.sortedBy { it.slot })
    }
}

/** Alarm identity. The same key always yields the same PendingIntent, so re-arming replaces instead of duplicating. */
object AlarmKeys {
    const val PULSE_CODE = 1
    const val RESTART_CODE = 2
    const val PULSE_URI = "kaivo-alarm://pulse"
    const val RESTART_URI = "kaivo-alarm://restart"

    fun requestCode(flowId: String, sparkId: String): Int = ("$flowId|$sparkId").hashCode() and 0x7fff_ffff or 0x100

    fun scheduleUri(flowId: String, sparkId: String): String =
        "kaivo-alarm://schedule/${enc(flowId)}/${enc(sparkId)}"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
