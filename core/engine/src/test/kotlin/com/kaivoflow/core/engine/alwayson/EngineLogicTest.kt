package com.kaivoflow.core.engine.alwayson

import com.kaivoflow.core.engine.alwayson.EngineState.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class EngineLogicTest {
    private fun r(s: EngineState, e: EngineEvent) = EngineStateMachine.reduce(s, e)

    @Test fun startPath() {
        var s = Stopped
        s = r(s, EngineEvent.StartRequested); assertEquals(Starting, s)
        s = r(s, EngineEvent.ServiceStarted); assertEquals(Running, s)
        s = r(s, EngineEvent.DegradedReported(true)); assertEquals(Degraded, s)
        s = r(s, EngineEvent.ServiceStarted); assertEquals("a repeat start does not clear Degraded", Degraded, s)
        s = r(s, EngineEvent.DegradedReported(false)); assertEquals(Running, s)
    }

    @Test fun refusedBackgroundStartBlocksUntilATapSucceeds() {
        var s = r(Stopped, EngineEvent.StartRequested)
        s = r(s, EngineEvent.StartDenied("not allowed")); assertEquals(StartBlocked, s)
        s = r(s, EngineEvent.StartRequested); assertEquals(Starting, s)
        s = r(s, EngineEvent.ServiceStarted); assertEquals(Running, s)
    }

    @Test fun denialWhileRunningDoesNotDowngrade() {
        assertEquals(Running, r(Running, EngineEvent.StartDenied("x")))
        assertEquals(Degraded, r(Degraded, EngineEvent.StartDenied("x")))
    }

    @Test fun deathVersusCleanStop() {
        assertEquals(DeadSuspected, r(Running, EngineEvent.ServiceStopped(clean = false)))
        assertEquals(Stopped, r(Running, EngineEvent.ServiceStopped(clean = true)))
        assertEquals("a stop after a requested stop is not a death", Stopped, r(Stopped, EngineEvent.ServiceStopped(clean = false)))
        assertEquals(Stopped, r(Running, EngineEvent.StopRequested))
        assertEquals(DeadSuspected, r(Running, EngineEvent.HeartbeatStale))
        assertEquals(Stopped, r(Stopped, EngineEvent.HeartbeatStale))
    }

    @Test fun decisionTable() {
        assertEquals(EngineAction.Start, EngineDecision.decide(true, Stopped))
        assertEquals(EngineAction.Start, EngineDecision.decide(true, DeadSuspected))
        assertEquals(EngineAction.Start, EngineDecision.decide(true, StartBlocked))
        assertEquals(EngineAction.None, EngineDecision.decide(true, Running))
        assertEquals(EngineAction.None, EngineDecision.decide(true, Starting))
        assertEquals(EngineAction.Stop, EngineDecision.decide(false, Running))
        assertEquals(EngineAction.Stop, EngineDecision.decide(false, Degraded))
        assertEquals(EngineAction.None, EngineDecision.decide(false, Running, transientHold = true))
        assertEquals(EngineAction.None, EngineDecision.decide(false, Stopped))
        assertEquals(EngineAction.None, EngineDecision.decide(false, StartBlocked))
    }

    @Test fun throttleAllowsOnePerKeyPerHour() {
        val c = FakeClock(); val t = AlertThrottle(c)
        assertTrue(t.tryAcquire("fgs")); assertFalse(t.tryAcquire("fgs")); assertTrue(t.tryAcquire("other"))
        c.advance(59 * 60_000); assertFalse(t.tryAcquire("fgs"))
        c.advance(2 * 60_000); assertTrue(t.tryAcquire("fgs"))
    }

    @Test fun livenessThresholds() {
        val now = 10_000_000L
        assertTrue(LivenessPolicy.isStale(null, now, false))
        assertFalse(LivenessPolicy.isStale(now - 24 * 60_000, now, false))
        assertTrue(LivenessPolicy.isStale(now - 26 * 60_000, now, false))
        assertTrue(LivenessPolicy.isStale(now - 4 * 60_000, now, true))
        assertFalse(LivenessPolicy.isStale(now - 2 * 60_000, now, true))
    }

    @Test fun restartBoundHoldsWithEitherSafetyNetAlone() {
        assertTrue(LivenessPolicy.worstCaseRestartMs() <= LivenessPolicy.RESTART_BOUND_MS)
        assertTrue("the watchdog alone, with slack, is also within the bound", LivenessPolicy.WATCHDOG_INTERVAL_MS + 60_000 <= LivenessPolicy.RESTART_BOUND_MS)
    }

    @Test fun everyTriggerRunsTheRepairRoutineWithTheRightScope() {
        ReconcileTrigger.entries.forEach { t ->
            val s = ReconcilePolicy.scopeFor(t)
            if (t != ReconcileTrigger.RoutesChanged) assertTrue("$t re-arms", s.rearmAlarms)
            assertEquals("only Boot dispatches the Boot Spark ($t)", t == ReconcileTrigger.Boot, s.dispatchBootSpark)
            assertEquals("only clock changes recompute ($t)", t == ReconcileTrigger.TimeChanged || t == ReconcileTrigger.TimezoneChanged, s.recomputeSchedules)
        }
        assertTrue(ReconcilePolicy.scopeFor(ReconcileTrigger.Pulse).ensureEngine)
        assertEquals(ReliabilityKinds.BOOT_RECOVERED, ReconcilePolicy.scopeFor(ReconcileTrigger.Boot).eventKind)
    }
}

class WakeLockTest {
    private val c = FakeClock(1_700_000_000_000L)
    private val ledger = WakeLockLedger(c, ZoneOffset.UTC)

    @Test fun runTimeoutIsFlowTimeoutPlus30sCappedAt15min() {
        assertEquals(60_000L + 30_000L, WakeLockPolicy.runTimeoutMs(60_000))
        assertEquals(15 * 60_000L, WakeLockPolicy.runTimeoutMs(15 * 60_000))
        assertEquals(15 * 60_000L, WakeLockPolicy.runTimeoutMs(24 * 3_600_000L))
        assertEquals(30_000L, WakeLockPolicy.runTimeoutMs(-5))
    }

    @Test fun intakeLockNeverExceeds20s() {
        assertEquals(20_000L, WakeLockPolicy.intakeTimeoutMs())
        assertEquals(20_000L, WakeLockPolicy.intakeTimeoutMs(90_000))
        assertEquals(5_000L, WakeLockPolicy.intakeTimeoutMs(5_000))
        assertEquals(1L, WakeLockPolicy.intakeTimeoutMs(0))
    }

    @Test fun releaseCountsHeldTime() {
        val t = ledger.acquired("run", 60_000)
        c.advance(12_000)
        assertEquals(12_000, ledger.released(t))
        assertEquals(0, ledger.released(t)) // second release is ignored
        val m = ledger.metrics()
        assertEquals(12_000, m.millisToday); assertEquals(1, m.acquiredToday); assertEquals(0, m.heldNow)
    }

    @Test fun neverCountsPastTheTimeoutBecauseThePlatformDropsTheLock() {
        val t = ledger.acquired("run", 20_000)
        c.advance(10 * 60_000) // caller forgot to release
        assertEquals(20_000, ledger.released(t))
        assertEquals(1, ledger.metrics().timedOutToday)
    }

    @Test fun openLockCountsLiveAndSweepRemovesExpired() {
        ledger.acquired("a", 30_000)
        c.advance(10_000)
        assertEquals(10_000, ledger.metrics().millisToday)
        assertEquals(1, ledger.metrics().heldNow)
        c.advance(60_000)
        assertEquals("live total is capped at the timeout", 30_000, ledger.metrics().millisToday)
        assertEquals(1, ledger.sweepExpired().size)
        assertEquals(0, ledger.metrics().heldNow)
        assertEquals(30_000, ledger.metrics().millisToday)
    }

    @Test fun spansAreSplitAtMidnight() {
        val midnight = (c.t / 86_400_000L + 1) * 86_400_000L
        c.t = midnight - 5_000
        val t = ledger.acquired("run", 60_000)
        c.advance(20_000)
        ledger.released(t)
        assertEquals("only the part after midnight counts today", 15_000, ledger.metrics().millisToday)
    }

    @Test fun dailyWarningThreshold() {
        val t = ledger.acquired("run", WakeLockPolicy.RUN_HARD_MAX_MS)
        c.advance(WakeLockPolicy.RUN_HARD_MAX_MS)
        ledger.released(t)
        assertFalse(ledger.metrics().overDailyWarning)
        repeat(4) { val k = ledger.acquired("run", WakeLockPolicy.RUN_HARD_MAX_MS); c.advance(WakeLockPolicy.RUN_HARD_MAX_MS); ledger.released(k) }
        assertTrue(ledger.metrics().overDailyWarning)
    }

    @Test fun quietDayStaysUnder10Minutes() {
        repeat(40) { val t = ledger.acquired("intake", 20_000); c.advance(300); ledger.released(t) }
        assertTrue(ledger.metrics().millisToday < 10 * 60_000)
    }
}
