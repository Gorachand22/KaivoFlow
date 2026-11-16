package com.kaivoflow.feature.canvas.inspector.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.KaivoBottomSheet
import com.kaivoflow.core.design.KaivoChip
import com.kaivoflow.core.design.KaivoIconButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SearchField
import com.kaivoflow.core.design.SegmentOption
import com.kaivoflow.core.design.SegmentedSwitch
import com.kaivoflow.core.design.SheetOptionRow
import com.kaivoflow.feature.canvas.inspector.state.DataPanelState
import com.kaivoflow.feature.canvas.inspector.state.DataViewMode
import com.kaivoflow.feature.canvas.inspector.state.InspectorIntent
import com.kaivoflow.feature.canvas.inspector.state.PageRequest
import com.kaivoflow.feature.canvas.inspector.state.PanelSide
import com.kaivoflow.feature.canvas.inspector.state.PinInfo
import com.kaivoflow.feature.canvas.inspector.state.RunSelection

/**
 * Header of a data panel: parent chip (Input) or port tabs (Output), run stepper, item count,
 * mode switch and search. Everything is read from [state] and answered with inspector intents.
 * [nowMs] is passed in so ages are deterministic in previews and tests. Runs are expected newest first.
 */
@Composable
fun DataPanelHeader(
    state: DataPanelState,
    callbacks: DataPanelCallbacks,
    countLabel: String?,
    showBinary: Boolean,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val side = state.side
    var searchOpen by remember { mutableStateOf(state.search.isNotEmpty()) }
    var sheet by remember { mutableStateOf<HeaderSheet?>(null) }

    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Row 1: where the data comes from.
        if (side == PanelSide.Input && state.sources.size > 1) {
            val current = state.sources.firstOrNull { it.stepId == state.sourceStepId }
            KaivoChip(
                text = current?.name ?: "Choose parent Step",
                onClick = { sheet = HeaderSheet.Parents },
                iconKey = "flow",
                trailingIconKey = "expandMore",
                description = "Parent Step, ${current?.name ?: "none chosen"}. Double tap to change",
            )
        }
        if (state.ports.size > 1) {
            SegmentedSwitch(
                options = state.ports.map { p ->
                    SegmentOption(label = p.label ?: "Port ${p.port + 1}", badge = p.itemCount?.toString())
                },
                selectedIndex = state.ports.indexOfFirst { it.port == state.port }.coerceAtLeast(0),
                onSelect = { i -> callbacks.onIntent(InspectorIntent.SelectPort(side, state.ports[i].port)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Row 2: run stepper, count, search.
        val runIndex = when (val r = state.run) {
            RunSelection.Latest -> 0
            is RunSelection.Specific -> state.runs.indexOfFirst { it.runId == r.runId }
            RunSelection.Pinned -> -1
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.runs.size > 1 && runIndex >= 0) {
                KaivoIconButton("back", "Older run", {
                    selectRunAt(callbacks, side, state, runIndex + 1)
                }, enabled = runIndex < state.runs.lastIndex)
                KaivoChip(
                    text = PanelFormat.runLabel(runIndex + 1, state.runs.size),
                    onClick = { sheet = HeaderSheet.Runs },
                    trailingIconKey = "expandMore",
                    description = "${PanelFormat.runLabel(runIndex + 1, state.runs.size)}. Double tap to pick a run",
                )
                KaivoIconButton("forward", "Newer run", {
                    selectRunAt(callbacks, side, state, runIndex - 1)
                }, enabled = runIndex > 0)
            }
            Spacer(Modifier.weight(1f))
            if (countLabel != null) {
                Text(
                    countLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = KaivoTheme.colors.inkSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            KaivoIconButton(
                iconKey = "search",
                description = if (searchOpen) "Hide search" else "Search data",
                onClick = { searchOpen = !searchOpen },
                tint = if (searchOpen || state.search.isNotEmpty()) KaivoTheme.colors.royalGold else KaivoTheme.colors.inkSecondary,
            )
        }
        if (searchOpen) {
            SearchField(
                value = state.search,
                onValueChange = { callbacks.onIntent(InspectorIntent.SearchChanged(side, it)) },
                placeholder = "Search keys and values",
            )
        }
        // Row 3: mode switch.
        val modes = if (showBinary) DataViewMode.entries else DataViewMode.entries.filter { it != DataViewMode.Binary }
        val labels = mapOf(
            DataViewMode.Schema to SegmentOption("Schema", "dataObject"),
            DataViewMode.Table to SegmentOption("Table", "table"),
            DataViewMode.Json to SegmentOption("JSON", "braces"),
            DataViewMode.Binary to SegmentOption("Binary", "attachFile"),
        )
        SegmentedSwitch(
            options = modes.map { labels.getValue(it) },
            selectedIndex = modes.indexOf(state.mode).let { if (it < 0) modes.indexOf(DataViewMode.Table) else it },
            onSelect = { i -> callbacks.onIntent(InspectorIntent.SetViewMode(side, modes[i])) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    when (sheet) {
        HeaderSheet.Parents -> KaivoBottomSheet(title = "Parent Step", subtitle = "Which Step's data to look at", onDismiss = { sheet = null }) {
            state.sources.forEach { s ->
                SheetOptionRow(
                    title = s.name,
                    selected = s.stepId == state.sourceStepId,
                    subtitle = listOfNotNull(
                        s.itemCount?.let { "$it ${if (it == 1) "item" else "items"}" },
                        s.lastRunAt?.let { PanelFormat.age(nowMs, it) },
                    ).joinToString(" · ").ifEmpty { null },
                    onClick = {
                        callbacks.onIntent(InspectorIntent.SelectInputParent(s.stepId))
                        sheet = null
                    },
                )
            }
        }
        HeaderSheet.Runs -> KaivoBottomSheet(title = "Runs", onDismiss = { sheet = null }) {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(items = state.runs.withIndex().toList(), key = { it.value.runId }) { (i, r) ->
                    SheetOptionRow(
                        title = PanelFormat.runLabel(i + 1, state.runs.size) + if (r.failed) ", failed" else "",
                        selected = i == runIndex,
                        subtitle = PanelFormat.age(nowMs, r.startedAt),
                        onClick = {
                            selectRunAt(callbacks, side, state, i)
                            sheet = null
                        },
                    )
                }
            }
        }
        null -> Unit
    }
}

private enum class HeaderSheet { Parents, Runs }

private fun selectRunAt(callbacks: DataPanelCallbacks, side: PanelSide, state: DataPanelState, index: Int) {
    val target = state.runs.getOrNull(index) ?: return
    val selection = if (index == 0) RunSelection.Latest else RunSelection.Specific(target.runId)
    callbacks.onIntent(InspectorIntent.SelectRun(side, selection))
}

/** `Pinned, 3 items, 2 KB, 4 min ago` with Edit and Unpin, framed by a gold hairline. */
@Composable
fun PinnedStrip(
    pin: PinInfo,
    nowMs: Long,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onUnpin: (() -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(KaivoShapes.chip)
            .background(colors.royalGold.copy(alpha = 0.08f))
            .border(1.dp, colors.royalGold.copy(alpha = 0.5f), KaivoShapes.chip)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(KaivoIcons.icon("pushPin"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            PanelFormat.pinnedLine(pin.itemCount, pin.bytes, nowMs, pin.pinnedAt),
            style = MaterialTheme.typography.labelLarge,
            color = colors.inkPrimary,
            modifier = Modifier.weight(1f),
        )
        if (onEdit != null) KaivoChip("Edit", onEdit, iconKey = "edit")
        if (onUnpin != null) KaivoChip("Unpin", onUnpin)
    }
}

/** One-line notice above the data: truncation, search result count. */
@Composable
fun PanelBanner(text: String, modifier: Modifier = Modifier, kind: BadgeKind = BadgeKind.Neutral) {
    val colors = KaivoTheme.colors
    val tint = when (kind) {
        BadgeKind.Warning -> colors.statusWarning
        BadgeKind.Error -> colors.statusError
        BadgeKind.Gold -> colors.royalGold
        BadgeKind.Success -> colors.statusSuccess
        BadgeKind.Neutral -> colors.inkSecondary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 36.dp)
            .background(tint.copy(alpha = 0.10f))
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** Page-size and page-jump sheets behind the pager bar. */
@Composable
fun PagerSheets(
    visible: PagerSheet?,
    info: PagerInfo,
    pageSize: Int,
    onSize: (Int) -> Unit,
    onPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    when (visible) {
        PagerSheet.Size -> KaivoBottomSheet(title = "Items per page", onDismiss = onDismiss) {
            PageRequest.Sizes.forEach { size ->
                SheetOptionRow(title = "$size items", selected = size == pageSize, onClick = { onSize(size); onDismiss() })
            }
        }
        PagerSheet.Jump -> KaivoBottomSheet(title = "Go to page", subtitle = "${info.total} items", onDismiss = onDismiss) {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(items = (1..info.pageCount).toList(), key = { it }) { page ->
                    SheetOptionRow(title = "Page $page", selected = page == info.page, onClick = { onPage(page); onDismiss() })
                }
            }
        }
        null -> Unit
    }
}

enum class PagerSheet { Size, Jump }
