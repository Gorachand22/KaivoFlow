package com.kaivoflow.feature.canvas.inspector.mapper

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.TypeIcon
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.SchemaType
import kotlinx.coroutines.delay

/** The controller of the nearest [DragMapHost]; null outside one, so every widget also works on its own. */
val LocalDragMap = compositionLocalOf<DragMapController?> { null }

/**
 * Wraps the whole inspector: provides the controller, measures the area, wires haptics, runs the
 * frame clock while a drag is active, and draws the pearl chip under the finger. The chip shows the
 * field name and type; once it is over a target it shows the final reference text.
 */
@Composable
fun DragMapHost(
    controller: DragMapController,
    modifier: Modifier = Modifier,
    handler: DragMapHandler = DragMapHandler(),
    content: @Composable BoxScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(handler)
    // Haptics only confirm; every one has a visual equivalent (glow, shake, snackbar).
    SideEffect {
        controller.handler = object : DragMapHandler() {
            override fun onHaptic(kind: HapticKind) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); latest.onHaptic(kind) }
            override fun onRejected(target: ParamPath) = latest.onRejected(target)
            override fun onSwitchPage(edge: Edge) = latest.onSwitchPage(edge)
            override fun onCancelled() = latest.onCancelled()
            override fun onInserted(target: ParamPath) = latest.onInserted(target)
        }
    }
    val active by remember(controller) { derivedStateOf { controller.state.active } }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = System.nanoTime()
        while (true) {
            delay(16)
            val now = System.nanoTime()
            controller.tick(((now - last) / 1_000_000L).coerceIn(1L, 64L))
            last = now
        }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalDragMap provides controller) {
        Box(
            modifier = modifier.onGloballyPositioned { c ->
                origin = c.positionInRoot()
                controller.setHost(c)
            },
        ) {
            content()
            if (active) {
                val s = controller.state
                val payload = s.payload
                if (payload != null) {
                    DragPearl(
                        payload = payload,
                        showReference = s.hover?.accepts == true,
                        modifier = Modifier.offset { IntOffset((s.x - origin.x).toInt() + 16, (s.y - origin.y).toInt() - 72) },
                    )
                }
            }
        }
    }
}

/** The drag shadow: a pearl chip with the field name and type, or the reference once a target is under it. */
@Composable
fun DragPearl(payload: DragPayload, showReference: Boolean, modifier: Modifier = Modifier) {
    val colors = KaivoTheme.colors
    Row(
        modifier = modifier
            .clip(KaivoShapes.pill)
            .background(colors.surfaceElevated)
            .border(1.dp, colors.royalGold, KaivoShapes.pill)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeIcon(payload.type ?: SchemaType.Mixed, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            if (showReference) payload.reference else payload.label.ifEmpty { "field" },
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = KaivoTheme.type.monoFamily),
            color = colors.inkPrimary,
            maxLines = 1,
        )
    }
}

/**
 * Registers this node as a drop target for the length of its composition. While a drag is active
 * accepting targets glow with a gold hairline (stronger under the finger), rejecting targets dim,
 * and a rejected drop shakes the target (not under reduced motion). Without a [DragMapHost] it does nothing.
 */
@Composable
fun Modifier.mapDropTarget(
    path: ParamPath,
    accepts: Boolean,
    label: String,
    onDrop: (DragPayload, Int, Int) -> Unit,
): Modifier {
    val controller = LocalDragMap.current ?: return this
    val colors = KaivoTheme.colors
    val reduceMotion = KaivoTheme.motion.reduceMotion
    val currentDrop by rememberUpdatedState(onDrop)
    val token = remember(controller, path) { controller.register(path, accepts, label) { p, s, e -> currentDrop(p, s, e) } }
    DisposableEffect(controller, path, token) { onDispose { controller.unregister(path, token) } }
    val shake = remember { Animatable(0f) }
    val rejection = controller.rejection
    LaunchedEffect(rejection?.token) {
        if (rejection != null && rejection.path == path && !reduceMotion) {
            for (x in listOf(-10f, 10f, -6f, 6f, 0f)) shake.animateTo(x, tween(40))
        }
    }
    return this
        .onGloballyPositioned { c -> controller.update(path, token, c, accepts, label) { p, s, e -> currentDrop(p, s, e) } }
        .graphicsLayer { translationX = shake.value }
        .drawWithContent {
            drawContent()
            val st = controller.state
            if (st.active) {
                val hover = st.hover?.path == path
                if (accepts) {
                    val stroke = if (hover) 2.dp.toPx() else 1.dp.toPx()
                    drawRect(
                        color = colors.royalGold.copy(alpha = if (hover) 0.95f else 0.45f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                    )
                } else {
                    drawRect(color = colors.canvasVoid.copy(alpha = 0.5f))
                }
            }
        }
}

/** The "Insert here" label drawn on the hovered target; spoken state for TalkBack lives on the field itself. */
@Composable
fun BoxScope.DropHint(path: ParamPath) {
    val controller = LocalDragMap.current ?: return
    val hovered by remember(controller, path) { derivedStateOf { controller.state.hover?.path == path } }
    if (!hovered) return
    val colors = KaivoTheme.colors
    val text = controller.state.hoverLabel ?: return
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = colors.canvasVoid,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .clip(KaivoShapes.pill)
            .background(colors.royalGold)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Marks the scrolling parameter list: its bounds drive edge auto-scroll and the cancel margin, and
 * the machine scrolls it through [state]. The frame clock only runs while a drag is active.
 */
@Composable
fun Modifier.dragMapViewport(state: ScrollState): Modifier {
    val controller = LocalDragMap.current ?: return this
    DisposableEffect(controller, state) { onDispose { controller.setViewport(null, null) } }
    return this.onGloballyPositioned { c -> controller.setViewport(c) { dy -> state.dispatchRawDelta(dy) } }
}

/**
 * A drag source for rows that are not the data panels' own (those use `mapDragSource`): long-press
 * then drag. Moves are reported in root coordinates.
 */
@Composable
fun Modifier.dragMapSource(payload: DragPayload, enabled: Boolean = true): Modifier {
    val controller = LocalDragMap.current ?: return this
    if (!enabled) return this
    val latest by rememberUpdatedState(payload)
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(controller) {
            var x = 0f
            var y = 0f
            detectDragGesturesAfterLongPress(
                onDragStart = { p -> x = origin.x + p.x; y = origin.y + p.y; controller.begin(latest, x, y) },
                onDrag = { change, amount -> change.consume(); x += amount.x; y += amount.y; controller.move(x, y) },
                onDragEnd = { controller.drop() },
                onDragCancel = { controller.cancel() },
            )
        }
}
