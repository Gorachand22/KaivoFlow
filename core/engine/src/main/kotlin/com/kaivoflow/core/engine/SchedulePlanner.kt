package com.kaivoflow.core.engine

import com.kaivoflow.core.model.ScheduleMath
import java.time.ZoneId

/**
 * Pure helpers for arming the NEXT daily/weekly/monthly occurrence before the current one runs.
 * Timezone and DST handling stay in [ScheduleMath].
 */
object SchedulePlanner {

    /** Two alternating unique-work slots, so arming the next run never replaces the running one. */
    fun nextSlot(current: String?): String = if (current == SLOT_A) SLOT_B else SLOT_A

    /**
     * Delay (from [nowMillis]) until the occurrence AFTER the one that is firing now.
     * WorkManager may start work slightly before its nominal time; using [firedFor] + 1 minute as a
     * floor stops an early start from re-arming the same occurrence (a double Run).
     */
    fun delayAfterFire(
        firedFor: Long?,
        nowMillis: Long,
        timeText: String,
        frequency: String,
        daysOfWeek: String,
        dayOfMonth: Int,
        zone: ZoneId,
    ): Long {
        val reference = maxOf(nowMillis, (firedFor ?: Long.MIN_VALUE / 2) + 60_000L)
        val delayFromReference = ScheduleMath.nextDelayMillis(timeText, frequency, daysOfWeek, dayOfMonth, zone, reference)
        return (reference - nowMillis) + delayFromReference
    }

    const val SLOT_A = "A"
    const val SLOT_B = "B"
}
