package com.kaivoflow.feature.canvas.inspector.panels

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.EmptyStateBlock
import com.kaivoflow.core.design.ErrorBlock
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.PagerBar
import com.kaivoflow.core.design.SkeletonRows
import com.kaivoflow.core.model.SchemaPath
import com.kaivoflow.feature.canvas.inspector.state.DataPanelState
import com.kaivoflow.feature.canvas.inspector.state.DataRequest
import com.kaivoflow.feature.canvas.inspector.state.DataStatus
import com.kaivoflow.feature.canvas.inspector.state.DataViewMode
import com.kaivoflow.feature.canvas.inspector.state.InspectorIntent
import com.kaivoflow.feature.canvas.inspector.state.PageRequest
import com.kaivoflow.feature.canvas.inspector.state.PanelSide
import com.kaivoflow.feature.canvas.inspector.state.PinInfo
import com.kaivoflow.feature.canvas.inspector.state.RunSelection
import kotlinx.coroutines.CancellationException

/** Presentation state that belongs to the view, not to the inspector: what is expanded and selected. */
data class PanelViewState(
    val schemaExpansion: TreeExpansion = TreeExpansion(),
    val jsonExpansion: TreeExpansion = TreeExpansion(),
    val selection: RowSelection = RowSelection(),
    val selectedPath: SchemaPath? = null,
)

/**
 * A data panel (Input or Output). Reads its [state] (committed inspector state), loads the shown
 * page or schema through [source] when the state asks for it, and sends every change back as an
 * intent in [callbacks]. The only state kept here is [PanelViewState]. The same composable serves
 * the Input page, the Output page and the Mapper tray.
 */
@Composable
fun DataPanel(
    state: DataPanelState,
    origin: PanelOrigin,
    source: PanelDataSource,
    callbacks: DataPanelCallbacks,
    modifier: Modifier = Modifier,
    pin: PinInfo? = null,
    nowMs: Long = System.currentTimeMillis(),
    dragEnabled: Boolean = true,
) {
    var view by remember(state.side, state.sourceStepId, state.port, state.run) { mutableStateOf(PanelViewState()) }
    val loaded = state.status is DataStatus.Loaded
    val request = DataRequest(state.side, state.sourceStepId, state.run, state.port, state.page, state.appliedSearch, state.inbound)
    val schemaMode = state.mode == DataViewMode.Schema
    // The shape of a port does not depend on the page or the search, so those do not reload it.
    val loadKey = if (schemaMode) request.copy(page = PageRequest(), search = "") else request
    val content by produceState<PanelContent>(PanelContent.Loading, loadKey, state.mode, state.loadToken, loaded) {
        value = PanelContent.Loading
        if (!loaded) return@produceState
        value = try {
            if (schemaMode) PanelContent.Schema(source.schema(loadKey)) else PanelContent.Page(source.page(loadKey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PanelContent.Failed(e.message ?: "Could not read the stored data")
        }
    }
    DataPanelBody(
        state = state,
        content = content,
        view = view,
        onView = { view = it },
        origin = origin,
        callbacks = callbacks,
        modifier = modifier,
        pin = pin,
        nowMs = nowMs,
        dragEnabled = dragEnabled,
    )
}

/** Stateless layout of a data panel for a given [content]. Use it directly in previews and tests. */
@Composable
fun DataPanelBody(
    state: DataPanelState,
    content: PanelContent,
    view: PanelViewState,
    onView: (PanelViewState) -> Unit,
    origin: PanelOrigin,
    callbacks: DataPanelCallbacks,
    modifier: Modifier = Modifier,
    pin: PinInfo? = null,
    nowMs: Long = 0L,
    dragEnabled: Boolean = true,
) {
    val colors = KaivoTheme.colors
    val summary = state.summary
    val pinned = state.run == RunSelection.Pinned && pin != null
    var pagerSheet by remember { mutableStateOf<PagerSheet?>(null) }
    val page = (content as? PanelContent.Page)?.page

    Column(
        modifier = modifier
            .then(if (pinned) Modifier.border(1.dp, colors.royalGold.copy(alpha = 0.5f), KaivoShapes.chip) else Modifier),
    ) {
        DataPanelHeader(
            state = state,
            callbacks = callbacks,
            countLabel = summary?.let { Paging.countLabel(it.storedCount, it.itemCount) },
            showBinary = summary?.hasBinary == true,
            nowMs = nowMs,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (pinned && pin != null) {
            PinnedStrip(
                pin = pin,
                nowMs = nowMs,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                onEdit = callbacks.onEditPin,
                onUnpin = callbacks.onUnpin,
            )
        }
        if (summary != null && summary.truncated) {
            PanelBanner(
                "Showing first ${summary.storedCount} of ${summary.itemCount} items, stored data is capped",
                kind = BadgeKind.Warning,
            )
        }
        if (page != null && state.appliedSearch.isNotBlank()) {
            PanelBanner(Paging.matchLabel(page.totalMatching, page.totalStored), kind = BadgeKind.Gold)
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            when (val status = state.status) {
                DataStatus.NoConnection -> Padded {
                    EmptyStateBlock(
                        title = "No Step connected",
                        message = "Connect a Step to see the data it passes on.",
                        primaryLabel = callbacks.onConnectStep?.let { "Connect a Step" },
                        onPrimary = callbacks.onConnectStep,
                    )
                }
                DataStatus.Idle -> Padded {
                    EmptyStateBlock(
                        title = if (state.side == PanelSide.Input) "Run the previous Step to see its data" else "Run this Step to see its data",
                        primaryLabel = callbacks.onRunPrevious?.let { if (state.side == PanelSide.Input) "Run previous Step" else "Run Step" },
                        onPrimary = callbacks.onRunPrevious,
                        secondaryLabel = callbacks.onUseSample?.let { "Use sample" },
                        onSecondary = callbacks.onUseSample,
                    )
                }
                DataStatus.Loading -> SkeletonRows()
                is DataStatus.Failed -> Padded {
                    ErrorBlock(
                        title = "Could not load data",
                        message = status.message,
                        onCopyDetails = { callbacks.onCopy("error", status.message) },
                        actionLabel = callbacks.onOpenStepSettings?.let { "Open Step settings" },
                        onAction = callbacks.onOpenStepSettings,
                    )
                }
                is DataStatus.Loaded -> if (status.summary.itemCount == 0) {
                    Padded {
                        EmptyStateBlock(
                            title = "This Step ran and produced no items",
                            message = if (state.appliedSearch.isNotBlank()) null else "The branch may not have been taken, or a filter dropped everything.",
                        )
                    }
                } else {
                    LoadedContent(state, content, view, onView, origin, callbacks, dragEnabled)
                }
            }
        }
        if (page != null && state.mode != DataViewMode.Schema && page.totalMatching > PageRequest.Sizes.min()) {
            val info = Paging.info(state.page.offset, state.page.size, page.totalMatching)
            PagerBar(
                pageLabel = info.pageLabel,
                rangeLabel = info.rangeLabel,
                hasPrev = info.hasPrev,
                hasNext = info.hasNext,
                onPrev = { callbacks.onIntent(InspectorIntent.SetPageRequest(state.side, info.prevOffset, state.page.size)) },
                onNext = { callbacks.onIntent(InspectorIntent.SetPageRequest(state.side, info.nextOffset, state.page.size)) },
                sizeLabel = "${state.page.size} / page",
                onPickSize = { pagerSheet = PagerSheet.Size },
                onJump = { pagerSheet = PagerSheet.Jump },
            )
            PagerSheets(
                visible = pagerSheet,
                info = info,
                pageSize = state.page.size,
                onSize = { size ->
                    callbacks.onIntent(
                        InspectorIntent.SetPageRequest(state.side, Paging.offsetAfterSizeChange(state.page.offset, size, page.totalMatching), size),
                    )
                },
                onPage = { p ->
                    callbacks.onIntent(
                        InspectorIntent.SetPageRequest(state.side, Paging.offsetForPage(p, state.page.size, page.totalMatching), state.page.size),
                    )
                },
                onDismiss = { pagerSheet = null },
            )
        }
    }
}

@Composable
private fun Padded(content: @Composable () -> Unit) {
    Box(Modifier.padding(16.dp)) { content() }
}

@Composable
private fun LoadedContent(
    state: DataPanelState,
    content: PanelContent,
    view: PanelViewState,
    onView: (PanelViewState) -> Unit,
    origin: PanelOrigin,
    callbacks: DataPanelCallbacks,
    dragEnabled: Boolean,
) {
    val query = state.appliedSearch
    when (content) {
        PanelContent.Loading -> SkeletonRows()
        is PanelContent.Failed -> Padded {
            ErrorBlock(
                title = "Could not read the stored data",
                message = content.message,
                onCopyDetails = { callbacks.onCopy("error", content.message) },
            )
        }
        is PanelContent.Schema -> if (state.mode == DataViewMode.Schema) {
            SchemaView(
                root = content.root,
                origin = origin,
                expansion = view.schemaExpansion,
                onExpansionChange = { onView(view.copy(schemaExpansion = it)) },
                callbacks = callbacks,
                query = query,
                selectedPath = view.selectedPath,
                onSelectPath = { onView(view.copy(selectedPath = it)) },
                dragEnabled = dragEnabled,
            )
        } else SkeletonRows()
        is PanelContent.Page -> {
            val page = content.page
            when (state.mode) {
                DataViewMode.Table -> {
                    val model = remember(page) { TableBuilder.build(page.items, page.originalIndices) }
                    TableView(
                        model = model,
                        origin = origin,
                        callbacks = callbacks,
                        query = query,
                        selection = view.selection,
                        onSelectionChange = { onView(view.copy(selection = it)) },
                        dragEnabled = dragEnabled,
                    )
                }
                DataViewMode.Json -> {
                    val tree = remember(page, view.jsonExpansion, query) {
                        JsonTreeFlattener.flatten(page.items, page.originalIndices, view.jsonExpansion, query)
                    }
                    JsonView(
                        result = tree,
                        origin = origin,
                        expansion = view.jsonExpansion,
                        onExpansionChange = { onView(view.copy(jsonExpansion = it)) },
                        callbacks = callbacks,
                        query = query,
                        onCopyAll = { callbacks.onCopy("data", JsonText.pretty(page.items.map { it.json })) },
                        dragEnabled = dragEnabled,
                    )
                }
                DataViewMode.Binary -> {
                    val entries = remember(page) { BinaryEntries.collect(page.items, page.originalIndices) }
                    BinaryView(entries = entries, origin = origin, callbacks = callbacks, query = query)
                }
                DataViewMode.Schema -> SkeletonRows()
            }
        }
    }
}
