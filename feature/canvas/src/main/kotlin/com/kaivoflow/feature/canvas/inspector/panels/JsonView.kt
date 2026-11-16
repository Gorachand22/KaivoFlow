package com.kaivoflow.feature.canvas.inspector.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.CountPill
import com.kaivoflow.core.design.EmptyStateBlock
import com.kaivoflow.core.design.KaivoChip
import com.kaivoflow.core.design.KaivoDataTypes
import com.kaivoflow.core.design.KaivoIconButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.expressions.assist.ReferenceBuilder
import com.kaivoflow.core.model.SchemaType

internal fun highlighted(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    TableBuilder.highlightRanges(text, query).forEach { addStyle(SpanStyle(background = color), it.first, it.last + 1) }
}

private fun JsonKind.schemaType(): SchemaType = when (this) {
    JsonKind.ItemHeader, JsonKind.Object -> SchemaType.Object
    JsonKind.Array -> SchemaType.Array
    JsonKind.Text -> SchemaType.String
    JsonKind.Number -> SchemaType.Number
    JsonKind.Bool -> SchemaType.Bool
    JsonKind.Null -> SchemaType.Null
}

/**
 * JSON view: a lazy tree per item. Tap a row to offer its path for insertion, tap the copy button
 * to copy the node as JSON, long-press then drag to map a node. With a search query the tree
 * opens to the matches and the toolbar steps through them.
 */
@Composable
fun JsonView(
    result: JsonTreeResult,
    origin: PanelOrigin,
    expansion: TreeExpansion,
    onExpansionChange: (TreeExpansion) -> Unit,
    callbacks: DataPanelCallbacks,
    modifier: Modifier = Modifier,
    query: String = "",
    onCopyAll: (() -> Unit)? = null,
    dragEnabled: Boolean = true,
) {
    val colors = KaivoTheme.colors
    val listState = rememberLazyListState()
    var cursor by remember(query, result.matchCount) { mutableStateOf(0) }
    var jump by remember { mutableStateOf(0) }
    val positions = result.matchPositions

    LaunchedEffect(jump) {
        if (jump > 0 && positions.isNotEmpty()) {
            listState.animateScrollToItem(positions[cursor.coerceIn(0, positions.lastIndex)])
        }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KaivoChip("Expand all", { onExpansionChange(TreeExpansion.All) }, iconKey = "unfoldMore")
            KaivoChip("Collapse all", { onExpansionChange(TreeExpansion(openDepth = 1)) }, iconKey = "unfoldLess")
            if (onCopyAll != null) KaivoChip("Copy all", onCopyAll, iconKey = "copy")
            Spacer(Modifier.weight(1f))
            if (query.isNotBlank()) {
                CountPill(
                    text = if (positions.isEmpty()) "0" else "${cursor.coerceIn(0, positions.lastIndex) + 1}/${positions.size}",
                    description = "${result.matchCount} matches",
                )
                KaivoIconButton("back", "Previous match", {
                    if (positions.isNotEmpty()) {
                        cursor = (cursor - 1 + positions.size) % positions.size
                        jump++
                    }
                }, enabled = positions.isNotEmpty())
                KaivoIconButton("forward", "Next match", {
                    if (positions.isNotEmpty()) {
                        cursor = (cursor + 1) % positions.size
                        jump++
                    }
                }, enabled = positions.isNotEmpty())
            }
        }
        if (result.rows.isEmpty()) {
            Box(Modifier.padding(16.dp)) { EmptyStateBlock(title = "No items to show") }
            return@Column
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(items = result.rows, key = { it.id }) { row ->
                JsonRowView(row, origin, expansion, onExpansionChange, callbacks, query, dragEnabled)
            }
        }
    }
}

@Composable
private fun JsonRowView(
    row: JsonRow,
    origin: PanelOrigin,
    expansion: TreeExpansion,
    onExpansionChange: (TreeExpansion) -> Unit,
    callbacks: DataPanelCallbacks,
    query: String,
    dragEnabled: Boolean,
) {
    val colors = KaivoTheme.colors
    val source = PanelSources.node(origin, row)
    val type = row.kind.schemaType()
    val tint = KaivoDataTypes.tint(type, colors)
    val header = row.kind == JsonKind.ItemHeader
    val shownValue = if (row.kind == JsonKind.Text) "\"${row.preview}\"" else row.preview
    val toggle = { onExpansionChange(expansion.toggle(row.id, row.depth)) }
    val copyNode = { callbacks.onCopy(if (header) "item" else row.label, JsonText.pretty(row.value)) }
    val matchColor = colors.royalGold.copy(alpha = 0.35f)
    val actions = buildList<CustomAccessibilityAction> {
        add(insertIntoFieldAction { callbacks.onMapTo(source) })
        add(copyAction("Copy JSON") { copyNode() })
        add(copyAction("Copy as expression") { callbacks.onCopy("expression", ReferenceBuilder.build(source)) })
        if (row.expandable) add(copyAction(if (row.expanded) "Collapse" else "Expand") { toggle() })
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(
                when {
                    row.matches -> colors.royalGold.copy(alpha = 0.10f)
                    header -> colors.surfaceElevated.copy(alpha = 0.5f)
                    else -> Color.Transparent
                },
            )
            .mapDragSource(source, callbacks.onIntent, enabled = dragEnabled)
            .clickable(role = Role.Button) { callbacks.onInsert(source) }
            .padding(start = 8.dp + 14.dp * row.depth)
            .semantics(mergeDescendants = true) {
                contentDescription = "${row.label}, ${KaivoDataTypes.label(type)}, $shownValue"
                customActions = actions
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.expandable) {
            Box(
                Modifier.size(48.dp).clickable(role = Role.Button) { toggle() }.clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    KaivoIcons.icon(if (row.expanded) "expandMore" else "chevronRight"),
                    contentDescription = null,
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Row(Modifier.weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Text(
                highlighted(row.label, query, matchColor),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = KaivoTheme.type.monoFamily),
                color = if (header) colors.inkPrimary else tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                highlighted(shownValue, query, matchColor),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = KaivoTheme.type.monoFamily),
                color = if (row.kind == JsonKind.Null) colors.inkMuted else colors.inkSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        KaivoIconButton("copy", "Copy ${row.label}", { copyNode() }, modifier = Modifier.clearAndSetSemantics { })
    }
}
