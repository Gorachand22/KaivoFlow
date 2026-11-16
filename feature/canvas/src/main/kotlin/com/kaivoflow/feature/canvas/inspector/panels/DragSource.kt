package com.kaivoflow.feature.canvas.inspector.panels

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.feature.canvas.inspector.state.InspectorIntent

private class RootOrigin { var x = 0f; var y = 0f }

/**
 * Long-press then drag starts a mapping drag through the inspector's own intents
 * (BeginDrag, DragMoved, DragDropped, DragCancelled). Positions are reported in root
 * coordinates so the drag layer that hosts the whole inspector can place the pearl chip and find
 * drop targets. [onHover] maps a root position to the drop target under it (null until the
 * drag layer provides targets). The haptic tick confirms the start; the pearl chip is the visual
 * equivalent. Taps are untouched, so a tap-to-insert click handler can sit on the same node.
 */
@Composable
fun Modifier.mapDragSource(
    source: MapSource,
    onIntent: (InspectorIntent) -> Unit,
    enabled: Boolean = true,
    onHover: (Offset) -> ParamPath? = { null },
): Modifier {
    if (!enabled) return this
    val haptics = LocalHapticFeedback.current
    val currentSource by rememberUpdatedState(source)
    val currentIntent by rememberUpdatedState(onIntent)
    val currentHover by rememberUpdatedState(onHover)
    val origin = remember { RootOrigin() }
    return this
        .onGloballyPositioned { coords ->
            val p = coords.positionInRoot()
            origin.x = p.x
            origin.y = p.y
        }
        .pointerInput(Unit) {
            var x = 0f
            var y = 0f
            detectDragGesturesAfterLongPress(
                onDragStart = { local ->
                    x = origin.x + local.x
                    y = origin.y + local.y
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentIntent(InspectorIntent.BeginDrag(currentSource, x, y))
                },
                onDrag = { change, amount ->
                    change.consume()
                    x += amount.x
                    y += amount.y
                    currentIntent(InspectorIntent.DragMoved(x, y, currentHover(Offset(x, y))))
                },
                onDragEnd = { currentIntent(InspectorIntent.DragDropped()) },
                onDragCancel = { currentIntent(InspectorIntent.DragCancelled) },
            )
        }
}

/** TalkBack equivalent of a drag: `Insert into field` opens the same target list as the long-press menu. */
fun insertIntoFieldAction(onMapTo: () -> Unit): CustomAccessibilityAction =
    CustomAccessibilityAction("Insert into field") { onMapTo(); true }

/** TalkBack action that copies a value or path. */
fun copyAction(label: String, onCopy: () -> Unit): CustomAccessibilityAction =
    CustomAccessibilityAction(label) { onCopy(); true }
