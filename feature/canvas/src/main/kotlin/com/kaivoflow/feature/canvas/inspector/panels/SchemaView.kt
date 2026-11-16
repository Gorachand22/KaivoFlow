package com.kaivoflow.feature.canvas.inspector.panels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.EmptyStateBlock
import com.kaivoflow.core.design.FieldRow
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.core.model.SchemaPath

/**
 * Schema view: the shape of the data as a tree. Tap selects the path and offers it for insertion;
 * long-press then drag starts a mapping drag. Stateless: expansion and selection are passed in.
 */
@Composable
fun SchemaView(
    root: SchemaNode,
    origin: PanelOrigin,
    expansion: TreeExpansion,
    onExpansionChange: (TreeExpansion) -> Unit,
    callbacks: DataPanelCallbacks,
    modifier: Modifier = Modifier,
    query: String = "",
    selectedPath: SchemaPath? = null,
    onSelectPath: (SchemaPath) -> Unit = {},
    dragEnabled: Boolean = true,
) {
    val rows = remember(root, expansion, query) { SchemaTreeFlattener.flatten(root, expansion, query) }
    if (rows.isEmpty()) {
        Box(modifier.padding(16.dp)) {
            EmptyStateBlock(
                title = if (query.isNotBlank()) "No matching fields" else "No fields to show",
                message = if (query.isNotBlank()) "Nothing in the shape matches \"${query.trim()}\"." else null,
            )
        }
        return
    }
    val declared = rows.any { it.declared }
    LazyColumn(modifier = modifier) {
        if (declared) {
            item(key = "sample-shape-tag") {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    StatusBadge("Sample shape", BadgeKind.Gold)
                }
            }
        }
        items(items = rows, key = { it.id }) { row ->
            val source = PanelSources.json(origin, row.path)
            val note = listOfNotNull(row.seenLabel, if (row.truncated) "deeper levels not shown" else null)
                .joinToString(" · ").ifEmpty { null }
            FieldRow(
                label = row.label,
                type = row.type,
                depth = row.depth,
                sample = row.sample,
                note = note,
                optional = row.optional,
                expanded = if (row.expandable) row.expanded else null,
                onToggleExpand = if (row.expandable) {
                    { onExpansionChange(expansion.toggle(row.id, row.depth)) }
                } else null,
                selected = selectedPath == row.path,
                dimmed = row.declared,
                onClick = {
                    onSelectPath(row.path)
                    callbacks.onInsert(source)
                },
                extraActions = listOf(insertIntoFieldAction { callbacks.onMapTo(source) }),
                modifier = Modifier.mapDragSource(source, callbacks.onIntent, enabled = dragEnabled),
            )
        }
    }
}
