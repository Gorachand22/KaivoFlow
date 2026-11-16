package com.kaivoflow.feature.canvas.inspector.mapper

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.SchemaPath
import com.kaivoflow.core.model.SchemaType
import com.kaivoflow.feature.canvas.inspector.state.InspectorIntent

/** What the host does with the outcomes the machine cannot do itself. All have no-op defaults. */
open class DragMapHandler {
    open fun onHaptic(kind: HapticKind) {}
    /** A drop on a field that cannot take data: show the snackbar [DragMachine.REJECT_MESSAGE]. */
    open fun onRejected(target: ParamPath) {}
    open fun onSwitchPage(edge: Edge) {}
    open fun onCancelled() {}
    /** After a drop or a tap inserted into [target]; the host shows the undo bar from its own edit record. */
    open fun onInserted(target: ParamPath) {}
}

data class Rejection(val path: ParamPath, val token: Int)

/**
 * Holds the [DragMapState] and the registry of drop targets that fields announce while composed.
 * Target bounds are read from the layout coordinates at the moment they are needed, so a
 * scrolling list never leaves stale rectangles. The state machine itself is pure and tested on the JVM.
 *
 * Two ways in: [begin]/[move]/[drop]/[cancel] for the modifiers in this package, and [handleIntent]
 * for the data panels' existing `mapDragSource`, whose drag intents the host routes here before
 * the reducer so nested targets work too.
 */
@Stable
class DragMapController(
    val config: DragConfig = DragConfig(),
    private val typeOf: (MapSource) -> SchemaType? = { null },
) {
    var state: DragMapState by mutableStateOf(DragMapState())
        private set

    /** The last rejected target, for the shake. */
    var rejection: Rejection? by mutableStateOf(null)
        private set

    /** The field with focus, the target of tap-to-insert. */
    var focused: ParamPath? by mutableStateOf(null)
        private set

    var handler: DragMapHandler = DragMapHandler()

    private class Entry(
        var coords: LayoutCoordinates?,
        var accepts: Boolean,
        var label: String,
        var onDrop: (DragPayload, Int, Int) -> Unit,
    )

    private val entries = LinkedHashMap<ParamPath, Entry>()
    private val selections = HashMap<ParamPath, Pair<Int, Int>>()
    private var viewport: LayoutCoordinates? = null
    private var host: LayoutCoordinates? = null
    private var scroller: ((Float) -> Unit)? = null
    private var rejectionCounter = 0

    // ---- registry (called from modifiers and fields) ----

    internal fun register(path: ParamPath, accepts: Boolean, label: String, onDrop: (DragPayload, Int, Int) -> Unit): Any {
        val e = Entry(null, accepts, label, onDrop)
        entries[path] = e
        return e
    }

    internal fun update(path: ParamPath, token: Any, coords: LayoutCoordinates?, accepts: Boolean, label: String, onDrop: (DragPayload, Int, Int) -> Unit) {
        val e = entries[path]
        if (e !== token) return
        e.coords = coords; e.accepts = accepts; e.label = label; e.onDrop = onDrop
    }

    internal fun unregister(path: ParamPath, token: Any) {
        if (entries[path] === token) entries.remove(path)
    }

    internal fun setViewport(coords: LayoutCoordinates?, scrollBy: ((Float) -> Unit)?) { viewport = coords; scroller = scrollBy }
    internal fun setHost(coords: LayoutCoordinates?) { host = coords }

    fun reportSelection(path: ParamPath, start: Int, end: Int) { selections[path] = start to end }
    fun clearSelection(path: ParamPath) { selections.remove(path) }
    fun selectionOf(path: ParamPath): Pair<Int, Int> = selections[path] ?: (-1 to -1)
    fun reportFocus(path: ParamPath?) { focused = path }

    fun reject(path: ParamPath) {
        rejectionCounter++
        rejection = Rejection(path, rejectionCounter)
        handler.onHaptic(HapticKind.Reject)
        handler.onRejected(path)
    }

    // ---- geometry ----

    private fun clippedSnapshot(): List<DropTarget> = entries.mapNotNull { (p, e) ->
        val c = e.coords ?: return@mapNotNull null
        if (!c.isAttached) return@mapNotNull null
        val r = c.boundsInRoot()
        if (r.width <= 0f || r.height <= 0f) null else DropTarget(p, Bounds(r.left, r.top, r.right, r.bottom), e.accepts, e.label)
    }

    private fun viewportBounds(): Bounds {
        val c = viewport?.takeIf { it.isAttached } ?: host?.takeIf { it.isAttached }
        if (c != null) {
            val r = c.boundsInRoot()
            return Bounds(r.left, r.top, r.right, r.bottom)
        }
        return Bounds(0f, 0f, 100_000f, 100_000f)
    }

    /** Every accepting field, on screen or not, in reading order: the "Map to..." list for TalkBack and long-press. */
    fun mapToOptions(): List<MapToOptions.Option> {
        val all = entries.mapNotNull { (p, e) ->
            val c = e.coords ?: return@mapNotNull null
            if (!c.isAttached) return@mapNotNull null
            val pos = c.positionInRoot()
            DropTarget(p, Bounds(pos.x, pos.y, pos.x + c.size.width, pos.y + c.size.height), e.accepts, e.label)
        }
        return MapToOptions.list(all)
    }

    // ---- drag ----

    fun payloadFor(source: MapSource): DragPayload =
        DragPayload(source, typeOf(source), source.path.segments.lastOrNull { it != SchemaPath.ELEMENT }.orEmpty().ifEmpty { source.stepName })

    fun begin(payload: DragPayload, x: Float, y: Float) {
        dispatch(DragEvent.Begin(payload, x, y, viewportBounds(), clippedSnapshot()))
    }

    fun move(x: Float, y: Float) {
        if (!state.active) return
        dispatch(DragEvent.TargetsChanged(clippedSnapshot(), viewportBounds()))
        dispatch(DragEvent.Move(x, y))
    }

    fun tick(dtMs: Long) {
        if (!state.active) return
        dispatch(DragEvent.TargetsChanged(clippedSnapshot(), viewportBounds()))
        dispatch(DragEvent.Tick(dtMs))
    }

    fun drop() {
        if (!state.active) return
        dispatch(DragEvent.TargetsChanged(clippedSnapshot(), viewportBounds()))
        val sel = state.hover?.path?.let(::selectionOf) ?: (-1 to -1)
        dispatch(DragEvent.Drop(sel.first, sel.second))
    }

    fun cancel() { dispatch(DragEvent.Cancel) }

    /** Routes the data panels' drag intents here. Returns true when the intent was a drag intent and is handled. */
    fun handleIntent(intent: InspectorIntent): Boolean = when (intent) {
        is InspectorIntent.BeginDrag -> { begin(payloadFor(intent.source), intent.x, intent.y); true }
        is InspectorIntent.DragMoved -> { move(intent.x, intent.y); true }
        is InspectorIntent.DragDropped -> { drop(); true }
        InspectorIntent.DragCancelled -> { cancel(); true }
        else -> false
    }

    // ---- tap to insert (equal rank) ----

    /** Inserts into [path] exactly as a drop would. False when the field is unknown or cannot take data. */
    fun insertInto(path: ParamPath, payload: DragPayload): Boolean {
        val e = entries[path] ?: return false
        if (!e.accepts) { reject(path); return false }
        val (s, en) = selectionOf(path)
        e.onDrop(payload, s, en)
        handler.onInserted(path)
        return true
    }

    /** Tap on a data row: goes to the focused field when there is one; otherwise [onNoTarget] opens the "Map to..." list. */
    fun tapInsert(source: MapSource, onNoTarget: (MapSource) -> Unit = {}) {
        val f = focused
        if (f == null || !insertInto(f, payloadFor(source))) onNoTarget(source)
    }

    private fun dispatch(e: DragEvent) {
        val step = DragMachine.reduce(state, e, config)
        state = step.state
        for (fx in step.effects) perform(fx)
    }

    private fun perform(fx: DragEffect) {
        when (fx) {
            is DragEffect.Haptic -> handler.onHaptic(fx.kind)
            is DragEffect.ScrollBy -> scroller?.invoke(fx.dy)
            is DragEffect.SwitchPage -> handler.onSwitchPage(fx.edge)
            is DragEffect.Insert -> {
                val e = entries[fx.target]
                if (e != null) { e.onDrop(fx.payload, fx.selStart, fx.selEnd); handler.onInserted(fx.target) }
            }
            is DragEffect.Rejected -> {
                rejectionCounter++
                rejection = Rejection(fx.target, rejectionCounter)
                handler.onRejected(fx.target)
            }
            DragEffect.Cancelled -> handler.onCancelled()
        }
    }
}
