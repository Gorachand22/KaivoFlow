package com.kaivoflow.feature.canvas.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SheetDragHandle
import com.kaivoflow.core.design.StepMedallion
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory

/**
 * Step Picker: search on top, category pills, two-column rich cards.
 * Live-searches the same registry the engine uses, so nothing offered here can be unrunnable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepPickerSheet(
    specs: List<NodeSpec>,
    onSearch: (String) -> Unit,
    onPick: (NodeSpec) -> Unit,
    onDismiss: () -> Unit,
    hasTrigger: Boolean,
) {
    val colors = KaivoTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<StepCategory?>(null) }

    val filtered = remember(specs, query, category, hasTrigger) {
        specs.filter { spec ->
            (category == null || spec.category == category) &&
                (query.isBlank() || spec.title.contains(query, true) ||
                    spec.subtitle.contains(query, true) ||
                    spec.keywords.any { it.contains(query, true) }) &&
                (spec.kind != com.kaivoflow.core.model.NodeKind.Trigger || !hasTrigger)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceAtelier,
        dragHandle = {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SheetDragHandle()
            }
        },
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Add a Step", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                if (hasTrigger) "Pick what happens next." else "Start with a Spark — the thing that begins your Flow.",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onSearch(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search Steps" },
                placeholder = { Text("Search Steps, apps, actions…") },
                leadingIcon = { Icon(KaivoIcons.icon("search"), null, tint = colors.inkSecondary) },
                singleLine = true,
                shape = KaivoShapes.chip,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Spacer(Modifier.height(12.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    CategoryChip("All", category == null) { category = null }
                }
                items(StepCategory.entries.toList()) { entry ->
                    CategoryChip(entry.label, category == entry) { category = if (category == entry) null else entry }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing matches \"$query\". Try \"weather\", \"telegram\" or \"AI\".",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.heightIn(max = 420.dp),
                ) {
                    items(filtered, key = { it.id }) { spec ->
                        val accent = colors.categoryColor(spec.category)
                        Column(
                            Modifier
                                .clip(KaivoShapes.stepCard)
                                .background(colors.surfaceCard)
                                .border(1.dp, colors.hairline, KaivoShapes.stepCard)
                                .clickable { onPick(spec) }
                                .padding(12.dp)
                                .semantics { contentDescription = "${spec.title}. ${spec.subtitle}" },
                        ) {
                            StepMedallion(iconKey = spec.iconKey, tint = accent, size = 36.dp, iconSize = 18.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                spec.title,
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                color = colors.inkPrimary,
                                maxLines = 1,
                            )
                            Text(
                                spec.subtitle,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                color = colors.inkSecondary,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = KaivoTheme.colors
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) colors.royalGold.copy(alpha = 0.18f) else colors.surfaceElevated.copy(alpha = 0.6f))
            .border(1.dp, if (selected) colors.royalGold.copy(alpha = 0.5f) else colors.hairline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.royalGold else colors.inkSecondary,
        )
    }
}
