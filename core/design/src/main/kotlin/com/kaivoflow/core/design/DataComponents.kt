package com.kaivoflow.core.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.model.SchemaType

// Components for the data panels and parameter widgets (spec P2 T14). Every colour comes from
// KaivoTheme.colors; every interactive node is at least 48dp; nothing here holds data state.

/** Name, icon and tint of each data type. The icon shape and the label carry the type, never colour alone. */
object KaivoDataTypes {
    fun label(type: SchemaType): String = when (type) {
        SchemaType.Null -> "null"
        SchemaType.Bool -> "boolean"
        SchemaType.Number -> "number"
        SchemaType.String -> "text"
        SchemaType.Date -> "date"
        SchemaType.Object -> "object"
        SchemaType.Array -> "list"
        SchemaType.Binary -> "binary"
        SchemaType.Mixed -> "mixed"
    }

    fun iconKey(type: SchemaType): String = when (type) {
        SchemaType.Null -> "remove"
        SchemaType.Bool -> "check"
        SchemaType.Number -> "numbers"
        SchemaType.String -> "textFormat"
        SchemaType.Date -> "calendar"
        SchemaType.Object -> "dataObject"
        SchemaType.Array -> "grid"
        SchemaType.Binary -> "attachFile"
        SchemaType.Mixed -> "shuffle"
    }

    fun tint(type: SchemaType, c: KaivoColorScheme): Color = when (type) {
        SchemaType.Null -> c.inkMuted
        SchemaType.Bool -> c.stepTrigger
        SchemaType.Number -> c.stepLogic
        SchemaType.String -> c.stepSaas
        SchemaType.Date -> c.stepDevice
        SchemaType.Object -> c.imperialAmethyst
        SchemaType.Array -> c.stepAi
        SchemaType.Binary -> c.royalGoldDeep
        SchemaType.Mixed -> c.statusWarning
    }
}

/** Small tinted square with the type's icon. Decorative: the row that holds it describes the type in words. */
@Composable
fun TypeIcon(type: SchemaType, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val tint = KaivoDataTypes.tint(type, KaivoTheme.colors)
    Box(
        modifier = modifier
            .size(size)
            .clip(KaivoShapes.chip)
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.28f), KaivoShapes.chip),
        contentAlignment = Alignment.Center,
    ) {
        Icon(KaivoIcons.icon(KaivoDataTypes.iconKey(type)), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.62f))
    }
}

/** Pill with icon and type name, for column headers and detail lines. */
@Composable
fun TypeBadge(type: SchemaType, modifier: Modifier = Modifier) {
    val tint = KaivoDataTypes.tint(type, KaivoTheme.colors)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.28f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(KaivoIcons.icon(KaivoDataTypes.iconKey(type)), contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(KaivoDataTypes.label(type), style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

/** Number or short text in a round pill: item counts, match counts, unread issues. */
@Composable
fun CountPill(text: String, modifier: Modifier = Modifier, kind: BadgeKind = BadgeKind.Neutral, description: String? = null) {
    val colors = KaivoTheme.colors
    val tint = when (kind) {
        BadgeKind.Success -> colors.statusSuccess
        BadgeKind.Warning -> colors.statusWarning
        BadgeKind.Error -> colors.statusError
        BadgeKind.Gold -> colors.royalGold
        BadgeKind.Neutral -> colors.inkSecondary
    }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontFamily = KaivoTheme.type.monoFamily), color = tint, maxLines = 1)
    }
}

/** Tappable chip. The visual stays compact; the hit area is at least 48dp by 48dp. */
@Composable
fun KaivoChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    iconKey: String? = null,
    trailingIconKey: String? = null,
    description: String? = null,
) {
    val colors = KaivoTheme.colors
    val tint = if (selected) colors.royalGold else colors.inkSecondary
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(KaivoShapes.chip)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                if (description != null) contentDescription = description
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .alpha(if (enabled) 1f else 0.4f)
                .clip(KaivoShapes.chip)
                .background(if (selected) colors.royalGold.copy(alpha = 0.16f) else colors.surfaceElevated.copy(alpha = 0.7f))
                .border(1.dp, if (selected) colors.royalGold.copy(alpha = 0.5f) else colors.hairline, KaivoShapes.chip)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconKey != null) {
                Icon(KaivoIcons.icon(iconKey), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (trailingIconKey != null) {
                Spacer(Modifier.width(4.dp))
                Icon(KaivoIcons.icon(trailingIconKey), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Immutable
data class SegmentOption(
    val label: String,
    val iconKey: String? = null,
    val badge: String? = null,
    val enabled: Boolean = true,
)

/** Segmented switch for view modes (Schema, Table, JSON, Binary). Each segment is a 48dp tab. */
@Composable
fun SegmentedSwitch(
    options: List<SegmentOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = modifier
            .clip(KaivoShapes.pill)
            .background(colors.surfaceElevated.copy(alpha = 0.6f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            val tint = if (isSelected) colors.royalGold else colors.inkSecondary
            Row(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .alpha(if (option.enabled) 1f else 0.4f)
                    .clip(KaivoShapes.pill)
                    .background(if (isSelected) colors.royalGold.copy(alpha = 0.18f) else Color.Transparent)
                    .border(
                        width = if (isSelected) 1.dp else 0.dp,
                        color = if (isSelected) colors.royalGold.copy(alpha = 0.5f) else Color.Transparent,
                        shape = KaivoShapes.pill,
                    )
                    .selectable(selected = isSelected, enabled = option.enabled, role = Role.Tab) { onSelect(index) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (option.iconKey != null) {
                    Icon(KaivoIcons.icon(option.iconKey), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(option.label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (option.badge != null) {
                    Spacer(Modifier.width(4.dp))
                    CountPill(option.badge, kind = if (isSelected) BadgeKind.Gold else BadgeKind.Neutral)
                }
            }
        }
    }
}

/**
 * One row of a field list (Schema view, mapper tray). [expanded] is null for a leaf. Apply a drag
 * or other gesture through [modifier]; tap runs [onClick]. The row announces name, type, optional
 * state and sample as one TalkBack node, with Expand and Collapse as custom actions.
 */
@Composable
fun FieldRow(
    label: String,
    type: SchemaType,
    modifier: Modifier = Modifier,
    depth: Int = 0,
    sample: String? = null,
    note: String? = null,
    optional: Boolean = false,
    expanded: Boolean? = null,
    onToggleExpand: (() -> Unit)? = null,
    selected: Boolean = false,
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null,
    extraActions: List<CustomAccessibilityAction> = emptyList(),
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    val spoken = buildString {
        append(label).append(", ").append(KaivoDataTypes.label(type))
        if (optional) append(", optional")
        if (note != null) append(", ").append(note)
        if (sample != null) append(", sample ").append(sample)
    }
    val toggleAction = if (expanded != null && onToggleExpand != null) {
        listOf(CustomAccessibilityAction(if (expanded) "Collapse" else "Expand") { onToggleExpand(); true })
    } else emptyList()
    val allActions = toggleAction + extraActions
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(if (selected) colors.royalGold.copy(alpha = 0.10f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 8.dp + 14.dp * depth, end = 8.dp)
            .alpha(if (dimmed) 0.55f else 1f)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                this.selected = selected
                if (allActions.isNotEmpty()) customActions = allActions
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(enabled = onToggleExpand != null, role = Role.Button) { onToggleExpand?.invoke() }
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    KaivoIcons.icon(if (expanded) "expandMore" else "chevronRight"),
                    contentDescription = null,
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        TypeIcon(type)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (optional) {
                    Spacer(Modifier.width(4.dp))
                    Text("?", style = MaterialTheme.typography.labelLarge, color = colors.statusWarning)
                }
            }
            if (note != null) {
                Text(note, style = MaterialTheme.typography.labelMedium, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (sample != null) {
                Text(
                    sample,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = KaivoTheme.type.monoFamily),
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Small-caps label above a group of rows, with an optional count and trailing action. */
@Composable
fun SectionLabel(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            CountPill(count.toString())
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Empty state: one line, an optional hint, at most a gold primary and a ghost secondary action. No illustration. */
@Composable
fun EmptyStateBlock(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    iconKey: String? = null,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    KaivoSurfaceCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (iconKey != null) {
                Icon(KaivoIcons.icon(iconKey), contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(28.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
            if (primaryLabel != null && onPrimary != null) GoldButton(primaryLabel, onPrimary, compact = true)
            if (secondaryLabel != null && onSecondary != null) GhostButton(secondaryLabel, onSecondary)
        }
    }
}

/** Rose error card with message, optional collapsible [details], and copy and action buttons. */
@Composable
fun ErrorBlock(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    details: String? = null,
    onCopyDetails: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    var showDetails by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KaivoShapes.stepCard)
            .background(colors.statusError.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, colors.statusError.copy(alpha = 0.40f)), KaivoShapes.stepCard)
            .padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(KaivoIcons.icon("error"), contentDescription = null, tint = colors.statusError, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.statusError)
        }
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
        if (details != null) {
            KaivoChip(
                text = if (showDetails) "Hide details" else "Show details",
                onClick = { showDetails = !showDetails },
                trailingIconKey = if (showDetails) "unfoldLess" else "unfoldMore",
            )
            if (showDetails) {
                Text(
                    details,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = KaivoTheme.type.monoFamily),
                    color = colors.inkSecondary,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onCopyDetails != null) GhostButton("Copy details", onCopyDetails, icon = "copy")
            if (actionLabel != null && onAction != null) GhostButton(actionLabel, onAction)
        }
    }
}

/** Skeleton rows in the shape of list content. Static when reduced motion is on. */
@Composable
fun SkeletonRows(modifier: Modifier = Modifier, rows: Int = 6) {
    val colors = KaivoTheme.colors
    val reduce = KaivoTheme.motion.reduceMotion
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    val a = if (reduce) 0.5f else pulse
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "Loading" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(rows) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(KaivoShapes.chip).background(colors.surfaceElevated.copy(alpha = a)))
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .weight(if (i % 2 == 0) 0.7f else 0.5f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(colors.surfaceElevated.copy(alpha = a)),
                )
                Spacer(Modifier.weight(if (i % 2 == 0) 0.3f else 0.5f))
            }
        }
    }
}

/** Search box with a clear button; submits on the keyboard's search key. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    onSubmit: (() -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(KaivoShapes.pill)
            .background(colors.surfaceElevated.copy(alpha = 0.6f))
            .border(1.dp, colors.hairline, KaivoShapes.pill)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(KaivoIcons.icon("search"), contentDescription = null, tint = colors.inkSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.inkPrimary),
            cursorBrush = SolidColor(colors.royalGold),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }),
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onValueChange("") }
                    .semantics { contentDescription = "Clear search" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(KaivoIcons.icon("close"), contentDescription = null, tint = colors.inkSecondary, modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(14.dp))
        }
    }
}

/** A 48dp round icon button. [description] is required: it is the TalkBack label. */
@Composable
fun KaivoIconButton(
    iconKey: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = KaivoTheme.colors.inkSecondary,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(KaivoIcons.icon(iconKey), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * Bottom bar of a paged list: previous, `3 / 20`, next, and a page-size chip. [onJump] (the page
 * label) and [onPickSize] open sheets built by the caller.
 */
@Composable
fun PagerBar(
    pageLabel: String,
    rangeLabel: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    sizeLabel: String? = null,
    onPickSize: (() -> Unit)? = null,
    onJump: (() -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceAtelier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KaivoIconButton("back", "Previous page", onPrev, enabled = hasPrev)
        Column(
            modifier = Modifier
                .defaultMinSize(minWidth = 72.dp, minHeight = 48.dp)
                .clip(KaivoShapes.chip)
                .then(if (onJump != null) Modifier.clickable(role = Role.Button, onClick = onJump) else Modifier)
                .padding(horizontal = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = "Page $pageLabel, items $rangeLabel" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(pageLabel, style = MaterialTheme.typography.titleMedium.copy(fontFamily = KaivoTheme.type.monoFamily), color = colors.inkPrimary, maxLines = 1)
            Text(rangeLabel, style = MaterialTheme.typography.labelMedium, color = colors.inkMuted, maxLines = 1)
        }
        KaivoIconButton("forward", "Next page", onNext, enabled = hasNext)
        Spacer(Modifier.weight(1f))
        if (sizeLabel != null && onPickSize != null) {
            KaivoChip(text = sizeLabel, onClick = onPickSize, trailingIconKey = "expandMore", description = "Page size $sizeLabel")
        }
    }
}

/** Sheet scaffold shared by the data and parameter pickers: gold handle, title, scrolling-friendly content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaivoBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = KaivoTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceAtelier,
        dragHandle = {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SheetDragHandle()
            }
        },
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionHeader(title = title, subtitle = subtitle)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** One choice in a picker sheet: title, optional second line, check when selected. */
@Composable
fun SheetOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(KaivoShapes.chip)
            .background(if (selected) colors.royalGold.copy(alpha = 0.10f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (selected) colors.royalGold else colors.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(KaivoIcons.icon("check"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(20.dp))
        }
    }
}
