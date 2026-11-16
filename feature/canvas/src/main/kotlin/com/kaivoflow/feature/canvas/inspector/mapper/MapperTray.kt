package com.kaivoflow.feature.canvas.inspector.mapper

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.EmptyStateBlock
import com.kaivoflow.core.design.KaivoBottomSheet
import com.kaivoflow.core.design.KaivoChip
import com.kaivoflow.core.design.KaivoIconButton
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SearchField
import com.kaivoflow.core.design.SheetOptionRow
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.feature.canvas.inspector.panels.DataPanelCallbacks
import com.kaivoflow.feature.canvas.inspector.panels.PanelOrigin
import com.kaivoflow.feature.canvas.inspector.panels.SchemaView
import com.kaivoflow.feature.canvas.inspector.panels.TreeExpansion

/** One parent Step in the tray: its origin (for the reference form) and the shape of its data. */
data class TraySection(val origin: PanelOrigin, val schema: SchemaNode?)

/**
 * The tap-to-insert Mapper tray (spec P2 3.4), equal rank with dragging for phone use. It shows
 * the Schema view of each parent Step, restricted to selectable rows. A tap inserts at the focused
 * field and the tray stays open so several fields can be inserted in a row; the close button
 * returns to the keyboard. Rows also keep their long-press drag and the TalkBack action
 * `Insert into field`, because they are the data panels' own rows.
 *
 * [focusedLabel] names the field a tap goes to, so the person always knows where it lands.
 * [callbacks] should route `onInsert` to `DragMapController.tapInsert` and `onMapTo` to the "Map to..." sheet.
 */
@Composable
fun MapperTray(
    sections: List<TraySection>,
    callbacks: DataPanelCallbacks,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    focusedLabel: String? = null,
) {
    val colors = KaivoTheme.colors
    var selected by remember(sections.size) { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var expansion by remember(selected) { mutableStateOf(TreeExpansion()) }
    val section = sections.getOrNull(selected.coerceIn(0, (sections.size - 1).coerceAtLeast(0)))
    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Fields", style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, modifier = Modifier.semantics { heading() })
                Text(
                    if (focusedLabel != null) "Tap a field to insert into $focusedLabel" else "Focus a field, then tap to insert. Or drag a row onto a field.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            KaivoIconButton("close", "Close Mapper tray", onClose)
        }
        if (sections.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEachIndexed { i, s ->
                    KaivoChip(
                        text = s.origin.stepName,
                        selected = i == selected,
                        onClick = { selected = i },
                        description = "Fields of ${s.origin.stepName}" + if (s.origin.isImmediateParent) ", previous Step" else "",
                    )
                }
            }
        }
        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            SearchField(value = query, onValueChange = { query = it }, placeholder = "Search fields")
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (section?.schema == null) {
                EmptyStateBlock(
                    title = "No data yet",
                    message = "Run the previous Step to see its fields.",
                    modifier = Modifier.padding(16.dp),
                    primaryLabel = callbacks.onRunPrevious?.let { "Run previous Step" },
                    onPrimary = callbacks.onRunPrevious,
                )
            } else {
                SchemaView(
                    root = section.schema,
                    origin = section.origin,
                    expansion = expansion,
                    onExpansionChange = { expansion = it },
                    callbacks = callbacks,
                    modifier = Modifier.fillMaxSize(),
                    query = query,
                )
            }
        }
    }
}

/**
 * "Map to...": a list of the fields a reference can go to. Opened from the long-press menu on a data
 * row and from the TalkBack action `Insert into field`; one tap inserts and closes.
 */
@Composable
fun MapToSheet(
    options: List<MapToOptions.Option>,
    onPick: (ParamPath) -> Unit,
    onDismiss: () -> Unit,
) {
    KaivoBottomSheet(title = "Insert into field", subtitle = "Pick where the reference goes", onDismiss = onDismiss) {
        if (options.isEmpty()) {
            Text(
                "No field here accepts data references.",
                style = MaterialTheme.typography.bodyMedium,
                color = KaivoTheme.colors.inkSecondary,
                modifier = Modifier.padding(vertical = 16.dp).semantics { contentDescription = "No field here accepts data references" },
            )
        } else {
            options.forEach { o ->
                SheetOptionRow(title = o.label, selected = false, onClick = { onPick(o.path); onDismiss() })
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
