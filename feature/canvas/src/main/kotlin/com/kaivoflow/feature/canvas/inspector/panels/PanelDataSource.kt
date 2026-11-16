package com.kaivoflow.feature.canvas.inspector.panels

import com.kaivoflow.core.engine.stepdata.Direction
import com.kaivoflow.core.engine.stepdata.ItemFilter
import com.kaivoflow.core.engine.stepdata.ItemPage
import com.kaivoflow.core.engine.stepdata.StepDataStore
import com.kaivoflow.core.engine.stepdata.StepExecutionKey
import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.core.model.SchemaType
import com.kaivoflow.feature.canvas.inspector.state.DataRequest
import com.kaivoflow.feature.canvas.inspector.state.RunSelection

/**
 * What the data panels need to read stored data. Small on purpose so the composables can be
 * previewed and tested with a fake. The request is the same [DataRequest] the reducer builds.
 */
interface PanelDataSource {
    suspend fun page(request: DataRequest): ItemPage
    suspend fun schema(request: DataRequest): SchemaNode
}

/**
 * Reads a [StepDataStore]. A panel shows the output port of the selected Step (for the Input panel
 * that is the chosen parent). [RunSelection.Pinned] is not stored there: give the panel a different
 * [PanelDataSource] built on the pin store when pinned data is shown.
 */
class StepDataStorePanelSource(
    private val store: StepDataStore,
    private val flowId: String,
    private val direction: Direction = Direction.Out,
) : PanelDataSource {

    private suspend fun keyOf(r: DataRequest): StepExecutionKey? {
        val stepId = r.stepId ?: return null
        return when (val run = r.run) {
            is RunSelection.Specific -> StepExecutionKey(run.runId, stepId, run.runIndex)
            RunSelection.Latest -> store.latestKey(flowId, stepId)
            RunSelection.Pinned -> null
        }
    }

    override suspend fun page(request: DataRequest): ItemPage {
        val key = keyOf(request) ?: return ItemPage.Empty
        val filter = request.search.trim().takeIf { it.isNotEmpty() }?.let { ItemFilter(query = it) }
        return store.page(key, direction, request.port, request.page.offset, request.page.size, filter)
    }

    override suspend fun schema(request: DataRequest): SchemaNode {
        val key = keyOf(request) ?: return SchemaNode(SchemaType.Object)
        return store.schema(key, direction, request.port)
    }
}

/** What a panel body has loaded for the current request. */
sealed interface PanelContent {
    data object Loading : PanelContent
    data class Page(val page: ItemPage) : PanelContent
    data class Schema(val root: SchemaNode) : PanelContent
    data class Failed(val message: String) : PanelContent
}
