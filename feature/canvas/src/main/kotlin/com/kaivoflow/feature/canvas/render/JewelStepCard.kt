package com.kaivoflow.feature.canvas.render

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.AccentRole
import com.kaivoflow.core.design.AnimatedStatusBadge
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.KaivoMotionTokens
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.MotionTokenValues
import com.kaivoflow.core.design.StepEffect
import com.kaivoflow.core.design.StepMotion
import com.kaivoflow.core.design.StepVisual
import com.kaivoflow.core.design.floatSpring
import com.kaivoflow.core.design.pulsingRing
import com.kaivoflow.core.design.rememberKaivoHaptics
import com.kaivoflow.core.design.rememberSettleState
import com.kaivoflow.core.design.rememberShakeState
import com.kaivoflow.core.design.settleScale
import com.kaivoflow.core.design.shake
import com.kaivoflow.core.design.tweenSpec
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
 *
 * Ports are real 48dp touch targets placed exactly where CanvasGeometry draws Stream endpoints:
 * one input pearl at the top centre and one draggable pearl per declared output port along the
 * bottom edge. Dragging an output pearl starts a Stream; dragging the card itself moves it in
 * Freeform mode.
 */
@Composable
fun JewelStepCard(
    step: StepNodeUiModel,
    selected: Boolean,
    isPressed: Boolean = false,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onRename: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    val sizes = KaivoTheme.sizes
    val motion = KaivoTheme.motion
    val density = LocalDensity.current
    val accent = colors.categoryColor(step.category)
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    val isHighlighted = selected || isPressed
    val borderAlpha by animateFloatAsState(
        targetValue = if (isHighlighted) 1f else 0.35f,
        animationSpec = motion.tween(),
        label = "cardBorder",
    )

    val policy = KaivoTheme.motionPolicy
    val visual = StepMotion.visual(step.status)
    val look = StepMotion.look(visual)
    val running = visual == StepVisual.Running
    val stateColor = when (look.accent) {
        AccentRole.Gold -> colors.royalGold
        AccentRole.Success -> colors.statusSuccess
        AccentRole.Error -> colors.statusError
        AccentRole.Muted -> colors.inkMuted
        AccentRole.None -> colors.hairline
    }
    val animatedStateColor by animateColorAsState(
        targetValue = stateColor,
        animationSpec = policy.tweenSpec<Color>(MotionTokenValues.STANDARD_MS),
        label = "cardStateColor",
    )
    val stateBorderAlpha by animateFloatAsState(
        targetValue = look.borderAlpha,
        animationSpec = policy.tweenSpec<Float>(MotionTokenValues.STANDARD_MS),
        label = "cardStateBorder",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = look.contentAlpha,
        animationSpec = policy.tweenSpec<Float>(MotionTokenValues.EMPHASIZED_MS),
        label = "cardContentAlpha",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && policy.animationsEnabled) 1.025f else 1f,
        animationSpec = policy.floatSpring(KaivoMotionTokens.pressSpring),
        label = "cardPressScale",
    )
    val glowTarget = when {
        isPressed -> 0.8f
        running -> KaivoMotionTokens.glowRunning
        isHighlighted -> 0.55f
        look.glow -> 0.4f
        else -> 0f
    }
    val glowAlpha by animateFloatAsState(
        targetValue = glowTarget,
        animationSpec = policy.tweenSpec<Float>(MotionTokenValues.STANDARD_MS),
        label = "cardGlow",
    )
    // Breathing exists only while this card is running; a Flow never has more than a few running.
    val breath: State<Float> = if (running && policy.runLoops && policy.ambientLoops) {
        rememberInfiniteTransition(label = "cardBreath").animateFloat(
            initialValue = 0.7f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(MotionTokenValues.BREATHING_PERIOD_MS, easing = KaivoMotionTokens.silk),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "cardBreathPhase",
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    // One-shot effects on state transitions. The first composition never replays one.
    val shake = rememberShakeState()
    val settle = rememberSettleState()
    val haptics = rememberKaivoHaptics()
    var lastVisual by remember { mutableStateOf(visual) }
    LaunchedEffect(visual) {
        val effect = StepMotion.effect(lastVisual, visual)
        lastVisual = visual
        when (effect) {
            StepEffect.Settle -> settle.settle(policy)
            StepEffect.Shake -> {
                haptics.reject()
                shake.shake(policy)
            }
            else -> Unit
        }
    }

    Box(
        modifier
            .shake(shake)
            .settleScale(settle)
            .width(sizes.stepCardWidth)
            .defaultMinSize(minHeight = sizes.stepCardMinHeight)
            .pulsingRing(active = look.ring, color = colors.royalGold, cornerRadius = 20.dp)
            .onSizeChanged { cardSize = it },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = sizes.stepCardMinHeight)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                    alpha = contentAlpha
                }
                .clip(KaivoShapes.stepCard)
                .background(colors.surfaceCard)
                .border(
                    width = if (isHighlighted || (look.accent != AccentRole.None && look.accent != AccentRole.Muted)) 1.5.dp else 1.dp,
                    brush = if (isHighlighted) {
                        Brush.linearGradient(listOf(colors.royalGold, colors.imperialAmethyst, colors.royalGold))
                    } else {
                        SolidColor(
                            if (look.accent == AccentRole.None) animatedStateColor
                            else animatedStateColor.copy(alpha = stateBorderAlpha),
                        )
                    },
                    shape = KaivoShapes.stepCard,
                )
                .drawWithCache {
                    // Gradient built once per size; the per-frame cost is one drawCircle with an alpha.
                    val glowRadius = size.width * 0.7f
                    val glowCenter = Offset(0f, size.height / 2f)
                    val glowBrush = Brush.radialGradient(
                        colors = listOf(colors.royalGold.copy(alpha = 0.22f), Color.Transparent),
                        center = glowCenter,
                        radius = glowRadius,
                    )
                    onDrawBehind {
                        val g = glowAlpha * breath.value
                        if (g > 0.01f) {
                            drawCircle(brush = glowBrush, radius = glowRadius, center = glowCenter, alpha = g.coerceIn(0f, 1f))
                        }
                    }
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
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepMedallion(iconKey = step.iconKey, tint = accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = step.name,
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(4.dp))
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .clickable { onRename() }
                                .padding(2.dp),
                        ) {
                            Icon(
                                KaivoIcons.icon("edit"),
                                contentDescription = "Rename ${step.name}",
                                tint = colors.inkMuted,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (step.paramSummary.isNotBlank()) step.paramSummary else step.subtitle,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val badge: Pair<String, BadgeKind> = when {
                    step.isFrozen -> "❄ Frozen" to BadgeKind.Gold
                    step.hasError -> "Needs a fix" to BadgeKind.Error
                    step.isDisabled -> "Off" to BadgeKind.Neutral
                    step.status == StepRunStatus.Skipped -> "Skipped" to BadgeKind.Neutral
                    step.status == StepRunStatus.Running -> "Running" to BadgeKind.Gold
                    step.status == StepRunStatus.Succeeded ->
                        "✓ ${step.itemCount ?: 0} • ${step.latencyMs ?: 0}ms" to BadgeKind.Success
                    step.status == StepRunStatus.Failed -> "Failed" to BadgeKind.Error
                    step.hasWarning -> "Check setup" to BadgeKind.Warning
                    else -> step.category.label to BadgeKind.Neutral
                }
                // One call site, so a change of text or kind crossfades instead of swapping.
                AnimatedStatusBadge(badge.first, badge.second)
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

        if (cardSize.width > 0 && cardSize.height > 0) {
            Box(Modifier.matchParentSize()) {
                val widthPx = cardSize.width.toFloat()
                val heightPx = cardSize.height.toFloat()
                val outputCount = step.outputPorts.coerceAtLeast(1)

                // Input port — a visible landing target, no drag gesture.
                val inputX = widthPx / 2f
                PortTarget(
                    label = "Input port of ${step.name}",
                    centerX = inputX,
                    centerY = 0f,
                    tint = accent,
                    isInput = true,
                )

                (0 until outputCount).forEach { port ->
                    val x = if (outputCount == 1) {
                        widthPx / 2f
                    } else {
                        widthPx * (port + 1f) / (outputCount + 1f)
                    }
                    val centerDp = with(density) {
                        Offset(x.toDp().value, heightPx.toDp().value)
                    }
                    val label = step.branchLabels.getOrElse(port) { "Output ${port + 1}" }
                    PortTarget(
                        label = "$label port of ${step.name}. Drag to connect a Stream.",
                        centerX = x,
                        centerY = heightPx,
                        tint = accent,
                        isInput = false,
                    )
                }
            }
        }
    }
}

/** A 48dp port touch target with a small gold-edged pearl at its centre. */
@Composable
private fun PortTarget(
    label: String,
    centerX: Float,
    centerY: Float,
    tint: Color,
    isInput: Boolean,
) {
    val colors = KaivoTheme.colors
    Box(
        Modifier
            .offset {
                val half = 24.dp.roundToPx()
                IntOffset((centerX - half).toInt(), (centerY - half).toInt())
            }
            .size(48.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (isInput) 10.dp else 12.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.95f))
                .border(1.dp, colors.surfaceCard, CircleShape),
        )
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(colors.canvasVoid.copy(alpha = 0.75f)),
        )
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
