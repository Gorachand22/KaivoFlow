package com.kaivoflow.feature.canvas.render

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.design.StepMedallion
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.feature.canvas.StepNodeUiModel

/**
 * The Jewel Card: 220dp wide, 84dp minimum height.
 *
 * Anatomy per AGENTS.md §4.2 — hairline border, left medallion, title + status row, human summary,
 * right status badge. Selected state is a 1.5dp gold gradient border with a halo drawn as a radial
 * gradient (never Modifier.blur).
 */
@Composable
fun JewelStepCard(
    step: StepNodeUiModel,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onOutputDragStart: (Offset) -> Unit,
    onOutputDrag: (Offset) -> Unit,
    onOutputDragEnd: () -> Unit,
    onOutputDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
    draggable: Boolean = false,
) {
    val colors = KaivoTheme.colors
    val sizes = KaivoTheme.sizes
    val motion = KaivoTheme.motion
    val haptics = LocalHapticFeedback.current
    val accent = colors.categoryColor(step.category)

    val borderAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.35f,
        animationSpec = motion.tween(),
        label = "cardBorder",
    )

    val running = step.status == StepRunStatus.Running
    val glowAlpha = if (running && !motion.reduceMotion) {
        val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "cardGlow")
        infinite.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.75f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(
                    motion.breathingDurationMs.toInt(),
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "cardGlowAlpha",
        ).value
    } else 0f

    Column(
        modifier = modifier
            .width(sizes.stepCardWidth)
            .defaultMinSize(minHeight = sizes.stepCardMinHeight)
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                brush = if (selected) {
                    Brush.linearGradient(listOf(colors.royalGold, colors.imperialAmethyst, colors.royalGold))
                } else {
                    Brush.linearGradient(listOf(colors.hairline, colors.hairline))
                },
                shape = KaivoShapes.stepCard,
            )
            .drawBehind {
                if (glowAlpha > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(colors.royalGold.copy(alpha = 0.18f * glowAlpha), Color.Transparent),
                            center = Offset(0f, size.height / 2f),
                            radius = size.width * 0.7f,
                        ),
                        radius = size.width * 0.7f,
                        center = Offset(0f, size.height / 2f),
                    )
                }
            }
            .then(
                if (draggable) {
                    Modifier.pointerInput(step.id) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOutputDragStart(offset)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onOutputDrag(dragAmount)
                            },
                            onDragEnd = { onOutputDragEnd() },
                            onDragCancel = { onOutputDragCancel() },
                        )
                    }
                } else Modifier
            )
            .pointerInput(step.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                )
            }
            .semantics {
                contentDescription = buildString {
                    append(if (step.isTrigger) "Spark " else "Step ")
                    append(step.name)
                    append(", ")
                    append(step.subtitle)
                    when (step.status) {
                        StepRunStatus.Running -> append(", running")
                        StepRunStatus.Succeeded -> append(", finished with ${step.itemCount ?: 0} items")
                        StepRunStatus.Failed -> append(", failed")
                        StepRunStatus.Skipped -> append(", skipped")
                        StepRunStatus.Frozen -> append(", output frozen")
                        else -> Unit
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepMedallion(iconKey = step.iconKey, tint = accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = step.name,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = step.paramSummary,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            when {
                step.isFrozen -> StatusBadge("❄ Frozen", BadgeKind.Gold)
                step.hasError -> StatusBadge("Needs a fix", BadgeKind.Error)
                step.isDisabled -> StatusBadge("Off", BadgeKind.Neutral)
                step.status == StepRunStatus.Skipped -> StatusBadge("Skipped", BadgeKind.Neutral)
                step.status == StepRunStatus.Running -> StatusBadge("Running", BadgeKind.Gold)
                step.status == StepRunStatus.Succeeded -> StatusBadge(
                    "✓ ${step.itemCount ?: 0} • ${step.latencyMs ?: 0}ms",
                    BadgeKind.Success,
                )
                step.status == StepRunStatus.Failed -> StatusBadge("Failed", BadgeKind.Error)
                step.hasWarning -> StatusBadge("Check setup", BadgeKind.Warning)
                else -> StatusBadge(step.category.label, BadgeKind.Neutral)
            }
            Spacer(Modifier.weight(1f))
            if (step.attempts > 1) {
                StatusBadge("${step.attempts} tries", BadgeKind.Warning)
            }
            if (step.outputPorts > 1) {
                Spacer(Modifier.width(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    step.branchLabels.take(step.outputPorts).forEach { label ->
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.25f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(
                                label,
                                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                color = colors.inkPrimary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A dimmed placeholder shown while cards are being materialised (staggered entrance). */
@Composable
fun StepCardSkeleton(modifier: Modifier = Modifier) {
    val colors = KaivoTheme.colors
    Box(
        modifier
            .width(KaivoTheme.sizes.stepCardWidth)
            .height(KaivoTheme.sizes.stepCardMinHeight)
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard.copy(alpha = 0.5f))
            .border(1.dp, colors.hairline, KaivoShapes.stepCard),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(KaivoTheme.sizes.medallion)
                    .clip(KaivoShapes.medallion)
                    .background(colors.surfaceElevated),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Box(Modifier.width(120.dp).height(12.dp).clip(CircleShape).background(colors.surfaceElevated))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(80.dp).height(10.dp).clip(CircleShape).background(colors.surfaceElevated))
            }
        }
    }
}
