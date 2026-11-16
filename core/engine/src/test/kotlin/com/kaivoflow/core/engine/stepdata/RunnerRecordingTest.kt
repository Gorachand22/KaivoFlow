package com.kaivoflow.core.engine.stepdata

import com.kaivoflow.core.engine.ExecutionMode
import com.kaivoflow.core.engine.FlowRunner
import com.kaivoflow.core.engine.InMemoryCredentials
import com.kaivoflow.core.engine.NullHttpGateway
import com.kaivoflow.core.engine.RunOptions
import com.kaivoflow.core.engine.StubEvaluator
import com.kaivoflow.core.engine.SlowExecutor
import com.kaivoflow.core.engine.item
import com.kaivoflow.core.engine.registry
import com.kaivoflow.core.engine.step
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.RunEvent
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.RunTrigger
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class RunnerRecordingTest {
    private val sink = Collections.synchronizedList(ArrayList<CapturedExecution>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorder = ChannelStepExecutionRecorder({ sink += it }, scope)

    private fun runner(pins: PinReader? = null, rec: StepExecutionRecorder = recorder) =
        FlowRunner(registry(), StubEvaluator(), NullHttpGateway(), InMemoryCredentials(), recorder = rec, pins = pins)

    private fun threeStepFlow() = Flow(
        id = "flow-1", name = "F",
        steps = persistentListOf(
            step("t", "spark.manual"),
            step("a", "test.set", params = mapOf("field" to "x", "value" to 1), y = 10f),
            step("b", "test.pass", y = 20f),
        ),
        streams = listOf(StreamEdge("e1", "t", "a"), StreamEdge("e2", "a", "b")).toImmutableList(),
    )

    private fun trigger() = RunTrigger(items = listOf(item("id" to 1), item("id" to 2)).toImmutableList())

    @Test fun threeStepFlowStoresInputAndOutputForEachStep() = runBlocking {
        val events = ArrayList<RunEvent>()
        val rec = runner().run(threeStepFlow(), trigger(), onEvent = { events += it })
        assertEquals(RunStatus.Succeeded, rec.status)
        val byStep = sink.associateBy { it.record.key.stepId }
        assertEquals(setOf("t", "a", "b"), byStep.keys)
        val a = byStep.getValue("a")
        assertEquals(rec.id, a.record.key.runId)
        assertEquals(2, a.record.inputs.single().itemCount)
        assertEquals(listOf(ParentRef("t", 0)), a.record.inputs.single().parents)
        assertEquals(listOf(1, 1), a.outputs.single().items.map { it.json["x"] })
        assertTrue(a.record.manual)
        assertEquals("a", byStep.getValue("b").record.inputs.single().parents.single().let { "a" })
        // Output lineage is stored with the items.
        assertEquals("t", a.outputs.single().items[0].sources.single().stepId)
        // The finished event carries the key the data was stored under.
        val fin = events.filterIsInstance<RunEvent.StepFinished>().first { it.stepId == "a" }
        assertEquals(a.record.key, fin.executionKey())
    }

    @Test fun failedStepStoresItsInputsAndError() = runBlocking {
        val flow = Flow(
            id = "f", name = "F",
            steps = persistentListOf(step("t", "spark.manual"), step("x", "test.fail", y = 10f)),
            streams = listOf(StreamEdge("e1", "t", "x")).toImmutableList(),
        )
        val rec = runner().run(flow, trigger())
        assertEquals(RunStatus.Failed, rec.status)
        val x = sink.single { it.record.key.stepId == "x" }
        assertEquals(StepRunStatus.Failed, x.record.status)
        assertEquals("boom", x.record.error!!.message)
        assertEquals(2, x.record.inputs.single().itemCount)
    }

    @Test fun killedRunStillLeavesFinishedStepsData() = runBlocking {
        val flow = Flow(
            id = "f", name = "F",
            steps = persistentListOf(
                step("t", "spark.manual"),
                step("a", "test.set", params = mapOf("field" to "x", "value" to 1), y = 10f),
                step("slow", "test.slow", params = mapOf("ms" to 60_000), y = 20f),
            ),
            streams = listOf(StreamEdge("e1", "t", "a"), StreamEdge("e2", "a", "slow")).toImmutableList(),
        )
        val job = async(Dispatchers.Default) { runner().run(flow, trigger()) }
        delay(400)
        job.cancel()
        runCatching { job.await() }
        val ids = sink.map { it.record.key.stepId }.toSet()
        assertTrue("finished steps keep data: $ids", ids.containsAll(setOf("t", "a")))
        assertFalse("the step that never finished has none", "slow" in ids)
    }

    @Test fun defaultRecorderRecordsNothingAndRunStillWorks() = runBlocking {
        val r = FlowRunner(registry(), StubEvaluator(), NullHttpGateway(), InMemoryCredentials())
        assertEquals(RunStatus.Succeeded, r.run(threeStepFlow(), trigger()).status)
        assertTrue(sink.isEmpty())
    }

    // ---- pinned replay ----

    private class MapPins(val map: Map<String, PinnedData>) : PinReader {
        override suspend fun pinnedFor(flowId: String, stepId: String) = map[stepId]
    }

    private val pinA = PinnedData(listOf(listOf(Item.of("pinned" to true))), 10, 1, PinSource.Json)

    @Test fun pinnedStepIsSkippedInManualRunAndReplaysItsData() = runBlocking {
        val rec = runner(MapPins(mapOf("a" to pinA))).run(threeStepFlow(), trigger(), RunOptions(mode = ExecutionMode.Manual))
        val a = rec.stepRuns.single { it.stepId == "a" }
        assertEquals(StepRunStatus.Frozen, a.status)
        val b = rec.stepRuns.single { it.stepId == "b" }
        assertEquals(1, b.itemsIn)
        assertEquals(true, b.output.single().json["pinned"])
        assertNull(b.output.single().json["x"])
        recorder.flush()
        assertTrue(sink.single { it.record.key.stepId == "a" }.record.pinned)
    }

    @Test fun pinnedStepRunsNormallyInProductionRuns() = runBlocking {
        val rec = runner(MapPins(mapOf("a" to pinA))).run(threeStepFlow(), trigger(), RunOptions(mode = ExecutionMode.Production))
        assertEquals(StepRunStatus.Succeeded, rec.stepRuns.single { it.stepId == "a" }.status)
        assertEquals(1, rec.stepRuns.single { it.stepId == "b" }.output.first().json["x"])
    }

    @Test fun ignoreFrozenRunsPinnedStepLive() = runBlocking {
        val rec = runner(MapPins(mapOf("a" to pinA))).run(threeStepFlow(), trigger(), RunOptions(mode = ExecutionMode.Manual, ignoreFrozen = true))
        assertEquals(StepRunStatus.Succeeded, rec.stepRuns.single { it.stepId == "a" }.status)
    }

    @Test fun testStepUsesPinnedParentAsInputAndExecutesTheTestedStep() = runBlocking {
        val pinT = PinnedData(listOf(listOf(Item.of("id" to 99))), 5, 1, PinSource.Json)
        val run = runner(MapPins(mapOf("t" to pinT))).testStep(threeStepFlow(), "a", emptyList())
        assertEquals(StepRunStatus.Succeeded, run.status)
        assertEquals(1, run.itemsIn)
        assertEquals(99, run.output.single().json["id"])
        assertEquals(1, run.output.single().json["x"])
    }

    @Test fun testedStepIsNeverReplayedFromItsOwnPin() = runBlocking {
        val run = runner(MapPins(mapOf("a" to pinA))).testStep(threeStepFlow(), "a", listOf(item("id" to 5)))
        assertEquals(1, run.output.single().json["x"])
    }
}
