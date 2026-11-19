package com.kaivoflow.feature.canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
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
import com.kaivoflow.core.design.KaivoMotionTokens
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.MotionTokenValues
import com.kaivoflow.core.design.Stagger
import com.kaivoflow.core.design.goldSweep
import com.kaivoflow.core.design.rememberKaivoHaptics
import com.kaivoflow.core.design.tweenSpec
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
import com.kaivoflow.feature.canvas.render.CanvasGroupFrame
import com.kaivoflow.feature.canvas.render.CanvasStickyNote
import com.kaivoflow.feature.canvas.render.JewelStepCard
import com.kaivoflow.feature.canvas.render.StreamCanvasLayer
import com.kaivoflow.feature.canvas.render.StreamCountChip
import com.kaivoflow.feature.canvas.render.deriveLiveStreams
import com.kaivoflow.feature.canvas.render.StreamDragPreview
import com.kaivoflow.feature.canvas.inspector.host.StepInspector
import com.kaivoflow.feature.canvas.inspector.host.StepInspectorActions
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
    onOpenCredentials: () -> Unit = {},
    onOpenAiAssistant: (String) -> Unit = {},
    /** Hosts the publish sheet; the app passes `PublishSheetEntry` (this module cannot depend on feature:flows). */
    publishEntry: @Composable (flowId: String?, onDismiss: () -> Unit, onFix: (com.kaivoflow.core.engine.ValidationIssue) -> Unit) -> Unit = { _, _, _ -> },
    viewModel: CanvasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val credentialRefs by viewModel.credentialRefs.collectAsStateWithLifecycle()
    val colors = KaivoTheme.colors
    val sizes = KaivoTheme.sizes
    val motion = KaivoTheme.motion
    val density = LocalDensity.current
    val pixelScale = density.density
    val haptics = LocalHapticFeedback.current
    val policy = KaivoTheme.motionPolicy
    val kaivoHaptics = rememberKaivoHaptics()
    // Streams with run-derived flags (Flowing / Done / Dimmed) and the item counts for edge chips.
    val live = remember(state.streams, state.run, state.steps) {
        deriveLiveStreams(state.streams, state.run, state.steps)
    }
    // Step ids whose entrance has played: re-scrolling a card into view must not replay it.
    val enteredSteps = remember { mutableSetOf<String>() }

    // Run starting: one light tick, the Run button's gold sweep plays from the same trigger.
    LaunchedEffect(state.run.runId, state.run.isRunning) {
        if (state.run.runId != null && state.run.isRunning) kaivoHaptics.tick()
    }

    val transform = rememberCanvasTransformState()
    var viewportSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var cardHeights by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var dragStart by remember { mutableStateOf<Point?>(null) }
    var dragEnd by remember { mutableStateOf<Point?>(null) }
    var dragSourcePort by remember { mutableStateOf(0) }
    var draggedFromStepId by remember { mutableStateOf<String?>(null) }
    var liveCardDrag by remember { mutableStateOf<Triple<String, Float, Float>?>(null) }
    var liveNoteDrag by remember { mutableStateOf<Triple<String, Float, Float>?>(null) }
    var pressedStepId by remember { mutableStateOf<String?>(null) }
    var pressedNoteId by remember { mutableStateOf<String?>(null) }
    var snappedTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val registry = remember { NodeCatalog.registry() }
    val specs: List<NodeSpec> = remember { registry.specs }
    val outputPortCounts = remember(state.steps) {
        state.steps.associate { it.id to it.outputPorts.coerceAtLeast(1) }
    }

    // Layout is recomputed only when the Flow structure changes — never during a gesture.
    val layouts: Map<String, StepLayout> = remember(
        state.steps,
        state.streams,
        state.layoutMode,
        state.positions,
        cardHeights,
    ) {
        val flow = FakeFlow.from(state)
        if (state.layoutMode == LayoutMode.GuidedPath) {
            CanvasGeometry.guidedLayout(flow, cardHeights, outputPortCounts = outputPortCounts)
        } else {
            CanvasGeometry.freeformLayout(flow, cardHeights, outputPortCounts = outputPortCounts)
        }
    }

    // Live layout with dragged card position to smoothly bend connecting streams
    val effectiveLayouts = remember(layouts, liveCardDrag) {
        val drag = liveCardDrag
        if (drag == null) layouts
        else {
            val (dragId, dragX, dragY) = drag
            val base = layouts[dragId]
            if (base == null) layouts
            else {
                val width = base.size.width
                val height = base.size.height
                val outputCount = base.ports.outputs.size.coerceAtLeast(1)
                val updatedOutputs = (0 until outputCount).map { port ->
                    val px = if (outputCount == 1) dragX + width / 2f
                    else dragX + width * (port + 1f) / (outputCount + 1f)
                    Point(px, dragY + height)
                }.toImmutableList()
                val updated = base.copy(
                    topLeft = Point(dragX, dragY),
                    ports = base.ports.copy(
                        input = Point(dragX + width / 2f, dragY),
                        outputs = updatedOutputs,
                    ),
                )
                layouts + (dragId to updated)
            }
        }
    }

    // First paint: fit the Flow to the screen.
    LaunchedEffect(layouts.isEmpty(), viewportSize) {
        if (layouts.isNotEmpty() && viewportSize.width > 0) {
            val bounds = CanvasGeometry.contentBounds(layouts.values, state.notes)
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
            .onSizeChanged { viewportSize = it },
    ) {
        if (state.steps.isEmpty()) {
            CanvasAura(Modifier.fillMaxSize())
        }

        // Interactive Canvas Layer with unified gesture handling
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(layouts, state.notes, state.steps, state.positions) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startScreen = down.position
                        var lastScreen = down.position
                        val worldDown = transform.toCanvas(down.position.x, down.position.y, pixelScale)

                        // 1. Output port drag (Stream wiring)
                        val hitPort = findOutputPort(worldDown, layouts, state.steps)
                        if (hitPort != null) {
                            val (stepId, portIndex, portCenter) = hitPort
                            draggedFromStepId = stepId
                            dragSourcePort = portIndex
                            dragStart = portCenter
                            dragEnd = portCenter
                            snappedTarget = null
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val currentWorld = transform.toCanvas(change.position.x, change.position.y, pixelScale)
                                dragEnd = currentWorld
                                snappedTarget = CanvasGeometry.nearestPort(
                                    currentWorld,
                                    layouts.values,
                                    excludeStepId = stepId,
                                    allowOutputs = false,
                                )
                            }
                            val target = snappedTarget
                            val end = dragEnd
                            if (target != null) {
                                val connected = viewModel.connectStream(stepId, dragSourcePort, target.first)
                                if (connected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            } else if (end != null) {
                                viewModel.openStepPicker(afterStepId = stepId, fromPort = dragSourcePort)
                            }
                            dragStart = null
                            dragEnd = null
                            snappedTarget = null
                            draggedFromStepId = null
                            return@awaitEachGesture
                        }

                        // 2. Step card tap or drag
                        val hitStep = findStepCard(worldDown, layouts, state.steps, state.positions, cardHeights)
                        if (hitStep != null) {
                            val (stepId, initialPos) = hitStep
                            pressedStepId = stepId
                            var currentWorldPos = initialPos
                            var isDragging = false
                            val touchSlop = viewConfiguration.touchSlop
                            var totalDragDistance = 0f

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (isDragging) {
                                        viewModel.moveStep(stepId, currentWorldPos.first, currentWorldPos.second)
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else {
                                        change.consume()
                                        viewModel.openInspector(stepId)
                                    }
                                    break
                                }

                                val deltaScreen = change.position - lastScreen
                                lastScreen = change.position
                                totalDragDistance += (change.position - startScreen).getDistance()

                                if (!isDragging && totalDragDistance > touchSlop) {
                                    isDragging = true
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }

                                if (isDragging) {
                                    change.consume()
                                    val deltaWorldX = deltaScreen.x / (transform.zoom * pixelScale)
                                    val deltaWorldY = deltaScreen.y / (transform.zoom * pixelScale)
                                    currentWorldPos = Pair(currentWorldPos.first + deltaWorldX, currentWorldPos.second + deltaWorldY)
                                    liveCardDrag = Triple(stepId, currentWorldPos.first, currentWorldPos.second)
                                }
                            }
                            pressedStepId = null
                            liveCardDrag = null
                            return@awaitEachGesture
                        }

                        // 3. Sticky note tap or drag
                        val hitNote = findStickyNote(worldDown, state.notes)
                        if (hitNote != null) {
                            pressedNoteId = hitNote.id
                            var currentWorldPos = Pair(hitNote.x, hitNote.y)
                            var isDragging = false
                            val touchSlop = viewConfiguration.touchSlop
                            var totalDragDistance = 0f

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (isDragging) {
                                        viewModel.moveStickyNote(hitNote.id, currentWorldPos.first, currentWorldPos.second)
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else {
                                        change.consume()
                                        viewModel.editNote(hitNote)
                                    }
                                    break
                                }

                                val deltaScreen = change.position - lastScreen
                                lastScreen = change.position
                                totalDragDistance += (change.position - startScreen).getDistance()

                                if (!isDragging && totalDragDistance > touchSlop) {
                                    isDragging = true
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }

                                if (isDragging) {
                                    change.consume()
                                    val deltaWorldX = deltaScreen.x / (transform.zoom * pixelScale)
                                    val deltaWorldY = deltaScreen.y / (transform.zoom * pixelScale)
                                    currentWorldPos = Pair(currentWorldPos.first + deltaWorldX, currentWorldPos.second + deltaWorldY)
                                    liveNoteDrag = Triple(hitNote.id, currentWorldPos.first, currentWorldPos.second)
                                }
                            }
                            pressedNoteId = null
                            liveNoteDrag = null
                            return@awaitEachGesture
                        }

                        // 4. Empty Canvas: Pan & Pinch-to-Zoom
                        transform.beginInteraction()
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { !it.pressed }) {
                                transform.endInteraction()
                                break
                            }
                            if (event.changes.size >= 2) {
                                val zoom = event.calculateZoom()
                                val centroid = event.calculateCentroid()
                                if (zoom != 1f) {
                                    transform.onZoom(zoom, Point(centroid.x, centroid.y))
                                }
                            }
                            val pan = event.calculatePan()
                            if (pan != Offset.Zero) {
                                transform.onPan(pan.x, pan.y)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
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
                    layouts = effectiveLayouts,
                    streams = live.streams,
                    transform = transform,
                    reduceMotion = motion.reduceMotion,
                    pixelScale = pixelScale,
                    modifier = Modifier.fillMaxSize(),
                )

                // Interactive stream disconnect joints at bezier midpoints
                state.streams.forEach { stream ->
                    val fromLayout = effectiveLayouts[stream.fromStepId]
                    val toLayout = effectiveLayouts[stream.toStepId]
                    if (fromLayout != null && toLayout != null) {
                        val start = fromLayout.ports.outputs.getOrNull(stream.fromPort) ?: fromLayout.bottomCenter
                        val end = toLayout.ports.input
                        val control = CanvasGeometry.bezier(start, end, pixelScale = 1f)
                        val mid = CanvasGeometry.pointAlongBezier(control, 0.5f)

                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    translationX = (mid.x * pixelScale) - (13f * density.density)
                                    translationY = (mid.y * pixelScale) - (13f * density.density)
                                }
                                .size(26.dp)
                                .background(
                                    color = Color(0xFF14141A),
                                    shape = CircleShape,
                                )
                                .border(
                                    width = 1.dp,
                                    color = colors.hairline,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    viewModel.requestDisconnectStream(stream)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = KaivoIcons.icon("close"),
                                contentDescription = "Disconnect stream",
                                tint = colors.velvetRose,
                                modifier = Modifier.size(13.dp),
                            )
                        }

                        val chipCount = live.counts[stream.id]
                        if (chipCount != null) {
                            StreamCountChip(
                                count = chipCount,
                                modifier = Modifier.graphicsLayer {
                                    translationX = (mid.x * pixelScale) + (18f * density.density)
                                    translationY = (mid.y * pixelScale) - (10f * density.density)
                                },
                            )
                        }
                    }
                }

                // Visual Step Group frames (underneath step cards)
                state.groups.forEach { group ->
                    val memberLayouts = group.stepIds.mapNotNull { layouts[it] }
                    CanvasGroupFrame(
                        group = group,
                        memberLayouts = memberLayouts,
                        pixelScale = pixelScale,
                        density = density,
                        zoom = transform.zoom,
                        onEdit = { viewModel.editGroup(group) },
                        onUngroup = { viewModel.deleteGroup(group.id) },
                        onDrag = { dx, dy -> viewModel.moveGroup(group.id, dx, dy) },
                    )
                }

                // Freeform canvas sticky notes
                state.notes.forEach { note ->
                    val notePos = liveNoteDrag?.takeIf { it.first == note.id }?.let { it.second to it.third }
                    CanvasStickyNote(
                        note = note,
                        currentPos = notePos,
                        isPressed = pressedNoteId == note.id,
                        pixelScale = pixelScale,
                        density = density,
                        zoom = transform.zoom,
                        onEdit = { viewModel.editNote(note) },
                        onDelete = { viewModel.deleteStickyNote(note.id) },
                    )
                }

                // Step cards: only the ones inside the viewport are composed.
                val visible = transform.visibleRect(
                    viewportSize.width.toFloat().coerceAtLeast(1f),
                    viewportSize.height.toFloat().coerceAtLeast(1f),
                    pixelScale = pixelScale,
                )
                layouts.values.forEachIndexed { layoutIndex, layout ->
                    if (!CanvasGeometry.isVisible(layout, visible)) return@forEachIndexed
                    val step = state.steps.firstOrNull { it.id == layout.id } ?: return@forEachIndexed
                    val animateIn = Stagger.shouldAnimate(policy, step.id in enteredSteps)
                    // Only the very first paint staggers; a Step added later appears at once.
                    val staggerIndex = if (enteredSteps.isEmpty()) layoutIndex else 0
                    val entrance = remember(step.id) { Animatable(if (animateIn) 0f else 1f) }
                    LaunchedEffect(step.id) {
                        if (animateIn) {
                            delay(Stagger.delayMs(staggerIndex, policy.staggerStepMs, policy.staggerMaxItems))
                            entrance.animateTo(
                                1f,
                                policy.tweenSpec<Float>(MotionTokenValues.STANDARD_MS, KaivoMotionTokens.flowing),
                            )
                        }
                        enteredSteps.add(step.id)
                    }
                    val currentPos = liveCardDrag?.takeIf { it.first == step.id }?.let { it.second to it.third }
                        ?: (state.positions[step.id]?.takeIf { it.first != 0f || it.second != 0f }
                            ?: Pair(layout.topLeft.x, layout.topLeft.y))

                    Box(
                        Modifier
                            .graphicsLayer {
                                // StepLayout is in world dp; graphicsLayer works in screen pixels.
                                translationX = currentPos.first * pixelScale
                                translationY = currentPos.second * pixelScale
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
                            isPressed = pressedStepId == step.id,
                            onRename = { viewModel.openStepRename(step.id) },
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
        }

        // ---------------- top bar ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 1. Back button
            Box(
                Modifier
                    .size(48.dp)
                    .clip(KaivoShapes.pill)
                    .background(colors.surfaceGlass)
                    .border(1.dp, colors.hairline, KaivoShapes.pill)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = "Back to Flows" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(KaivoIcons.icon("back"), "Back to Flows", tint = colors.inkPrimary, modifier = Modifier.size(20.dp))
            }

            // 2. Flow Title & Status (Click title to rename, click info icon for details)
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(KaivoShapes.pill)
                    .background(colors.surfaceGlass)
                    .border(1.dp, colors.hairline, KaivoShapes.pill)
                    .clickable(onClick = viewModel::openRenameDialog)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.flowName.ifBlank { "Flow" },
                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                color = colors.inkPrimary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                KaivoIcons.icon("edit"),
                                contentDescription = "Rename Flow",
                                tint = colors.inkMuted,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (state.isActive) colors.royalGold else if (state.isSaving) colors.royalGold else colors.statusSuccess),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                if (state.isSaving) "Saving…" else if (state.isActive) "24/7 Active • ${state.steps.size} Steps" else "Saved • ${state.steps.size} Steps",
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                color = if (state.isActive) colors.royalGold else colors.inkMuted,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .clickable(onClick = viewModel::openInfoDialog)
                            .padding(6.dp),
                    ) {
                        Icon(
                            KaivoIcons.icon("info"),
                            contentDescription = "Flow Details",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // 4. Quick Actions: + Note and Workflow Execution History
            Row(
                Modifier
                    .height(44.dp)
                    .clip(KaivoShapes.pill)
                    .background(colors.surfaceGlass)
                    .border(1.dp, colors.hairline, KaivoShapes.pill)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { viewModel.addStickyNote() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        KaivoIcons.icon("note"),
                        contentDescription = "Add Sticky Note",
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box(
                    Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(colors.hairline),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { viewModel.openExecutions() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        KaivoIcons.icon("history"),
                        contentDescription = "Workflow Executions & Snapshots",
                        tint = if (state.flowRuns.any { it.status == com.kaivoflow.core.model.RunStatus.Running }) colors.royalGold else colors.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box(
                    Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(colors.hairline),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onOpenAiAssistant(state.flowId) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        KaivoIcons.icon("sparkles"),
                        contentDescription = "Ask AI Assistant",
                        tint = colors.royalGold,
                        modifier = Modifier.size(18.dp),
                    )
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

        // ---------------- minimap & floating navigation dock ----------------
        if (layouts.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 84.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Floating Undo / Redo Controls
                CanvasUndoRedoDock(
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                )

                // Floating Zoom / Fit Controls
                CanvasControlsDock(
                    currentZoom = transform.zoom,
                    onZoomIn = {
                        val center = Point(viewportSize.width / 2f, viewportSize.height / 2f)
                        transform.onZoom(1.25f, center)
                    },
                    onZoomOut = {
                        val center = Point(viewportSize.width / 2f, viewportSize.height / 2f)
                        transform.onZoom(0.8f, center)
                    },
                    onResetZoom = {
                        val center = Point(viewportSize.width / 2f, viewportSize.height / 2f)
                        transform.onZoom(1f / transform.zoom, center)
                    },
                    onFitToScreen = {
                        val bounds = CanvasGeometry.contentBounds(layouts.values, state.notes)
                        if (bounds.width > 0f) {
                            transform.fit(
                                bounds,
                                viewportSize.width.toFloat(),
                                viewportSize.height.toFloat(),
                                pixelScale = pixelScale,
                            )
                        }
                    },
                )
            }
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
            AnimatedVisibility(
                visible = state.run.runId != null,
                enter = if (policy.animationsEnabled) {
                    fadeIn(tween(policy.ms(MotionTokenValues.STANDARD_MS), easing = KaivoMotionTokens.flowing)) +
                        slideInVertically(tween(policy.ms(MotionTokenValues.EMPHASIZED_MS), easing = KaivoMotionTokens.settle)) { it / 2 }
                } else EnterTransition.None,
                exit = if (policy.animationsEnabled) {
                    fadeOut(tween(policy.ms(MotionTokenValues.QUICK_MS), easing = KaivoMotionTokens.exit))
                } else ExitTransition.None,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RunHud(
                        run = state.run,
                        flowName = state.flowName.ifBlank { "Flow" },
                        onCancel = viewModel::cancelRun,
                        onOpenHistory = { viewModel.openExecutions() },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassPill {
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
                }
                Spacer(Modifier.size(12.dp))
                GoldButton(
                    modifier = Modifier
                        .clip(KaivoShapes.pill)
                        .goldSweep(trigger = state.run.runId?.takeIf { state.run.isRunning }),
                    text = if (state.run.isRunning) "Running…" else "Run",
                    icon = if (state.run.isRunning) "pause" else "play",
                    enabled = state.canRun || state.run.isRunning,
                    onClick = { if (state.run.isRunning) viewModel.cancelRun() else viewModel.run() },
                )
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
                onPick = { spec ->
                    val centerCanvas = transform.toCanvas(viewportSize.width / 2f, viewportSize.height / 2f, pixelScale)
                    viewModel.addStep(
                        spec.id,
                        atX = centerCanvas.x - CanvasGeometry.CARD_WIDTH / 2f,
                        atY = centerCanvas.y - 42f,
                    )
                },
                onDropOnCanvas = { spec, dropScreenOffset ->
                    val worldPoint = transform.toCanvas(dropScreenOffset.x, dropScreenOffset.y, pixelScale)
                    viewModel.addStep(
                        spec.id,
                        atX = worldPoint.x - CanvasGeometry.CARD_WIDTH / 2f,
                        atY = worldPoint.y - 42f,
                    )
                },
                onDismiss = viewModel::closeStepPicker,
                hasTrigger = state.triggers.isNotEmpty(),
            )
        }

        val inspectorSession by viewModel.inspectorSession.collectAsStateWithLifecycle()
        val openInspector = state.inspector
        val session = inspectorSession
        if (openInspector != null && session != null && session.stepId == openInspector.stepId) {
            val node = openInspector.node
            val spec = registry.spec(node.type)
            StepInspector(
                session = session,
                node = node,
                spec = spec,
                iconTint = colors.categoryColor(spec?.category ?: com.kaivoflow.core.model.StepCategory.Transform),
                credentials = credentialRefs,
                actions = StepInspectorActions(
                    onRename = { viewModel.renameStep(node.id, it) },
                    onToggleDisabled = { viewModel.toggleStepDisabled(node.id) },
                    onDuplicate = { viewModel.duplicateStep(node.id) },
                    onDelete = { viewModel.deleteStep(node.id) },
                    onEditStep = { edit -> viewModel.editStep(node.id, "behaviour", edit) },
                    onAddCredential = onOpenCredentials,
                    onDismiss = viewModel::closeInspector,
                    vars = state.vars,
                    flowId = state.flowId,
                    flowName = state.flowName,
                ),
            )
        }

        // The activate toggle opens the shared publish sheet (hosted by the app: feature:flows depends on this module).
        publishEntry(
            state.publishFlowId,
            viewModel::dismissPublish,
        ) { issue ->
            viewModel.dismissPublish()
            issue.stepId?.let { viewModel.openInspector(it) }
        }

        if (state.isExecutionsOpen) {
            com.kaivoflow.feature.canvas.sheets.WorkflowExecutionsSheet(
                flowName = state.flowName,
                runs = state.flowRuns,
                selectedRun = state.selectedRunDetails,
                onSelectRun = viewModel::selectRunDetails,
                onRunNow = viewModel::run,
                onDismiss = viewModel::closeExecutions,
                onOpenStep = { stepId, runId -> viewModel.closeExecutions(); viewModel.openInspector(stepId, runId) },
                onTestFromHere = viewModel::testFromHere,
            )
        }

        // ---------------- flow & canvas dialogs ----------------
        if (state.showRenameDialog) {
            FlowRenameDialog(
                currentName = state.flowName,
                onDismiss = viewModel::dismissRenameDialog,
                onSave = viewModel::setFlowName,
            )
        }

        if (state.showInfoDialog) {
            FlowInfoDialog(
                flowId = state.flowId,
                flowName = state.flowName,
                stepCount = state.steps.size,
                streamCount = state.streams.size,
                isActive = state.isActive,
                lastSavedAt = state.lastSavedAt,
                onDismiss = viewModel::dismissInfoDialog,
            )
        }



        state.editingNote?.let { note ->
            StickyNoteEditDialog(
                note = note,
                onDismiss = { viewModel.editNote(null) },
                onSave = viewModel::saveStickyNote,
                onDelete = { viewModel.deleteStickyNote(note.id) },
            )
        }

        state.editingGroup?.let { group ->
            FlowGroupEditDialog(
                group = group,
                onDismiss = { viewModel.editGroup(null) },
                onSave = viewModel::saveGroup,
                onDelete = { viewModel.deleteGroup(group.id) },
            )
        }

        state.stepRenaming?.let { (stepId, currentName) ->
            StepRenameDialog(
                initialName = currentName,
                onConfirm = { newName -> viewModel.renameStep(stepId, newName) },
                onDismiss = viewModel::closeStepRename,
            )
        }

        state.streamDisconnectTarget?.let { edge ->
            val fromName = state.steps.firstOrNull { it.id == edge.fromStepId }?.name ?: "Step"
            val toName = state.steps.firstOrNull { it.id == edge.toStepId }?.name ?: "Step"
            StreamDisconnectDialog(
                fromStepName = fromName,
                toStepName = toName,
                onConfirm = viewModel::confirmDisconnectStream,
                onDismiss = viewModel::dismissDisconnectStream,
            )
        }
    }
}

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

private fun findOutputPort(
    worldPoint: Point,
    layouts: Map<String, StepLayout>,
    steps: List<StepNodeUiModel>,
): Triple<String, Int, Point>? {
    for (step in steps) {
        val layout = layouts[step.id] ?: continue
        val outputs = layout.ports.outputs
        val outputCount = outputs.size.coerceAtLeast(1)
        for (port in 0 until outputCount) {
            val portCenter = outputs.getOrNull(port) ?: layout.bottomCenter
            val dist = kotlin.math.hypot(worldPoint.x - portCenter.x, worldPoint.y - portCenter.y)
            if (dist <= 28f) {
                return Triple(step.id, port, portCenter)
            }
        }
    }
    return null
}

private fun findStepCard(
    worldPoint: Point,
    layouts: Map<String, StepLayout>,
    steps: List<StepNodeUiModel>,
    positions: Map<String, Pair<Float, Float>>,
    cardHeights: Map<String, Float>,
): Pair<String, Pair<Float, Float>>? {
    for (step in steps.asReversed()) {
        val layout = layouts[step.id]
        val pos = positions[step.id]?.takeIf { it.first != 0f || it.second != 0f }
            ?: layout?.let { it.topLeft.x to it.topLeft.y }
            ?: Pair(32f, 48f)
        val height = cardHeights[step.id] ?: layout?.size?.height ?: 84f
        val width = layout?.size?.width ?: 320f
        if (worldPoint.x in pos.first..(pos.first + width) &&
            worldPoint.y in pos.second..(pos.second + height + 10f)
        ) {
            return Pair(step.id, pos)
        }
    }
    return null
}

private fun findStickyNote(
    worldPoint: Point,
    notes: List<com.kaivoflow.core.model.StickyNote>,
): com.kaivoflow.core.model.StickyNote? {
    for (note in notes.asReversed()) {
        if (worldPoint.x in note.x..(note.x + note.width) &&
            worldPoint.y in note.y..(note.y + note.height)
        ) {
            return note
        }
    }
    return null
}
