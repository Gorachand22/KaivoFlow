package com.kaivoflow.core.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Reusable motion modifiers. Rules every one of them follows:
 *  - transforms and alpha go through graphicsLayer lambdas (no recomposition per frame);
 *  - animated State is read inside draw/layer lambdas only;
 *  - objects (Stroke, Brush, Path) are created in drawWithCache or remember, never per frame;
 *  - every decision about whether to animate comes from LocalMotionPolicy.
 */

/**
 * Press feedback: spring scale to [pressedScale] plus a gold hairline glow. Put it FIRST in a
 * chain (before clip/background) so the whole surface scales and the glow draws on top.
 * With animations off the scale is skipped and the glow still shows, so press state stays visible.
 */
fun Modifier.kaivoPressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    cornerRadius: Dp = 20.dp,
    pressedScale: Float = KaivoMotionTokens.pressScale,
    glow: Boolean = true,
    onClickLabel: String? = null,
): Modifier = composed {
    val policy = LocalMotionPolicy.current
    val gold = KaivoTheme.colors.royalGold
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val progress by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = policy.floatSpring(KaivoMotionTokens.pressSpring),
        label = "pressProgress",
    )
    val scaleOn = policy.animationsEnabled
    this
        .graphicsLayer {
            if (scaleOn) {
                val s = 1f - (1f - pressedScale) * progress
                scaleX = s
                scaleY = s
            }
        }
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
        .drawWithContent {
            drawContent()
            val p = progress.coerceIn(0f, 1f)
            if (glow && p > 0.01f) {
                val r = cornerRadius.toPx()
                drawRoundRect(
                    color = gold.copy(alpha = KaivoMotionTokens.glowPress * p),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                drawRoundRect(
                    color = gold.copy(alpha = 0.06f * p),
                    cornerRadius = CornerRadius(r, r),
                )
            }
        }
}

/**
 * List entrance: fade and rise, staggered by [index] (capped by the policy). Plays once per [key];
 * pass a `remember { mutableSetOf<Any>() }` as [played] so rows that scroll back in do not replay.
 */
fun Modifier.staggeredEntrance(
    index: Int,
    key: Any? = null,
    played: MutableSet<Any>? = null,
): Modifier = composed {
    val policy = LocalMotionPolicy.current
    val k: Any = key ?: index
    val animate = Stagger.shouldAnimate(policy, played?.contains(k) == true)
    val progress = remember(k) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(k) {
        if (animate) {
            delay(Stagger.delayMs(index, policy.staggerStepMs, policy.staggerMaxItems))
            progress.animateTo(1f, policy.tweenSpec<Float>(MotionTokenValues.EMPHASIZED_MS, KaivoMotionTokens.flowing))
        }
        played?.add(k)
    }
    val risePx = with(LocalDensity.current) { KaivoMotionTokens.entranceRise.toPx() }
    this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * risePx
    }
}

/** Horizontal shake for errors. Drive it with [ShakeState.shake] from a LaunchedEffect. */
@Stable
class ShakeState {
    internal val offset = Animatable(0f)

    suspend fun shake(policy: MotionPolicy) {
        if (!policy.animationsEnabled) return
        val steps = floatArrayOf(-1f, 1f, -0.7f, 0.7f, -0.35f, 0f)
        for (target in steps) {
            offset.animateTo(target, tween(durationMillis = policy.ms(46).coerceAtLeast(16), easing = LinearEasing))
        }
    }
}

@Composable
fun rememberShakeState(): ShakeState = remember { ShakeState() }

fun Modifier.shake(state: ShakeState, amplitude: Dp = KaivoMotionTokens.shakeAmplitude): Modifier = composed {
    val amplitudePx = with(LocalDensity.current) { amplitude.toPx() }
    this.graphicsLayer { translationX = state.offset.value * amplitudePx }
}

/** One-shot overshoot scale, used for the success settle. */
@Stable
class SettleState {
    internal val scale = Animatable(1f)

    suspend fun settle(policy: MotionPolicy) {
        if (!policy.animationsEnabled) return
        scale.snapTo(KaivoMotionTokens.settlePeak)
        scale.animateTo(1f, KaivoMotionTokens.settleSpring)
    }
}

@Composable
fun rememberSettleState(): SettleState = remember { SettleState() }

fun Modifier.settleScale(state: SettleState): Modifier = this.graphicsLayer {
    val s = state.scale.value
    scaleX = s
    scaleY = s
}

/** One clock for every shimmer on a screen. A negative value means "static, draw no band". */
@Composable
fun rememberShimmerPhase(): State<Float> {
    val policy = LocalMotionPolicy.current
    return if (policy.shimmer) {
        rememberInfiniteTransition(label = "shimmer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(MotionTokenValues.SHIMMER_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmerPhase",
        )
    } else {
        remember { mutableStateOf(-1f) }
    }
}

/** Soft band sweeping across the element. Apply AFTER clip so the band stays inside the shape. */
fun Modifier.shimmerSweep(phase: State<Float>, tint: Color, bandFraction: Float = 0.45f): Modifier = drawWithCache {
    val band = size.width * bandFraction
    val brush = Brush.horizontalGradient(listOf(Color.Transparent, tint, Color.Transparent), startX = 0f, endX = band)
    val bandSize = Size(band, size.height)
    onDrawWithContent {
        drawContent()
        val p = phase.value
        if (p >= 0f && band > 0f) {
            translate(left = -band + p * (size.width + band)) {
                drawRect(brush = brush, size = bandSize)
            }
        }
    }
}

/**
 * One-shot gold sweep for premium moments (Run starting, Flow published). Plays whenever [trigger]
 * changes to a non-null value. Apply AFTER clip.
 */
fun Modifier.goldSweep(trigger: Any?, strength: Float = 0.38f): Modifier = composed {
    val policy = LocalMotionPolicy.current
    val gold = KaivoTheme.colors.royalGold
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger == null || !policy.animationsEnabled) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(policy.ms(MotionTokenValues.SLOW_MS), easing = KaivoMotionTokens.flowing))
    }
    this.drawWithCache {
        val band = size.width * 0.5f
        val brush = Brush.horizontalGradient(
            listOf(Color.Transparent, gold.copy(alpha = strength), Color.Transparent),
            startX = 0f,
            endX = band,
        )
        val bandSize = Size(band, size.height)
        onDrawWithContent {
            drawContent()
            val p = progress.value
            if (p < 1f) {
                translate(left = -band + p * (size.width + band)) {
                    drawRect(brush = brush, size = bandSize)
                }
            }
        }
    }
}

/**
 * Two staggered rings expanding out of the element and fading: the "this Step is running" pulse.
 * Cheap: one infinite transition per active element. Static ring when loops are off.
 */
fun Modifier.pulsingRing(
    active: Boolean,
    color: Color,
    cornerRadius: Dp,
    maxGrow: Dp = 10.dp,
): Modifier = composed {
    val policy = LocalMotionPolicy.current
    if (!active) return@composed this
    val phase: State<Float> = if (policy.runLoops) {
        rememberInfiniteTransition(label = "ring").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
            label = "ringPhase",
        )
    } else {
        remember { mutableStateOf(0.35f) }
    }
    this.drawWithCache {
        val growPx = maxGrow.toPx()
        val radiusPx = cornerRadius.toPx()
        val stroke = Stroke(width = 1.5.dp.toPx())
        onDrawBehind {
            val p1 = phase.value
            val p2 = (p1 + 0.5f) % 1f
            val g1 = growPx * p1
            drawRoundRect(
                color = color.copy(alpha = 0.5f * (1f - p1)),
                topLeft = Offset(-g1, -g1),
                size = Size(size.width + 2f * g1, size.height + 2f * g1),
                cornerRadius = CornerRadius(radiusPx + g1, radiusPx + g1),
                style = stroke,
            )
            if (policy.runLoops && policy.ambientLoops) {
                val g2 = growPx * p2
                drawRoundRect(
                    color = color.copy(alpha = 0.35f * (1f - p2)),
                    topLeft = Offset(-g2, -g2),
                    size = Size(size.width + 2f * g2, size.height + 2f * g2),
                    cornerRadius = CornerRadius(radiusPx + g2, radiusPx + g2),
                    style = stroke,
                )
            }
        }
    }
}

/** Slow scale breathing for empty-state hero shapes. No-op unless ambient loops are allowed. */
fun Modifier.breathing(amplitude: Float = 0.04f): Modifier = composed {
    val policy = LocalMotionPolicy.current
    if (!policy.ambientLoops) return@composed this
    val t by rememberInfiniteTransition(label = "breathing").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(MotionTokenValues.BREATHING_PERIOD_MS, easing = KaivoMotionTokens.silk),
            RepeatMode.Reverse,
        ),
        label = "breathingPhase",
    )
    this.graphicsLayer {
        val s = 1f + amplitude * t
        scaleX = s
        scaleY = s
    }
}
