package com.kaivoflow.feature.canvas.render

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.feature.canvas.CanvasTransformState
import com.kaivoflow.feature.canvas.StreamEdgeUiModel
import com.kaivoflow.feature.canvas.layout.CanvasGeometry
import com.kaivoflow.feature.canvas.layout.Point
import com.kaivoflow.feature.canvas.layout.StepLayout
import kotlinx.collections.immutable.ImmutableList

/**
 * The Stream layer: dot grid + every Bézier Stream + the Golden Current, in ONE Canvas.
 *
 * Deliberately not one Composable per connection (AGENTS.md §6.1): a 1,000-Step Flow has ~1,000
 * Streams, and a Composable each would flood the composition with layout nodes.
 *
 * StepLayout coordinates are world dp; drawing happens in screen pixels, so every point is
 * multiplied once by [pixelScale] before a Path is built. The parent graphicsLayer still owns
 * pan/zoom, so dragging never rebuilds paths.
 */
@Composable
fun StreamCanvasLayer(
    layouts: Map<String, StepLayout>,
    streams: ImmutableList<StreamEdgeUiModel>,
    transform: CanvasTransformState,
    reduceMotion: Boolean,
    pixelScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    val motion = KaivoTheme.motion

    // Cached once per layout/stream/density change — never inside onDraw.
    val geometry = remember(layouts, streams, pixelScale) {
        streams.mapNotNull { stream ->
            val from = layouts[stream.fromStepId] ?: return@mapNotNull null
            val to = layouts[stream.toStepId] ?: return@mapNotNull null
            val startDp = from.ports.outputs.getOrNull(stream.fromPort) ?: from.bottomCenter
            val endDp = to.ports.input
            val start = startDp * pixelScale
            val end = endDp * pixelScale
            val control = CanvasGeometry.bezier(start, end, pixelScale = pixelScale)
            StreamGeometry(
                stream = stream,
                startDp = startDp,
                endDp = endDp,
                start = start,
                end = end,
                control = control,
                path = buildPath(start, end, control),
            )
        }
    }

    // One clock for every travelling orb: cheaper than an animation per Stream.
    val phase = if (reduceMotion) {
        0.5f
    } else {
        val infinite = rememberInfiniteTransition(label = "goldenCurrent")
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(motion.goldenOrbDurationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "streamPhase",
        ).value
    }

    Canvas(
        modifier = modifier.semantics {
            contentDescription = "${streams.size} Streams connecting ${layouts.size} Steps"
        }
    ) {
        // Read pan/zoom here (draw phase only) so gestures never recompose the canvas.
        val zoom = transform.zoom
        val panX = transform.panX
        val panY = transform.panY
        // visibleRect works in world dp; pan and viewport arrive in screen pixels.
        val viewport = CanvasGeometry.visibleRect(
            panX / pixelScale,
            panY / pixelScale,
            zoom,
            size.width / pixelScale,
            size.height / pixelScale,
        )

        drawDotGrid(viewport, colors.inkMuted, pixelScale)

        geometry.forEach { geo ->
            if (!CanvasGeometry.isStreamVisible(geo.startDp, geo.endDp, viewport)) return@forEach
            val active = geo.stream.isActive
            val complete = geo.stream.isComplete
            val dimmed = geo.stream.isDimmed

            val coreColor = when {
                complete -> colors.statusSuccess
                active -> colors.royalGold
                else -> null
            }
            val brush = if (coreColor != null) {
                Brush.linearGradient(
                    listOf(
                        coreColor.copy(alpha = 0.85f),
                        colors.royalGold.copy(alpha = 0.85f),
                        coreColor.copy(alpha = 0.85f),
                    ),
                    start = Offset(geo.start.x, geo.start.y),
                    end = Offset(geo.end.x, geo.end.y),
                )
            } else {
                Brush.linearGradient(
                    listOf(
                        Color(geo.stream.sourceColorArgb).copy(alpha = if (dimmed) 0.12f else 0.35f),
                        Color(geo.stream.targetColorArgb).copy(alpha = if (dimmed) 0.12f else 0.35f),
                    ),
                    start = Offset(geo.start.x, geo.start.y),
                    end = Offset(geo.end.x, geo.end.y),
                )
            }

            drawPath(
                path = geo.path,
                brush = brush,
                style = Stroke(
                    width = if (active) 3f * density else 2f * density,
                    cap = StrokeCap.Round,
                ),
            )

            // Soft halo under an active Stream — a gradient stroke, never Modifier.blur().
            if (active) {
                drawPath(
                    path = geo.path,
                    color = colors.royalGold.copy(alpha = 0.14f),
                    style = Stroke(width = 12f * density, cap = StrokeCap.Round),
                )
            }

            if (active) {
                drawGoldenOrb(geo, phase, colors.royalGold, reducedMotion = reduceMotion)
            }

            drawPortPearl(geo.start, Color(geo.stream.sourceColorArgb), active)
            drawPortPearl(geo.end, Color(geo.stream.targetColorArgb), active)
        }
    }
}

private operator fun Point.times(scale: Float): Point = Point(x * scale, y * scale)

private class StreamGeometry(
    val stream: StreamEdgeUiModel,
    val startDp: Point,
    val endDp: Point,
    val start: Point,
    val end: Point,
    val control: List<Point>,
    val path: Path,
)

private fun buildPath(start: Point, end: Point, control: List<Point> = CanvasGeometry.bezier(start, end)): Path =
    Path().apply {
        moveTo(start.x, start.y)
        cubicTo(control[1].x, control[1].y, control[2].x, control[2].y, end.x, end.y)
    }

/** Ambient dot grid, drawn only inside the visible rect. */
private fun DrawScope.drawDotGrid(
    viewport: com.kaivoflow.feature.canvas.layout.Rect2,
    color: Color,
    pixelScale: Float,
) {
    val spacing = 24f
    val radius = 1.2f * pixelScale
    val startX = kotlin.math.floor(viewport.left / spacing) * spacing
    val startY = kotlin.math.floor(viewport.top / spacing) * spacing
    var x = startX
    while (x <= viewport.right) {
        var y = startY
        while (y <= viewport.bottom) {
            drawCircle(color.copy(alpha = 0.28f), radius = radius, center = Offset(x * pixelScale, y * pixelScale))
            y += spacing
        }
        x += spacing
    }
}

/** The travelling 6dp gold orb with a fading trail. */
private fun DrawScope.drawGoldenOrb(
    geo: StreamGeometry,
    phase: Float,
    gold: Color,
    reducedMotion: Boolean,
) {
    val t = if (reducedMotion) 0.5f else phase
    val head = CanvasGeometry.pointAlongBezier(geo.control, t)
    val orbRadius = 6f * density
    if (!reducedMotion) {
        // Trail: three fading samples behind the head.
        for (i in 1..3) {
            val trailT = (t - i * 0.035f).coerceAtLeast(0f)
            val trail = CanvasGeometry.pointAlongBezier(geo.control, trailT)
            drawCircle(
                color = gold.copy(alpha = 0.20f / i),
                radius = orbRadius * (1f - i * 0.18f),
                center = Offset(trail.x, trail.y),
            )
        }
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(gold.copy(alpha = 0.55f), gold.copy(alpha = 0f)),
            center = Offset(head.x, head.y),
            radius = orbRadius * 3.2f,
        ),
        radius = orbRadius * 3.2f,
        center = Offset(head.x, head.y),
    )
    drawCircle(color = gold, radius = orbRadius, center = Offset(head.x, head.y))
    drawCircle(color = Color.White.copy(alpha = 0.85f), radius = orbRadius * 0.35f, center = Offset(head.x, head.y))
}

private fun DrawScope.drawPortPearl(point: Point, tint: Color, active: Boolean) {
    val radius = (if (active) 5f else 4f) * density
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(tint.copy(alpha = 0.35f), tint.copy(alpha = 0f)),
            center = Offset(point.x, point.y),
            radius = radius * 2.6f,
        ),
        radius = radius * 2.6f,
        center = Offset(point.x, point.y),
    )
    drawCircle(color = tint.copy(alpha = 0.95f), radius = radius, center = Offset(point.x, point.y))
    drawCircle(color = Color.White.copy(alpha = 0.7f), radius = radius * 0.42f, center = Offset(point.x, point.y))
}

/** Dashed bezier preview while the user drags a new Stream from an output port. */
@Composable
fun StreamDragPreview(
    start: Point?,
    end: Point?,
    modifier: Modifier = Modifier,
    isSnapped: Boolean = false,
    pixelScale: Float = 1f,
) {
    if (start == null || end == null) return
    val colors = KaivoTheme.colors
    Canvas(modifier) {
        val from = start * pixelScale
        val to = end * pixelScale
        val control = CanvasGeometry.bezier(from, to, pixelScale = pixelScale)
        val path = buildPath(from, to, control)
        val line = if (isSnapped) colors.statusSuccess else colors.royalGold
        drawPath(
            path = path,
            color = line.copy(alpha = 0.78f),
            style = Stroke(
                width = 2.5f * density,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(14f * density, 10f * density),
                ),
            ),
        )
        drawCircle(color = colors.royalGold, radius = 6f * density, center = Offset(from.x, from.y))
        drawCircle(color = line, radius = 7f * density, center = Offset(to.x, to.y))
        drawCircle(color = Color.White.copy(alpha = 0.8f), radius = 2.5f * density, center = Offset(to.x, to.y))
    }
}

/** Draws the empty-canvas aura behind the Prompt-to-Flow card. */
@Composable
fun CanvasAura(modifier: Modifier = Modifier) {
    val colors = KaivoTheme.colors
    val motion = KaivoTheme.motion
    val pulse = if (motion.reduceMotion) {
        0.6f
    } else {
        val infinite = rememberInfiniteTransition(label = "aura")
        infinite.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(motion.breathingDurationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "auraPulse",
        ).value
    }
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height * 0.42f)
        val radius = Math.min(size.width, size.height) * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.velvetRose.copy(alpha = 0.10f * pulse),
                    colors.royalGold.copy(alpha = 0.06f * pulse),
                    Color.Transparent,
                ),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}

/** Kept so unused imports stay honest for tooling; no-op drawing helper for tests. */
internal fun DrawScope.debugDrawFrame(size: Size) {
    drawRect(Color.Transparent, size = size)
}
