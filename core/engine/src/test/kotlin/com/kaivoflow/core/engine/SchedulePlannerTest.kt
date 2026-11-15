package com.kaivoflow.core.engine

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulePlannerTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()

    @Test fun slotsAlternate() {
        assertEquals("A", SchedulePlanner.nextSlot(null))
        assertEquals("B", SchedulePlanner.nextSlot("A"))
        assertEquals("A", SchedulePlanner.nextSlot("B"))
    }

    @Test fun nextAfterFireIsTomorrow() {
        val fired = at("2026-03-10T08:00:00+01:00[Europe/Berlin]")
        val d = SchedulePlanner.delayAfterFire(fired, fired + 2_000, "08:00", "daily", "", 1, zone)
        assertEquals(fired + 2_000 + d, at("2026-03-11T08:00:00+01:00[Europe/Berlin]"))
    }

    @Test fun earlyStartDoesNotRearmSameOccurrence() {
        val target = at("2026-03-10T08:00:00+01:00[Europe/Berlin]")
        val now = target - 5_000
        val d = SchedulePlanner.delayAfterFire(target, now, "08:00", "daily", "", 1, zone)
        assertEquals(at("2026-03-11T08:00:00+01:00[Europe/Berlin]"), now + d)
    }

    @Test fun dstSpringForwardKeepsWallClock() {
        val fired = at("2026-03-28T08:00:00+01:00[Europe/Berlin]")
        val d = SchedulePlanner.delayAfterFire(fired, fired + 1_000, "08:00", "daily", "", 1, zone)
        assertEquals(at("2026-03-29T08:00:00+02:00[Europe/Berlin]"), fired + 1_000 + d)
    }

    @Test fun weeklyPicksNextListedDay() {
        val fired = at("2026-03-09T08:00:00+01:00[Europe/Berlin]") // Monday
        val d = SchedulePlanner.delayAfterFire(fired, fired + 1_000, "08:00", "weekly", "1,4", 1, zone)
        assertEquals(at("2026-03-12T08:00:00+01:00[Europe/Berlin]"), fired + 1_000 + d)
    }
}
