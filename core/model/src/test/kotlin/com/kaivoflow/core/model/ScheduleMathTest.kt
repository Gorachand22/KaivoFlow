package com.kaivoflow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Scheduling is what users actually notice: a 08:00 brief that arrives at 08:15, or a "every Monday"
 * Flow that fires on Tuesday, reads as a broken app. These tests pin the calendar maths.
 */
class ScheduleMathTest {

    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId = kolkata): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private val minutes = 60_000L

    @Test
    fun `daily later today waits until that time`() {
        val now = millis(2026, 3, 10, 6, 0)
        val delay = ScheduleMath.nextDelayMillis("08:00", "daily", "", 1, kolkata, now)
        assertEquals(120 * minutes, delay)
    }

    @Test
    fun `daily after the time rolls to tomorrow`() {
        val now = millis(2026, 3, 10, 9, 30)
        val delay = ScheduleMath.nextDelayMillis("08:00", "daily", "", 1, kolkata, now)
        assertEquals((22 * 60 + 30) * minutes, delay)
    }

    @Test
    fun `daily exactly at the time goes to the next day, so it never fires twice`() {
        val now = millis(2026, 3, 10, 8, 0)
        val delay = ScheduleMath.nextDelayMillis("08:00", "daily", "", 1, kolkata, now)
        assertEquals(24 * 60 * minutes, delay)
    }

    @Test
    fun `weekly skips to the next allowed weekday`() {
        // 2026-03-10 is a Tuesday. Allowed days are Monday, Wednesday, Friday.
        val now = millis(2026, 3, 10, 12, 0)
        val delay = ScheduleMath.nextDelayMillis("08:00", "weekly", "1,3,5", 1, kolkata, now)
        // Next Wednesday 08:00 → 20 hours.
        assertEquals(20 * 60 * minutes, delay)
    }

    @Test
    fun `weekly with the same weekday later today stays today`() {
        val now = millis(2026, 3, 11, 6, 0) // Wednesday
        val delay = ScheduleMath.nextDelayMillis("08:00", "weekly", "3", 1, kolkata, now)
        assertEquals(120 * minutes, delay)
    }

    @Test
    fun `weekly with no valid days behaves like daily instead of never running`() {
        val now = millis(2026, 3, 10, 6, 0)
        val delay = ScheduleMath.nextDelayMillis("08:00", "weekly", "nonsense", 1, kolkata, now)
        assertEquals(120 * minutes, delay)
    }

    @Test
    fun `monthly on a day already passed moves to next month`() {
        val now = millis(2026, 3, 20, 12, 0)
        val delay = ScheduleMath.nextDelayMillis("09:00", "monthly", "", 5, kolkata, now)
        // 5 April 09:00.
        assertTrue("expected a wait of roughly two weeks, got $delay", delay in 15L * 24 * 60 * minutes..16L * 24 * 60 * minutes)
    }

    @Test
    fun `monthly on day 31 is clamped so February still fires`() {
        val now = millis(2026, 1, 31, 12, 0)
        val delay = ScheduleMath.nextDelayMillis("09:00", "monthly", "", 31, kolkata, now)
        val next = ZonedDateTime.now(kolkata).let {
            java.time.Instant.ofEpochMilli(now + delay).atZone(kolkata)
        }
        assertEquals(2, next.monthValue)
        assertEquals(28, next.dayOfMonth)
        assertEquals(9, next.hour)
    }

    @Test
    fun `timezone is the Flow's, not the device's`() {
        val now = millis(2026, 3, 10, 6, 0)
        val delhiDelay = ScheduleMath.nextDelayMillis("08:00", "daily", "", 1, ZoneId.of("Asia/Kolkata"), now)
        val utcDelay = ScheduleMath.nextDelayMillis("08:00", "daily", "", 1, ZoneId.of("UTC"), now)
        // 08:00 IST is 02:30 UTC: in UTC the same wall-clock time is still ahead, so the waits differ.
        assertTrue(delhiDelay != utcDelay)
    }

    @Test
    fun `malformed time falls back to 0800 instead of failing the Flow`() {
        assertEquals(8 to 0, ScheduleMath.parseTime(""))
        assertEquals(8 to 0, ScheduleMath.parseTime("not a time"))
        assertEquals(23 to 59, ScheduleMath.parseTime("25:99"))
        assertEquals(7 to 5, ScheduleMath.parseTime("07:05"))
    }

    @Test
    fun `sub daily interval is floored at fifteen minutes`() {
        assertEquals(15L, ScheduleMath.minutesInterval(1.0))
        assertEquals(15L, ScheduleMath.minutesInterval(0.0))
        assertEquals(30L, ScheduleMath.minutesInterval(30.0))
    }

    @Test
    fun `descriptions read like a sentence`() {
        assertEquals("Daily at 08:00", ScheduleMath.describe("daily", "08:00", "", 1))
        assertEquals("Every Mon, Wed at 07:30", ScheduleMath.describe("weekly", "07:30", "3,1", 1))
        assertEquals("Monthly on day 5 at 09:00", ScheduleMath.describe("monthly", "09:00", "", 5))
    }

    @Test
    fun `spring forward does not produce a negative or zero delay`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 2026-03-29 02:30 does not exist in Berlin; the scheduler must still return a usable delay.
        val now = millis(2026, 3, 29, 1, 0, berlin)
        val delay = ScheduleMath.nextDelayMillis("02:30", "daily", "", 1, berlin, now)
        assertTrue("delay must be positive, was $delay", delay > 0)
        assertTrue("delay must be under a day, was $delay", delay <= 24 * 60 * minutes + 60 * minutes)
    }
}
