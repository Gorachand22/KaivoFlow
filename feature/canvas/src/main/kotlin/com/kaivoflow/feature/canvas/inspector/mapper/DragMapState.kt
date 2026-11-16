package com.kaivoflow.feature.canvas.inspector.mapper

import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.expressions.assist.ReferenceBuilder
import com.kaivoflow.core.expressions.assist.ReferenceTarget
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.SchemaType

// Pure state machine of drag to map (spec P2 3.4). It knows nothing about Compose: the host feeds
// it pointer events and measured target bounds and carries out the effects it returns.

/** What is being dragged: a place in data, its schema type (for inference on drop) and a short label for the pearl chip. */
data class DragPayload(val source: MapSource, val type: SchemaType? = null, val label: String = "") {
    val reference: String get() = ReferenceBuilder.build(source, ReferenceTarget.Template)
}

/** Rectangle in root coordinates, in pixels. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = Math.max(0f, width) * Math.max(0f, height)
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

/** A drop target registered by a field. [accepts] is false for code, secret, credential and notice fields: they dim, and a drop on them is rejected. */
data class DropTarget(val path: ParamPath, val bounds: Bounds, val accepts: Boolean, val label: String = "")

/** Pixel thresholds; the host converts the spec's dp values with the screen density. */
data class DragConfig(
    /** Auto-scroll starts within this distance of the top or bottom of the viewport (64dp). */
    val edgeScrollZonePx: Float = 160f,
    val maxScrollPxPerSecond: Float = 1400f,
    /** Page switching by dwell at the left or right edge (phone pager); 0 turns it off. */
    val edgeSwitchZonePx: Float = 48f,
    val edgeSwitchDwellMs: Long = 650L,
    /** The finger may go this far past the viewport before a release counts as a cancel (drag outside). */
    val cancelMarginPx: Float = 120f,
)

enum class Edge { Left, Right }

data class DragMapState(
    val payload: DragPayload? = null,
    val x: Float = 0f,
    val y: Float = 0f,
    val viewport: Bounds? = null,
    val targets: List<DropTarget> = emptyList(),
    /** The innermost target under the finger, accepting or not. */
    val hover: DropTarget? = null,
    /** True when the finger is outside the viewport by more than the cancel margin: releasing cancels. */
    val outside: Boolean = false,
    val edge: Edge? = null,
    val edgeDwellMs: Long = 0L,
    /** After a page switch the edge must be left before it can switch again. */
    val edgeSpent: Boolean = false,
) {
    val active: Boolean get() = payload != null
    /** Targets that glow while a drag is active. */
    val glowing: List<DropTarget> get() = targets.filter { it.accepts }
    val dimmed: List<DropTarget> get() = targets.filter { !it.accepts }
    /** The label on the hovered target. */
    val hoverLabel: String? get() = hover?.let { if (it.accepts) "Insert here" else "Cannot take data" }
}

sealed interface DragEvent {
    data class Begin(val payload: DragPayload, val x: Float, val y: Float, val viewport: Bounds, val targets: List<DropTarget>) : DragEvent
    data class Move(val x: Float, val y: Float) : DragEvent
    /** Layout changed (the list scrolled): fresh bounds for the targets. */
    data class TargetsChanged(val targets: List<DropTarget>, val viewport: Bounds? = null) : DragEvent
    /** Frame clock while the finger is down; drives auto-scroll and the edge dwell. */
    data class Tick(val dtMs: Long) : DragEvent
    /** [selStart] and [selEnd] are the caret in the hovered field, -1 when unknown. */
    data class Drop(val selStart: Int = -1, val selEnd: Int = -1) : DragEvent
    data object Cancel : DragEvent
}

enum class HapticKind { Start, Drop, Reject }

sealed interface DragEffect {
    data class Haptic(val kind: HapticKind) : DragEffect
    /** Scroll the parameter list by this many pixels (positive is down). */
    data class ScrollBy(val dy: Float) : DragEffect
    /** Dwell at a side edge: go to the page on that side. */
    data class SwitchPage(val edge: Edge) : DragEffect
    data class Insert(val target: ParamPath, val payload: DragPayload, val selStart: Int, val selEnd: Int) : DragEffect
    /** Short shake on the target and the snackbar "This field cannot take data references". */
    data class Rejected(val target: ParamPath) : DragEffect
    data object Cancelled : DragEffect
}

data class DragStep(val state: DragMapState, val effects: List<DragEffect> = emptyList())

object DragMachine {
    const val REJECT_MESSAGE = "This field cannot take data references"

    fun reduce(s: DragMapState, e: DragEvent, config: DragConfig = DragConfig()): DragStep = when (e) {
        is DragEvent.Begin -> {
            val st = place(
                DragMapState(payload = e.payload, viewport = e.viewport, targets = e.targets),
                e.x, e.y, config,
            )
            DragStep(st, listOf(DragEffect.Haptic(HapticKind.Start)))
        }
        is DragEvent.Move -> if (!s.active) DragStep(s) else DragStep(place(s, e.x, e.y, config))
        is DragEvent.TargetsChanged -> if (!s.active) DragStep(s) else {
            val moved = s.copy(targets = e.targets, viewport = e.viewport ?: s.viewport)
            DragStep(place(moved, s.x, s.y, config))
        }
        is DragEvent.Tick -> if (!s.active) DragStep(s) else tick(s, e.dtMs, config)
        is DragEvent.Drop -> drop(s, e)
        DragEvent.Cancel -> if (!s.active) DragStep(s) else DragStep(DragMapState(), listOf(DragEffect.Cancelled))
    }

    /** The innermost target containing the point (smallest area wins, so a field inside a card beats the card). */
    fun hitTest(targets: List<DropTarget>, x: Float, y: Float): DropTarget? =
        targets.filter { it.bounds.contains(x, y) }.minByOrNull { it.bounds.area }

    private fun place(s: DragMapState, x: Float, y: Float, c: DragConfig): DragMapState {
        val vp = s.viewport
        val outside = vp != null && (x < vp.left - c.cancelMarginPx || x > vp.right + c.cancelMarginPx ||
            y < vp.top - c.cancelMarginPx || y > vp.bottom + c.cancelMarginPx)
        val edge = if (vp == null || c.edgeSwitchZonePx <= 0f || outside) null else when {
            x <= vp.left + c.edgeSwitchZonePx -> Edge.Left
            x >= vp.right - c.edgeSwitchZonePx -> Edge.Right
            else -> null
        }
        val sameEdge = edge != null && edge == s.edge
        return s.copy(
            x = x, y = y, outside = outside,
            hover = if (outside) null else hitTest(s.targets, x, y),
            edge = edge,
            edgeDwellMs = if (sameEdge) s.edgeDwellMs else 0L,
            edgeSpent = if (sameEdge) s.edgeSpent else false,
        )
    }

    /** Pixels per second, signed, for a finger at [y]. Zero away from the top and bottom edges. */
    fun scrollVelocity(vp: Bounds?, y: Float, c: DragConfig): Float {
        if (vp == null || c.edgeScrollZonePx <= 0f) return 0f
        val topDepth = (vp.top + c.edgeScrollZonePx) - y
        val bottomDepth = y - (vp.bottom - c.edgeScrollZonePx)
        return when {
            topDepth > 0f -> -c.maxScrollPxPerSecond * Math.min(1f, topDepth / c.edgeScrollZonePx)
            bottomDepth > 0f -> c.maxScrollPxPerSecond * Math.min(1f, bottomDepth / c.edgeScrollZonePx)
            else -> 0f
        }
    }

    private fun tick(s: DragMapState, dtMs: Long, c: DragConfig): DragStep {
        val effects = ArrayList<DragEffect>()
        val v = scrollVelocity(s.viewport, s.y, c)
        if (v != 0f) effects += DragEffect.ScrollBy(v * dtMs / 1000f)
        var next = s
        val edge = s.edge
        if (edge != null && !s.edgeSpent) {
            val dwell = s.edgeDwellMs + dtMs
            if (dwell >= c.edgeSwitchDwellMs) {
                effects += DragEffect.SwitchPage(edge)
                next = s.copy(edgeDwellMs = 0L, edgeSpent = true)
            } else next = s.copy(edgeDwellMs = dwell)
        }
        return DragStep(next, effects)
    }

    private fun drop(s: DragMapState, e: DragEvent.Drop): DragStep {
        val payload = s.payload ?: return DragStep(s)
        val end = DragMapState()
        val hover = s.hover
        return when {
            s.outside || hover == null -> DragStep(end, listOf(DragEffect.Cancelled))
            !hover.accepts -> DragStep(end, listOf(DragEffect.Haptic(HapticKind.Reject), DragEffect.Rejected(hover.path)))
            else -> DragStep(end, listOf(DragEffect.Haptic(HapticKind.Drop), DragEffect.Insert(hover.path, payload, e.selStart, e.selEnd)))
        }
    }
}

/** Tap-to-insert and TalkBack: "Map to..." lists the fields a drag could reach, in reading order. */
object MapToOptions {
    data class Option(val path: ParamPath, val label: String)

    fun list(targets: List<DropTarget>): List<Option> =
        targets.filter { it.accepts }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .map { Option(it.path, it.label.ifBlank { it.path.toString() }) }

    /** The field a tap-to-insert goes to: the focused one when it accepts, else null (the tray then asks). */
    fun focusedTarget(targets: List<DropTarget>, focused: ParamPath?): DropTarget? =
        focused?.let { f -> targets.firstOrNull { it.path == f && it.accepts } }
}
