package com.kaivoflow.feature.canvas.layout

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas geometry — pure maths, no Compose, so it is unit-tested on the JVM.
 *
 * Two layout modes share one geometry layer:
 *  • [LayoutMode.GuidedPath] — the phone default. Steps fall in a centred waterfall with a
 *    48dp gap; branching Steps split into parallel tracks.
 *  • [LayoutMode.Freeform] — an infinite plane where the user places Steps by hand, with
 *    magnetic snapping to a 16dp grid.
 */
enum class LayoutMode { GuidedPath, Freeform }

data class Point(val x: Float, val y: Float)

data class Rect2(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    fun intersects(other: Rect2): Boolean =
        left <= other.right && other.left <= right && top <= other.bottom && other.top <= bottom
    fun inflate(dx: Float, dy: Float) = Rect2(left - dx, top - dy, right + dx, bottom + dy)
}

data class PortGeometry(
    val input: Point,
    val outputs: List<Point>,
)

data class StepLayout(
    val id: String,
    val topLeft: Point,
    val size: Size2,
    val ports: PortGeometry,
) {
    val centerX: Float get() = topLeft.x + size.width / 2f
    val bottomCenter: Point get() = Point(centerX, topLeft.y + size.height)
    val topCenter: Point get() = Point(centerX, topLeft.y)
    fun bounds(): Rect2 = Rect2(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height)
}

data class Size2(val width: Float, val height: Float)

object CanvasGeometry {

    const val CARD_WIDTH = 220f
    const val CARD_MIN_HEIGHT = 84f
    /** Mock-up height used before a card has been measured. */
    const val CARD_APPROX_HEIGHT = 92f
    const val GUIDED_GAP = 48f
    const val SNAP_GRID = 16f
    const val CULLING_BUFFER = 120f
    const val PORT_HIT_RADIUS = 36f
    const val MIN_ZOOM = 0.35f
    const val MAX_ZOOM = 2.0f

    fun snap(value: Float, grid: Float = SNAP_GRID): Float = Math.round(value / grid) * grid

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /**
     * Guided Path layout.
     *
     * Nodes are grouped into "rows" by longest distance from a trigger, so parallel branches land
     * side by side on the same row instead of drifting diagonally. Within a row each node is
     * centred, rows are separated by [GUIDED_GAP].
     */
    fun guidedLayout(
        flow: Flow,
        cardHeights: Map<String, Float> = emptyMap(),
        columnGap: Float = 24f,
        /** Declared output ports from the catalog; used even before the first Stream exists. */
        outputPortCounts: Map<String, Int> = emptyMap(),
    ): Map<String, StepLayout> {
        val depths = computeDepths(flow)
        val rows = flow.steps.groupBy { depths[it.id] ?: 0 }.toSortedMap()
        val layouts = LinkedHashMap<String, StepLayout>()
        var y = 0f

        rows.forEach { (_, stepsInRow) ->
            val ordered = stepsInRow.sortedWith(compareBy({ it.x }, { it.name }))
            val totalWidth = ordered.size * CARD_WIDTH + (ordered.size - 1).coerceAtLeast(0) * columnGap
            var x = -totalWidth / 2f
            var rowHeight = 0f
            ordered.forEach { step ->
                val height = cardHeights[step.id] ?: CARD_APPROX_HEIGHT
                rowHeight = max(rowHeight, height)
                layouts[step.id] = buildLayout(
                    step, flow, Point(x, y), Size2(CARD_WIDTH, height),
                    outputPortCounts[step.id] ?: 1,
                )
                x += CARD_WIDTH + columnGap
            }
            y += rowHeight + GUIDED_GAP
        }
        return layouts
    }

    /** Freeform layout: positions come straight from the model, snapped to grid. */
    fun freeformLayout(
        flow: Flow,
        cardHeights: Map<String, Float> = emptyMap(),
        outputPortCounts: Map<String, Int> = emptyMap(),
    ): Map<String, StepLayout> = flow.steps.associate { step ->
        val height = cardHeights[step.id] ?: CARD_APPROX_HEIGHT
        step.id to buildLayout(
            step, flow, Point(snap(step.x), snap(step.y)), Size2(CARD_WIDTH, height),
            outputPortCounts[step.id] ?: 1,
        )
    }

    /**
     * Longest-path depth from any trigger. Used by Guided Path rows and by the "auto arrange"
     * action. Cycles are impossible here (the validator rejects them) but the guard keeps the
     * function total.
     */
    fun computeDepths(flow: Flow): Map<String, Int> {
        val depths = mutableMapOf<String, Int>()
        val triggerIds = flow.steps.filter { it.isTrigger }.map { it.id }
        triggerIds.forEach { depths[it] = 0 }
        if (triggerIds.isEmpty() && flow.steps.isNotEmpty()) {
            flow.steps.firstOrNull { step -> flow.streams.none { it.toStepId == step.id } }?.let { depths[it.id] = 0 }
        }
        var changed = true
        var guard = 0
        while (changed && guard++ < flow.steps.size + 2) {
            changed = false
            flow.steps.forEach { step ->
                val incoming = flow.streams.filter { it.toStepId == step.id }
                if (incoming.isEmpty()) {
                    if (depths[step.id] == null) depths[step.id] = 0
                    return@forEach
                }
                val candidate = incoming.mapNotNull { depths[it.fromStepId] }.maxOrNull()?.plus(1)
                if (candidate != null && depths[step.id] != candidate) {
                    depths[step.id] = candidate
                    changed = true
                }
            }
        }
        return depths
    }

    private fun buildLayout(
        step: StepNode,
        flow: Flow,
        topLeft: Point,
        size: Size2,
        declaredOutputs: Int = 1,
    ): StepLayout {
        val used = (flow.streams.filter { it.fromStepId == step.id }.map { it.fromPort }.maxOrNull() ?: 0) + 1
        val outputPortCount = maxOf(used, declaredOutputs.coerceAtLeast(1))
        val outputs = (0 until outputPortCount).map { port ->
            Point(
                x = topLeft.x + size.width * if (outputPortCount == 1) 0.5f else (port + 1f) / (outputPortCount + 1f),
                y = topLeft.y + size.height,
            )
        }
        return StepLayout(
            id = step.id,
            topLeft = topLeft,
            size = size,
            ports = PortGeometry(
                input = Point(topLeft.x + size.width / 2f, topLeft.y),
                outputs = outputs,
            ),
        )
    }

    /**
     * Cubic Bézier between two ports. The control points push vertically, which reads as a
     * "stream" rather than a wire, and stays smooth when branches sit side by side.
     */
    fun bezier(start: Point, end: Point, pixelScale: Float = 1f): List<Point> {
        val dy = abs(end.y - start.y)
        val dx = abs(end.x - start.x)
        // Points arrive in screen pixels; keep the lift density-independent.
        val lift = max(28f * pixelScale, min(120f * pixelScale, dy * 0.45f + dx * 0.15f))
        return listOf(
            start,
            Point(start.x, start.y + lift),
            Point(end.x, end.y - lift),
            end,
        )
    }

    fun pointAlongBezier(control: List<Point>, t: Float): Point {
        require(control.size == 4)
        val u = 1f - t
        val x = u * u * u * control[0].x + 3 * u * u * t * control[1].x + 3 * u * t * t * control[2].x + t * t * t * control[3].x
        val y = u * u * u * control[0].y + 3 * u * u * t * control[1].y + 3 * u * t * t * control[2].y + t * t * t * control[3].y
        return Point(x, y)
    }

    /** Content bounds of the whole Flow, used by "fit to screen" and the minimap. */
    fun contentBounds(
        layouts: Collection<StepLayout>,
        notes: Collection<com.kaivoflow.core.model.StickyNote> = emptyList(),
    ): Rect2 {
        if (layouts.isEmpty() && notes.isEmpty()) return Rect2(0f, 0f, 0f, 0f)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        layouts.forEach { layout ->
            val bounds = layout.bounds()
            left = min(left, bounds.left)
            top = min(top, bounds.top)
            right = max(right, bounds.right)
            bottom = max(bottom, bounds.bottom)
        }
        notes.forEach { note ->
            left = min(left, note.x)
            top = min(top, note.y)
            right = max(right, note.x + note.width)
            bottom = max(bottom, note.y + note.height)
        }
        return Rect2(left, top, right, bottom)
    }

    /**
     * Visible region in canvas coordinates. Used for viewport culling: cards and Streams outside
     * this rect are never composed or drawn.
     */
    fun visibleRect(panX: Float, panY: Float, zoom: Float, viewportWidth: Float, viewportHeight: Float): Rect2 {
        val left = -panX / zoom
        val top = -panY / zoom
        val right = left + viewportWidth / zoom
        val bottom = top + viewportHeight / zoom
        return Rect2(left, top, right, bottom)
    }

    fun isVisible(layout: StepLayout, visible: Rect2): Boolean =
        layout.bounds().intersects(visible.inflate(CULLING_BUFFER, CULLING_BUFFER))

    fun isStreamVisible(start: Point, end: Point, visible: Rect2): Boolean {
        val bounds = Rect2(
            min(start.x, end.x), min(start.y, end.y),
            max(start.x, end.x), max(start.y, end.y),
        )
        return bounds.intersects(visible.inflate(CULLING_BUFFER, CULLING_BUFFER))
    }

    /** Nearest port within the magnetic snap radius, or null. */
    fun nearestPort(
        point: Point,
        layouts: Collection<StepLayout>,
        excludeStepId: String? = null,
        allowOutputs: Boolean = false,
    ): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        var bestDistance = PORT_HIT_RADIUS
        layouts.forEach { layout ->
            if (layout.id == excludeStepId) return@forEach
            val candidates = if (allowOutputs) {
                layout.ports.outputs.mapIndexed { index, port -> port to index }
            } else {
                listOf(layout.ports.input to 0)
            }
            candidates.forEach { (port, index) ->
                val distance = kotlin.math.hypot(port.x - point.x, port.y - point.y)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = layout.id to index
                }
            }
        }
        return best
    }

    /** Zoom and pan that fit the whole Flow with comfortable padding. */
    fun fitToScreen(
        bounds: Rect2,
        viewportWidth: Float,
        viewportHeight: Float,
        padding: Float = 56f,
        /** Pixels per dp. Layout coordinates are dp; pan returned here is in screen pixels. */
        pixelScale: Float = 1f,
    ): Triple<Float, Float, Float> {
        if (bounds.width <= 0f || bounds.height <= 0f) return Triple(1f, 0f, 0f)
        val widthDp = viewportWidth / pixelScale
        val heightDp = viewportHeight / pixelScale
        val zoom = clampZoom(
            min(
                (widthDp - padding * 2) / bounds.width,
                (heightDp - padding * 2) / bounds.height,
            )
        )
        val panX = viewportWidth / 2f - (bounds.left + bounds.width / 2f) * zoom * pixelScale
        val panY = viewportHeight / 2f - (bounds.top + bounds.height / 2f) * zoom * pixelScale
        return Triple(zoom, panX, panY)
    }
}
