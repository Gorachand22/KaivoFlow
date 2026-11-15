package com.kaivoflow.feature.canvas.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SheetDragHandle
import com.kaivoflow.core.design.StepMedallion
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory

/**
 * Step Picker: in-canvas bottom sheet.
 * Supports:
 *  1. Tap card -> adds to viewport center.
 *  2. Touch & drag -> lifts card and drags freely onto the canvas. Dropping places node at finger position.
 */
@Composable
fun StepPickerSheet(
    specs: List<NodeSpec>,
    onSearch: (String) -> Unit,
    onPick: (NodeSpec) -> Unit,
    onDismiss: () -> Unit,
    hasTrigger: Boolean,
    onDropOnCanvas: ((NodeSpec, Offset) -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    val haptics = LocalHapticFeedback.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<StepCategory?>(null) }
    var draggingSpec by remember { mutableStateOf<NodeSpec?>(null) }
    var dragScreenOffset by remember { mutableStateOf(Offset.Zero) }

    val filtered = remember(specs, query, category) {
        specs.filter { spec ->
            (category == null || spec.category == category) &&
                (query.isBlank() || spec.title.contains(query, true) ||
                    spec.subtitle.contains(query, true) ||
                    spec.keywords.any { it.contains(query, true) })
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Scrim
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (draggingSpec != null) 0.15f else 0.55f))
                .clickable(enabled = draggingSpec == null, onClick = onDismiss)
        )

        // Slide-up sheet
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(colors.surfaceAtelier.copy(alpha = if (draggingSpec != null) 0.35f else 0.98f))
                    .border(1.dp, colors.hairline, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SheetDragHandle()
                }

                Text("Add a Step", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hasTrigger) "Tap to add, or drag onto canvas." else "Start with a Spark — the thing that begins your Flow.",
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
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.heightIn(max = 420.dp),
                    ) {
                        items(filtered, key = { it.id }) { spec ->
                            val accent = colors.categoryColor(spec.category)
                            var cardOffsetInWindow by remember { mutableStateOf(Offset.Zero) }

                            Column(
                                Modifier
                                    .onGloballyPositioned { coordinates ->
                                        cardOffsetInWindow = coordinates.positionInWindow()
                                    }
                                    .clip(KaivoShapes.stepCard)
                                    .background(colors.surfaceCard)
                                    .border(1.dp, colors.hairline, KaivoShapes.stepCard)
                                    .pointerInput(spec.id) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val startPos = down.position
                                            var lastPos = down.position
                                            var isDragging = false
                                            val touchSlop = viewConfiguration.touchSlop
                                            var totalDrag = 0f

                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!change.pressed) {
                                                    if (isDragging) {
                                                        val current = draggingSpec
                                                        val dropPos = dragScreenOffset
                                                        draggingSpec = null
                                                        if (current != null) {
                                                            if (onDropOnCanvas != null) {
                                                                onDropOnCanvas(current, dropPos)
                                                            } else {
                                                                onPick(current)
                                                            }
                                                        }
                                                    } else {
                                                        change.consume()
                                                        onPick(spec)
                                                    }
                                                    break
                                                }

                                                val deltaScreen = change.position - lastPos
                                                lastPos = change.position
                                                totalDrag += (change.position - startPos).getDistance()

                                                if (!isDragging && totalDrag > touchSlop) {
                                                    isDragging = true
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    draggingSpec = spec
                                                    dragScreenOffset = cardOffsetInWindow + change.position
                                                }

                                                if (isDragging) {
                                                    change.consume()
                                                    dragScreenOffset += deltaScreen
                                                }
                                            }
                                        }
                                    }
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

        // Fullscreen Drag Preview floating freely across the entire canvas
        draggingSpec?.let { dragged ->
            val accent = colors.categoryColor(dragged.category)
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (dragScreenOffset.x - 110.dp.roundToPx()).toInt(),
                            (dragScreenOffset.y - 42.dp.roundToPx()).toInt(),
                        )
                    }
                    .width(220.dp)
                    .clip(KaivoShapes.stepCard)
                    .background(colors.surfaceCard)
                    .border(2.dp, colors.royalGold, KaivoShapes.stepCard)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepMedallion(iconKey = dragged.iconKey, tint = accent, size = 36.dp, iconSize = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(dragged.title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.inkPrimary, maxLines = 1)
                        Text(dragged.subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkSecondary, maxLines = 1)
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
