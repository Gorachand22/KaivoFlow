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
import com.kaivoflow.feature.canvas.inspector.state.DEFAULT_MANUAL_INPUT
import com.kaivoflow.feature.canvas.inspector.state.DataRequest
import com.kaivoflow.feature.canvas.inspector.state.InputSource
import com.kaivoflow.feature.canvas.inspector.state.TestRequest
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
        val upstreams = ArrayList<Map<String, Map<Int, List<Item>>>>()
        override suspend fun test(flow: Flow, stepId: String, input: List<Item>, upstream: Map<String, Map<Int, List<Item>>>): StepRun {
            calls += stepId to input
            upstreams += upstream
            val failed = stepId == failOn
            write(stepId, if (failed) rows(1) else rows(2), runId = "test-${calls.size}", error = if (failed) StepErrorInfo("boom", itemIndex = 2) else null,
                status = if (failed) StepRunStatus.Failed else StepRunStatus.Succeeded)
            return StepRun(stepId, stepId, if (failed) StepRunStatus.Failed else StepRunStatus.Succeeded, 0, error = if (failed) "boom" else null)
        }
    }

    private fun gateway(stepId: String = "c", tester: StepTestRunner = FakeTester(), f: Flow = flow) =
        CanvasInspectorGateway("f", stepId, { f }, store, pins, tester, KaivoExpressionAssist(), { _, _ -> })

    private fun run(gw: CanvasInspectorGateway, source: InputSource? = null, manual: String = DEFAULT_MANUAL_INPUT, policy: ParentPolicy = ParentPolicy.OnlyThis, confirmed: Boolean = true, step: String = "c") =
        runBlocking { gw.runTest(TestRequest(step, policy, source, manual, confirmed)).toList() }

    private fun req(stepId: String, run: RunSelection = RunSelection.Latest) = DataRequest(PanelSide.Output, stepId, run, 0, PageRequest(), "")

    @Test fun loadReportsStoredCountsAndEmpty() = runBlocking {
        val gw = gateway()
        assertEquals(0, gw.loadData(req("b")).itemCount)
        write("b", rows(3), runId = "r1")
        val s = gw.loadData(req("b"))
        assertEquals(3, s.itemCount); assertEquals(3, s.storedCount); assertEquals(false, s.truncated)
        assertEquals(3, gw.loadData(req("b", RunSelection.Specific("r1"))).itemCount)
    }

    @Test fun testWithoutParentDataRunsOnlyThisStepOnTheManualInput() {
        val tester = FakeTester()
        val events = run(gateway(tester = tester), source = null, manual = "[{\"x\": 1}, {\"x\": 2}]")
        assertEquals(listOf("c"), tester.calls.map { it.first })
        assertEquals(listOf(1L, 2L), tester.calls.single().second.map { it.json["x"] })
        val done = events.last() as InspectorTestEvent.Finished
        assertEquals(InputSource.Manual, done.summary!!.source)
    }

    @Test fun testUsesStoredParentDataAndStoresItsOwnOutput() = runBlocking {
        write("b", rows(3))
        val tester = FakeTester()
        val gw = gateway(tester = tester)
        val events = run(gw)
        assertEquals(listOf("c"), tester.calls.map { it.first })
        assertEquals(3, tester.calls.single().second.size)
        assertTrue(events.first() is InspectorTestEvent.Progress)
        val done = events.last() as InspectorTestEvent.Finished
        assertEquals("test-1", done.executionKey); assertEquals(InputSource.LastRun, done.summary!!.source)
        assertEquals(2, gw.loadData(req("c")).itemCount) // persisted in the store, not in memory
        assertEquals(3, tester.upstreams.single().getValue("b").getValue(0).size) // $step lookups see the parent's data without running it
    }

    @Test fun lastRunSourceLabelAndPinnedBeatsLastRun() = runBlocking {
        write("b", rows(3))
        val t1 = FakeTester()
        run(gateway(tester = t1)); assertEquals(3, t1.calls.single().second.size)
        val cat = gateway().loadInputCatalog()
        assertEquals(listOf(InputSource.LastRun), cat.options.map { it.source })
        // pin 2 other items on b: pinned wins automatically
        write("b", rows(2), runId = "r-pin")
        gateway(stepId = "b").pinCurrent(req("b", RunSelection.Specific("r-pin")))
        write("b", rows(5), runId = "r-later")
        val t2 = FakeTester()
        val events = run(gateway(tester = t2), source = InputSource.Pinned)
        assertEquals(2, t2.calls.single().second.size)
        assertEquals(InputSource.Pinned, (events.last() as InspectorTestEvent.Finished).summary!!.source)
        val t3 = FakeTester()
        run(gateway(tester = t3), source = InputSource.LastRun)
        assertEquals(5, t3.calls.single().second.size) // explicit Last run ignores the pin
    }

    @Test fun pinnedParentCountsAsData() = runBlocking {
        write("b", rows(2))
        gateway(stepId = "b").pinCurrent(req("b"))
        val tester = FakeTester()
        val events = run(gateway(tester = tester))
        assertTrue(events.last() is InspectorTestEvent.Finished)
        assertEquals(2, tester.calls.single().second.size)
        assertEquals(InputSource.Pinned, gateway().loadInputCatalog().options.first().source)
    }

    @Test fun lastTestInputIsOfferedAfterATestAndReusedWhenNothingElseExists() = runBlocking {
        val tester = FakeTester()
        run(gateway(tester = tester), manual = "[{\"typed\": true}]")
        // the fake stores no input, so register one the way the engine does
        clock += 10
        val raw = StepExecutionCapture(StepExecutionKey("test-77", "c", 0), "f", StepRunStatus.Succeeded, clock, clock + 1,
            inputs = listOf(RawPort(0, null, listOf(Item(json = mapOf("typed" to true))))), outputs = listOf(RawPort(0, "main", rows(1))), manual = true)
        val got = ArrayList<CapturedExecution>()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rec = ChannelStepExecutionRecorder({ got += it }, sc, StepDataPolicy())
        rec.record(raw); rec.flush(); rec.close(); sc.cancel()
        store.write(got.single())
        val opts = gateway().loadInputCatalog().options
        assertEquals(listOf(InputSource.LastTest), opts.map { it.source })
        assertEquals("test-77", opts.single().runId)
        val t2 = FakeTester()
        val events = run(gateway(tester = t2), source = null, manual = "[{}]")
        // no parent data, so the automatic order picks the last test input, not the manual text
        assertEquals(listOf(true), t2.calls.single().second.map { it.json["typed"] })
        assertEquals(InputSource.LastTest, (events.last() as InspectorTestEvent.Finished).summary!!.source)
        // and the stored input is readable as an inbound panel request
        val inbound = DataRequest(PanelSide.Input, "c", RunSelection.Specific("test-77"), 0, PageRequest(), "", inbound = true)
        assertEquals(1, gateway().loadData(inbound).itemCount)
    }

    @Test fun middleStepTestNeverTouchesParentsEvenWithoutTheirData() {
        val tester = FakeTester()
        run(gateway(stepId = "b", tester = tester), step = "b")
        assertEquals(listOf("b"), tester.calls.map { it.first })
    }

    @Test fun earlierStepsRunOnlyWhenExplicitlyAsked() {
        val tester = FakeTester()
        val events = run(gateway(tester = tester), policy = ParentPolicy.RunThem)
        assertEquals(listOf("a", "b", "c"), tester.calls.map { it.first })
        assertEquals(2, tester.calls[1].second.size) // b received a's stored output
        assertTrue(events.last() is InspectorTestEvent.Finished)
    }

    @Test fun sideEffectOfEarlierStepAsksFirstWhenRunningThem() {
        val sms = flow.copy(steps = persistentListOf(StepNode("a", "device.sms", "Send"), StepNode("b", "t", "B"), StepNode("c", "t", "C")))
        val tester = FakeTester()
        val events = run(gateway(tester = tester, f = sms), policy = ParentPolicy.RunThem, confirmed = false)
        assertTrue(events.single() is InspectorTestEvent.NeedsConfirm)
        assertTrue(tester.calls.isEmpty())
        run(gateway(tester = tester, f = sms), policy = ParentPolicy.RunThem, confirmed = true)
        assertEquals(listOf("a", "b", "c"), tester.calls.map { it.first })
    }

    @Test fun disabledStepIsNotTested() {
        val off = flow.copy(steps = persistentListOf(StepNode("a", "t", "A"), StepNode("b", "t", "B"), StepNode("c", "t", "C", disabled = true)))
        val tester = FakeTester()
        val events = run(gateway(tester = tester, f = off))
        assertTrue(events.single() is InspectorTestEvent.Refused)
        assertTrue(tester.calls.isEmpty())
    }

    @Test fun sparkSampleIsOfferedAndTestedWithoutParents() = runBlocking {
        val sp = Flow(id = "f", name = "F", steps = persistentListOf(StepNode("s", "spark.sms", "On SMS")), streams = persistentListOf())
        val cat = gateway(stepId = "s", f = sp).loadInputCatalog()
        assertTrue(cat.options.isEmpty()); assertTrue(cat.sampleText.contains("\"from\""))
        val tester = FakeTester()
        val events = run(gateway(stepId = "s", tester = tester, f = sp), manual = cat.sampleText, step = "s")
        assertEquals(1, tester.calls.single().second.size)
        assertEquals(InputSource.Sample, (events.last() as InspectorTestEvent.Finished).summary!!.source)
    }

    @Test fun invalidManualTextIsRefusedNotRun() {
        val tester = FakeTester()
        val events = run(gateway(tester = tester), source = InputSource.Manual, manual = "[{")
        assertTrue(events.single() is InspectorTestEvent.Refused); assertTrue(tester.calls.isEmpty())
    }

    @Test fun failureCarriesItemIndexAndPartialData() = runBlocking {
        write("b", rows(1))
        val events = run(gateway(tester = FakeTester(failOn = "c")))
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

    @Test fun previewUsesParentData() = runBlocking {
        write("b", rows(2))
        val gw = gateway()
        val ok = gw.evaluatePreview("{{ \$json.s }}", 1)
        assertTrue(ok.toString(), ok is PreviewState.Value && ok.rendered == "row 1")
        assertTrue(gw.evaluatePreview("{{ 1 + }}", 0) is PreviewState.Error)
    }
}
