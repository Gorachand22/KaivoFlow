package com.kaivoflow.core.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A floating glass pill — toolbars, HUD chips, minimap frames. */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = KaivoTheme.colors
    Box(
        modifier = modifier
            .clip(KaivoShapes.pill)
            .background(colors.surfaceGlass)
            .border(1.dp, colors.hairline, KaivoShapes.pill)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { content() }
}

/**
 * The gold call-to-action. 48dp tall by contract, with a metallic gradient and a pressed state
 * rather than a drop shadow (shadows read as dirt on an AMOLED canvas).
 */
@Composable
fun GoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val colors = KaivoTheme.colors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "goldButtonAlpha")

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(KaivoShapes.pill)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        if (pressed) colors.royalGoldDeep else colors.royalGold,
                        if (pressed) colors.royalGold else colors.royalGoldDeep,
                    )
                )
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = if (compact) 16.dp else 22.dp, vertical = 12.dp)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = KaivoIcons.icon(icon),
                contentDescription = null,
                tint = Color(0xFF241A05).copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = KaivoTheme.type.bodyFamily),
            color = Color(0xFF241A05),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(alpha),
        )
    }
}

/** Ghost button for secondary actions on the canvas. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(KaivoShapes.pill)
            .background(colors.surfaceElevated.copy(alpha = 0.55f))
            .border(1.dp, colors.hairline, KaivoShapes.pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(KaivoIcons.icon(icon), null, tint = colors.inkSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, maxLines = 1)
    }
}

/** The 44dp squircle medallion that carries a Step's jewel tone. */
@Composable
fun StepMedallion(
    iconKey: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = KaivoTheme.sizes.medallion,
    iconSize: androidx.compose.ui.unit.Dp = KaivoTheme.sizes.medallionIcon,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(KaivoShapes.medallion)
            .background(
                Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = 0.30f), tint.copy(alpha = 0.10f)),
                )
            )
            .border(1.dp, tint.copy(alpha = 0.28f), KaivoShapes.medallion),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = KaivoIcons.icon(iconKey),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

enum class BadgeKind { Success, Warning, Error, Neutral, Gold }

/** Small mono-font badge: `✓ 12 • 85ms`, `Frozen`, `2 retries`. */
@Composable
fun StatusBadge(
    text: String,
    kind: BadgeKind = BadgeKind.Neutral,
    modifier: Modifier = Modifier,
) {
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
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.30f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
        )
    }
}

/** Expression chip: `{{ $json.amount }}`. */
@Composable
fun ExpressionChip(
    expression: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 32.dp)
            .clip(KaivoShapes.chip)
            .background(colors.surfaceElevated)
            .border(1.dp, colors.imperialAmethyst.copy(alpha = 0.35f), KaivoShapes.chip)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = expression,
            style = MaterialTheme.typography.labelMedium,
            color = colors.imperialAmethyst,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
        }
        trailing?.invoke()
    }
}

/** The gold drag handle at the top of every bottom sheet. */
@Composable
fun SheetDragHandle(modifier: Modifier = Modifier) {
    val colors = KaivoTheme.colors
    Box(
        modifier = modifier
            .width(KaivoTheme.sizes.dragPillWidth)
            .height(KaivoTheme.sizes.dragPillHeight)
            .clip(CircleShape)
            .background(colors.royalGold.copy(alpha = 0.85f)),
    )
}

@Composable
fun KaivoSurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = KaivoTheme.colors
    Surface(
        modifier = modifier,
        color = colors.surfaceCard,
        shape = KaivoShapes.stepCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairline),
        content = content,
    )
}

/** Row of segmented tabs used by the inspector and the step picker. */
@Composable
fun SegmentedPill(
    options: List<String>,
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
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 40.dp)
                    .clip(KaivoShapes.pill)
                    .background(if (selected) colors.royalGold.copy(alpha = 0.18f) else Color.Transparent)
                    .border(
                        width = if (selected) 1.dp else 0.dp,
                        color = if (selected) colors.royalGold.copy(alpha = 0.5f) else Color.Transparent,
                        shape = KaivoShapes.pill,
                    )
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    color = if (selected) colors.royalGold else colors.inkSecondary,
                )
            }
        }
    }
}
