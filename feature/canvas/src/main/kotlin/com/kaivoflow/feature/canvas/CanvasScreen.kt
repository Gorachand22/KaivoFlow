package com.kaivoflow.feature.canvas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaivoflow.core.design.GlassPill
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.nodes.NodeCatalog
import com.kaivoflow.feature.canvas.layout.CanvasGeometry
import com.kaivoflow.feature.canvas.layout.LayoutMode
import com.kaivoflow.feature.canvas.layout.Point
import com.kaivoflow.feature.canvas.layout.StepLayout
import com.kaivoflow.feature.canvas.render.CanvasAura
import com.kaivoflow.feature.canvas.render.JewelStepCard
import com.kaivoflow.feature.canvas.render.StreamCanvasLayer
import com.kaivoflow.feature.canvas.render.StreamDragPreview
import com.kaivoflow.feature.canvas.sheets.StepInspectorSheet
import com.kaivoflow.feature.canvas.sheets.StepPickerSheet
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay

/**
 * The canvas screen.
 *
 * Layering (bottom to top): aura, Stream Canvas (single pass), Step cards, drag preview, HUD,
 * minimap, sheets. Pan and zoom are applied by ONE graphicsLayer around the Stream layer and the
 * card layer, so a gesture is a property write, not a recomposition.
 */
@Composable
fun CanvasScreen(
    onBack: () -> Unit,
    onOpenRunHistory: () -> Unit,
    viewModel: CanvasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = KaivoTheme.colors
    val sizes = KaivoTheme.sizes
    val motion = KaivoTheme.motion
    val density = LocalDensity.current
    val pixelScale = density.density
    val haptics = LocalHapticFeedback.current

    val transform = rememberCanvasTransformState()
    var viewportSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var cardHeights by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var dragStart by remember { mutableStateOf<Point?>(null) }
    var dragEnd by remember { mutableStateOf<Point?>(null) }
    var dragSourcePort by remember { mutableStateOf(0) }
    var draggedFromStepId by remember { mutableStateOf<String?>(null) }
    var cardDrag by remember { mutableStateOf<Triple<String, Float, Float>?>(null) }
    var snappedTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val registry = remember { NodeCatalog.registry() }
    val specs: List<NodeSpec> = remember { registry.specs }
    val outputPortCounts = remember(state.steps) {
        state.steps.associate { it.id to it.outputPorts.coerceAtLeast(1) }
    }

    // Layout is recomputed only when the Flow structure changes — never during a gesture.
    val layouts: Map<String, StepLayout> = remember(state.steps, state.streams, state.layoutMode, cardHeights) {
        val flow = FakeFlow.from(state)
        if (state.layoutMode == LayoutMode.GuidedPath) {
            CanvasGeometry.guidedLayout(flow, cardHeights, outputPortCounts = outputPortCounts)
        } else {
            CanvasGeometry.freeformLayout(flow, cardHeights, outputPortCounts = outputPortCounts)
        }
    }

    // First paint: fit the Flow to the screen.
    LaunchedEffect(layouts.isEmpty(), viewportSize) {
        if (layouts.isNotEmpty() && viewportSize.width > 0) {
            val bounds = CanvasGeometry.contentBounds(layouts.values)
            val fit = CanvasGeometry.fitToScreen(
                bounds,
                viewportSize.width.toFloat(),
                viewportSize.height.toFloat(),
                pixelScale = density.density,
            )
            transform.jumpTo(fit.first, fit.second, fit.third)
        }
    }

    LaunchedEffect(state.toasts.firstOrNull()) {
        val toast = state.toasts.firstOrNull() ?: return@LaunchedEffect
        snackbar.showSnackbar(toast.text)
        viewModel.consumeToast(toast.id)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvasVoid)
            .onSizeChanged { viewportSize = it }
            .pointerInput(Unit) {
                // Pinch to zoom, drag to pan. Reads/writes transform state only.
                detectTransformGestures { centroid, pan, zoom, _ ->
                    transform.beginInteraction()
                    if (pan != Offset.Zero) transform.onPan(pan.x, pan.y)
                    if (zoom != 1f) {
                        transform.onZoom(zoom, Point(centroid.x, centroid.y))
                    }
                }
            },
    ) {
        if (state.showPromptCard || state.steps.isEmpty()) {
            CanvasAura(Modifier.fillMaxSize())
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // The ONLY place pan/zoom is read: zero recomposition while dragging.
                    translationX = transform.panX
                    translationY = transform.panY
                    scaleX = transform.zoom
                    scaleY = transform.zoom
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                },
        ) {
            StreamCanvasLayer(
                layouts = layouts,
                streams = state.streams,
                transform = transform,
                reduceMotion = motion.reduceMotion,
                pixelScale = pixelScale,
                modifier = Modifier.fillMaxSize(),
            )

            // Step cards: only the ones inside the viewport are composed.
            val visible = transform.visibleRect(
                viewportSize.width.toFloat().coerceAtLeast(1f),
                viewportSize.height.toFloat().coerceAtLeast(1f),
                pixelScale = pixelScale,
            )
            layouts.values.forEach { layout ->
                if (!CanvasGeometry.isVisible(layout, visible)) return@forEach
                val step = state.steps.firstOrNull { it.id == layout.id } ?: return@forEach
                val entrance = remember(step.id) { Animatable(0f) }
                LaunchedEffect(step.id) {
                    if (!motion.reduceMotion) {
                        delay(motion.stagerStepMs * (layouts.keys.indexOf(step.id).coerceAtLeast(0)))
                        entrance.animateTo(1f, tween(220))
                    } else {
                        entrance.snapTo(1f)
                    }
                }
                Box(
                    Modifier
                        .graphicsLayer {
                            // StepLayout is in world dp; graphicsLayer works in screen pixels.
                            translationX = layout.topLeft.x * pixelScale
                            translationY = layout.topLeft.y * pixelScale
                            alpha = entrance.value
                            scaleX = 0.92f + 0.08f * entrance.value
                            scaleY = 0.92f + 0.08f * entrance.value
                        }
                        .onSizeChanged { size ->
                            val heightDp = with(density) { size.height.toDp().value }
                            val previous = cardHeights[step.id]
                            if (previous == null || kotlin.math.abs(previous - heightDp) > 2f) {
                                cardHeights = cardHeights + (step.id to heightDp)
                            }
                        },
                ) {
                    JewelStepCard(
                        step = step,
                        selected = state.selectedStepId == step.id,
                        onTap = { viewModel.openInspector(step.id) },
                        onLongPress = { viewModel.selectStep(step.id) },
                        draggable = state.layoutMode == LayoutMode.Freeform,
                        onOutputDragStart = { port, offset ->
                            val origin = Point(layout.topLeft.x + offset.x, layout.topLeft.y + offset.y)
                            draggedFromStepId = step.id
                            dragSourcePort = port
                            dragStart = origin
                            dragEnd = origin
                            snappedTarget = null
                        },
                        onOutputDrag = { amount ->
                            val base = dragEnd ?: dragStart
                            if (base != null) {
                                // Pointer deltas are screen pixels; world-dp delta = screen px / (density * zoom).
                                val dx = with(density) { amount.x.toDp().value / transform.zoom }
                                val dy = with(density) { amount.y.toDp().value / transform.zoom }
                                val point = Point(base.x + dx, base.y + dy)
                                dragEnd = point
                                snappedTarget = CanvasGeometry.nearestPort(
                                    point,
                                    layouts.values,
                                    excludeStepId = step.id,
                                    allowOutputs = false,
                                )
                            }
                        },
                        onOutputDragEnd = {
                            val target = snappedTarget
                            val end = dragEnd
                            if (target != null) {
                                val connected = viewModel.connectStream(step.id, dragSourcePort, target.first)
                                if (connected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            } else if (end != null) {
                                // Dropped on empty canvas: offer the next Step on the chosen branch.
                                viewModel.openStepPicker(afterStepId = step.id, fromPort = dragSourcePort)
                            }
                            dragStart = null
                            dragEnd = null
                            snappedTarget = null
                            draggedFromStepId = null
                        },
                        onOutputDragCancel = {
                            dragStart = null
                            dragEnd = null
                            snappedTarget = null
                            draggedFromStepId = null
                        },
                        onCardDragStart = {
                            cardDrag = Triple(step.id, layout.topLeft.x, layout.topLeft.y)
                        },
                        onCardDrag = { amount ->
                            val current = cardDrag
                            if (current != null && current.first == step.id) {
                                val dx = with(density) { amount.x.toDp().value / transform.zoom }
                                val dy = with(density) { amount.y.toDp().value / transform.zoom }
                                val x = current.second + dx
                                val y = current.third + dy
                                cardDrag = Triple(step.id, x, y)
                                viewModel.moveStep(step.id, x, y)
                            }
                        },
                        onCardDragEnd = { cardDrag = null },
                        onCardDragCancel = { cardDrag = null },
                    )
                }
            }

            if (dragStart != null && dragEnd != null) {
                StreamDragPreview(
                    start = dragStart,
                    end = dragEnd,
                    isSnapped = snappedTarget != null,
                    pixelScale = pixelScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ---------------- top bar ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassPill(Modifier.padding(end = 8.dp)) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = "Back to Flows" },
                ) {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(KaivoIcons.icon("back"), "Back to Flows", tint = colors.inkPrimary, modifier = Modifier.size(24.dp))
                    }
                }
            }
            GlassPill(Modifier.weight(1f)) {
                Column {
                    Text(
                        state.flowName.ifBlank { "Flow" },
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = when {
                            state.validation.blocking.size > 0 -> "${state.validation.blocking.size} things to fix"
                            state.validation.warnings.isNotEmpty() -> "Ready, with ${state.validation.warnings.size} tips"
                            else -> "${state.steps.size} Steps • ready"
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = if (state.validation.blocking.isEmpty()) colors.inkSecondary else colors.statusWarning,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            GlassPill {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .padding(6.dp)
                    ) {
                        Icon(
                            KaivoIcons.icon(if (state.layoutMode == LayoutMode.GuidedPath) "bolt" else "globe"),
                            contentDescription = if (state.layoutMode == LayoutMode.GuidedPath) {
                                "Switch to Freeform canvas"
                            } else "Switch to Guided Path"
,
                            tint = colors.inkSecondary,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .androidClickable { viewModel.toggleLayoutMode() },
                        )
                    }
                }
            }
        }

        // ---------------- validation banner ----------------
        val blocking = state.validation.blocking.firstOrNull()
        if (blocking != null && state.steps.isNotEmpty()) {
            ValidationBanner(
                message = blocking.message,
                onFix = {
                    blocking.stepId?.let { viewModel.openInspector(it) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 96.dp),
            )
        }

        // ---------------- minimap ----------------
        if (state.layoutMode == LayoutMode.Freeform && layouts.isNotEmpty()) {
            Minimap(
                layouts = layouts.values,
                activeStepId = state.run.currentStepId,
                visible = transform.visibleRect(
                    viewportSize.width.toFloat().coerceAtLeast(1f),
                    viewportSize.height.toFloat().coerceAtLeast(1f),
                    pixelScale = pixelScale,
                ),
                onJump = { x, y ->
                    transform.jumpTo(
                        transform.zoom,
                        -x * transform.zoom * pixelScale,
                        -y * transform.zoom * pixelScale,
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp),
            )
        }

        // ---------------- HUD + primary action ----------------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.run.runId != null) {
                RunHud(
                    run = state.run,
                    flowName = state.flowName.ifBlank { "Flow" },
                    onCancel = viewModel::cancelRun,
                    onOpenHistory = onOpenRunHistory,
                )
                Spacer(Modifier.height(12.dp))
            }

            if (state.showPromptCard) {
                MagicPromptCard(
                    isGenerating = state.isPromptGenerating,
                    onGenerate = viewModel::generateFromPrompt,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassPill {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier
                                    .size(sizes.touchTarget)
                                    .clip(CircleShape)
                                    .androidClickable { viewModel.openStepPicker(null) }
                                    .semantics { contentDescription = "Add a Step" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(KaivoIcons.icon("add"), "Add a Step", tint = colors.royalGold, modifier = Modifier.size(24.dp))
                            }
                            Box(
                                Modifier
                                    .size(sizes.touchTarget)
                                    .clip(CircleShape)
                                    .androidClickable {
                                        val bounds = CanvasGeometry.contentBounds(layouts.values)
                                        if (bounds.width > 0f) {
                                            transform.fit(
                                                bounds,
                                                viewportSize.width.toFloat(),
                                                viewportSize.height.toFloat(),
                                                pixelScale = pixelScale,
                                            )
                                        }
                                    }
                                    .semantics { contentDescription = "Fit the Flow on screen" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(KaivoIcons.icon("scale"), "Fit on screen", tint = colors.inkSecondary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    GoldButton(
                        text = if (state.run.isRunning) "Running…" else "Run",
                        icon = if (state.run.isRunning) "pause" else "play",
                        enabled = state.canRun || state.run.isRunning,
                        onClick = { if (state.run.isRunning) viewModel.cancelRun() else viewModel.run() },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp, start = 16.dp, end = 16.dp),
        )

        // ---------------- sheets ----------------
        if (state.isStepPickerOpen) {
            StepPickerSheet(
                specs = specs,
                onSearch = {},
                onPick = { spec -> viewModel.addStep(spec.id) },
                onDismiss = viewModel::closeStepPicker,
                hasTrigger = state.triggers.isNotEmpty(),
            )
        }

        state.inspector?.let { inspector ->
            val step = state.steps.firstOrNull { it.id == inspector.stepId }
            if (step != null) {
                StepInspectorSheet(
                    inspector = inspector,
                    step = step,
                    spec = registry.spec(step.type),
                    params = step.paramSummary.let { _ ->
                        // Raw params come back through the ViewModel; the UI only needs current values.
                        state.let { _ -> rawParamsFor(state, step.id) }
                    },
                    previousStepNames = state.steps
                        .takeWhile { it.id != step.id }
                        .map { it.name },
                    onTab = viewModel::setInspectorTab,
                    onParamChange = { key, value -> viewModel.updateParam(step.id, key, value) },
                    onRename = { viewModel.renameStep(step.id, it) },
                    onTestStep = viewModel::testStep,
                    onFreezeOutput = { viewModel.freezeOutput(step.id) },
                    onToggleDisabled = { viewModel.toggleStepDisabled(step.id) },
                    onDelete = { viewModel.deleteStep(step.id) },
                    onDismiss = viewModel::closeInspector,
                )
            }
        }
    }
}

/** Params are held by the ViewModel's Flow; the screen reads them through this indirection. */
private fun rawParamsFor(state: CanvasUiState, stepId: String): Map<String, Any?> =
    state.paramCache[stepId] ?: emptyMap()

/** Small helper so call sites read like Material's clickable without importing it everywhere. */
private fun Modifier.androidClickable(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/**
 * Adapter that rebuilds the model objects the layout maths needs from the immutable UI state.
 * Keeps [CanvasGeometry] free of Compose and testable on the JVM.
 */
private object FakeFlow {
    fun from(state: CanvasUiState): com.kaivoflow.core.model.Flow = com.kaivoflow.core.model.Flow(
        id = state.flowId,
        name = state.flowName,
        steps = state.steps.map {
            com.kaivoflow.core.model.StepNode(
                id = it.id,
                type = it.type,
                name = it.name,
                x = state.positions[it.id]?.first ?: 0f,
                y = state.positions[it.id]?.second ?: 0f,
            )
        }.toImmutableList(),
        streams = state.streams.map {
            com.kaivoflow.core.model.StreamEdge(
                id = it.id,
                fromStepId = it.fromStepId,
                toStepId = it.toStepId,
                fromPort = it.fromPort,
            )
        }.toImmutableList(),
    )
}
