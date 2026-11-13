package com.kaivoflow.feature.canvas.layout

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Canvas maths is pure, so it is verified here rather than on a device. */
class CanvasGeometryTest {

    private fun flow(vararg steps: StepNode, streams: List<StreamEdge> = emptyList()) = Flow(
        id = "f",
        name = "Flow",
        steps = steps.toList().toImmutableList(),
        streams = streams.toImmutableList(),
    )

    private fun step(id: String, x: Float = 0f, y: Float = 0f, trigger: Boolean = false) =
        StepNode(id = id, type = if (trigger) "trigger.manual" else "transform.set", name = id, x = x, y = y)

    private fun stream(from: String, to: String, port: Int = 0) = StreamEdge("$from-$to-$port", from, to, fromPort = port)

    @Test
    fun `guided layout stacks a linear flow vertically with a consistent gap`() {
        val f = flow(
            step("a", trigger = true),
            step("b"),
            step("c"),
            streams = listOf(stream("a", "b"), stream("b", "c")),
        )
        val layouts = CanvasGeometry.guidedLayout(f)
        val a = layouts.getValue("a").topLeft
        val b = layouts.getValue("b").topLeft
        val c = layouts.getValue("c").topLeft
        assertTrue(b.y > a.y)
        assertTrue(c.y > b.y)
        assertEquals(a.x, b.x, 0.01f)
        assertEquals(
            CanvasGeometry.CARD_APPROX_HEIGHT + CanvasGeometry.GUIDED_GAP,
            b.y - a.y,
            0.01f,
        )
    }

    @Test
    fun `guided layout puts sibling branches side by side on the same row`() {
        val f = flow(
            step("t", trigger = true),
            step("yes x", x = 0f),
            step("no x", x = 10f),
            streams = listOf(stream("t", "yes x"), stream("t", "no x")),
        )
        val layouts = CanvasGeometry.guidedLayout(f)
        val yes = layouts.getValue("yes x")
        val no = layouts.getValue("no x")
        assertEquals(yes.topLeft.y, no.topLeft.y, 0.01f)
        assertTrue("siblings must not overlap", kotlin.math.abs(yes.topLeft.x - no.topLeft.x) >= CanvasGeometry.CARD_WIDTH)
    }

    @Test
    fun `depths follow the longest path`() {
        val f = flow(
            step("t", trigger = true),
            step("a"),
            step("b"),
            step("c"),
            streams = listOf(stream("t", "a"), stream("t", "b"), stream("a", "c"), stream("b", "c")),
        )
        val depths = CanvasGeometry.computeDepths(f)
        assertEquals(0, depths["t"])
        assertEquals(1, depths["a"])
        assertEquals(2, depths["c"])
    }

    @Test
    fun `branch ports are distributed across the card width`() {
        val f = flow(
            step("t", trigger = true),
            step("a"),
            step("b"),
            streams = listOf(stream("t", "a", 0), stream("t", "b", 1)),
        )
        val t = CanvasGeometry.guidedLayout(f).getValue("t")
        assertEquals(2, t.ports.outputs.size)
        assertTrue(t.ports.outputs[0].x < t.ports.outputs[1].x)
        assertEquals(t.centerX, t.ports.input.x, 0.01f)
    }

    @Test
    fun `bezier control points push vertically and stay inside a sane lift`() {
        val control = CanvasGeometry.bezier(Point(0f, 0f), Point(0f, 200f))
        assertEquals(4, control.size)
        assertTrue(control[1].y > 0f)
        assertTrue(control[1].y <= 120f)
        val midpoint = CanvasGeometry.pointAlongBezier(control, 0.5f)
        assertEquals(100f, midpoint.y, 1f)
    }

    @Test
    fun `zooming is clamped to the documented range`() {
        assertEquals(CanvasGeometry.MIN_ZOOM, CanvasGeometry.clampZoom(0.1f), 0.001f)
        assertEquals(CanvasGeometry.MAX_ZOOM, CanvasGeometry.clampZoom(5f), 0.001f)
        assertEquals(1f, CanvasGeometry.clampZoom(1f), 0.001f)
    }

    @Test
    fun `culling only returns steps inside the viewport`() {
        val f = flow(step("a", y = 0f), step("b", y = 2000f), streams = listOf(stream("a", "b")))
        val layouts = CanvasGeometry.freeformLayout(f)
        val visible = CanvasGeometry.visibleRect(panX = 0f, panY = 0f, zoom = 1f, viewportWidth = 400f, viewportHeight = 800f)
        assertTrue(CanvasGeometry.isVisible(layouts.getValue("a"), visible))
        assertTrue(!CanvasGeometry.isVisible(layouts.getValue("b"), visible))
    }

    @Test
    fun `magnetic snap finds the nearest input port inside the radius`() {
        val f = flow(step("a", y = 0f), step("b", y = 200f), streams = listOf(stream("a", "b")))
        val layouts = CanvasGeometry.freeformLayout(f)
        val target = layouts.getValue("b").ports.input
        val found = CanvasGeometry.nearestPort(Point(target.x + 10f, target.y + 5f), layouts.values, excludeStepId = "a")
        assertNotNull(found)
        assertEquals("b", found!!.first)
        val tooFar = CanvasGeometry.nearestPort(Point(target.x + 400f, target.y), layouts.values)
        assertTrue(tooFar == null)
    }

    @Test
    fun `fit to screen centres the content`() {
        val bounds = Rect2(0f, 0f, 1000f, 500f)
        val (zoom, panX, panY) = CanvasGeometry.fitToScreen(bounds, viewportWidth = 1080f, viewportHeight = 1920f)
        assertTrue(zoom in CanvasGeometry.MIN_ZOOM..CanvasGeometry.MAX_ZOOM)
        val cx = (0f + 1000f / 2f) * zoom + panX
        assertEquals(540f, cx, 1f)
        assertEquals(960f, (250f * zoom) + panY, 1f)
    }

    @Test
    fun `freeform layout snaps to the 16dp grid`() {
        val f = flow(step("a", x = 21f, y = 39f))
        val layout = CanvasGeometry.freeformLayout(f).getValue("a")
        assertEquals(16f, layout.topLeft.x, 0.01f)
        assertEquals(32f, layout.topLeft.y, 0.01f)
    }

    @Test
    fun `empty flow produces empty bounds and does not crash`() {
        val bounds = CanvasGeometry.contentBounds(emptyList())
        assertEquals(0f, bounds.width, 0.001f)
        val layouts = CanvasGeometry.guidedLayout(Flow(id = "e", name = "empty", steps = persistentListOf()))
        assertTrue(layouts.isEmpty())
    }
}
