package com.kaivoflow.core.engine.alwayson

import java.time.Instant
import java.time.ZoneId

/** Wake lock rules of spec 3.6. No standing lock, ever: only a Run and Inbox intake hold one, always with a timeout. */
object WakeLockPolicy {
    const val RUN_GRACE_MS = 30_000L
    const val RUN_HARD_MAX_MS = 15 * 60_000L
    const val INTAKE_MAX_MS = 20_000L
    /** The Reliability Center warns above this many held minutes per day. */
    const val DAILY_WARN_MS = 60 * 60_000L

    /** The Flow's run timeout plus 30 seconds, never more than 15 minutes. */
    fun runTimeoutMs(flowTimeoutMs: Long): Long =
        (flowTimeoutMs.coerceAtLeast(0) + RUN_GRACE_MS).coerceAtMost(RUN_HARD_MAX_MS)

    fun intakeTimeoutMs(requestedMs: Long = INTAKE_MAX_MS): Long = requestedMs.coerceIn(1L, INTAKE_MAX_MS)
}

data class WakeLockMetrics(
    val heldNow: Int,
    val acquiredToday: Int,
    val millisToday: Long,
    val longestTodayMs: Long,
    val timedOutToday: Int,
) {
    val overDailyWarning: Boolean get() = millisToday > WakeLockPolicy.DAILY_WARN_MS
}

/**
 * Bookkeeping for the wake-lock metric (AO-10: visible in the dump, soak total under 10 minutes on a quiet day).
 * Time held is capped at the lock's timeout because the platform drops the lock then, whether or not the caller
 * released it. Spans crossing local midnight are split. Thread-safe.
 */
class WakeLockLedger(private val clock: Clock, private val zone: ZoneId = ZoneId.systemDefault()) {
    class Token internal constructor(val id: Long, val tag: String, val acquiredAt: Long, val timeoutMs: Long)

    private val open = HashMap<Long, Token>()
    private val perDay = HashMap<Long, Long>()
    private val countPerDay = HashMap<Long, Int>()
    private val longestPerDay = HashMap<Long, Long>()
    private val timedOutPerDay = HashMap<Long, Int>()
    private var nextId = 1L

    @Synchronized
    fun acquired(tag: String, timeoutMs: Long): Token {
        val t = Token(nextId++, tag, clock.now(), timeoutMs)
        open[t.id] = t
        val day = dayOf(t.acquiredAt)
        countPerDay[day] = (countPerDay[day] ?: 0) + 1
        return t
    }

    /** Returns the held duration that was counted (capped at the timeout), or 0 for an unknown or repeated release. */
    @Synchronized
    fun released(token: Token): Long {
        if (open.remove(token.id) == null) return 0L
        val now = clock.now()
        val held = (now - token.acquiredAt).coerceAtLeast(0)
        val timedOut = held >= token.timeoutMs
        val counted = held.coerceAtMost(token.timeoutMs)
        addSpan(token.acquiredAt, token.acquiredAt + counted)
        val day = dayOf(token.acquiredAt)
        longestPerDay[day] = maxOf(longestPerDay[day] ?: 0L, counted)
        if (timedOut) timedOutPerDay[day] = (timedOutPerDay[day] ?: 0) + 1
        prune(day)
        return counted
    }

    /** Locks the platform has already dropped because their timeout passed. They count and are forgotten. */
    @Synchronized
    fun sweepExpired(): List<Token> {
        val now = clock.now()
        val expired = open.values.filter { now - it.acquiredAt >= it.timeoutMs }
        expired.forEach { released(it) }
        return expired
    }

    @Synchronized
    fun metrics(): WakeLockMetrics {
        val now = clock.now()
        val today = dayOf(now)
        var millis = perDay[today] ?: 0L
        open.values.forEach { t ->
            val end = minOf(now, t.acquiredAt + t.timeoutMs)
            millis += overlapWithDay(t.acquiredAt, end, today)
        }
        return WakeLockMetrics(open.size, countPerDay[today] ?: 0, millis, longestPerDay[today] ?: 0L, timedOutPerDay[today] ?: 0)
    }

    private fun addSpan(start: Long, end: Long) {
        var cursor = start
        while (cursor < end) {
            val day = dayOf(cursor)
            val dayEnd = startOfDay(day + 1)
            val chunkEnd = minOf(end, dayEnd)
            perDay[day] = (perDay[day] ?: 0L) + (chunkEnd - cursor)
            cursor = chunkEnd
        }
    }

    private fun overlapWithDay(start: Long, end: Long, day: Long): Long {
        val from = maxOf(start, startOfDay(day))
        val to = minOf(end, startOfDay(day + 1))
        return (to - from).coerceAtLeast(0)
    }

    private fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
    private fun startOfDay(day: Long): Long = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun prune(today: Long) {
        listOf(perDay, longestPerDay).forEach { m -> m.keys.removeAll { it < today - 7 } }
        countPerDay.keys.removeAll { it < today - 7 }
        timedOutPerDay.keys.removeAll { it < today - 7 }
    }
}
