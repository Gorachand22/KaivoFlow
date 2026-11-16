package com.kaivoflow.feature.canvas.inspector.logic

import com.kaivoflow.feature.canvas.inspector.state.InspectorMessage

/** Snackbar text for the inspector's one-shot messages. */
object InspectorMessages {
    fun text(m: InspectorMessage): String = when (m) {
        InspectorMessage.DropRejected -> "That field cannot take data references."
        InspectorMessage.ExpressionNotAllowed -> "This field cannot use an expression."
        InspectorMessage.FixedNotAllowed -> "This field must stay an expression."
        InspectorMessage.PinRefusedTruncated -> "Only part of this data was kept, so it cannot be pinned."
        InspectorMessage.NothingToPin -> "Nothing to pin yet. Test the Step first."
        InspectorMessage.PinFailed -> "Could not change the pinned data."
        InspectorMessage.ReadOnly -> "You are viewing a past Run. Editing is off."
    }
}
