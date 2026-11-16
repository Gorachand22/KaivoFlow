package com.kaivoflow.feature.canvas.inspector.host

import com.kaivoflow.core.data.InMemoryPinPersistence
import com.kaivoflow.core.data.InMemoryStepDataAccess
import com.kaivoflow.core.data.StepDataStoreImpl
import com.kaivoflow.core.engine.stepdata.CapturedExecution
import com.kaivoflow.core.engine.stepdata.ChannelStepExecutionRecorder
import com.kaivoflow.core.engine.stepdata.PinService
import com.kaivoflow.core.engine.stepdata.RawPort
import com.kaivoflow.core.engine.stepdata.StepDataPolicy
import com.kaivoflow.core.engine.stepdata.StepErrorInfo
import com.kaivoflow.core.engine.stepdata.StepExecutionCapture
import com.kaivoflow.core.engine.stepdata.StepExecutionKey
import com.kaivoflow.core.expressions.assist.KaivoExpressionAssist
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StepRun
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.StreamEdge
import com.kaivoflow.feature.canvas.inspector.state.DataRequest
import com.kaivoflow.feature.canvas.inspector.state.InspectorTestEvent
import com.kaivoflow.feature.canvas.inspector.state.PageRequest
import com.kaivoflow.feature.canvas.inspector.state.PanelSide
import com.kaivoflow.feature.canvas.inspector.state.ParentPolicy
import com.kaivoflow.feature.canvas.inspector.state.PreviewState
import com.kaivoflow.feature.canvas.inspector.state.RunSelection
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasInspectorGatewayTest {
    private val store = StepDataStoreImpl(InMemoryStepDataAccess(), Dispatchers.Unconfined)
    private val pins = PinService(InMemoryPinPersistence(), store)
    private val flow = Flow(
        id = "f", name = "F",
        steps = persistentListOf(StepNode("a", "t", "A"), StepNode("b", "t", "B"), StepNode("c", "t", "C")),
        streams = persistentListOf(StreamEdge("1", "a", "b"), StreamEdge("2", "b", "c")),
    )
    private var clock = 1_000L

    private fun rows(n: Int) = List(n) { Item(json = mapOf("s" to "row $it")) }

    private fun write(stepId: String, items: List<Item>, runId: String = "r${clock}", error: StepErrorInfo? = null, status: StepRunStatus = StepRunStatus.Succeeded) = runBlocking {
        clock += 10
        val raw = StepExecutionCapture(
            key = StepExecutionKey(runId, stepId, 0), flowId = "f", status = status, startedAt = clock, finishedAt = clock + 5,
            inputs = emptyList(), outputs = listOf(RawPort(0, "main", items)), error = error, manual = true,
        )
        val got = ArrayList<CapturedExecution>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rec = ChannelStepExecutionRecorder({ got += it }, scope, StepDataPolicy())
        rec.record(raw); rec.flush(); rec.close(); scope.cancel()
        store.write(got.single())
    }

    private inner class FakeTester(val failOn: String? = null) : StepTestRunner {
        val calls = ArrayList<Pair<String, List<Item>>>()
        override suspend fun test(flow: Flow, stepId: String, input: List<Item>): StepRun {
            calls += stepId to input
            val failed = stepId == failOn
            write(stepId, if (failed) rows(1) else rows(2), runId = "t${calls.size}", error = if (failed) StepErrorInfo("boom", itemIndex = 2) else null,
                status = if (failed) StepRunStatus.Failed else StepRunStatus.Succeeded)
            return StepRun(stepId, stepId, if (failed) StepRunStatus.Failed else StepRunStatus.Succeeded, 0, error = if (failed) "boom" else null)
        }
    }

    private fun gateway(stepId: String = "c", tester: StepTestRunner = FakeTester()) =
        CanvasInspectorGateway("f", stepId, { flow }, store, pins, tester, KaivoExpressionAssist(), { _, _ -> })

    private fun req(stepId: String, run: RunSelection = RunSelection.Latest) = DataRequest(PanelSide.Output, stepId, run, 0, PageRequest(), "")

    @Test fun loadReportsStoredCountsAndEmpty() = runBlocking {
        val gw = gateway()
        assertEquals(0, gw.loadData(req("b")).itemCount)
        write("b", rows(3), runId = "r1")
        val s = gw.loadData(req("b"))
        assertEquals(3, s.itemCount); assertEquals(3, s.storedCount); assertEquals(false, s.truncated)
        assertEquals(3, gw.loadData(req("b", RunSelection.Specific("r1"))).itemCount)
    }

    @Test fun testAsksWhenParentHasNoData() = runBlocking {
        val tester = FakeTester()
        val events = gateway(tester = tester).runTest("c", ParentPolicy.Ask).toList()
        assertEquals(listOf<InspectorTestEvent>(InspectorTestEvent.NeedsParents(listOf("b"))), events)
        assertTrue(tester.calls.isEmpty())
    }

    @Test fun testUsesStoredParentDataAndStoresItsOwnOutput() = runBlocking {
        write("b", rows(3))
        val tester = FakeTester()
        val gw = gateway(tester = tester)
        val events = gw.runTest("c", ParentPolicy.Ask).toList()
        assertEquals(listOf("c"), tester.calls.map { it.first })
        assertEquals(3, tester.calls.single().second.size)
        assertTrue(events.first() is InspectorTestEvent.Progress)
        assertEquals(InspectorTestEvent.Finished("t1"), events.last())
        assertEquals(2, gw.loadData(req("c")).itemCount) // persisted in the store, not in memory
    }

    @Test fun runThemTestsMissingAncestorsFirst() = runBlocking {
        val tester = FakeTester()
        val events = gateway(tester = tester).runTest("c", ParentPolicy.RunThem).toList()
        assertEquals(listOf("a", "b", "c"), tester.calls.map { it.first })
        assertEquals(2, tester.calls[1].second.size) // b received a's stored output
        assertTrue(events.last() is InspectorTestEvent.Finished)
    }

    @Test fun failureCarriesItemIndexAndPartialData() = runBlocking {
        write("b", rows(1))
        val events = gateway(tester = FakeTester(failOn = "c")).runTest("c", ParentPolicy.Ask).toList()
        val f = events.last() as InspectorTestEvent.Failed
        assertEquals("boom", f.message); assertEquals(2, f.itemIndex); assertTrue(f.hasPartialData)
    }

    @Test fun pinAndUnpinRoundTrip() = runBlocking {
        write("b", rows(4))
        val gw = gateway(stepId = "b")
        val info = gw.pinCurrent(req("b"))
        assertEquals(4, info.itemCount)
        assertEquals(4, gw.loadData(req("b", RunSelection.Pinned)).itemCount)
        gw.unpin()
        val failed = runCatching { gw.loadData(req("b", RunSelection.Pinned)) }
        assertTrue(failed.isFailure)
    }

    @Test fun pinnedParentCountsAsData() = runBlocking {
        write("b", rows(2))
        gateway(stepId = "b").pinCurrent(req("b"))
        val tester = FakeTester()
        val events = gateway(tester = tester).runTest("c", ParentPolicy.Ask).toList()
        assertTrue(events.last() is InspectorTestEvent.Finished)
        assertEquals(2, tester.calls.single().second.size)
    }

    @Test fun previewUsesParentData() = runBlocking {
        write("b", rows(2))
        val gw = gateway()
        val ok = gw.evaluatePreview("{{ \$json.s }}", 1)
        assertTrue(ok.toString(), ok is PreviewState.Value && ok.rendered == "row 1")
        assertTrue(gw.evaluatePreview("{{ 1 + }}", 0) is PreviewState.Error)
    }
}
