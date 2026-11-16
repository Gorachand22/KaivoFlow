package com.kaivoflow.core.engine.reliability

import com.kaivoflow.core.engine.alwayson.EngineState
import com.kaivoflow.core.engine.alwayson.PrecisionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectedRunLogTest {
    private val day = RunAggregation.DAY_MS
    private fun s(state: String, due: Long, late: Long? = null, flow: String = "f", interval: Long = day) =
        ExpectedSample(flow, due, state, late, interval)

    @Test fun `missed threshold is max of 5 minutes and 10 percent of the interval`() {
        assertEquals(5 * 60_000L, MissedRule.missedAfterMs(15 * 60_000L))
        assertEquals(day / 10, MissedRule.missedAfterMs(day))
        assertFalse(MissedRule.isMissed(1000, 15 * 60_000L, 1000 + 5 * 60_000L))
        assertTrue(MissedRule.isMissed(1000, 15 * 60_000L, 1000 + 5 * 60_000L + 1))
    }

    @Test fun `sweep takes only pending rows past the threshold`() {
        val rows = listOf(s("PENDING", 0, interval = 60_000L), s("PENDING", 10 * 60_000L, interval = 60_000L), s("FIRED", 0))
        assertEquals(listOf(0L), MissedRule.sweep(rows, 6 * 60_000L).map { it.dueAt })
    }

    @Test fun `percentile uses nearest rank and handles empty`() {
        assertEquals(0L, RunAggregation.percentile(emptyList(), 95))
        val v = (1L..20L).toList()
        assertEquals(10L, RunAggregation.percentile(v, 50)); assertEquals(19L, RunAggregation.percentile(v, 95)); assertEquals(1L, RunAggregation.percentile(v, 1))
    }

    @Test fun `aggregate counts states and lateness stats`() {
        val a = RunAggregation.aggregate("f", 0, listOf(s("FIRED", 1, 2000), s("FIRED", 2, 1000), s("LATE", 3, 90_000), s("MISSED", 4), s("SKIPPED", 5), s("PAUSED", 6)))
        assertEquals(2, a.fired); assertEquals(1, a.late); assertEquals(1, a.missed); assertEquals(1, a.skipped); assertEquals(1, a.paused)
        assertEquals(2000L, a.p50Ms); assertEquals(90_000L, a.maxMs); assertEquals(4, a.measured)
    }

    @Test fun `compaction groups per flow and day and keeps recent rows out`() {
        val now = 100 * day
        val rows = listOf(s("FIRED", 10 * day + 5, 100), s("LATE", 10 * day + 9, 70_000), s("FIRED", 11 * day, 50), s("FIRED", 99 * day, 1), s("PENDING", 10 * day), s("SUPERSEDED", 10 * day), s("FIRED", 10 * day, 5, flow = "g"))
        val out = RunAggregation.compact(rows, RetentionPolicy.expectedCutoff(now))
        assertEquals(3, out.size)
        val f10 = out.first { it.flowId == "f" && it.dayStart == 10 * day }
        assertEquals(1, f10.fired); assertEquals(1, f10.late)
    }

    @Test fun `day start honours the zone offset`() {
        val offset = 5 * 3_600_000L + 30 * 60_000L
        val at = 3 * day + 20 * 3_600_000L // 20:00 UTC is already the next local day at +5:30
        assertEquals(4 * day - offset, RunAggregation.dayStart(at, offset))
    }

    @Test fun `flow reliability builds a 14 day strip and ratio from aggregates plus raw rows`() {
        val now = 50 * day + 3_600_000L
        val aggs = listOf(DailyAggregate("f", 40 * day, 1, 0, 1, 0, 0, 0, 0, 0), DailyAggregate("f", 41 * day, 1, 1, 0, 0, 0, 0, 0, 0), DailyAggregate("f", 20 * day, 5, 0, 0, 0, 0, 0, 0, 0))
        val recent = listOf(s("FIRED", 50 * day + 10, 5), s("PAUSED", 49 * day))
        val r = FlowReliabilityCalc.compute("f", aggs, recent, now)
        assertEquals(14, r.strip.size)
        assertEquals(DayOutcome.OnTime, r.strip.last())
        assertEquals(DayOutcome.Idle, r.strip[12])   // day 49: only paused
        assertEquals(DayOutcome.Late, r.strip[13 - 9]) // day 41
        assertEquals(DayOutcome.Missed, r.strip[13 - 10]) // day 40
        assertEquals(5, r.measured); assertEquals(0.6, r.onTimeRatio!!, 0.0001) // day 20 is outside the window
        assertEquals(41 * day, r.lastLateOrMissed)
    }

    @Test fun `empty history gives no ratio`() {
        assertNull(FlowReliabilityCalc.compute("f", emptyList(), emptyList(), 10 * day).onTimeRatio)
    }

    @Test fun `retention windows and the row cap`() {
        val now = 1000 * day
        assertEquals(now - 30 * day, RetentionPolicy.expectedCutoff(now))
        assertEquals(now - 180 * day, RetentionPolicy.aggregateCutoff(now))
        assertEquals(now - 90 * day, RetentionPolicy.eventCutoffForRowCap(listOf(now - 1, now - 2), now))
        val many = (1..6000).map { now - it.toLong() } // newest first by construction
        assertEquals(now - 5000, RetentionPolicy.eventCutoffForRowCap(many, now))
        val old = (1..6000).map { now - 200 * day - it }
        assertEquals(now - 90 * day, RetentionPolicy.eventCutoffForRowCap(old, now))
    }

    @Test fun `precision modes are carried`() { assertEquals(PrecisionMode.Exact, s("FIRED", 0).mode) }
}

class DeadEngineTest {
    private val min = 60_000L
    private fun facts(last: Long?, clean: Boolean = false, then: Int? = 5, now: Int? = 5) = HeartbeatFacts(last, clean, 1, then, now)

    @Test fun `a gap over 25 minutes without a clean stop is an outage`() {
        val o = DeadEngineDetector.detect(facts(0), 26 * min, "LowMemory")!!
        assertEquals(0L, o.from); assertEquals(26 * min, o.to); assertEquals("LowMemory", o.reason)
    }

    @Test fun `no outage for a short gap, a clean stop, or no heartbeat`() {
        assertNull(DeadEngineDetector.detect(facts(0), 25 * min))
        assertNull(DeadEngineDetector.detect(facts(0, clean = true), 500 * min))
        assertNull(DeadEngineDetector.detect(facts(null), 500 * min))
    }

    @Test fun `a changed boot count is reported as a reboot`() {
        assertEquals("Reboot", DeadEngineDetector.detect(facts(0, then = 5, now = 6), 60 * min, "Other")!!.reason)
        assertEquals("Unknown", DeadEngineDetector.detect(facts(0), 60 * min)!!.reason)
    }

    @Test fun `exit reason names match the platform constants`() {
        assertEquals("LowMemory", ExitReasons.name(3)); assertEquals("Crash", ExitReasons.name(4)); assertEquals("Anr", ExitReasons.name(6))
        assertEquals("UserRequested", ExitReasons.name(10)); assertEquals("Freezer", ExitReasons.name(14)); assertEquals("Unknown(99)", ExitReasons.name(99))
        assertTrue("UserRequested" in ExitReasons.forceStopped)
        assertEquals("KaivoFlow was force-stopped", ExitReasons.plain("UserStopped"))
    }

    @Test fun `alert text matches the spec and severity follows missed runs`() {
        val t = OutageAlert.build(EngineOutage(0, 1, "LowMemory"), 2, 1) { if (it == 0L) "07:12" else "08:41" }
        assertEquals("KaivoFlow's engine was not running from 07:12 to 08:41. 2 scheduled runs were missed, 1 was caught up.", t.body)
        assertTrue(t.high)
        val q = OutageAlert.build(EngineOutage(0, 1, "Crash"), 0, 0) { "x" }
        assertFalse(q.high); assertTrue(q.body.endsWith("No scheduled runs were missed."))
        assertTrue(OutageAlert.build(EngineOutage(0, 1, "x"), 1, 0) { "t" }.body.contains("1 scheduled run was missed."))
        assertFalse(OutageAlert.shouldAlert(EngineOutage(0, 1, "Reboot"), 0)); assertTrue(OutageAlert.shouldAlert(EngineOutage(0, 1, "Reboot"), 1))
    }
}

class ReliabilityChecksTest {
    private val now = 10_000_000_000L
    private fun facts(
        published: Int = 1, beat: Long? = now - 60_000, state: EngineState = EngineState.Running, exempt: Boolean = true,
        exact: Boolean = false, notif: Boolean = false, geofence: Boolean = false, sms: Boolean = false,
        oem: Boolean = false, attested: Boolean = false, soak: Long? = null, webhook: WebhookFacts? = null,
    ) = DeviceFacts(
        now = now, sdk = 34, publishedFlows = published, errorFlows = 0, alwaysOnMode = false, usesExactSchedule = exact,
        usesNotificationSpark = notif, usesSmsSpark = sms, usesSendSms = false, usesGeofence = geofence, usesWebhook = webhook != null,
        heartbeatWall = beat, engineState = state, batteryExempt = exempt, notificationsEnabled = true, engineChannelBlocked = false,
        exactAlarmAllowed = true, backgroundRestricted = false, standbyBucket = 10, listenerGranted = true,
        listenerConnectedAt = now - 1000, listenerDisconnectedAt = null, receiveSmsGranted = true, sendSmsGranted = true,
        fineLocation = true, backgroundLocation = true, locationServicesOn = true, flowPermissionGaps = emptyList(),
        aggressiveOem = oem, oemName = "Xiaomi", oemAttested = attested, soakPassedAt = soak, dataSaverStatus = 1,
        batterySaverOn = false, autoTime = true, autoZone = true, exitInfoAvailable = true, forceStoppedAt = null,
        lowMemoryKills7d = 0, webhook = webhook, freeStorageBytes = 5_000_000_000, databaseBytes = 1_000_000, integrityOk = true,
    )

    private fun by(f: DeviceFacts, id: String) = CheckEvaluator.evaluate(f).first { it.id == id }

    @Test fun `seventeen checks, every one with evidence`() {
        val all = CheckEvaluator.evaluate(facts(exact = true, notif = true, geofence = true, sms = true, oem = true, webhook = WebhookFacts(true, 8787, false, null, true)))
        assertEquals(17, all.size)
        assertTrue(all.all { it.evidence.isNotBlank() })
    }

    @Test fun `unused checks are hidden and nothing is needed without published flows`() {
        val all = CheckEvaluator.evaluate(facts(published = 0))
        assertTrue(CheckEvaluator.visible(all).isEmpty())
        val v = CheckEvaluator.visible(CheckEvaluator.evaluate(facts())).map { it.id }
        assertFalse("C4" in v); assertFalse("C6" in v); assertTrue("C1" in v)
    }

    @Test fun `heartbeat age thresholds`() {
        assertEquals(CheckState.Ok, by(facts(beat = now - 19 * 60_000), "C1").state)
        assertEquals(CheckState.Warn, by(facts(beat = now - 21 * 60_000), "C1").state)
        assertEquals(CheckState.Fail, by(facts(beat = now - 41 * 60_000), "C1").state)
        assertEquals(CheckState.Unknown, by(facts(beat = null), "C1").state)
        assertEquals(CheckState.Fail, by(facts(state = EngineState.StartBlocked), "C1").state)
    }

    @Test fun `battery exemption fail has a fix`() {
        val c = by(facts(exempt = false), "C2")
        assertEquals(CheckState.Fail, c.state); assertEquals(FixAction.BatteryExemption, c.fix)
        assertEquals(CheckState.Ok, by(facts(), "C2").state)
    }

    @Test fun `OEM check is never green without attestation and a fresh soak pass`() {
        assertEquals(CheckState.Unknown, by(facts(oem = true), "C10").state)
        assertEquals(CheckState.Unknown, by(facts(oem = true, attested = true), "C10").state)
        assertEquals(CheckState.Unknown, by(facts(oem = true, soak = now - 1000), "C10").state)
        assertEquals(CheckState.Ok, by(facts(oem = true, attested = true, soak = now - 1000), "C10").state)
        assertEquals(CheckState.Unknown, by(facts(oem = true, attested = true, soak = now - CheckEvaluator.SOAK_VALID_MS - 1), "C10").state)
        assertEquals(CheckState.NotNeeded, by(facts(oem = false), "C10").state)
    }

    @Test fun `listener state follows connect and disconnect order`() {
        val base = facts(notif = true)
        assertEquals(CheckState.Ok, by(base, "C6").state)
        assertEquals(CheckState.Fail, by(base.copy(listenerDisconnectedAt = now), "C6").state)
        assertEquals(CheckState.Unknown, by(base.copy(listenerConnectedAt = null), "C6").state)
        assertEquals(CheckState.Fail, by(base.copy(listenerGranted = false), "C6").state)
    }

    @Test fun `geofence needs all the time location`() {
        val g = facts(geofence = true)
        assertEquals(CheckState.Fail, by(g.copy(backgroundLocation = false), "C9").state)
        assertEquals(CheckState.Ok, by(g, "C9").state)
    }

    @Test fun `score is full minus the not proven cap when nothing is measured`() {
        val s = HealthScorer.score(CheckEvaluator.evaluate(facts()), null)
        assertEquals(85, s.score); assertEquals(HealthBand.MostlyReliable, s.band); assertNotNull(s.capReason)
    }

    @Test fun `measured runs can reach the reliable band`() {
        val s = HealthScorer.score(CheckEvaluator.evaluate(facts()), MeasuredFacts(30, 0.99, 0, 0))
        assertEquals(99, s.score); assertEquals(HealthBand.Reliable, s.band)
    }

    @Test fun `fewer than ten expected runs is not a measurement`() {
        assertNull(HealthScorer.score(CheckEvaluator.evaluate(facts()), MeasuredFacts(9, 1.0, 0, 0)).measuredScore)
    }

    @Test fun `measured penalties for dead engine and missed runs are capped`() {
        val s = HealthScorer.score(CheckEvaluator.evaluate(facts()), MeasuredFacts(40, 1.0, 9, 9))
        assertEquals(100 - 45 - 30, s.measuredScore)
    }

    @Test fun `heartbeat failure caps at 30`() {
        val s = HealthScorer.score(CheckEvaluator.evaluate(facts(beat = now - 60 * 60_000)), MeasuredFacts(40, 1.0, 0, 0))
        assertTrue(s.score <= 30); assertEquals(HealthBand.Unreliable, s.band)
    }

    @Test fun `unverified aggressive OEM caps at 85 even with perfect measurement`() {
        val s = HealthScorer.score(CheckEvaluator.evaluate(facts(oem = true)), MeasuredFacts(40, 1.0, 0, 0))
        assertEquals(85, s.score)
    }

    @Test fun `config score uses weights and unknown credit`() {
        val all = CheckEvaluator.evaluate(facts(exempt = false))
        val s = HealthScorer.score(all, MeasuredFacts(40, 1.0, 0, 0))
        // Applicable weights: C1 20, C2 20, C3 10, C5 10, C11 3, C12 2, C14 3, C16 2 = 70; C2 fails: 50/70.
        assertEquals(Math.round(100.0 * 50 / 70).toInt(), s.configScore)
    }

    @Test fun `fix first orders by weight lost and takes three`() {
        val f = facts(exempt = false).copy(notificationsEnabled = false, batterySaverOn = true, backgroundRestricted = true)
        assertEquals(listOf("C2", "C5", "C3"), HealthScorer.fixFirst(CheckEvaluator.evaluate(f)).map { it.id })
    }

    @Test fun `bands and banner`() {
        assertEquals(HealthBand.Reliable, HealthScorer.bandFor(90)); assertEquals(HealthBand.MostlyReliable, HealthScorer.bandFor(89))
        assertEquals(HealthBand.AtRisk, HealthScorer.bandFor(69)); assertEquals(HealthBand.Unreliable, HealthScorer.bandFor(39))
        assertTrue(HealthScorer.showHomeBanner(HealthScorer.score(CheckEvaluator.evaluate(facts(exempt = false, beat = null)), null)))
    }

    @Test fun `ago formats`() { assertEquals("45 s", CheckEvaluator.ago(45_000)); assertEquals("4 min", CheckEvaluator.ago(240_000)); assertEquals("3 h", CheckEvaluator.ago(3 * 3_600_000L)) }
}

class SelfTestCriteriaTest {
    @Test fun `st1 within 60 seconds`() {
        assertEquals(SelfTestState.Pass, SelfTestCriteria.st1(5_000, 20_000).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st1(5_000, 61_000).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st1(null, 1).state)
    }
    @Test fun `st2 limits depend on the host`() {
        assertEquals(SelfTestState.Pass, SelfTestCriteria.st2(4_000, true).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st2(6_000, true).state)
        assertEquals(SelfTestState.Pass, SelfTestCriteria.st2(29_000, false).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st2(null, false).state)
    }
    @Test fun `st3 pending then pass or fail`() {
        assertEquals(SelfTestState.Pending, SelfTestCriteria.st3(null, 60_000).state)
        assertEquals(SelfTestState.Pass, SelfTestCriteria.st3(600_000, 600_000).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st3(null, 17 * 60_000L).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st3(17 * 60_000L, 17 * 60_000L).state)
    }
    @Test fun `st4 needs 2xx with token and 401 without`() {
        assertEquals(SelfTestState.Pass, SelfTestCriteria.st4(200, 401).state)
        assertEquals(SelfTestState.Pass, SelfTestCriteria.st4(404, 401).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st4(401, 401).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st4(200, 200).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st4(null, 401).state)
    }
    @Test fun `st5 and st6`() {
        assertEquals(SelfTestState.Pass, SelfTestCriteria.st5(2_999).state); assertEquals(SelfTestState.Fail, SelfTestCriteria.st5(3_001).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st5(null).state)
        assertEquals(SelfTestState.Pass, SelfTestCriteria.st6(true, true).state); assertEquals(SelfTestState.Fail, SelfTestCriteria.st6(false, true).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st6(true, false).state)
    }
    @Test fun `st7 stock and oem thresholds`() {
        val slots = List(100) { 10_000L }
        assertEquals(SelfTestState.Pass, SelfTestCriteria.st7(slots, 0, true).state)
        val oem = List(95) { 120_000L } + List(5) { null }
        assertEquals(SelfTestState.Pass, SelfTestCriteria.st7(oem, 0, false).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st7(oem, 0, true).state)
        assertEquals(SelfTestState.Fail, SelfTestCriteria.st7(slots, 1, true).state)
        assertEquals(SelfTestState.Skipped, SelfTestCriteria.st7(emptyList(), 0, true).state)
    }
}

class RegistrationRowsTest {
    @Test fun `stored rows map back to states with a recovered failure code`() {
        assertTrue(RegistrationRows.toState("spark.webhook", "ARMED", "x").isArmed)
        assertEquals(com.kaivoflow.core.engine.FailureCode.PermissionMissing, RegistrationRows.toState("spark.geofence", "FAILED_PERMANENT", "SecurityException").failure)
        assertEquals(com.kaivoflow.core.engine.FailureCode.EngineStartBlocked, RegistrationRows.toState("spark.webhook", "FAILED_TRANSIENT", "EngineStartBlocked").failure)
        assertEquals(com.kaivoflow.core.engine.FailureCode.GeofenceRejected, RegistrationRows.toState("spark.geofence", "FAILED_TRANSIENT", "IllegalStateException").failure)
        assertEquals(com.kaivoflow.core.engine.FailureCode.InvalidConfig, RegistrationRows.toState("spark.x", "FAILED_PERMANENT", "weird").failure)
        assertFalse(RegistrationRows.toState("k", "PENDING", "EngineNotRunning").isFailure)
    }

    @Test fun `result lines use the spec wording`() {
        val tz = java.util.TimeZone.getTimeZone("UTC")
        assertTrue(RegistrationRows.nextFireText(0L, "EXACT", tz).startsWith("next fire Thu 00:00"))
        assertEquals("Armed: listener connected", RegistrationRows.resultLine(com.kaivoflow.core.engine.alwayson.SparkRegState.armed("listener connected")))
        assertTrue(RegistrationRows.resultLine(com.kaivoflow.core.engine.alwayson.SparkRegState.failed(com.kaivoflow.core.engine.FailureCode.PermissionMissing, "RECEIVE_SMS")).startsWith("Needs permission: allow SMS"))
    }
}

class OutageImpactTest {
    private val outage = EngineOutage(1000, 2000, "LowMemory")
    @Test fun `counts missed and caught up inside the gap only`() {
        val rows = listOf(
            OutageImpact.Row(1100, "MISSED", null), OutageImpact.Row(1200, "LATE", "CaughtUp"), OutageImpact.Row(1300, "SKIPPED", "TooLate"),
            OutageImpact.Row(1400, "PAUSED", null), OutageImpact.Row(1500, "FIRED", null), OutageImpact.Row(500, "MISSED", null), OutageImpact.Row(3000, "MISSED", null),
        )
        assertEquals(OutageImpact.Count(missed = 3, caughtUp = 1), OutageImpact.count(rows, outage))
    }

    @Test fun `failure text and retry text`() {
        assertEquals("Android disconnected the notification listener.", FailureText.plain("ListenerUnbound"))
        assertEquals("A Spark could not be armed.", FailureText.plain(null))
        assertEquals("Waiting for you to fix it", FailureText.retryText(null, 0))
        assertEquals("Retrying in 5 min", FailureText.retryText(300_000, 0)); assertEquals("Retrying in 15 s", FailureText.retryText(15_000, 0))
    }
}
