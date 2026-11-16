package com.kaivoflow.feature.canvas.inspector.state

import com.kaivoflow.core.model.ParamValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorStateHolderTest {
    private class FakeGateway : InspectorGateway {
        val loads = Collections.synchronizedList(ArrayList<DataRequest>())
        val persisted = Collections.synchronizedList(ArrayList<Pair<String, ParamValue?>>())
        var gate: CompletableDeferred<Unit>? = null
        var failNext = false
        var unpinned = 0
        override suspend fun loadData(request: DataRequest): DataSummary {
            loads += request
            gate?.let { g -> gate = null; g.await() }
            if (failNext) { failNext = false; error("disk") }
            return DataSummary(itemCount = if (request.port == 0) 1 else 7, storedCount = 7)
        }
        override suspend fun pinCurrent(request: DataRequest) = PinInfo(7, 100, 1)
        override suspend fun unpin() { unpinned++ }
        override fun runTest(stepId: String, policy: ParentPolicy): Flow<InspectorTestEvent> =
            flowOf(InspectorTestEvent.Progress(listOf(stepId)), InspectorTestEvent.Finished("k"))
        override suspend fun evaluatePreview(text: String, itemIndex: Int) = PreviewState.Value("v:$text", "string")
        override fun persistParam(key: String, value: ParamValue?) { persisted += key to value }
    }

    private fun <T> withHolder(gw: FakeGateway = FakeGateway(), block: suspend (InspectorStateHolder, FakeGateway) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val h = InspectorStateHolder(InspectorState.initial("me"), gw, scope, InspectorReducer(searchDebounceMs = 40), previewDebounceMs = 20)
        try { withTimeout(10_000) { block(h, gw) } } finally { h.close(); scope.cancel() }
    }

    private suspend fun until(what: String, cond: () -> Boolean) {
        withTimeout(5_000) { while (!cond()) delay(5) }.also { assertTrue(what, cond()) }
    }

    @Test fun refreshLoadsBothPanels() = withHolder { h, gw ->
        h.dispatch(InspectorIntent.ParentsChanged(listOf(SourceChip("p1", "P"))))
        h.dispatch(InspectorIntent.Refresh)
        until("loaded") { h.state.value.input.status is DataStatus.Loaded && h.state.value.output.status is DataStatus.Loaded }
        assertEquals(1, h.state.value.output.summary!!.itemCount)
        assertTrue(gw.loads.any { it.stepId == "p1" } && gw.loads.any { it.stepId == "me" })
    }

    @Test fun typingSearchLoadsOnceAfterDebounce() = withHolder { h, gw ->
        for (t in listOf("a", "ab", "abc")) { h.dispatch(InspectorIntent.SearchChanged(PanelSide.Output, t)); delay(5) }
        until("applied") { h.state.value.output.appliedSearch == "abc" && h.state.value.output.status is DataStatus.Loaded }
        delay(120)
        assertEquals(listOf("abc"), gw.loads.map { it.search })
    }

    @Test fun olderSlowLoadCannotOverwriteNewerOne() = withHolder { h, gw ->
        val gate = CompletableDeferred<Unit>()
        gw.gate = gate
        h.dispatch(InspectorIntent.SearchChanged(PanelSide.Output, "slow"))
        until("slow started") { gw.loads.any { it.search == "slow" } }
        h.dispatch(InspectorIntent.SelectPort(PanelSide.Output, 2))
        until("fast loaded") { h.state.value.output.status is DataStatus.Loaded }
        gate.complete(Unit)
        delay(50)
        assertEquals(7, h.state.value.output.summary!!.itemCount)
    }

    @Test fun loadFailureBecomesFailedStatus() = withHolder { h, gw ->
        gw.failNext = true
        h.dispatch(InspectorIntent.Refresh)
        until("failed") { h.state.value.output.status is DataStatus.Failed }
        assertEquals(DataStatus.Failed("disk"), h.state.value.output.status)
    }

    @Test fun editPersistsAndEvaluatesPreview() = withHolder { h, gw ->
        val schema = com.kaivoflow.core.model.ParamSchema(1, kotlinx.collections.immutable.persistentListOf(
            com.kaivoflow.core.model.ParamDef("url", "URL", com.kaivoflow.core.model.ParamKind.Text())))
        h.dispatch(InspectorIntent.ParamsLoaded(schema, emptyMap()))
        h.dispatch(InspectorIntent.EditParam("url", ParamValue.Expr("{{ 1 }}")))
        assertEquals(listOf("url" to ParamValue.Expr("{{ 1 }}")), gw.persisted)
        until("preview") { h.state.value.previews["url"] != null }
        assertEquals(PreviewState.Value("v:{{ 1 }}", "string"), h.state.value.previews["url"])
    }

    @Test fun testStepFlowReachesFinishedAndReloads() = withHolder { h, gw ->
        h.dispatch(InspectorIntent.TestStepRequested())
        until("finished") { h.state.value.test is TestState.Finished }
        until("reloaded") { h.state.value.output.status is DataStatus.Loaded }
        assertTrue(gw.loads.isNotEmpty())
    }

    @Test fun pinAndUnpinGoThroughGateway() = withHolder { h, gw ->
        h.dispatch(InspectorIntent.Refresh)
        until("loaded") { h.state.value.output.status is DataStatus.Loaded }
        h.dispatch(InspectorIntent.PinCurrent)
        until("pinned") { h.state.value.pin != null }
        h.dispatch(InspectorIntent.Unpin)
        until("unpinned") { h.state.value.pin == null }
        assertEquals(1, gw.unpinned)
    }

    @Test fun uiEffectsAreEmitted() = withHolder { h, _ ->
        val got = CompletableDeferred<InspectorEffect>()
        val job = CoroutineScope(Dispatchers.Default).launch { h.uiEffects.collect { got.complete(it) } }
        delay(50)
        h.dispatch(InspectorIntent.PinCurrent)
        assertEquals(InspectorEffect.Notify(InspectorMessage.NothingToPin), got.await())
        job.cancel()
    }
}
