package com.kaivoflow.feature.canvas.inspector.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.SegmentedPill
import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.model.CredentialRef
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.SchemaInference
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.feature.canvas.inspector.logic.ErrorSurface
import com.kaivoflow.feature.canvas.inspector.mapper.DragMapController
import com.kaivoflow.feature.canvas.inspector.mapper.MapperTray
import com.kaivoflow.feature.canvas.inspector.mapper.TraySection
import com.kaivoflow.feature.canvas.inspector.panels.DataPanel
import com.kaivoflow.feature.canvas.inspector.panels.DataPanelCallbacks
import com.kaivoflow.feature.canvas.inspector.panels.PanelOrigin
import com.kaivoflow.feature.canvas.inspector.params.ExprFieldEnv
import com.kaivoflow.feature.canvas.inspector.params.ParamForm
import com.kaivoflow.feature.canvas.inspector.params.ParamFormEnv
import com.kaivoflow.feature.canvas.inspector.state.InspectorIntent
import com.kaivoflow.feature.canvas.inspector.state.InspectorState
import com.kaivoflow.feature.canvas.inspector.state.PanelSide
import com.kaivoflow.feature.canvas.inspector.state.ParamsTab
import com.kaivoflow.feature.canvas.inspector.state.ParentPolicy
import com.kaivoflow.feature.canvas.inspector.state.PreviewState
import com.kaivoflow.feature.canvas.inspector.state.TestState

/** Callbacks of a data panel. Drag intents go to the controller first so a drop can land in any pane. */
@Composable
private fun panelCallbacks(
    session: InspectorSession,
    controller: DragMapController,
    side: PanelSide,
    onMapTo: (MapSource) -> Unit,
): DataPanelCallbacks {
    val clipboard = LocalClipboardManager.current
    return DataPanelCallbacks(
        onIntent = { i -> if (!controller.handleIntent(i)) session.holder.dispatch(i) },
        onCopy = { _, text -> clipboard.setText(AnnotatedString(text)) },
        onInsert = { src -> controller.tapInsert(src) { onMapTo(it) } },
        onMapTo = onMapTo,
        // Input: the explicit "Run previous Step" button (earlier Steps run only because the user pressed it).
        // Output: test this Step alone.
        onRunPrevious = {
            session.holder.dispatch(InspectorIntent.TestStepRequested(if (side == PanelSide.Input) ParentPolicy.RunThem else ParentPolicy.OnlyThis))
        },
        onUnpin = { session.holder.dispatch(InspectorIntent.Unpin) },
    )
}

@Composable
fun InputPane(session: InspectorSession, state: InspectorState, node: StepNode, controller: DragMapController, onMapTo: (MapSource) -> Unit) {
    val src = state.input.sourceStepId
    val name = if (state.input.inbound) node.name else state.input.sources.firstOrNull { it.stepId == src }?.name.orEmpty()
    Column(Modifier.fillMaxSize()) {
        TestInputBar(session, state, hasParents = state.input.sources.isNotEmpty())
        if (!showsTypedInput(state)) {
            DataPanel(
                state = state.input,
                origin = PanelOrigin(src.orEmpty(), name, isImmediateParent = true),
                source = session.source,
                callbacks = panelCallbacks(session, controller, PanelSide.Input, onMapTo),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                dragEnabled = true,
            )
        }
    }
}

@Composable
fun OutputPane(session: InspectorSession, state: InspectorState, node: StepNode, controller: DragMapController, onMapTo: (MapSource) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TestBanner(state, session, node)
        DataPanel(
            state = state.output,
            origin = PanelOrigin(state.stepId, node.name, isImmediateParent = false),
            source = session.source,
            callbacks = panelCallbacks(session, controller, PanelSide.Output, onMapTo),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            pin = state.pin,
            dragEnabled = false,
        )
    }
}

@Composable
fun ParamsPane(
    session: InspectorSession,
    state: InspectorState,
    node: StepNode,
    spec: NodeSpec?,
    credentials: List<CredentialRef>?,
    actions: StepInspectorActions,
    controller: DragMapController,
    dockTray: Boolean,
    showTest: Boolean,
    onMapTo: (MapSource) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SegmentedPill(
            options = ParamsTab.entries.map { it.name },
            selectedIndex = state.paramsTab.ordinal,
            onSelect = { session.holder.dispatch(InspectorIntent.SelectParamsTab(ParamsTab.entries[it])) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.paramsTab) {
                ParamsTab.Parameters -> ParametersPage(session, state, node, credentials, actions, controller, dockTray, showTest, onMapTo)
                ParamsTab.Behaviour -> BehaviourTab(node, enabled = !state.readOnly, onEdit = actions.onEditStep)
                ParamsTab.About -> AboutTab(spec, state.schema)
            }
        }
    }
}

@Composable
private fun ParametersPage(
    session: InspectorSession,
    state: InspectorState,
    node: StepNode,
    credentials: List<CredentialRef>?,
    actions: StepInspectorActions,
    controller: DragMapController,
    dock: Boolean,
    showTest: Boolean,
    onMapTo: (MapSource) -> Unit,
) {
    val scope by session.scope.collectAsState()
    // A runtime failure that names a position becomes the preview of that field, so the range is highlighted in place.
    val spot = (state.test as? TestState.Failed)?.let { ErrorSurface.locate(it.message, state.values) }
    val dispatch = session.holder::dispatch
    val env = ParamFormEnv(
        expr = ExprFieldEnv(
            assist = session.assist,
            scope = scope,
            previewOf = { path ->
                val key = path.toString()
                if (spot != null && spot.key == key) ErrorSurface.asPreview(spot) else state.previews[key]
            },
            onFocus = { p -> controller.reportFocus(p); dispatch(InspectorIntent.FocusField(p)) },
            onOpenMapper = { dispatch(InspectorIntent.SetMapperVisible(!state.mapperVisible)) },
            itemIndex = state.previewItem,
            itemCount = state.input.summary?.itemCount ?: 0,
            onSelectItem = { dispatch(InspectorIntent.SelectPreviewItem(it)) },
        ),
        credentials = credentials,
        onAddCredential = actions.onAddCredential,
        binaryProperties = scope.input?.fields?.get(SchemaInference.BINARY_KEY)?.fields?.keys?.toList().orEmpty(),
    )
    val form: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            StepBanners(state, node, session, showTest)
            ParamForm(
                schema = state.schema,
                values = state.values,
                issues = state.issues,
                onChange = { key, v: ParamValue? -> dispatch(InspectorIntent.EditParam(key, v)) },
                modifier = Modifier.weight(1f),
                env = env,
                credentialsFilled = state.credentialsFilled,
                enabled = !state.readOnly,
                lastEdit = state.lastEdit,
                onUndo = { dispatch(InspectorIntent.UndoParamEdit) },
            )
        }
    }
    val tray: @Composable (Modifier) -> Unit = { m ->
        MapperTray(
            sections = scope.upstream.map { TraySection(PanelOrigin(it.stepId, it.name, it.isImmediate), it.schema) },
            callbacks = panelCallbacks(session, controller, PanelSide.Input, onMapTo),
            onClose = { dispatch(InspectorIntent.SetMapperVisible(false)) },
            modifier = m,
            focusedLabel = state.focusedField?.toString(),
        )
    }
    when {
        !state.mapperVisible -> form(Modifier.fillMaxSize())
        dock -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            tray(Modifier.weight(1f))
            form(Modifier.weight(1f))
        }
        else -> Column(Modifier.fillMaxSize()) {
            form(Modifier.weight(1f))
            tray(Modifier.weight(1f))
        }
    }
}
