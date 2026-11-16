package com.kaivoflow.core.engine.alwayson

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class FgsAndLeaseTest {

    // ---- Foreground start policy ----

    @Test fun everyStartReasonIsClassified() {
        StartReason.entries.forEach { FgsStartPolicy.classify(it) } // exhaustive when: a new reason must pick a class
    }

    @Test fun eventsAndGenericReassessNeverStartAServiceEvenWithTheBatteryExemption() {
        listOf(StartReason.IntakeEvent, StartReason.Reassess).forEach {
            assertEquals(StartClass.Forbidden, FgsStartPolicy.classify(it))
            assertFalse(it.name, FgsStartPolicy.mayStart(it, batteryExempt = true))
            assertFalse(it.name, FgsStartPolicy.mayStart(it, batteryExempt = false))
        }
    }

    @Test fun exemptTriggersAlwaysMayStart() {
        listOf(
            StartReason.UserOpen, StartReason.UserTap, StartReason.Publish, StartReason.Boot, StartReason.PackageReplaced,
            StartReason.TimeChanged, StartReason.Alarm, StartReason.RestartAlarm, StartReason.Pulse, StartReason.Geofence,
        ).forEach {
            assertTrue(it.name, FgsStartPolicy.mayStart(it, batteryExempt = false))
        }
    }

    @Test fun watchdogNeedsTheBatteryExemption() {
        assertFalse(FgsStartPolicy.mayStart(StartReason.Watchdog, batteryExempt = false))
        assertTrue(FgsStartPolicy.mayStart(StartReason.Watchdog, batteryExempt = true))
        assertFalse(FgsStartPolicy.mayStart(StartReason.ExactAlarmPermission, batteryExempt = false))
    }

    @Test fun theSystemRestartsStickyServicesItself() {
        assertEquals(StartClass.SystemOwned, FgsStartPolicy.classify(StartReason.StickyRestart))
        assertFalse(FgsStartPolicy.mayStart(StartReason.StickyRestart, true))
    }

    // ---- Crash record ----

    private fun row(id: Long, state: InboxRowState, lease: Long?, run: String?) =
        InboxRecord(id, "f", "s", "k", null, null, 0, state, "key$id", run, lease, null)

    @Test fun onlyStartedClaimsWithAnExpiredLeaseAreCrashed() {
        val rows = listOf(
            row(1, InboxRowState.Claimed, 100, "run-1"),   // crashed
            row(2, InboxRowState.Claimed, 100, null),      // never started: re-offered, not crashed
            row(3, InboxRowState.Claimed, 900, "run-3"),   // lease still live
            row(4, InboxRowState.Done, 100, "run-4"),
            row(5, InboxRowState.Claimed, null, "run-5"),  // started, lease cleared: crashed
        )
        assertEquals(listOf(1L, 5L), RunCrash.find(rows, now = 500).map { it.record.id })
    }

    @Test fun crashMessageSaysItWasNotRepeatedAndIsRecognisable() {
        val m = RunCrash.message("run-1", leaseExpiredAt = 1_000, detectedAt = 31_000)
        assertTrue(m.startsWith(RunCrash.ERROR_PREFIX))
        assertTrue(m.contains("not repeated")); assertTrue(m.contains("30 s"))
        assertTrue(RunCrash.isCrashMessage(m)); assertFalse(RunCrash.isCrashMessage("Run canceled.")); assertFalse(RunCrash.isCrashMessage(null))
        assertTrue(RunCrash.message("r", null, 5).startsWith(RunCrash.ERROR_PREFIX))
    }

    // ---- Dispatcher: Crashed outcome and fallback worker entry ----

    private val clock = FakeClock()
    private val store = MemInboxStore(clock)
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Counting : RunExecutor {
        val ran = Collections.synchronizedList(ArrayList<Long>())
        override suspend fun execute(record: InboxRecord, onStarted: suspend (String) -> Unit): RunOutcome {
            onStarted("run-${record.id}"); ran += record.id; return RunOutcome.Completed("run-${record.id}", "Succeeded")
        }
    }

    private suspend fun add(flow: String, n: Int): Long =
        store.insert(NewInboxRow(flow, "s", "webhook", null, null, clock.now(), "k:$flow:$n"))!!

    @Test fun crashedClaimIsRecordedOnceAndNeverRun() = runBlocking {
        val id = add("f", 1)
        store.claim(id, clock.now(), clock.now() + 60_000)
        store.markStarted(id, "run-x", clock.now() + 60_000)
        clock.advance(61_000)
        val seen = ArrayList<CrashedClaim>()
        val exec = Counting(); val s = scope()
        val d = RunDispatcher(store, exec, clock, s, listener = object : DispatchListener {
            override suspend fun onCrashed(claims: List<CrashedClaim>) { seen += claims }
        })
        d.pump(); d.pump(); d.awaitRunning()
        assertTrue(exec.ran.isEmpty())
        assertEquals(1, seen.size)
        assertEquals("run-x", seen[0].runId)
        assertEquals(RunCrash.DROP_REASON, store.get(id)!!.dropReason)
        assertTrue(seen[0].message().startsWith(RunCrash.ERROR_PREFIX))
        s.cancel()
    }

    @Test fun markCrashedRefusesALiveOrUnstartedClaim() = runBlocking {
        val a = add("f", 1); val b = add("f", 2)
        store.claim(a, clock.now(), clock.now() + 60_000); store.markStarted(a, "r", clock.now() + 60_000)
        assertFalse("lease still live", store.markCrashed(a, clock.now()))
        store.claim(b, clock.now(), clock.now() - 1)
        assertFalse("never started", store.markCrashed(b, clock.now() + 10))
    }

    @Test fun workerEntryRunsTheRowOnceAndReturnsAtOnceWhenItIsAlreadyDone() = runBlocking {
        val id = add("f", 1)
        val exec = Counting(); val s = scope()
        val d = RunDispatcher(store, exec, clock, s)
        assertEquals(RunDispatcher.RowWait.Finished, d.drainUntilFinished(id, 5_000))
        d.awaitRunning()
        assertEquals(listOf(id), exec.ran.toList())
        // The Engine (or an earlier worker) already ran it: nothing runs a second time.
        assertEquals(RunDispatcher.RowWait.Finished, d.drainUntilFinished(id, 5_000))
        d.awaitRunning()
        assertEquals("never double-runs a row", listOf(id), exec.ran.toList())
        s.cancel()
    }

    @Test fun workerEntryRespectsArrivalOrderWithinAFlow() = runBlocking {
        val first = add("f", 1); val second = add("f", 2)
        val exec = Counting(); val s = scope()
        val d = RunDispatcher(store, exec, clock, s)
        assertEquals(RunDispatcher.RowWait.Finished, d.drainUntilFinished(second, 5_000))
        assertEquals("the earlier row of the same Flow ran first", listOf(first, second), exec.ran.toList())
        s.cancel()
    }

    @Test fun workerEntryReportsMissingRowsAndTimesOutOnAForeignLiveClaim() = runBlocking {
        val exec = Counting(); val s = scope()
        val d = RunDispatcher(store, exec, clock, s)
        assertEquals(RunDispatcher.RowWait.Missing, d.drainUntilFinished(999, 100))
        val id = add("f", 1)
        store.claim(id, clock.now(), clock.now() + 600_000) // another holder with a live lease
        val wait = RunDispatcher.RowWait.TimedOut
        // The fake clock does not move, so use a zero budget: the loop must give up instead of spinning.
        assertEquals(wait, d.drainUntilFinished(id, 0))
        assertTrue(exec.ran.isEmpty())
        s.cancel()
    }

    @Test fun outcomeCarriesTheResponseJsonForWaitingCallers() {
        val c = RunOutcome.Completed("r", "Succeeded", "{\"a\":1}")
        assertEquals("{\"a\":1}", c.responseJson)
        assertNull(RunOutcome.Completed("r").responseJson)
    }
}
