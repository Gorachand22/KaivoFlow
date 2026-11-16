package com.kaivoflow.feature.canvas.inspector.panels

import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.model.Item
import com.kaivoflow.feature.canvas.inspector.state.InspectorIntent

/**
 * Everything a data panel can ask its host to do. Intents go to the inspector state holder; the
 * rest are side effects the panels must not perform themselves (clipboard, sharing, navigation).
 * All have safe no-op defaults so a preview or test only sets what it checks.
 */
class DataPanelCallbacks(
    val onIntent: (InspectorIntent) -> Unit,
    /** Copy [text]; [label] says what was copied, for the confirmation message. */
    val onCopy: (label: String, text: String) -> Unit = { _, _ -> },
    /** Tap-to-insert: the Mapper tray inserts this at the focused field. */
    val onInsert: (MapSource) -> Unit = {},
    /** Long-press menu and TalkBack action: pick a target field for [MapSource]. */
    val onMapTo: (MapSource) -> Unit = {},
    val onConnectStep: (() -> Unit)? = null,
    val onRunPrevious: (() -> Unit)? = null,
    val onUseSample: (() -> Unit)? = null,
    val onOpenStepSettings: (() -> Unit)? = null,
    val onShareRaw: (() -> Unit)? = null,
    val onEditPin: (() -> Unit)? = null,
    val onUnpin: (() -> Unit)? = null,
    val onOpenBinary: ((Item, String) -> Unit)? = null,
    val onShareBinary: ((Item, String) -> Unit)? = null,
)
