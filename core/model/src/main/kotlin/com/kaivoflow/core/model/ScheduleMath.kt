package com.kaivoflow.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure scheduling arithmetic for the Schedule Spark.
 *
 * Deliberately Android-free so it can be unit-tested on the JVM: calendar bugs ("every Monday"
 * firing on Tuesday, monthly on the 31st skipping February) are the single most embarrassing class
 * of automation bug, and they are invisible until a user misses a real reminder.
 *
 * The Spark's fields are interpreted in the Flow's own timezone ([zone]), never the device's.
 */
object ScheduleMath {

    /** Fastest interval the platform accepts for recurring work. */
    const val MIN_INTERVAL_MINUTES = 15L

    data class Target(val atMillis: Long, val description: String)

    /**
     * Milliseconds from [nowMillis] until the next occurrence of the configured time.
     *
     * Returns at least 0. Frequencies other than weekly/monthly are treated as daily here; the
     * sub-daily cases are handled by [minutesInterval] and by periodic work in the scheduler.
     */
    fun nextDelayMillis(
        timeText: String,
        frequency: String,
        daysOfWeek: String,
        dayOfMonth: Int,
        zone: ZoneId,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val (hour, minute) = parseTime(timeText)

        var candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)

        when (frequency) {
            "weekly" -> {
                val days = parseDays(daysOfWeek)
                if (days.isNotEmpty()) {
                    var guard = 0
                    while (candidate.dayOfWeek.value !in days && guard++ < 8) {
                        candidate = candidate.plusDays(1)
                    }
                }
            }
            "monthly" -> {
                val day = clampDayOfMonth(dayOfMonth)
                candidate = candidate.withDayOfMonth(day)
                if (!candidate.isAfter(now)) {
                    candidate = candidate.plusMonths(1).withDayOfMonth(day)
                }
            }
            else -> Unit
        }

        return (candidate.toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0)
    }

    /** `08:00` → (8, 0). Anything malformed falls back to 08:00 rather than failing a Run. */
    fun parseTime(timeText: String): Pair<Int, Int> {
        val hour = timeText.substringBefore(':').trim().toIntOrNull()?.coerceIn(0, 23) ?: 8
        val minute = timeText.substringAfter(':', "0").trim().take(2).toIntOrNull()?.coerceIn(0, 59) ?: 0
        return hour to minute
    }

    /** `1,3,5` → {1,3,5}; empty or garbage → no filter (every day). */
    fun parseDays(daysOfWeek: String): Set<Int> = daysOfWeek
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 1..7 }
        .toSet()

    /**
     * The 31st is coerced to the 28th: a monthly Flow must fire every month, not skip February.
     * Silently skipping a month is worse than firing three days early once a year.
     */
    fun clampDayOfMonth(day: Int): Int = day.coerceIn(1, 28)

    /** Sub-daily intervals, floored at the platform minimum. */
    fun minutesInterval(minutes: Double): Long =
        minutes.toLong().coerceAtLeast(MIN_INTERVAL_MINUTES)

    /** The next occurrence as an instant, for showing "next run at …" in the UI. */
    fun nextOccurrence(
        timeText: String,
        frequency: String,
        daysOfWeek: String,
        dayOfMonth: Int,
        zone: ZoneId,
        nowMillis: Long = System.currentTimeMillis(),
    ): ZonedDateTime = Instant
        .ofEpochMilli(nowMillis + nextDelayMillis(timeText, frequency, daysOfWeek, dayOfMonth, zone, nowMillis))
        .atZone(zone)

    /** A human sentence used on the Flow card and the Spark's inspector. */
    fun describe(frequency: String, timeText: String, daysOfWeek: String, dayOfMonth: Int): String {
        val (hour, minute) = parseTime(timeText)
        val clock = "%02d:%02d".format(hour, minute)
        return when (frequency) {
            "minutes" -> "Every few minutes"
            "hourly" -> "Every hour"
            "weekly" -> {
                val days = parseDays(daysOfWeek)
                if (days.isEmpty()) "Weekly at $clock"
                else "Every ${days.sorted().joinToString(", ") { DAY_NAMES[it - 1] }} at $clock"
            }
            "monthly" -> "Monthly on day ${clampDayOfMonth(dayOfMonth)} at $clock"
            else -> "Daily at $clock"
        }
    }

    private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
}
