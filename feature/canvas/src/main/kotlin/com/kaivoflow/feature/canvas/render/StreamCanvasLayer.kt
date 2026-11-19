package com.kaivoflow.feature.canvas.render

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.MotionTokenValues
import com.kaivoflow.core.design.StreamVisual
import com.kaivoflow.feature.canvas.CanvasTransformState
import com.kaivoflow.feature.canvas.StreamEdgeUiModel
import com.kaivoflow.feature.canvas.layout.CanvasGeometry
import com.kaivoflow.feature.canvas.layout.Point
import com.kaivoflow.feature.canvas.layout.StepLayout
import kotlinx.collections.immutable.ImmutableList

/**
 * The Stream layer, in TWO Canvases so a running Run never repaints the dot grid:
 *  - the static Canvas draws grid + every Stream (redrawn on pan, zoom or Stream state change);
 *  - the current Canvas draws only the travelling gold orbs of Flowing Streams and is the only
 *    thing that repaints every frame while a Run is live.
 *
 * Deliberately not one Composable per connection (AGENTS.md 6.1): a 1,000-Step Flow has ~1,000
 * Streams, and a Composable each would flood the composition with layout nodes.
 *
 * StepLayout coordinates are world dp; drawing happens in screen pixels, so every point is
 * multiplied once by [pixelScale] before a Path is built. The parent graphicsLayer still owns
 * pan/zoom, so dragging never rebuilds paths.
 *
 * Allocation rules: brushes, strokes, paths and PathMeasures are built in `remember` (when the
 * layout or a Stream's visual state changes). The draw lambdas only read numbers and call
 * drawCircle / drawPath with those cached objects.
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
    val policy = KaivoTheme.motionPolicy
    val density = LocalDensity.current.density
    val animateCurrent = !reduceMotion && policy.runLoops

    val idleStroke = remember(density) { Stroke(width = 2f * density, cap = StrokeCap.Round) }
    val activeStroke = remember(density) { Stroke(width = 3f * density, cap = StrokeCap.Round) }
    val haloStroke = remember(density) { Stroke(width = 12f * density, cap = StrokeCap.Round) }

    // Cached once per layout / stream / density / theme change, never inside a draw lambda.
    val geometry = remember(layouts, streams, pixelScale, colors) {
        streams.mapNotNull { stream ->
            val from = layouts[stream.fromStepId] ?: return@mapNotNull null
            val to = layouts[stream.toStepId] ?: return@mapNotNull null
            val startDp = from.ports.outputs.getOrNull(stream.fromPort) ?: from.bottomCenter
            val endDp = to.ports.input
            val start = startDp * pixelScale
            val end = endDp * pixelScale
            val control = CanvasGeometry.bezier(start, end, pixelScale = pixelScale)
            val path = buildPath(start, end, control)
            val visual = when {
                stream.isActive -> StreamVisual.Flowing
                stream.isComplete -> StreamVisual.Done
                stream.isDimmed -> StreamVisual.Dimmed
                else -> StreamVisual.Idle
            }
            val sourceColor = Color(stream.sourceColorArgb)
            val targetColor = Color(stream.targetColorArgb)
            val a = if (visual == StreamVisual.Dimmed) 0.12f else 0.35f
            val gradient = when (visual) {
                StreamVisual.Flowing, StreamVisual.Done -> {
                    val core = if (visual == StreamVisual.Done) colors.statusSuccess else colors.royalGold
                    listOf(core.copy(alpha = 0.85f), colors.royalGold.copy(alpha = 0.85f), core.copy(alpha = 0.85f))
                }
                else -> listOf(sourceColor.copy(alpha = a), targetColor.copy(alpha = a))
            }
            StreamGeometry(
                stream = stream,
                visual = visual,
                startDp = startDp,
                endDp = endDp,
                start = start,
                end = end,
                control = control,
                path = path,
                brush = Brush.linearGradient(
                    gradient,
                    start = Offset(start.x, start.y),
                    end = Offset(end.x, end.y),
                ),
                sourceColor = sourceColor,
                targetColor = targetColor,
                measure = if (visual == StreamVisual.Flowing) PathMeasure().apply { setPath(path, false) } else null,
            )
        }
    }

    val flowing = remember(geometry, policy) {
        geometry.filter { it.visual == StreamVisual.Flowing }.take(policy.maxAnimatedStreams)
    }

    // One clock for every travelling orb, and it exists only while something is flowing.
    val phase: State<Float> = if (animateCurrent && flowing.isNotEmpty()) {
        rememberInfiniteTransition(label = "goldenCurrent").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(MotionTokenValues.CURRENT_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "streamPhase",
        )
    } else {
        remember { mutableStateOf(0.5f) }
    }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "${streams.size} Streams connecting ${layouts.size} Steps"
                },
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
                val flowingNow = geo.visual == StreamVisual.Flowing
                drawPath(path = geo.path, brush = geo.brush, style = if (flowingNow) activeStroke else idleStroke)
                // Soft halo under a live Stream: a wide low-alpha stroke, never Modifier.blur().
                if (flowingNow) {
                    drawPath(path = geo.path, color = colors.royalGold.copy(alpha = 0.14f), style = haloStroke)
                }
                drawPortPearl(geo.start, geo.sourceColor, flowingNow)
                drawPortPearl(geo.end, geo.targetColor, flowingNow)
            }
        }

        if (flowing.isNotEmpty()) {
            val orbs = policy.orbsPerStream
            val trail = policy.orbTrail
            val animated = animateCurrent
            Canvas(Modifier.fillMaxSize()) {
                val p = phase.value
                val orbRadius = 6f * density
                flowing.forEach { geo ->
                    val measure = geo.measure ?: return@forEach
                    val length = measure.length
                    if (length <= 0f) return@forEach
                    for (i in 0 until orbs) {
                        val t = if (animated) (p + i.toFloat() / orbs) % 1f else 0.5f
                        // Tent fade so orbs appear out of one port and dissolve into the next.
                        val fade = 1f - kotlin.math.abs(2f * t - 1f) * 0.65f
                        drawCurrentOrb(measure, length, t, orbRadius, fade, colors.royalGold)
                        for (k in 1..trail) {
                            val tt = t - k * 0.03f
                            if (tt <= 0f) break
                            drawCircle(
                                color = colors.royalGold.copy(alpha = 0.22f * fade / k),
                                radius = orbRadius * (1f - k * 0.2f),
                                center = measure.getPosition(length * tt),
                            )
                        }
                    }
                }
            }
        }
    }
}

private operator fun Point.times(scale: Float): Point = Point(x * scale, y * scale)

private class StreamGeometry(
    val stream: StreamEdgeUiModel,
    val visual: StreamVisual,
    val startDp: Point,
    val endDp: Point,
    val start: Point,
    val end: Point,
    val control: List<Point>,
    val path: Path,
    val brush: Brush,
    val sourceColor: Color,
    val targetColor: Color,
    val measure: PathMeasure?,
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
    val dot = color.copy(alpha = 0.28f)
    val startX = kotlin.math.floor(viewport.left / spacing) * spacing
    val startY = kotlin.math.floor(viewport.top / spacing) * spacing
    var x = startX
    while (x <= viewport.right) {
        var y = startY
        while (y <= viewport.bottom) {
            drawCircle(dot, radius = radius, center = Offset(x * pixelScale, y * pixelScale))
            y += spacing
        }
        x += spacing
    }
}

/** One gold orb: two soft concentric halos (no gradient allocation), a solid core, a white spark. */
private fun DrawScope.drawCurrentOrb(
    measure: PathMeasure,
    length: Float,
    t: Float,
    radius: Float,
    fade: Float,
    gold: Color,
) {
    val head = measure.getPosition(length * t)
    drawCircle(color = gold.copy(alpha = 0.10f * fade), radius = radius * 3.2f, center = head)
    drawCircle(color = gold.copy(alpha = 0.22f * fade), radius = radius * 1.9f, center = head)
    drawCircle(color = gold.copy(alpha = fade), radius = radius, center = head)
    drawCircle(color = Color.White.copy(alpha = 0.85f * fade), radius = radius * 0.35f, center = head)
}

/** Port pearl: soft halo ring, solid core, spark. Concentric circles, so no Brush per call. */
private fun DrawScope.drawPortPearl(point: Point, tint: Color, active: Boolean) {
    val radius = (if (active) 5f else 4f) * density
    val center = Offset(point.x, point.y)
    drawCircle(color = tint.copy(alpha = 0.10f), radius = radius * 2.4f, center = center)
    drawCircle(color = tint.copy(alpha = 0.18f), radius = radius * 1.5f, center = center)
    drawCircle(color = tint.copy(alpha = 0.95f), radius = radius, center = center)
    drawCircle(color = Color.White.copy(alpha = 0.7f), radius = radius * 0.42f, center = center)
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
    val policy = KaivoTheme.motionPolicy
    val pulse: State<Float> = if (!policy.ambientLoops) {
        remember { mutableStateOf(0.6f) }
    } else {
        rememberInfiniteTransition(label = "aura").animateFloat(
            initialValue = 0.45f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(MotionTokenValues.BREATHING_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "auraPulse",
        )
    }
    // The gradient is painted once; the breathing is a layer alpha, so no per-frame drawing.
    Canvas(modifier.graphicsLayer { alpha = pulse.value }) {
        val center = Offset(size.width / 2f, size.height * 0.42f)
        val radius = Math.min(size.width, size.height) * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.velvetRose.copy(alpha = 0.16f),
                    colors.royalGold.copy(alpha = 0.10f),
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
