package com.kaivoflow.feature.canvas.inspector.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.expressions.assist.ReferenceBuilder
import com.kaivoflow.core.design.EmptyStateBlock
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.KaivoBottomSheet
import com.kaivoflow.core.design.KaivoDataTypes
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.TypeIcon

private val IndexColumnWidth = 56.dp
private const val VALUE_SHEET_MAX_CHARS = 20_000

/**
 * Table view: sticky header row and sticky index column (the header and index sit outside the
 * scrolling areas, and every row shares one horizontal scroll state). Tap an index cell to select
 * the row, tap a cell to offer a reference for insertion, long-press then drag a cell or header to
 * map the column. Nested and clipped values open a value sheet on tap.
 */
@Composable
fun TableView(
    model: TableModel,
    origin: PanelOrigin,
    callbacks: DataPanelCallbacks,
    modifier: Modifier = Modifier,
    query: String = "",
    selection: RowSelection = RowSelection(),
    onSelectionChange: (RowSelection) -> Unit = {},
    dragEnabled: Boolean = true,
) {
    val colors = KaivoTheme.colors
    if (model.rows.isEmpty() || model.columns.isEmpty()) {
        Box(modifier.padding(16.dp)) {
            EmptyStateBlock(title = if (query.isNotBlank()) "No matching rows" else "No rows to show")
        }
        return
    }
    val scroll = rememberScrollState()
    var sheet by remember { mutableStateOf<Pair<TableColumn, TableRowCell>?>(null) }
    val widths = remember(model) {
        model.columns.mapIndexed { i, c -> TableBuilder.columnWidthDp(c.key, model.rows.map { it.cells[i] }) }
    }

    Column(modifier) {
        // Sticky header: lives outside the lazy list, scrolls horizontally with the rows.
        Row(Modifier.fillMaxWidth().background(colors.surfaceAtelier)) {
            Box(Modifier.width(IndexColumnWidth).defaultMinSize(minHeight = 48.dp), contentAlignment = Alignment.Center) {
                Text("#", style = MaterialTheme.typography.labelLarge, color = colors.inkMuted)
            }
            Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                model.columns.forEachIndexed { i, column ->
                    val source = PanelSources.column(origin, column)
                    Row(
                        modifier = Modifier
                            .width(widths[i].dp)
                            .defaultMinSize(minHeight = 48.dp)
                            .mapDragSource(source, callbacks.onIntent, enabled = dragEnabled)
                            .clickable(role = Role.Button) { callbacks.onInsert(source) }
                            .padding(horizontal = 8.dp)
                            .semantics {
                                contentDescription = "Column ${column.key}, ${KaivoDataTypes.label(column.type)}"
                                customActions = listOf(
                                    insertIntoFieldAction { callbacks.onMapTo(source) },
                                    copyAction("Copy path") { callbacks.onCopy("path", "\$json" + ReferenceBuilder.keyAccess(column.key)) },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypeIcon(column.type, size = 22.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            column.key,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
        LazyColumn(Modifier.weight(1f)) {
            items(items = model.rows, key = { it.itemIndex }) { row ->
                val isSelected = selection.isSelected(row.itemIndex)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) colors.royalGold.copy(alpha = 0.10f) else Color.Transparent),
                ) {
                    Box(
                        modifier = Modifier
                            .width(IndexColumnWidth)
                            .defaultMinSize(minHeight = 48.dp)
                            .selectable(selected = isSelected, role = Role.Checkbox) {
                                onSelectionChange(selection.toggle(row.itemIndex))
                            }
                            .semantics { contentDescription = "Item ${row.itemIndex + 1}${if (row.hasError) ", error" else ""}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(KaivoIcons.icon("check"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(18.dp))
                        } else {
                            Text(
                                (row.itemIndex + 1).toString(),
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = KaivoTheme.type.monoFamily),
                                color = if (row.hasError) colors.statusError else colors.inkMuted,
                            )
                        }
                    }
                    Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                        model.columns.forEachIndexed { i, column ->
                            val cell = row.cells[i]
                            val source = PanelSources.column(origin, column, row.itemIndex)
                            val opensSheet = cell.isSummary || cell.truncated
                            TableCellView(
                                cell = cell,
                                columnKey = column.key,
                                widthDp = widths[i],
                                query = query,
                                modifier = Modifier
                                    .mapDragSource(source, callbacks.onIntent, enabled = dragEnabled)
                                    .clickable(role = Role.Button) {
                                        if (opensSheet) sheet = column to TableRowCell(row.itemIndex, cell)
                                        else callbacks.onInsert(source)
                                    },
                                actions = listOf(
                                    insertIntoFieldAction { callbacks.onMapTo(source) },
                                    copyAction("Copy value") { callbacks.onCopy("value", cell.full) },
                                    copyAction("Copy path") { callbacks.onCopy("path", ReferenceBuilder.body(source)) },
                                    copyAction("Show full value") { sheet = column to TableRowCell(row.itemIndex, cell) },
                                ),
                            )
                        }
                    }
                }
            }
            if (model.hiddenColumns > 0) {
                item(key = "hidden-columns") {
                    Text(
                        "${model.hiddenColumns} more columns not shown",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    sheet?.let { (column, picked) ->
        val source = PanelSources.column(origin, column, picked.itemIndex)
        KaivoBottomSheet(
            title = "${column.key}, item ${picked.itemIndex + 1}",
            subtitle = KaivoDataTypes.label(column.type),
            onDismiss = { sheet = null },
        ) {
            val text = when (picked.cell.kind) {
                CellKind.Object, CellKind.Array -> JsonText.pretty(picked.cell.raw)
                else -> picked.cell.full
            }.take(VALUE_SHEET_MAX_CHARS)
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = KaivoTheme.type.monoFamily),
                    color = colors.inkPrimary,
                )
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Copy value", { callbacks.onCopy("value", picked.cell.full) }, icon = "copy")
                GhostButton("Insert", { callbacks.onInsert(source); sheet = null }, icon = "add")
            }
        }
    }
}

private data class TableRowCell(val itemIndex: Int, val cell: TableCell)

@Composable
private fun TableCellView(
    cell: TableCell,
    columnKey: String,
    widthDp: Int,
    query: String,
    modifier: Modifier,
    actions: List<CustomAccessibilityAction>,
) {
    val colors = KaivoTheme.colors
    Box(
        modifier = modifier
            .width(widthDp.dp)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics {
                contentDescription = "$columnKey: ${cell.text.ifEmpty { "empty" }}"
                customActions = actions
            },
        contentAlignment = if (cell.rightAligned) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        when (cell.kind) {
            CellKind.Null -> Text("null", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted, maxLines = 1)
            CellKind.EmptyString -> Text(cell.text, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted, maxLines = 1)
            CellKind.Object, CellKind.Array -> Text(
                cell.text,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = KaivoTheme.type.monoFamily),
                color = colors.imperialAmethyst,
                modifier = Modifier
                    .clip(KaivoShapes.chip)
                    .background(colors.imperialAmethyst.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                maxLines = 1,
            )
            else -> {
                val highlight = colors.royalGold.copy(alpha = 0.35f)
                val ranges = TableBuilder.highlightRanges(cell.text, query)
                val text = buildAnnotatedString {
                    append(cell.text)
                    ranges.forEach { addStyle(SpanStyle(background = highlight), it.first, it.last + 1) }
                }
                Text(
                    text,
                    style = (if (cell.kind == CellKind.Number) {
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = KaivoTheme.type.monoFamily)
                    } else MaterialTheme.typography.bodyMedium),
                    color = colors.inkPrimary,
                    textAlign = if (cell.rightAligned) TextAlign.End else TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
