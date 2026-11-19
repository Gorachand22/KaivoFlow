package com.kaivoflow.core.design

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Status badge that crossfades when its text or kind changes. Static when animations are off. */
@Composable
fun AnimatedStatusBadge(
    text: String,
    kind: BadgeKind = BadgeKind.Neutral,
    modifier: Modifier = Modifier,
) {
    val policy = LocalMotionPolicy.current
    if (!policy.animationsEnabled) {
        StatusBadge(text, kind, modifier)
        return
    }
    Crossfade(
        targetState = BadgeSpec(text, kind),
        modifier = modifier,
        animationSpec = tween(policy.ms(MotionTokenValues.QUICK_MS)),
        label = "statusBadge",
    ) { spec -> StatusBadge(spec.text, spec.kind) }
}

private data class BadgeSpec(val text: String, val kind: BadgeKind)

/**
 * Number ticker. Counts from the previous value to the new one, but TalkBack always reads the
 * final value (never the intermediate frames).
 */
@Composable
fun AnimatedCounter(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    format: (Int) -> String = { it.toString() },
) {
    val policy = LocalMotionPolicy.current
    val anim = remember { Animatable(value.toFloat()) }
    LaunchedEffect(value, policy.level) {
        val target = value.toFloat()
        if (!policy.animationsEnabled || anim.value == target) {
            anim.snapTo(target)
        } else {
            val ms = policy.ms(CounterMath.durationMs(anim.value.roundToInt(), value))
            anim.animateTo(target, tween(durationMillis = ms, easing = KaivoMotionTokens.settle))
        }
    }
    val shown by remember { derivedStateOf { anim.value.roundToInt() } }
    val description = format(value)
    Text(
        text = format(shown),
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        style = style,
        color = color,
    )
}

/**
 * Circle that draws itself, then a check mark that draws itself. Shows the finished mark
 * immediately when animations are off or [play] is false.
 */
@Composable
fun SuccessCheck(
    modifier: Modifier = Modifier,
    diameter: Dp = 24.dp,
    color: Color = KaivoTheme.colors.statusSuccess,
    play: Boolean = true,
) {
    val policy = LocalMotionPolicy.current
    val progress = remember { Animatable(if (play && policy.animationsEnabled) 0f else 1f) }
    LaunchedEffect(play, policy.level) {
        if (!play || !policy.animationsEnabled) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(policy.ms(MotionTokenValues.EMPHASIZED_MS + 160), easing = KaivoMotionTokens.settle))
        }
    }
    Box(
        modifier
            .size(diameter)
            .drawWithCache {
                val w = size.width
                val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val inset = stroke.width / 2f
                val arcSize = Size(w - stroke.width, size.height - stroke.width)
                val check = Path().apply {
                    moveTo(w * 0.30f, size.height * 0.52f)
                    lineTo(w * 0.45f, size.height * 0.66f)
                    lineTo(w * 0.72f, size.height * 0.36f)
                }
                val measure = PathMeasure().apply { setPath(check, false) }
                val segment = Path()
                onDrawBehind {
                    val p = progress.value
                    val ring = (p / 0.6f).coerceIn(0f, 1f)
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * ring,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = stroke,
                    )
                    val tick = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
                    if (tick > 0f) {
                        segment.rewind()
                        measure.getSegment(0f, measure.length * tick, segment, true)
                        drawPath(segment, color, style = stroke)
                    }
                }
            }
            .semantics { contentDescription = "Success" },
    )
}

/**
 * Health ring: animated sweep with a gold-to-[color] gradient and a centre slot.
 * Fills with a luxurious spring on first show and on every score change.
 */
@Composable
fun HealthRing(
    score: Int,
    color: Color,
    modifier: Modifier = Modifier,
    diameter: Dp = 112.dp,
    strokeWidth: Dp = 10.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val policy = LocalMotionPolicy.current
    val colors = KaivoTheme.colors
    val sweep = remember { Animatable(0f) }
    val target = RingMath.sweepDegrees(score)
    LaunchedEffect(target, policy.level) {
        if (!policy.animationsEnabled) sweep.snapTo(target)
        else sweep.animateTo(target, KaivoMotionTokens.ringSpring)
    }
    Box(
        modifier
            .size(diameter)
            .drawWithCache {
                val sw = strokeWidth.toPx()
                val stroke = Stroke(width = sw, cap = StrokeCap.Round)
                val inset = sw / 2f
                val arcSize = Size(size.width - sw, size.height - sw)
                val brush = Brush.sweepGradient(
                    listOf(colors.royalGoldDeep, colors.royalGold, color),
                    center = Offset(size.width / 2f, size.height / 2f),
                )
                val track = colors.hairline
                onDrawBehind {
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
                    val s = sweep.value
                    if (s > 0.5f) {
                        rotate(-90f) {
                            drawArc(brush, 0f, s, false, Offset(inset, inset), arcSize, style = stroke)
                        }
                    }
                }
            }
            .semantics { contentDescription = "Reliability score $score out of 100" },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A rounded placeholder block with the shared shimmer band. Pass one [phase] for the whole screen. */
@Composable
fun SkeletonBlock(
    phase: State<Float>,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(7.dp),
) {
    val colors = KaivoTheme.colors
    Box(
        modifier
            .clip(shape)
            .background(colors.surfaceElevated.copy(alpha = 0.55f))
            .shimmerSweep(phase, colors.royalGold.copy(alpha = 0.16f)),
    )
}

/** Settings control for the owner's motion level. Host it in the Settings screen. */
@Composable
fun MotionSettingsSection(modifier: Modifier = Modifier) {
    val controller = LocalMotionController.current
    Column(modifier) {
        SectionHeader(
            title = "Motion",
            subtitle = "Calm shortens effects and stops decorative loops. Off removes them. The system remove-animations setting always wins.",
        )
        Spacer(Modifier.height(8.dp))
        SegmentedPill(
            options = listOf("Full", "Calm", "Off"),
            selectedIndex = controller.level.ordinal,
            onSelect = { index -> controller.setLevel(MotionLevel.values()[index]) },
        )
    }
}
