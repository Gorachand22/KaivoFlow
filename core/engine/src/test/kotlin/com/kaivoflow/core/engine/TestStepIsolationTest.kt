package com.kaivoflow.core.engine

import com.kaivoflow.core.engine.stepdata.CapturedExecution
import com.kaivoflow.core.engine.stepdata.ChannelStepExecutionRecorder
import com.kaivoflow.core.engine.stepdata.RunDataMeta
import com.kaivoflow.core.engine.stepdata.StepDataRetention
import com.kaivoflow.core.engine.stepdata.TEST_RUN_PREFIX
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.RunEvent
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Test Step runs exactly one Step: proven with executors that count every call. */
class TestStepIsolationTest {
    private class Counting(private val inner: StepExecutor, val calls: MutableList<String>) : StepExecutor {
        override val spec = inner.spec
        override suspend fun execute(ctx: StepContext): StepResult {
            calls += ctx.step.id
            return inner.execute(ctx)
        }
    }

    private val calls = Collections.synchronizedList(ArrayList<String>())
    private val sink = Collections.synchronizedList(ArrayList<CapturedExecution>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun runner() = FlowRunner(
        NodeRegistry(listOf(Counting(ManualTriggerExecutor(), calls), Counting(SetExecutor(), calls), Counting(PassExecutor(), calls))),
        StubEvaluator(), NullHttpGateway(), InMemoryCredentials(),
        recorder = ChannelStepExecutionRecorder({ sink += it }, scope),
    )

    private fun chain(middleDisabled: Boolean = false) = Flow(
        id = "f", name = "F",
        steps = persistentListOf(
            step("t", "spark.manual"),
            step("a", "test.set", params = mapOf("field" to "x", "value" to 1), y = 10f, disabled = middleDisabled),
            step("b", "test.pass", y = 20f),
        ),
        streams = listOf(StreamEdge("e1", "t", "a"), StreamEdge("e2", "a", "b")).toImmutableList(),
    )

    @Test fun testingTheMiddleStepExecutesOneStepOnly() = runBlocking {
        val run = runner().testStep(chain(), "a", listOf(item("id" to 7)))
        assertEquals(listOf("a"), calls.toList())
        assertEquals(StepRunStatus.Succeeded, run.status)
        assertEquals(7, run.output.single().json["id"])
        assertEquals(1, run.output.single().json["x"])
    }

    @Test fun manualInputIsExactlyWhatTheStepReceives() = runBlocking {
        val manual = listOf(item("n" to 1), item("n" to 2), item("n" to 3))
        val run = runner().testStep(chain(), "a", manual)
        assertEquals(3, run.itemsIn)
        assertEquals(listOf(1, 2, 3), run.output.map { it.json["n"] })
        assertEquals(listOf("a"), calls.toList())
    }

    @Test fun pinnedInputIsUsedWhenNoInputIsGiven() = runBlocking {
        val pins = com.kaivoflow.core.engine.stepdata.PinReader { _, id ->
            if (id == "t") com.kaivoflow.core.engine.stepdata.PinnedData(listOf(listOf(item("p" to 1), item("p" to 2))), 1, 1, com.kaivoflow.core.engine.stepdata.PinSource.Json) else null
        }
        val r = FlowRunner(NodeRegistry(listOf(Counting(ManualTriggerExecutor(), calls), Counting(SetExecutor(), calls), Counting(PassExecutor(), calls))), StubEvaluator(), NullHttpGateway(), InMemoryCredentials(), pins = pins)
        val run = r.testStep(chain(), "a", emptyList())
        assertEquals(listOf(1, 2), run.output.map { it.json["p"] })
        assertEquals(listOf("a"), calls.toList()) // the pinned parent was replayed, never executed
    }

    @Test fun sparkIsTestedWithItsSampleEventOnly() = runBlocking {
        val run = runner().testStep(chain(), "t", emptyList())
        assertEquals(listOf("t"), calls.toList())
        assertEquals(1, run.itemsOut)
        assertNotNull(run.output.single().json["timestamp"])
        assertEquals("test", run.output.single().json["trigger"])
    }

    @Test fun disabledStepIsNotExecutedOrRecorded() = runBlocking {
        val run = runner().testStep(chain(middleDisabled = true), "a", listOf(item()))
        assertEquals(StepRunStatus.Skipped, run.status)
        assertTrue(calls.isEmpty())
        assertTrue(sink.isEmpty())
    }

    @Test fun executionIsStoredAsTestAndNoRunRecordIsProduced() = runBlocking {
        val events = ArrayList<RunEvent>()
        runner().testStep(chain(), "a", listOf(item("id" to 1)), onEvent = { events += it })
        withTimeoutFlush()
        val exec = sink.single()
        assertTrue(exec.record.key.isTest)
        assertTrue(exec.record.key.runId.startsWith(TEST_RUN_PREFIX))
        assertTrue(exec.record.manual)
        assertEquals("a", exec.record.key.stepId)
        assertTrue(events.none { it is RunEvent.StepStarted && it.stepId != "a" })
    }

    @Test fun testRunsHaveTheirOwnRetentionBucket() {
        val manual = (1..20).map { RunDataMeta("m$it", "f", it.toLong(), true, false) }
        val tests = (1..15).map { RunDataMeta("${TEST_RUN_PREFIX}$it", "f", 100L + it, true, false) }
        val drop = StepDataRetention.runsToDrop(manual + tests)
        assertEquals(5, drop.size)
        assertTrue(drop.all { it.startsWith(TEST_RUN_PREFIX) })
        assertFalse(drop.any { it.startsWith("m") })
    }

    private suspend fun withTimeoutFlush() {
        repeat(100) { if (sink.isEmpty()) kotlinx.coroutines.delay(10) }
    }
}
