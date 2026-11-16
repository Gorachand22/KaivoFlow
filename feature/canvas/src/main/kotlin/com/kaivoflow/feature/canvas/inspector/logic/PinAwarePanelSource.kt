package com.kaivoflow.feature.canvas.inspector.logic

import com.kaivoflow.core.engine.stepdata.ItemPage
import com.kaivoflow.core.engine.stepdata.PinReader
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.SchemaInference
import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.feature.canvas.inspector.panels.PanelDataSource
import com.kaivoflow.feature.canvas.inspector.state.DataRequest
import com.kaivoflow.feature.canvas.inspector.state.RunSelection

/**
 * Reads pinned data for [RunSelection.Pinned] and hands every other request to [delegate]
 * (the stored executions). Pinned items are held in full, so paging and search are done here.
 */
class PinAwarePanelSource(
    private val delegate: PanelDataSource,
    private val pins: PinReader,
    private val flowId: String,
) : PanelDataSource {

    override suspend fun page(request: DataRequest): ItemPage {
        if (request.run != RunSelection.Pinned) return delegate.page(request)
        val items = pinnedItems(request)
        val q = request.search.trim()
        val matching: List<Pair<Int, Item>> = items.withIndex().map { it.index to it.value }
            .filter { q.isEmpty() || it.second.json.toString().contains(q, ignoreCase = true) }
        val from = request.page.offset.coerceIn(0, matching.size)
        val slice = matching.subList(from, (from + request.page.size).coerceAtMost(matching.size))
        return ItemPage(
            items = slice.map { it.second },
            originalIndices = slice.map { it.first },
            offset = from,
            totalStored = items.size,
            totalMatching = matching.size,
            totalTrue = items.size,
            truncation = null,
        )
    }

    override suspend fun schema(request: DataRequest): SchemaNode {
        if (request.run != RunSelection.Pinned) return delegate.schema(request)
        return SchemaInference.infer(pinnedItems(request).asSequence())
    }

    private suspend fun pinnedItems(r: DataRequest): List<Item> {
        val id = r.stepId ?: return emptyList()
        return pins.pinnedFor(flowId, id)?.ports?.getOrNull(r.port).orEmpty()
    }
}
