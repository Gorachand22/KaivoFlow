package com.kaivoflow.feature.canvas.inspector.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoIconButton
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SegmentedPill
import com.kaivoflow.core.design.SheetDragHandle
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.design.StepMedallion
import com.kaivoflow.core.model.CredentialRef
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.feature.canvas.inspector.logic.InspectorLayoutKind
import com.kaivoflow.feature.canvas.inspector.logic.InspectorLayouts
import com.kaivoflow.feature.canvas.inspector.logic.InspectorMessages
import com.kaivoflow.feature.canvas.inspector.logic.InspectorPages
import com.kaivoflow.feature.canvas.inspector.logic.PaneWidths
import com.kaivoflow.feature.canvas.inspector.mapper.DragMachine
import com.kaivoflow.feature.canvas.inspector.mapper.DragMapController
import com.kaivoflow.feature.canvas.inspector.mapper.DragMapHandler
import com.kaivoflow.feature.canvas.inspector.mapper.DragMapHost
import com.kaivoflow.feature.canvas.inspector.mapper.Edge
import com.kaivoflow.feature.canvas.inspector.mapper.MapToSheet
import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.feature.canvas.inspector.state.InspectorEffect
import com.kaivoflow.feature.canvas.inspector.state.InspectorIntent
import com.kaivoflow.feature.canvas.inspector.state.InspectorPage
import com.kaivoflow.feature.canvas.inspector.state.InspectorState
import com.kaivoflow.feature.canvas.inspector.state.ParentPolicy
import com.kaivoflow.feature.canvas.inspector.state.TestState

/** What the inspector asks the screen to do with the Step document. Every function is a plain callback. */
class StepInspectorActions(
    val onRename: (String) -> Unit,
    val onToggleDisabled: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    /** Applies a pure edit (see StepBehaviour) to the Step. */
    val onEditStep: ((StepNode) -> StepNode) -> Unit,
    val onAddCredential: () -> Unit,
    val onDismiss: () -> Unit,
    /** The workspace and Flow variables as `$vars` reads them (expression variables already evaluated), for the notes preview. */
    val vars: Map<String, Any?> = emptyMap(),
    val flowId: String = "",
    val flowName: String = "",
)

/**
 * The Step inspector. One composable for every window size: a full-height bottom sheet with a
 * three-page pager on a portrait phone, a 60% side sheet on a phone on its side, and three panes
 * on a tablet. The panes are the same composables in every layout ([InspectorPanes]), and one
 * [DragMapHost] wraps them all, so a drag can start in Input and end in Parameters wherever they are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepInspector(
    session: InspectorSession,
    node: StepNode,
    spec: NodeSpec?,
    iconTint: Color,
    credentials: List<CredentialRef>?,
    actions: StepInspectorActions,
) {
    val colors = KaivoTheme.colors
    val config = LocalConfiguration.current
    val kind = InspectorLayouts.choose(config.screenWidthDp, config.screenHeightDp)
    val state by session.holder.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val controller = remember(session) { DragMapController() }
    var mapTo by remember(session) { mutableStateOf<MapSource?>(null) }

    LaunchedEffect(session) {
        session.holder.uiEffects.collect { e ->
            if (e is InspectorEffect.Notify) snackbar.showSnackbar(InspectorMessages.text(e.message))
        }
    }
    DisposableEffect(session) { onDispose { controller.cancel() } }

    val handler = object : DragMapHandler() {
        override fun onRejected(target: com.kaivoflow.core.model.ParamPath) {
            // The snackbar is a suspend call, so it is launched from a side effect keyed on the rejection below.
        }
        override fun onSwitchPage(edge: Edge) {
            if (kind == InspectorLayoutKind.ThreePane) return
            val cur = session.holder.state.value.page
            session.holder.dispatch(InspectorIntent.SelectPage(if (edge == Edge.Right) InspectorPages.next(cur) else InspectorPages.previous(cur)))
        }
    }
    val rejection = controller.rejection
    LaunchedEffect(rejection?.token) { if (rejection != null) snackbar.showSnackbar(DragMachine.REJECT_MESSAGE) }

    val body: @Composable () -> Unit = {
        DragMapHost(controller = controller, handler = handler, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                InspectorHeader(state, node, spec, iconTint, actions, session, showPages = kind != InspectorLayoutKind.ThreePane)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (kind) {
                        InspectorLayoutKind.ThreePane -> ThreePaneBody(session, state, node, spec, credentials, actions, controller) { mapTo = it }
                        else -> PagerBody(session, state, node, spec, credentials, actions, controller, dockTray = kind == InspectorLayoutKind.PhoneLandscape) { mapTo = it }
                    }
                }
                InspectorFooter(state, session, stepOff = node.disabled)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
        }
    }

    when (kind) {
        InspectorLayoutKind.PhonePortrait -> ModalBottomSheet(
            onDismissRequest = actions.onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surfaceAtelier,
            dragHandle = { Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) { SheetDragHandle() } },
        ) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding().navigationBarsPadding()) { body() }
        }
        InspectorLayoutKind.PhoneLandscape, InspectorLayoutKind.ThreePane -> Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = actions.onDismiss).semantics { contentDescription = "Close inspector" })
            val modifier = if (kind == InspectorLayoutKind.PhoneLandscape) {
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(InspectorLayouts.LANDSCAPE_SHEET_FRACTION)
            } else {
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.92f)
            }
            Box(modifier.clip(KaivoShapes.chip).background(colors.surfaceAtelier).statusBarsPadding().imePadding().navigationBarsPadding()) { body() }
        }
    }

    mapTo?.let { source ->
        MapToSheet(
            options = controller.mapToOptions(),
            onPick = { path -> controller.insertInto(path, controller.payloadFor(source)); mapTo = null },
            onDismiss = { mapTo = null },
        )
    }
}

@Composable
private fun InspectorHeader(
    state: InspectorState,
    node: StepNode,
    spec: NodeSpec?,
    tint: Color,
    actions: StepInspectorActions,
    session: InspectorSession,
    showPages: Boolean,
) {
    val colors = KaivoTheme.colors
    var name by remember(node.id, node.name) { mutableStateOf(node.name) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KaivoIconButton("close", "Close inspector", actions.onDismiss)
            StepMedallion(iconKey = spec?.iconKey ?: "bolt", tint = tint)
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                enabled = !state.readOnly,
                singleLine = true,
                label = { Text("Step name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank() && name != node.name) actions.onRename(name) }),
                modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused && name.isNotBlank() && name != node.name && !state.readOnly) actions.onRename(name) },
            )
            Spacer(Modifier.width(8.dp))
            when {
                node.disabled -> StatusBadge("Off", BadgeKind.Neutral)
                state.test is TestState.Running -> StatusBadge("Testing", BadgeKind.Gold)
                state.issueCount > 0 -> StatusBadge("${state.issueCount} to fix", BadgeKind.Error)
                else -> Unit
            }
            Box {
                KaivoIconButton("more", "More actions", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!state.readOnly) {
                        DropdownMenuItem(
                            text = { Text(if (state.pin != null) "Unpin output" else "Pin output") },
                            onClick = { menu = false; session.holder.dispatch(if (state.pin != null) InspectorIntent.Unpin else InspectorIntent.PinCurrent) },
                        )
                        DropdownMenuItem(text = { Text(if (node.disabled) "Enable Step" else "Disable Step") }, onClick = { menu = false; actions.onToggleDisabled() })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; actions.onDuplicate() })
                        DropdownMenuItem(text = { Text("Delete", color = colors.statusError) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        }
        if (showPages) {
        Spacer(Modifier.height(8.dp))
        SegmentedPill(
            options = listOf(
                "Input" + (state.input.summary?.let { " ${it.itemCount}" } ?: ""),
                "Parameters",
                "Output" + (state.output.summary?.let { " ${it.itemCount}" } ?: ""),
            ),
            selectedIndex = state.page.ordinal,
            onSelect = { session.holder.dispatch(InspectorIntent.SelectPage(InspectorPage.entries[it])) },
            modifier = Modifier.fillMaxWidth(),
        )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = colors.surfaceAtelier,
            title = { Text("Delete \"${node.name}\"?", color = colors.inkPrimary) },
            text = { Text("Its connections are removed too. You can undo this from the canvas.", color = colors.inkSecondary) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; actions.onDelete() }) { Text("Delete", color = colors.statusError) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep", color = colors.royalGold) } },
        )
    }
}

/** Test Step stays at the bottom, above the keyboard. While a test runs it becomes Cancel. */
@Composable
private fun InspectorFooter(state: InspectorState, session: InspectorSession, stepOff: Boolean = false) {
    val running = state.test is TestState.Running
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (running) {
            GhostButton("Cancel test", onClick = { session.holder.dispatch(InspectorIntent.CancelTest) }, icon = "stop", modifier = Modifier.weight(1f))
        }
        GoldButton(
            text = if (running) "Testing" else "Test Step",
            icon = "play",
            enabled = !running && !state.readOnly && !stepOff,
            onClick = { session.holder.dispatch(InspectorIntent.TestStepRequested(ParentPolicy.OnlyThis)) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** Phone layouts: the three pages stay composed side by side so a drag can start on one and end on another. */
@Composable
private fun PagerBody(
    session: InspectorSession,
    state: InspectorState,
    node: StepNode,
    spec: NodeSpec?,
    credentials: List<CredentialRef>?,
    actions: StepInspectorActions,
    controller: DragMapController,
    dockTray: Boolean,
    onMapTo: (MapSource) -> Unit,
) {
    val density = LocalDensity.current
    var swipe by remember { mutableStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().clip(KaivoShapes.chip)) {
        val w = maxWidth
        val widthPx = with(density) { w.toPx() }
        val index = state.page.ordinal
        Row(
            Modifier
                .fillMaxHeight()
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .width(w * 3)
                .offset { IntOffset(-(index * widthPx).toInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { swipe += it },
                    onDragStopped = {
                        val next = InspectorPages.afterSwipe(state.page, swipe, widthPx * 0.2f)
                        swipe = 0f
                        if (next != state.page) session.holder.dispatch(InspectorIntent.SelectPage(next))
                    },
                ),
        ) {
            Box(Modifier.width(w).fillMaxHeight()) { InputPane(session, state, node, controller, onMapTo) }
            Box(Modifier.width(w).fillMaxHeight()) { ParamsPane(session, state, node, spec, credentials, actions, controller, dockTray, true, onMapTo) }
            Box(Modifier.width(w).fillMaxHeight()) { OutputPane(session, state, node, controller, onMapTo) }
        }
    }
}

/** Tablet: Input, Parameters and Output side by side with draggable dividers (min 280 dp each). */
@Composable
private fun ThreePaneBody(
    session: InspectorSession,
    state: InspectorState,
    node: StepNode,
    spec: NodeSpec?,
    credentials: List<CredentialRef>?,
    actions: StepInspectorActions,
    controller: DragMapController,
    onMapTo: (MapSource) -> Unit,
) {
    val density = LocalDensity.current
    var inputOpen by remember { mutableStateOf(true) }
    var outputOpen by remember { mutableStateOf(true) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val totalDp = maxWidth.value
        var widths by remember(totalDp, inputOpen, outputOpen) { mutableStateOf(PaneWidths.initial(totalDp, inputOpen, outputOpen)) }
        Row(Modifier.fillMaxSize()) {
            if (widths.input > 0f) {
                Box(Modifier.width(widths.input.dp).fillMaxHeight()) {
                    InputPane(session, state, node, controller, onMapTo)
                    KaivoIconButton("chevronRight", "Hide Input", { inputOpen = false }, Modifier.align(Alignment.TopEnd))
                }
                Divider(onDelta = { widths = widths.drag(0, it / density.density) })
            } else {
                KaivoIconButton("chevronRight", "Show Input", { inputOpen = true })
            }
            Box(Modifier.width(widths.params.dp).fillMaxHeight()) { ParamsPane(session, state, node, spec, credentials, actions, controller, false, false, onMapTo) }
            if (widths.output > 0f) {
                Divider(onDelta = { widths = widths.drag(1, it / density.density) })
                Box(Modifier.width(widths.output.dp).fillMaxHeight()) {
                    OutputPane(session, state, node, controller, onMapTo)
                    KaivoIconButton("chevronRight", "Hide Output", { outputOpen = false }, Modifier.align(Alignment.TopEnd))
                }
            } else {
                Spacer(Modifier.weight(1f))
                KaivoIconButton("chevronRight", "Show Output", { outputOpen = true })
            }
        }
    }
}

@Composable
private fun Divider(onDelta: (Float) -> Unit) {
    val colors = KaivoTheme.colors
    Box(
        Modifier.width(12.dp).fillMaxHeight()
            .draggable(orientation = Orientation.Horizontal, state = rememberDraggableState { onDelta(it) })
            .semantics { contentDescription = "Resize panes" },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.width(2.dp).fillMaxHeight(0.2f).clip(KaivoShapes.pill).background(colors.hairline)) }
}
