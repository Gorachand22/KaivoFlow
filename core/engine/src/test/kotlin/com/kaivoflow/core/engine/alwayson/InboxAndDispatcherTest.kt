package com.kaivoflow.core.engine.alwayson

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class InboxAndDispatcherTest {

    private val clock = FakeClock()
    private val store = MemInboxStore(clock)
    private val files = MemFiles()

    private fun inbox(onDup: suspend (NewSpark) -> Unit = {}) = SparkInbox(store, files, clock, onDuplicate = onDup)

    private fun spark(flow: String, n: Int, key: String = DedupeKeys.event("sms", flow, "s1", "$n")) =
        NewSpark(flow, "s1", "sms", "{\"n\":$n}", key)

    // ---- SparkInbox ----

    @Test fun duplicateKeyIsDroppedAndRecorded() = runBlocking {
        val dropped = ArrayList<String>()
        val box = inbox { dropped += it.dedupeKey }
        val key = DedupeKeys.schedule("f1", 12_345L)
        assertTrue(box.enqueue(NewSpark("f1", "s", "scheduled", null, key)) is EnqueueResult.Stored)
        assertTrue(box.enqueue(NewSpark("f1", "s", "scheduled", null, key)) is EnqueueResult.Duplicate)
        assertEquals(1, store.snapshot.size)
        assertEquals(listOf(key), dropped)
    }

    @Test fun largePayloadIsSpilledToFileAndReadBack() = runBlocking {
        val box = inbox()
        val big = "x".repeat(200_000)
        val r = box.enqueue(NewSpark("f", "s", "webhook", big, "k-big")) as EnqueueResult.Stored
        assertTrue(r.spilledToFile)
        val row = store.get(r.id)!!
        assertNull(row.payload); assertNotNull(row.fileRef)
        assertEquals(big, box.payloadOf(row))
        box.discardPayload(row)
        assertTrue(files.files.isEmpty())
    }

    @Test fun smallPayloadStaysInline() = runBlocking {
        val box = inbox()
        val r = box.enqueue(spark("f", 1)) as EnqueueResult.Stored
        assertFalse(r.spilledToFile)
        assertEquals("{\"n\":1}", box.payloadOf(store.get(r.id)!!))
    }

    @Test fun multibytePayloadUsesByteSizeForSpill() = runBlocking {
        val box = inbox()
        val text = "é".repeat(20_000) // 20k chars, 40k bytes
        val r = box.enqueue(NewSpark("f", "s", "k", text, "k-mb")) as EnqueueResult.Stored
        assertTrue(r.spilledToFile)
    }

    @Test fun scheduleKeyRoundTrips() {
        assertEquals("f-1" to 99L, DedupeKeys.parseSchedule(DedupeKeys.schedule("f-1", 99)))
        assertEquals("a:b" to 5L, DedupeKeys.parseSchedule(DedupeKeys.schedule("a:b", 5)))
        assertNull(DedupeKeys.parseSchedule("event:sms:f:s:1"))
        assertNull(DedupeKeys.parseSchedule("schedule:nope"))
    }

    // ---- Lease rule ----

    @Test fun leaseRule() {
        assertTrue(LeasePolicy.isOffered(InboxRowState.Pending, null, null, 10))
        assertFalse(LeasePolicy.isOffered(InboxRowState.Claimed, 100, null, 50))
        assertTrue(LeasePolicy.isOffered(InboxRowState.Claimed, 100, null, 101))
        assertFalse("a started run is never replayed", LeasePolicy.isOffered(InboxRowState.Claimed, 100, "run", 500))
        assertTrue(LeasePolicy.isCrashed(InboxRowState.Claimed, 100, "run", 500))
        assertFalse(LeasePolicy.isCrashed(InboxRowState.Claimed, 100, "run", 50))
        assertFalse(LeasePolicy.isOffered(InboxRowState.Done, null, null, 0))
    }

    // ---- Dispatcher ----

    private class Recorder : RunExecutor {
        val order = Collections.synchronizedList(ArrayList<Pair<String, Int>>())
        val active = AtomicInteger(); val maxActive = AtomicInteger()
        val perFlowActive = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        @Volatile var perFlowOverlap = false
        override suspend fun execute(record: InboxRecord, onStarted: suspend (String) -> Unit): RunOutcome {
            onStarted("run-${record.id}")
            val n = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, n) }
            val f = perFlowActive.getOrPut(record.flowId) { AtomicInteger() }
            if (f.incrementAndGet() > 1) perFlowOverlap = true
            yield(); delay(1)
            order += record.flowId to record.payload!!.filter { it.isDigit() }.toInt()
            f.decrementAndGet(); active.decrementAndGet()
            return RunOutcome.Completed("run-${record.id}")
        }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test fun twoHundredEventsRunInOrderWithBoundedConcurrency() = runBlocking {
        val box = inbox()
        val flows = listOf("a", "b", "c", "d")
        repeat(200) { box.enqueue(spark(flows[it % 4], it)) }
        val exec = Recorder()
        val s = scope()
        val d = RunDispatcher(store, exec, clock, s, DispatcherConfig(maxConcurrent = 3))
        d.drainUntilIdle()
        d.awaitRunning()
        assertEquals(200, exec.order.size)
        flows.forEach { f ->
            val seq = exec.order.filter { it.first == f }.map { it.second }
            assertEquals("order for $f", seq.sorted(), seq)
        }
        assertTrue("global cap", exec.maxActive.get() <= 3)
        assertFalse("one Run per Flow", exec.perFlowOverlap)
        assertTrue(store.snapshot.all { it.state == InboxRowState.Done && it.runId != null })
        s.cancel()
    }

    @Test fun singleFlowTwoHundredEventsStrictlySequential() = runBlocking {
        val box = inbox()
        repeat(200) { box.enqueue(spark("only", it)) }
        val exec = Recorder()
        val s = scope()
        val d = RunDispatcher(store, exec, clock, s)
        d.drainUntilIdle(); d.awaitRunning()
        assertEquals((0 until 200).toList(), exec.order.map { it.second })
        assertEquals(1, exec.maxActive.get())
        s.cancel()
    }

    @Test fun crashMidClaimBeforeStartIsOfferedAgainAfterLease() = runBlocking {
        val box = inbox()
        val id = (box.enqueue(spark("f", 1)) as EnqueueResult.Stored).id
        // A previous process claimed the row and died before its Run started.
        assertTrue(store.claim(id, clock.now(), clock.now() + 60_000))
        val exec = Recorder()
        val s = scope()
        val d = RunDispatcher(store, exec, clock, s)
        d.pump(); d.awaitRunning()
        assertTrue("lease still live: nothing runs", exec.order.isEmpty())
        clock.advance(60_001)
        d.drainUntilIdle(); d.awaitRunning()
        assertEquals(1, exec.order.size)
        assertEquals(InboxRowState.Done, store.get(id)!!.state)
        s.cancel()
    }

    @Test fun startedRunWhoseProcessDiedIsNotReplayed() = runBlocking {
        val box = inbox()
        val id = (box.enqueue(spark("f", 1)) as EnqueueResult.Stored).id
        store.claim(id, clock.now(), clock.now() + 60_000)
        store.markStarted(id, "run-x", clock.now() + 60_000)
        clock.advance(120_000)
        val interrupted = AtomicInteger()
        val exec = Recorder()
        val s = scope()
        val d = RunDispatcher(store, exec, clock, s, listener = object : DispatchListener {
            override suspend fun onInterrupted(count: Int) { interrupted.addAndGet(count) }
        })
        d.drainUntilIdle()
        assertTrue(exec.order.isEmpty())
        assertEquals(InboxRowState.Dropped, store.get(id)!!.state)
        assertEquals("RunInterrupted", store.get(id)!!.dropReason)
        assertEquals(1, interrupted.get())
        s.cancel()
    }

    @Test fun busyFlowIsReleasedAndRetriedAfterBackoff() = runBlocking {
        val box = inbox()
        val id = (box.enqueue(spark("f", 1)) as EnqueueResult.Stored).id
        val calls = AtomicInteger()
        val exec = RunExecutor { _, started ->
            if (calls.incrementAndGet() == 1) RunOutcome.Busy else { started("r"); RunOutcome.Completed("r") }
        }
        val s = scope()
        val d = RunDispatcher(store, exec, clock, s, backoffMs = { 5_000L })
        d.pump(); d.awaitRunning()
        assertEquals(InboxRowState.Pending, store.get(id)!!.state)
        val blocked = d.pump()
        assertEquals("cooldown blocks the Flow", 0, blocked.started)
        assertNotNull(blocked.waitingForCooldownUntil)
        clock.advance(5_001)
        d.pump(); d.awaitRunning()
        assertEquals(InboxRowState.Done, store.get(id)!!.state)
        assertEquals(2, calls.get())
        s.cancel()
    }

    @Test fun droppedOutcomeFinishesRowWithReason() = runBlocking {
        val box = inbox()
        val id = (box.enqueue(spark("gone", 1)) as EnqueueResult.Stored).id
        val s = scope()
        val d = RunDispatcher(store, { _, _ -> RunOutcome.Dropped("FlowNotLive") }, clock, s)
        d.drainUntilIdle(); d.awaitRunning()
        assertEquals(InboxRowState.Dropped, store.get(id)!!.state)
        assertEquals("FlowNotLive", store.get(id)!!.dropReason)
        s.cancel()
    }

    @Test fun executorErrorBeforeStartIsRetriedThenDropped() = runBlocking {
        val box = inbox()
        val id = (box.enqueue(spark("f", 1)) as EnqueueResult.Stored).id
        val s = scope()
        val d = RunDispatcher(store, { _, _ -> error("boom") }, clock, s,
            DispatcherConfig(maxExecutorErrors = 3), backoffMs = { 0L })
        repeat(5) { d.pump(); d.awaitRunning() }
        assertEquals(InboxRowState.Dropped, store.get(id)!!.state)
        assertTrue(store.get(id)!!.dropReason!!.startsWith("ExecutorError"))
        s.cancel()
    }

    @Test fun executorErrorAfterStartCompletesRowAndNeverReplays() = runBlocking {
        val box = inbox()
        val id = (box.enqueue(spark("f", 1)) as EnqueueResult.Stored).id
        val calls = AtomicInteger()
        val s = scope()
        val d = RunDispatcher(store, { _, started -> calls.incrementAndGet(); started("r1"); error("late failure") }, clock, s)
        repeat(3) { d.pump(); d.awaitRunning() }
        assertEquals(1, calls.get())
        assertEquals(InboxRowState.Done, store.get(id)!!.state)
        s.cancel()
    }

    @Test fun leaseIsRenewedWhileARunIsInFlight() = runBlocking {
        val box = inbox()
        val id = (box.enqueue(spark("f", 1)) as EnqueueResult.Stored).id
        val gate = CompletableDeferred<Unit>()
        val s = scope()
        val d = RunDispatcher(store, { _, started -> started("r"); gate.await(); RunOutcome.Completed("r") }, clock, s,
            DispatcherConfig(leaseMs = 60_000, renewEveryMs = 20))
        d.pump()
        delay(150)
        val first = store.get(id)!!.leaseUntil!!
        clock.advance(10_000)
        delay(150)
        assertTrue("renewal moved the lease forward", store.get(id)!!.leaseUntil!! > first)
        gate.complete(Unit); d.awaitRunning()
        assertEquals(InboxRowState.Done, store.get(id)!!.state)
        s.cancel()
    }

    @Test fun cancellationReleasesUnstartedRowAndFinishesStartedOne() = runBlocking {
        val box = inbox()
        val idA = (box.enqueue(spark("a", 1)) as EnqueueResult.Stored).id
        val idB = (box.enqueue(spark("b", 1)) as EnqueueResult.Stored).id
        val gate = CompletableDeferred<Unit>()
        val s = scope()
        val d = RunDispatcher(store, { r, started ->
            if (r.flowId == "b") started("rb")
            gate.await(); RunOutcome.Completed(null)
        }, clock, s)
        d.pump(); delay(100)
        s.cancel()
        delay(200)
        assertEquals(InboxRowState.Pending, store.get(idA)!!.state)
        assertEquals(InboxRowState.Done, store.get(idB)!!.state)
        assertEquals("rb", store.get(idB)!!.runId)
    }

    @Test fun foreignLiveClaimBlocksLaterRowsOfSameFlow() = runBlocking {
        val box = inbox()
        val first = (box.enqueue(spark("f", 1)) as EnqueueResult.Stored).id
        box.enqueue(spark("f", 2))
        store.claim(first, clock.now(), clock.now() + 60_000) // another consumer holds row 1
        val exec = Recorder()
        val s = scope()
        val d = RunDispatcher(store, exec, clock, s)
        d.pump(); d.awaitRunning()
        assertTrue("row 2 must not overtake row 1", exec.order.isEmpty())
        s.cancel()
    }
}
