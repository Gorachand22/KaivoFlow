package com.kaivoflow.core.engine.alwayson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class SlotMathTest {
    private val ny = ZoneId.of("America/New_York")
    private val kol = ZoneId.of("Asia/Kolkata")
    private val utc = ZoneOffset.UTC

    private fun ms(z: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), z).toInstant().toEpochMilli()
    private fun local(ms: Long, z: ZoneId) = java.time.Instant.ofEpochMilli(ms).atZone(z).toLocalDateTime().toString()
    private fun daily(zone: ZoneId, time: String = "08:00") = SlotConfig("daily", time = time, zone = zone)

    @Test fun dailyNextSlotIsStrictlyAfter() {
        val cfg = daily(kol)
        val now = ms(kol, 2026, 11, 16, 8, 0)
        assertEquals("2026-11-17T08:00", local(SlotMath.nextSlotAfter(cfg, now, PrecisionMode.Exact), kol))
        assertEquals("2026-11-16T08:00", local(SlotMath.nextSlotAfter(cfg, now - 1, PrecisionMode.Exact), kol))
    }

    @Test fun weeklyPicksListedDays() {
        val cfg = SlotConfig("weekly", time = "09:30", daysOfWeek = "1,3", zone = kol) // Mon, Wed
        val tue = ms(kol, 2026, 11, 17, 12, 0)
        assertEquals("2026-11-18T09:30", local(SlotMath.nextSlotAfter(cfg, tue, PrecisionMode.Exact), kol))
        val wedLate = ms(kol, 2026, 11, 18, 10, 0)
        assertEquals("2026-11-23T09:30", local(SlotMath.nextSlotAfter(cfg, wedLate, PrecisionMode.Exact), kol))
    }

    @Test fun monthlyDay31IsClampedTo28() {
        val cfg = SlotConfig("monthly", time = "06:00", dayOfMonth = 31, zone = utc)
        val jan = ms(utc, 2027, 1, 30, 0, 0)
        assertEquals("2027-02-28T06:00", local(SlotMath.nextSlotAfter(cfg, jan, PrecisionMode.Exact), utc))
    }

    @Test fun springForwardGapFiresAtNextValidInstant() {
        // 2026-03-08: 02:00 EST jumps to 03:00 EDT. A 02:30 schedule fires at 03:00 EDT.
        val cfg = daily(ny, "02:30")
        val before = ms(ny, 2026, 3, 7, 12, 0)
        val slot = SlotMath.nextSlotAfter(cfg, before, PrecisionMode.Exact)
        assertEquals("2026-03-08T03:00", local(slot, ny))
        // and the day after is back to 02:30
        assertEquals("2026-03-09T02:30", local(SlotMath.nextSlotAfter(cfg, slot, PrecisionMode.Exact), ny))
    }

    @Test fun fallBackOverlapFiresOnceAtFirstOccurrence() {
        // 2026-11-01: 02:00 EDT falls back to 01:00 EST, so 01:30 happens twice.
        val cfg = daily(ny, "01:30")
        val before = ms(ny, 2026, 10, 31, 12, 0)
        val first = SlotMath.nextSlotAfter(cfg, before, PrecisionMode.Exact)
        assertEquals(ZonedDateTime.of(LocalDateTime.of(2026, 11, 1, 1, 30), ny).withEarlierOffsetAtOverlap().toInstant().toEpochMilli(), first)
        // asked again during the repeated hour, the next slot is the following day, not the second 01:30
        val secondOccurrence = first + 60 * 60_000L
        assertEquals("2026-11-02T01:30", local(SlotMath.nextSlotAfter(cfg, secondOccurrence, PrecisionMode.Exact), ny))
        assertEquals("2026-11-02T01:30", local(SlotMath.nextSlotAfter(cfg, first, PrecisionMode.Exact), ny))
    }

    @Test fun daySpanningDstTransitionKeepsWallClock() {
        val cfg = daily(ny, "08:00")
        val sat = ms(ny, 2026, 3, 7, 8, 0)
        val sun = SlotMath.nextSlotAfter(cfg, sat, PrecisionMode.Exact)
        assertEquals("2026-03-08T08:00", local(sun, ny))
        assertEquals("23h between the slots, not 24h", 23 * 3_600_000L, sun - sat)
    }

    @Test fun intervalGridStaysAnchoredAfterALateFire() {
        val cfg = SlotConfig("minutes", intervalMinutes = 5.0, zone = utc)
        val anchor = 1_000_000_000_000L
        val late = anchor + 5 * 60_000 + 90_000 // fired 90 s late at slot 1
        assertEquals(anchor + 10 * 60_000, SlotMath.nextSlotAfter(cfg, late, PrecisionMode.Exact, anchor))
        val longAfter = anchor + 47 * 60_000
        assertEquals(anchor + 50 * 60_000, SlotMath.nextSlotAfter(cfg, longAfter, PrecisionMode.Exact, anchor))
    }

    @Test fun intervalWithoutAnchorStartsOneIntervalAhead() {
        val cfg = SlotConfig("minutes", intervalMinutes = 3.0, zone = utc)
        assertEquals(1_000L + 180_000, SlotMath.nextSlotAfter(cfg, 1_000L, PrecisionMode.OnTime))
    }

    @Test fun intervalFloorDependsOnPrecision() {
        val cfg = SlotConfig("minutes", intervalMinutes = 2.0, zone = utc)
        assertEquals(2 * 60_000L, SlotMath.intervalMs(cfg, PrecisionMode.Exact))
        assertEquals("Flexible cannot go below the WorkManager floor", 15 * 60_000L, SlotMath.intervalMs(cfg, PrecisionMode.Flexible))
        assertEquals(60 * 60_000L, SlotMath.intervalMs(SlotConfig("hourly", zone = utc), PrecisionMode.Exact))
    }

    @Test fun slotsBetweenListsGridAndTruncatesOldest() {
        val cfg = SlotConfig("minutes", intervalMinutes = 1.0, zone = utc)
        val w = SlotMath.slotsBetween(cfg, PrecisionMode.Exact, 0, 10 * 60_000L, anchorMs = 0, limit = 4)
        assertEquals(listOf(7, 8, 9, 10).map { it * 60_000L }, w.slots)
        assertEquals(6, w.truncated)
        assertTrue(SlotMath.slotsBetween(cfg, PrecisionMode.Exact, 5, 5, 0).slots.isEmpty())
    }

    @Test fun slotsBetweenCalendarDaily() {
        val cfg = daily(kol)
        val from = ms(kol, 2026, 11, 10, 9, 0)
        val to = ms(kol, 2026, 11, 14, 9, 0)
        val w = SlotMath.slotsBetween(cfg, PrecisionMode.Exact, from, to)
        assertEquals(listOf(11, 12, 13, 14), w.slots.map { java.time.Instant.ofEpochMilli(it).atZone(kol).dayOfMonth })
    }

    @Test fun configHashChangesWithAnythingThatMovesSlots() {
        val base = SlotConfig("daily", time = "08:00", zone = kol)
        val h = SlotMath.configHash(base, PrecisionMode.Exact, MisfirePolicy.RunOnce, 1)
        assertEquals(h, SlotMath.configHash(base.copy(), PrecisionMode.Exact, MisfirePolicy.RunOnce, 1))
        assertNotEquals(h, SlotMath.configHash(base.copy(time = "08:01"), PrecisionMode.Exact, MisfirePolicy.RunOnce, 1))
        assertNotEquals(h, SlotMath.configHash(base.copy(zone = utc), PrecisionMode.Exact, MisfirePolicy.RunOnce, 1))
        assertNotEquals(h, SlotMath.configHash(base, PrecisionMode.Flexible, MisfirePolicy.RunOnce, 1))
        assertNotEquals(h, SlotMath.configHash(base, PrecisionMode.Exact, MisfirePolicy.Skip, 1))
    }
}

class PrecisionAndMisfireTest {
    @Test fun exactDegradesToFlexibleWithoutPermission() {
        assertEquals(PrecisionMode.Flexible, PrecisionPolicy.effective(PrecisionMode.Exact, false).mode)
        assertEquals(PrecisionMode.Exact, PrecisionPolicy.effective(PrecisionMode.Exact, false).degradedFrom)
        assertEquals(PrecisionMode.OnTime, PrecisionPolicy.effective(PrecisionMode.OnTime, false).degradedFrom)
        assertFalse(PrecisionPolicy.effective(PrecisionMode.Flexible, false).degraded)
        assertFalse(PrecisionPolicy.effective(PrecisionMode.Exact, true).degraded)
        assertTrue(PrecisionPolicy.onTimeOverCap(6)); assertFalse(PrecisionPolicy.onTimeOverCap(5))
    }

    @Test fun paramParsing() {
        assertEquals(PrecisionMode.Exact, PrecisionMode.fromParam(null))
        assertEquals(PrecisionMode.OnTime, PrecisionMode.fromParam("On-Time"))
        assertEquals(PrecisionMode.Flexible, PrecisionMode.fromParam("flexible"))
        assertEquals(PrecisionMode.OnTime, PrecisionMode.fromDb(PrecisionMode.OnTime.db))
        assertEquals(MisfirePolicy.RunOnce, MisfirePolicy.fromParam("nonsense"))
        assertEquals(MisfirePolicy.RunAll, MisfirePolicy.fromParam("run_all"))
    }

    private val now = 100 * 3_600_000L
    private val h = 3_600_000L

    @Test fun runOnceRunsMostRecentAndSupersedesOthers() {
        val d = MisfirePlanner.plan(MisfirePolicy.RunOnce, listOf(now - 3 * h, now - 2 * h, now - h), now, 12 * h)
        assertEquals(listOf(now - h), d.run)
        assertEquals(listOf("SupersededByCatchUp", "SupersededByCatchUp"), d.skipped.map { it.reason })
    }

    @Test fun skipPolicySkipsAll() {
        val d = MisfirePlanner.plan(MisfirePolicy.Skip, listOf(now - h), now, 12 * h)
        assertTrue(d.run.isEmpty()); assertEquals("PolicySkip", d.skipped.single().reason)
    }

    @Test fun tooLateSlotsAreAlwaysSkipped() {
        val d = MisfirePlanner.plan(MisfirePolicy.RunAll, listOf(now - 13 * h, now - h), now, 12 * h)
        assertEquals(listOf(now - h), d.run)
        assertEquals("TooLate", d.skipped.single().reason)
    }

    @Test fun runAllIsCappedAtFiveMostRecent() {
        val slots = (1..8).map { now - it * 10 * 60_000L }.reversed()
        val d = MisfirePlanner.plan(MisfirePolicy.RunAll, slots, now, 12 * h)
        assertEquals(5, d.run.size)
        assertEquals(slots.takeLast(5), d.run)
        assertEquals(3, d.skipped.count { it.reason == "RunAllCap" })
    }

    @Test fun defaultLatenessIsHalfDayOrOneInterval() {
        val utc = ZoneOffset.UTC
        assertEquals(12 * h, MisfirePlanner.defaultMaxLatenessMs(SlotConfig("daily", zone = utc), 0))
        assertEquals(5 * 60_000L, MisfirePlanner.defaultMaxLatenessMs(SlotConfig("minutes", zone = utc), 5 * 60_000L))
    }

    @Test fun latenessClassification() {
        assertEquals("FIRED", Lateness.classify(PrecisionMode.Exact, 1_000, 1_000 + 60_000).state)
        assertEquals("LATE", Lateness.classify(PrecisionMode.Exact, 1_000, 1_000 + 60_001).state)
        assertEquals("FIRED", Lateness.classify(PrecisionMode.Flexible, 0, 14 * 60_000).state)
        assertEquals(0, Lateness.classify(PrecisionMode.Exact, 5_000, 1_000).latenessMs)
    }

    @Test fun alarmKeysAreStableAndDistinct() {
        assertEquals(AlarmKeys.requestCode("f", "s"), AlarmKeys.requestCode("f", "s"))
        assertNotEquals(AlarmKeys.requestCode("f", "s"), AlarmKeys.requestCode("f", "t"))
        assertTrue(AlarmKeys.requestCode("f", "s") > 0xff)
        assertEquals("kaivo-alarm://schedule/a%2Fb/s+1", AlarmKeys.scheduleUri("a/b", "s 1"))
    }
}

/** An in-memory model of schedule_state and expected_run, to prove Reconciler passes converge. */
class ScheduleSyncPlannerTest {
    private val utc = ZoneOffset.UTC
    private val min = 60_000L
    private val t0 = 1_000_000_000_000L

    private fun interval(flow: String = "f", minutes: Double = 10.0, policy: MisfirePolicy = MisfirePolicy.RunOnce, precision: PrecisionMode = PrecisionMode.Exact): DesiredSchedule {
        val cfg = SlotConfig("minutes", intervalMinutes = minutes, zone = utc)
        val lateness = MisfirePlanner.defaultMaxLatenessMs(cfg, SlotMath.intervalMs(cfg, precision))
        return DesiredSchedule(flow, "s", cfg, precision, policy, lateness, SlotMath.configHash(cfg, precision, policy, lateness))
    }

    private class Model {
        val stored = HashMap<Pair<String, String>, StoredSchedule>()
        val pending = HashSet<Triple<String, String, Long>>()
        val ran = ArrayList<Long>()
        val skipped = ArrayList<MisfirePlanner.SkippedSlot>()

        fun apply(ops: List<SyncOp>) {
            for (op in ops) when (op) {
                is SyncOp.Disarm -> { stored.remove(op.flowId to op.sparkId); pending.removeAll { it.first == op.flowId && it.second == op.sparkId } }
                is SyncOp.Arm -> {
                    if (op.supersedePending) pending.removeAll { it.first == op.desired.flowId && it.second == op.desired.sparkId }
                    stored[op.desired.flowId to op.desired.sparkId] = StoredSchedule(op.desired.flowId, op.desired.sparkId, op.desired.configHash, op.slotMs, stored[op.desired.flowId to op.desired.sparkId]?.lastSlotMs)
                    pending += Triple(op.desired.flowId, op.desired.sparkId, op.slotMs)
                }
                is SyncOp.CatchUp -> {
                    ran += op.run; skipped += op.skipped
                    val all = op.run + op.skipped.map { it.slot }
                    pending.removeAll { p -> p.first == op.flowId && p.second == op.sparkId && p.third in all }
                    all.maxOrNull()?.let { last ->
                        val k = op.flowId to op.sparkId
                        stored[k] = stored[k]!!.copy(lastSlotMs = last)
                    }
                }
            }
        }

        fun elapsed(now: Long, graceMs: Long) = pending.filter { it.third + graceMs < now }.map { ElapsedSlot(it.first, it.second, it.third) }
    }

    private fun plan(m: Model, d: List<DesiredSchedule>, now: Long, exact: Boolean = true, recompute: Boolean = false, misfire: Boolean = true) =
        ScheduleSyncPlanner.plan(d, m.stored.values.toList(), m.elapsed(now, Lateness.elapsedGraceMs(PrecisionMode.Exact)), now, exact, misfire, recompute)

    @Test fun firstArmFromNothingPlansOneAheadAndNoCatchUp() {
        val ops = plan(Model(), listOf(interval()), t0)
        val arm = ops.single() as SyncOp.Arm
        assertEquals(t0 + 10 * min, arm.slotMs)
        assertFalse(arm.supersedePending)
    }

    @Test fun reconcilingTwiceChangesNothing() {
        val m = Model()
        val d = listOf(interval())
        m.apply(plan(m, d, t0))
        val first = m.stored.toMap(); val pend = m.pending.toSet()
        val again = plan(m, d, t0 + 1_000)
        assertTrue("no catch-up on a healthy state", again.none { it is SyncOp.CatchUp })
        m.apply(again)
        assertEquals(first, m.stored); assertEquals(pend, m.pending)
    }

    @Test fun longOutageRunOnceCatchesUpOnceAndRearmsOnGrid() {
        val m = Model()
        val d = listOf(interval(minutes = 10.0))
        m.apply(plan(m, d, t0))
        val firstSlot = t0 + 10 * min
        val now = firstSlot + 95 * min // device was dead for 95 minutes
        val ops = plan(m, d, now)
        val catchUp = ops.filterIsInstance<SyncOp.CatchUp>().single()
        assertEquals("RunOnce runs only the most recent in-lateness slot", 1, catchUp.run.size)
        assertEquals(firstSlot + 90 * min, catchUp.run.single())
        assertTrue(catchUp.skipped.isNotEmpty())
        val arm = ops.filterIsInstance<SyncOp.Arm>().single()
        assertEquals("next slot stays on the 10-minute grid", firstSlot + 100 * min, arm.slotMs)
        m.apply(ops)
        val settle = plan(m, d, now + 1_000)
        assertTrue(settle.none { it is SyncOp.CatchUp })
    }

    @Test fun changedConfigSupersedesInsteadOfCatchingUp() {
        val m = Model()
        m.apply(plan(m, listOf(interval(minutes = 10.0)), t0))
        val ops = plan(m, listOf(interval(minutes = 20.0)), t0 + 120 * min)
        assertTrue(ops.none { it is SyncOp.CatchUp })
        assertTrue((ops.single() as SyncOp.Arm).supersedePending)
    }

    @Test fun clockChangeRecomputesAndSupersedes() {
        val m = Model()
        m.apply(plan(m, listOf(interval()), t0))
        val ops = plan(m, listOf(interval()), t0 + 60 * min, recompute = true, misfire = false)
        val arm = ops.single() as SyncOp.Arm
        assertTrue(arm.supersedePending)
        assertEquals(t0 + 70 * min, arm.slotMs)
    }

    @Test fun removedFlowIsDisarmed() {
        val m = Model()
        m.apply(plan(m, listOf(interval("a"), interval("b")), t0))
        val ops = plan(m, listOf(interval("a")), t0 + min)
        assertEquals(listOf(SyncOp.Disarm("b", "s")), ops.filterIsInstance<SyncOp.Disarm>())
        m.apply(ops)
        assertEquals(setOf("a" to "s"), m.stored.keys)
    }

    @Test fun missingExactPermissionArmsFlexible() {
        val ops = plan(Model(), listOf(interval()), t0, exact = false)
        val arm = ops.single() as SyncOp.Arm
        assertEquals(PrecisionMode.Flexible, arm.mode)
        assertEquals(PrecisionMode.Exact, arm.degradedFrom)
    }

    @Test fun skipPolicyLogsSkippedAndRunsNothing() {
        val m = Model()
        val d = listOf(interval(policy = MisfirePolicy.Skip))
        m.apply(plan(m, d, t0))
        val ops = plan(m, d, t0 + 40 * min)
        val c = ops.filterIsInstance<SyncOp.CatchUp>().single()
        assertTrue(c.run.isEmpty()); assertTrue(c.skipped.all { it.reason == "PolicySkip" || it.reason == "TooLate" })
    }

    @Test fun slotInsideGraceIsNotYetMissed() {
        val m = Model()
        val d = listOf(interval())
        m.apply(plan(m, d, t0))
        val ops = plan(m, d, t0 + 10 * min + 2 * min) // 2 minutes late, grace is 5
        assertTrue(ops.none { it is SyncOp.CatchUp })
        assertEquals("the pending slot is kept, not replaced", t0 + 10 * min, (ops.single() as SyncOp.Arm).slotMs)
    }
}
