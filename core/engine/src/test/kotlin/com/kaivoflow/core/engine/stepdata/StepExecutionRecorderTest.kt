package com.kaivoflow.core.engine.stepdata

import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.StepRunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepExecutionRecorderTest {
    private fun cap(i: Int, n: Int = 3) = StepExecutionCapture(
        key = StepExecutionKey("r", "s$i", 0), flowId = "f", status = StepRunStatus.Succeeded, startedAt = i.toLong(), finishedAt = i + 1L,
        inputs = listOf(RawPort(0, items = List(n) { Item.of("a" to it) })),
        outputs = listOf(RawPort(0, "main", List(n) { Item.of("b" to it, "password" to "pw") }, listOf(ParentRef("p")))),
    )

    @Test fun capturesRedactsAndInfersSchema() = runBlocking {
        val got = Collections.synchronizedList(ArrayList<CapturedExecution>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rec = ChannelStepExecutionRecorder({ got += it }, scope)
        assertEquals(RecordOutcome.Queued, rec.record(cap(1)))
        rec.flush()
        val e = got.single()
        assertEquals(3, e.record.outputs[0].itemCount); assertEquals(3, e.outputs[0].items.size)
        assertEquals("••••••", e.outputs[0].items[0].json["password"])
        assertTrue(e.record.outputs[0].schema!!.fields.containsKey("b"))
        assertEquals(listOf(ParentRef("p")), e.record.outputs[0].parents)
        assertEquals(false, e.record.dataDropped)
        rec.close(); scope.cancel()
    }

    @Test fun bigOutputIsCappedInRecord() = runBlocking {
        val got = Collections.synchronizedList(ArrayList<CapturedExecution>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rec = ChannelStepExecutionRecorder({ got += it }, scope)
        rec.record(cap(1, 10_000)); rec.flush()
        assertEquals(10_000, got.single().record.outputs[0].itemCount)
        assertEquals(200, got.single().record.outputs[0].storedCount)
        rec.close(); scope.cancel()
    }

    @Test fun slowStoreDoesNotDelayTheRunAndOverflowIsFlagged() = runBlocking {
        val got = Collections.synchronizedList(ArrayList<CapturedExecution>())
        val gate = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rec = ChannelStepExecutionRecorder({ gate.await(5, TimeUnit.SECONDS); got += it }, scope, capacity = 4)
        val t0 = System.nanoTime()
        val outcomes = (1..300).map { rec.record(cap(it)) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("record() took $ms ms", ms < 500)
        assertTrue(outcomes.count { it == RecordOutcome.Dropped } >= 290)
        assertTrue(rec.droppedCount >= 290)
        gate.countDown()
        rec.flush()
        assertEquals(300, got.size)
        val flagged = got.filter { it.record.dataDropped }
        assertEquals(rec.droppedCount.toInt(), flagged.size)
        assertTrue(flagged.all { it.outputs[0].items.isEmpty() && it.record.outputs[0].itemCount == 3 })
        rec.close(); scope.cancel()
    }

    @Test fun failingSinkIsSwallowed() = runBlocking {
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var n = 0
        val rec = ChannelStepExecutionRecorder({ if (n++ == 0) error("disk full") }, scope, onError = { errors += it })
        rec.record(cap(1)); rec.record(cap(2)); rec.flush()
        assertEquals(1, rec.failureCount); assertEquals(1, rec.writtenCount); assertEquals(1, errors.size)
        rec.close(); scope.cancel()
    }

    @Test fun recordAfterCloseIsDropped() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rec = ChannelStepExecutionRecorder({ }, scope)
        rec.close()
        assertEquals(RecordOutcome.Dropped, rec.record(cap(1)))
        rec.flush()
        scope.cancel()
    }
}
