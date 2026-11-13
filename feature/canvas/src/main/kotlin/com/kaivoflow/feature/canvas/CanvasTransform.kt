package com.kaivoflow.feature.canvas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.kaivoflow.feature.canvas.layout.CanvasGeometry
import com.kaivoflow.feature.canvas.layout.Point
import com.kaivoflow.feature.canvas.layout.Rect2
import kotlinx.coroutines.launch

/**
 * Pan/zoom state for the canvas.
 *
 * Performance contract (AGENTS.md §6.3): [panX], [panY] and [zoom] are *only* read inside
 * `Modifier.graphicsLayer { }` and `Canvas { }` draw scopes. Dragging therefore causes zero
 * recompositions — the values are read in the draw phase, which is what keeps a 1,000-Step Flow
 * at 60/120fps.
 */
@Stable
class CanvasTransformState(
    initialZoom: Float = 1f,
    initialPanX: Float = 0f,
    initialPanY: Float = 0f,
) {
    var zoom by mutableFloatStateOf(initialZoom)
        private set
    var panX by mutableFloatStateOf(initialPanX)
        private set
    var panY by mutableFloatStateOf(initialPanY)
        private set

    /** Set while a pinch/drag gesture is active so ambient animation can be paused. */
    var isInteracting by mutableStateOf(false)
        private set

    fun onPan(dx: Float, dy: Float) {
        panX += dx
        panY += dy
    }

    fun onZoom(factor: Float, around: Point) {
        val newZoom = CanvasGeometry.clampZoom(zoom * factor)
        if (newZoom == zoom) return
        // Keep the point under the fingers fixed: p' = f + (p - f) * (z'/z)
        val ratio = newZoom / zoom
        panX = around.x - (around.x - panX) * ratio
        panY = around.y - (around.y - panY) * ratio
        zoom = newZoom
    }

    fun beginInteraction() { isInteracting = true }
    fun endInteraction() { isInteracting = false }

    fun visibleRect(viewportWidth: Float, viewportHeight: Float): Rect2 =
        CanvasGeometry.visibleRect(panX, panY, zoom, viewportWidth, viewportHeight)

    /** Converts a screen-space point (from a pointer event) into canvas coordinates. */
    fun toCanvas(screenX: Float, screenY: Float): Point = Point((screenX - panX) / zoom, (screenY - panY) / zoom)

    suspend fun animateTo(zoom: Float, panX: Float, panY: Float, durationMs: Int = 320) {
        val animator = Animatable(1f)
        val startZoom = this.zoom
        val startPanX = this.panX
        val startPanY = this.panY
        animator.animateTo(1f, tween(durationMs)) {
            val fraction = value
            this@CanvasTransformState.zoom = startZoom + (zoom - startZoom) * fraction
            this@CanvasTransformState.panX = startPanX + (panX - startPanX) * fraction
            this@CanvasTransformState.panY = startPanY + (panY - startPanY) * fraction
        }
    }

    fun jumpTo(zoom: Float, panX: Float, panY: Float) {
        this.zoom = CanvasGeometry.clampZoom(zoom)
        this.panX = panX
        this.panY = panY
    }

    fun fit(bounds: Rect2, viewportWidth: Float, viewportHeight: Float) {
        val (zoom, panX, panY) = CanvasGeometry.fitToScreen(bounds, viewportWidth, viewportHeight)
        jumpTo(zoom, panX, panY)
    }
}

@Composable
fun rememberCanvasTransformState(): CanvasTransformState = remember { CanvasTransformState() }

/** Helper for the "fit to screen" button, which needs a coroutine for the animation. */
@Composable
fun rememberFitToScreen(transform: CanvasTransformState): (Rect2, Float, Float) -> Unit {
    val scope = rememberCoroutineScope()
    return { bounds, width, height ->
        val (zoom, panX, panY) = CanvasGeometry.fitToScreen(bounds, width, height)
        scope.launch { transform.animateTo(zoom, panX, panY) }
    }
}
