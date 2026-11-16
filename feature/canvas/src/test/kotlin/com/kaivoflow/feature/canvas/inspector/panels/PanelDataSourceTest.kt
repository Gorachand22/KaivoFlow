package com.kaivoflow.feature.canvas.inspector.panels

import com.kaivoflow.core.engine.stepdata.CapturedExecution
import com.kaivoflow.core.engine.stepdata.Direction
import com.kaivoflow.core.engine.stepdata.ItemFilter
import com.kaivoflow.core.engine.stepdata.ItemPage
import com.kaivoflow.core.engine.stepdata.RunDataMeta
import com.kaivoflow.core.engine.stepdata.SearchResult
import com.kaivoflow.core.engine.stepdata.StepDataStore
import com.kaivoflow.core.engine.stepdata.StepExecutionKey
import com.kaivoflow.core.engine.stepdata.StepExecutionSummary
import com.kaivoflow.core.engine.stepdata.StoredExecutionInfo
import com.kaivoflow.core.model.BinaryDataRef
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.core.model.SchemaType
import com.kaivoflow.feature.canvas.inspector.state.DataRequest
import com.kaivoflow.feature.canvas.inspector.state.PageRequest
import com.kaivoflow.feature.canvas.inspector.state.PanelSide
import com.kaivoflow.feature.canvas.inspector.state.RunSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelDataSourceTest {
    private class FakeStore : StepDataStore {
        var lastKey: StepExecutionKey? = null
        var lastFilter: ItemFilter? = null
        var lastOffset = -1
        var lastLimit = -1
        var lastDirection: Direction? = null
        override suspend fun write(execution: CapturedExecution) = Unit
        override fun observeExecutions(flowId: String, stepId: String, limit: Int): Flow<List<StepExecutionSummary>> = emptyFlow()
        override suspend fun summary(key: StepExecutionKey): StepExecutionSummary? = null
        override suspend fun latestKey(flowId: String, stepId: String): StepExecutionKey? =
            if (stepId == "none") null else StepExecutionKey("latest-run", stepId, 3)
        override suspend fun page(key: StepExecutionKey, direction: Direction, port: Int, offset: Int, limit: Int, filter: ItemFilter?): ItemPage {
            lastKey = key; lastFilter = filter; lastOffset = offset; lastLimit = limit; lastDirection = direction
            return ItemPage(listOf(Item(mapOf("a" to 1))), listOf(offset), offset, 10, 10, 10, null)
        }
        override suspend fun schema(key: StepExecutionKey, direction: Direction, port: Int): SchemaNode { lastKey = key; return SchemaNode(SchemaType.Object, of = 7) }
        override suspend fun search(key: StepExecutionKey, direction: Direction, port: Int, query: String, limit: Int) = SearchResult(IntArray(0), 0)
        override suspend fun deleteRun(runId: String) = Unit
        override suspend fun usage(): List<StoredExecutionInfo> = emptyList()
        override suspend fun runMetas(): List<RunDataMeta> = emptyList()
    }

    private fun req(step: String?, run: RunSelection, search: String = "", offset: Int = 0) =
        DataRequest(PanelSide.Output, step, run, 0, PageRequest(offset, 25), search)

    @Test fun resolvesRunSelectionToKey() = runBlocking {
        val store = FakeStore()
        val src = StepDataStorePanelSource(store, "flow")
        src.page(req("s1", RunSelection.Latest, offset = 50))
        assertEquals(StepExecutionKey("latest-run", "s1", 3), store.lastKey)
        assertEquals(50, store.lastOffset)
        assertEquals(25, store.lastLimit)
        assertEquals(Direction.Out, store.lastDirection)
        assertNull(store.lastFilter)
        src.page(req("s1", RunSelection.Specific("r9", 2), search = "  oslo "))
        assertEquals(StepExecutionKey("r9", "s1", 2), store.lastKey)
        assertEquals("oslo", store.lastFilter?.query)
        assertEquals(7, src.schema(req("s1", RunSelection.Latest)).of)
    }

    @Test fun missingDataGivesEmptyResults() = runBlocking {
        val src = StepDataStorePanelSource(FakeStore(), "flow")
        assertTrue(src.page(req(null, RunSelection.Latest)).items.isEmpty())
        assertTrue(src.page(req("none", RunSelection.Latest)).items.isEmpty())
        assertTrue(src.page(req("s", RunSelection.Pinned)).items.isEmpty())
        assertTrue(src.schema(req("s", RunSelection.Pinned)).fields.isEmpty())
    }

    @Test fun binaryEntries() {
        val ref = BinaryDataRef("kaivo://files/a", "image/png", "a.png", 2048, "abc")
        val doc = BinaryDataRef("kaivo://files/b", "application/pdf", "b.pdf", 10)
        val items = listOf(Item(mapOf("x" to 1)), Item(mapOf("x" to 2), binary = mapOf("photo" to ref, "doc" to doc)))
        val all = BinaryEntries.collect(items, listOf(4, 5))
        assertEquals(2, all.size)
        assertEquals(5, all[0].itemIndex)
        assertEquals("image", all.first { it.name == "photo" }.iconKey)
        assertTrue(all.first { it.name == "photo" }.isImage)
        assertEquals("attachFile", all.first { it.name == "doc" }.iconKey)
        assertEquals(listOf("doc"), BinaryEntries.filter(all, "PDF").map { it.name })
        assertEquals(2, BinaryEntries.filter(all, " ").size)
    }
}
