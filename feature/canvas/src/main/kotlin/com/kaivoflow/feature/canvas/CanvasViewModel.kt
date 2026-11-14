package com.kaivoflow.feature.canvas

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaivoflow.core.data.CredentialRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.engine.ExecutionMode
import com.kaivoflow.core.engine.FlowRunner
import com.kaivoflow.core.engine.ValidationReport
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.FlowGroup
import com.kaivoflow.core.model.LogLevel
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.RunEvent
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.RunTrigger
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.StickyNote
import com.kaivoflow.core.model.StreamEdge
import com.kaivoflow.core.nodes.NodeCatalog
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.feature.canvas.layout.CanvasGeometry
import com.kaivoflow.feature.canvas.layout.LayoutMode
import com.kaivoflow.core.work.FlowEngine
import com.kaivoflow.core.work.FlowScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Canvas state machine.
 *
 * Editing is optimistic (the canvas updates immediately) and persistence is debounced by the
 * repository, so dragging a card never waits on the disk.
 */
@HiltViewModel
class CanvasViewModel @Inject constructor(
    private val flowRepository: FlowRepository,
    private val flowEngine: FlowEngine,
    private val scheduler: FlowScheduler,
    private val credentials: CredentialRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val flowId: String = savedStateHandle.get<String>("flowId").orEmpty()
    private val registry = NodeCatalog.registry()

    private val _state = MutableStateFlow(CanvasUiState(showPromptCard = false))
    val state: StateFlow<CanvasUiState> = _state.asStateFlow()

    private val _promptEvents = Channel<String>(Channel.BUFFERED)
    private var flow: Flow = Flow(id = flowId, name = "Flow")
    private var runJob: Job? = null
    private var pendingSave: Job? = null

    init {
        viewModelScope.launch {
            flowRepository.observeFlow(flowId).collect { loaded ->
                if (loaded == null) return@collect
                val isFirstLoad = flow.steps.isEmpty() && loaded.steps.isNotEmpty()
                flow = loaded
                refreshValidation()
                if (isFirstLoad) {
                    _state.update { it.copy(showPromptCard = loaded.steps.isEmpty()) }
                }
                syncState()
            }
        }
    }

    // ------------------------------------------------------------------ editing

    fun addStep(type: String, preferredName: String? = null) {
        val spec = registry.spec(type) ?: return
        if (spec.kind == NodeKind.Trigger && flow.triggers.isNotEmpty()) {
            toast("This Flow already has a Spark.", LogLevel.Warn)
            return
        }
        val insertAfter = _state.value.pickerInsertAfterStepId
        val insertPort = _state.value.pickerInsertAfterPort.coerceAtLeast(0)
        val step = StepNode(
            id = UUID.randomUUID().toString(),
            type = type,
            name = preferredName ?: uniqueName(spec.title),
            params = spec.params.filter { it.default != null }
                .associate { it.key to it.default }
                .toImmutableMap(),
            y = (flow.steps.maxOfOrNull { it.y } ?: 0f) + 120f,
        )
        flow = flow.plusStep(step)
        if (insertAfter != null) {
            flow = flow.plusStream(
                StreamEdge(UUID.randomUUID().toString(), insertAfter, step.id, fromPort = insertPort)
            )
            // Re-link the downstream Steps of the Step we inserted after, so the Flow still flows.
            flow.outgoing(insertAfter)
                .filter { it.toStepId != step.id }
                .forEach { existing ->
                    flow = flow.minusStream(existing.id)
                        .plusStream(existing.copy(id = UUID.randomUUID().toString(), fromStepId = step.id))
                }
        } else if (!spec.isTrigger) {
            val lastStep = flow.steps.lastOrNull { it.id != step.id && !it.isTrigger }
            if (lastStep != null) {
                flow = flow.plusStream(StreamEdge(UUID.randomUUID().toString(), lastStep.id, step.id))
            }
        }
        commit()
        _state.update {
            it.copy(
                isStepPickerOpen = false,
                pickerInsertAfterStepId = null,
                pickerInsertAfterPort = 0,
                selectedStepId = step.id,
            )
        }
        openInspector(step.id)
    }

    fun openStepPicker(afterStepId: String? = null, fromPort: Int = 0) {
        _state.update {
            it.copy(
                isStepPickerOpen = true,
                pickerInsertAfterStepId = afterStepId,
                pickerInsertAfterPort = fromPort.coerceAtLeast(0),
            )
        }
    }

    fun closeStepPicker() {
        _state.update {
            it.copy(
                isStepPickerOpen = false,
                pickerInsertAfterStepId = null,
                pickerInsertAfterPort = 0,
            )
        }
    }

    fun selectStep(stepId: String?) {
        _state.update { it.copy(selectedStepId = stepId) }
    }

    fun openInspector(stepId: String) {
        val step = flow.step(stepId) ?: return
        val incoming = flow.incoming(stepId).mapNotNull { stream ->
            flow.step(stream.fromStepId)?.let { upstream -> lastOutputs[upstream.name] }
        }.flatten()
        _state.update {
            it.copy(
                selectedStepId = stepId,
                inspector = InspectorUiState(
                    stepId = step.id,
                    stepName = step.name,
                    inputItems = incoming.toImmutableList(),
                    tab = InspectorTab.Configure,
                ),
            )
        }
    }

    fun closeInspector() {
        _state.update { it.copy(inspector = null) }
    }

    fun setInspectorTab(tab: InspectorTab) {
        _state.update { current -> current.inspector?.let { current.copy(inspector = it.copy(tab = tab)) } ?: current }
    }

    fun renameStep(stepId: String, name: String) {
        val step = flow.step(stepId) ?: return
        flow = flow.withStep(step.copy(name = name.ifBlank { step.name }))
        commit()
    }

    fun updateParam(stepId: String, key: String, value: Any?) {
        val step = flow.step(stepId) ?: return
        flow = flow.withStep(step.copy(params = (step.params + (key to value)).toImmutableMap()))
        commit()
    }

    fun toggleStepDisabled(stepId: String) {
        val step = flow.step(stepId) ?: return
        flow = flow.withStep(step.copy(disabled = !step.disabled))
        commit()
    }

    fun deleteStep(stepId: String) {
        flow = flow.minusStep(stepId)
        commit()
        _state.update { it.copy(selectedStepId = null, inspector = null) }
    }

    fun duplicateStep(stepId: String) {
        val step = flow.step(stepId) ?: return
        val copy = step.copy(id = UUID.randomUUID().toString(), name = uniqueName(step.name), y = step.y + 120f)
        flow = flow.plusStep(copy)
        commit()
    }

    /** Returns true only when a Stream was actually added, so the canvas can celebrate success. */
    fun connectStream(fromStepId: String, fromPort: Int, toStepId: String): Boolean {
        if (fromStepId == toStepId) return false
        // Reject cycles before they exist: a user dragging a loop gets a clear message.
        val candidate = flow.plusStream(
            StreamEdge(UUID.randomUUID().toString(), fromStepId, toStepId, fromPort = fromPort)
        )
        val cycle = com.kaivoflow.core.engine.FlowValidator(registry).detectCycle(candidate)
        if (cycle != null) {
            toast("That would create a loop. Use the Loop Step instead.", LogLevel.Warn)
            return false
        }
        flow = candidate
        commit()
        return true
    }

    fun disconnectStream(streamId: String) {
        flow = flow.minusStream(streamId)
        commit()
    }

    fun moveStep(stepId: String, x: Float, y: Float) {
        val step = flow.step(stepId) ?: return
        flow = flow.withStep(step.copy(x = CanvasGeometry.snap(x), y = CanvasGeometry.snap(y)))
        scheduleSave()
        syncState()
    }

    fun toggleLayoutMode() {
        val next = if (_state.value.layoutMode == LayoutMode.GuidedPath) LayoutMode.Freeform else LayoutMode.GuidedPath
        if (next == LayoutMode.Freeform) {
            // Seed freeform positions from the guided layout so switching modes does not scatter cards.
            val declaredPorts = flow.steps.associate { step ->
                step.id to (registry.spec(step.type)?.outputs ?: 1)
            }
            val layouts = CanvasGeometry.guidedLayout(flow, outputPortCounts = declaredPorts)
            flow = flow.copy(steps = flow.steps.map { step ->
                val layout = layouts[step.id] ?: return@map step
                step.copy(x = layout.topLeft.x, y = layout.topLeft.y)
            }.toImmutableList())
            commit()
        }
        _state.update { it.copy(layoutMode = next) }
    }

    fun setFlowName(name: String) {
        flow = flow.copy(name = name)
        commit()
    }

    fun setVar(key: String, value: Any?) {
        flow = flow.copy(vars = (flow.vars + (key to value)).toImmutableMap())
        commit()
    }

    /** Freeze Output (pinned data) for a Step — the debugging superpower. */
    fun freezeOutput(stepId: String) {
        val step = flow.step(stepId) ?: return
        val current = lastOutputs[step.name]
        if (current == null) {
            toast("Run the Step once first, then freeze its output.", LogLevel.Warn)
            return
        }
        flow = flow.withStep(step.copy(frozenOutput = current.toImmutableList()))
        commit()
        toast("Output frozen. This Step will be skipped on future Runs.")
    }

    fun unfreezeOutput(stepId: String) {
        val step = flow.step(stepId) ?: return
        flow = flow.withStep(step.copy(frozenOutput = null))
        commit()
    }

    // ------------------------------------------------------------------ running

    fun run() {
        if (runJob?.isActive == true) return
        val report = refreshValidation()
        if (!report.isValid) {
            toast(report.blocking.first().message, LogLevel.Error)
            return
        }
        runJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    run = RunUiState(
                        isRunning = true,
                        status = RunStatus.Running,
                        startedAt = System.currentTimeMillis(),
                        totalSteps = flow.steps.size,
                    )
                )
            }
            val record = flowEngine.run(
                flow = flow,
                trigger = RunTrigger(kind = "manual"),
                mode = ExecutionMode.Manual,
            ) { event -> handleRunEvent(event) }
            _state.update { current ->
                current.copy(
                    run = current.run.copy(
                        isRunning = false,
                        status = record.status,
                        finishedAt = record.finishedAt,
                        errorMessage = record.error,
                    )
                )
            }
        }
    }

    fun cancelRun() {
        flowEngine.cancel(flow.id)
        runJob?.cancel()
        _state.update { it.copy(run = it.run.copy(isRunning = false, status = RunStatus.Canceled)) }
    }

    private val lastOutputs = mutableMapOf<String, List<FlowData>>()

    private fun handleRunEvent(event: RunEvent) {
        when (event) {
            is RunEvent.RunStarted -> _state.update {
                it.copy(run = it.run.copy(runId = event.runId, isRunning = true, totalSteps = event.totalSteps))
            }
            is RunEvent.StepStarted -> {
                _state.update { current ->
                    current.copy(
                        run = current.run.copy(currentStepId = event.stepId),
                        steps = current.steps.map { step ->
                            if (step.id == event.stepId) step.copy(status = StepRunStatus.Running) else step
                        }.toImmutableList(),
                    )
                }
                // Light the Streams feeding this Step: this is the Golden Current trigger.
                val feeding = flow.incoming(event.stepId).map { it.id }.toImmutableList()
                _state.update { it.copy(run = it.run.copy(activeStreamIds = feeding)) }
            }
            is RunEvent.StepFinished -> {
                lastOutputs[flow.step(event.stepId)?.name ?: ""] = event.output
                _state.update { current ->
                    val completed = current.run.completedSteps + 1
                    current.copy(
                        run = current.run.copy(
                            completedSteps = completed,
                            activeStreamIds = persistentListOf(),
                            completedStreamIds = (current.run.completedStreamIds + flow.outgoing(event.stepId).map { s -> s.id })
                                .toImmutableList(),
                        ),
                        steps = current.steps.map { step ->
                            if (step.id == event.stepId) {
                                step.copy(
                                    status = if (event.frozen) StepRunStatus.Frozen else StepRunStatus.Succeeded,
                                    itemCount = event.itemsOut,
                                    latencyMs = event.durationMs,
                                    isFrozen = event.frozen,
                                )
                            } else step
                        }.toImmutableList(),
                    )
                }
                _state.update { current ->
                    val inspector = current.inspector
                    if (inspector?.stepId == event.stepId) {
                        current.copy(
                            inspector = inspector.copy(
                                outputItems = event.output,
                                isTesting = false,
                                outputError = null,
                            )
                        )
                    } else current
                }
            }
            is RunEvent.StepFailed -> {
                _state.update { current ->
                    current.copy(
                        run = current.run.copy(failedStepId = event.stepId),
                        steps = current.steps.map { step ->
                            if (step.id == event.stepId) step.copy(status = StepRunStatus.Failed) else step
                        }.toImmutableList(),
                    )
                }
            }
            is RunEvent.StepSkipped -> _state.update { current ->
                current.copy(
                    steps = current.steps.map { step ->
                        if (step.id == event.stepId && step.status == StepRunStatus.Pending) {
                            step.copy(status = StepRunStatus.Skipped)
                        } else step
                    }.toImmutableList(),
                )
            }
            is RunEvent.Log -> if (event.level == LogLevel.Error || event.level == LogLevel.Warn) {
                toast(event.message, event.level)
            }
            is RunEvent.RunFinished -> _state.update { current ->
                current.copy(
                    run = current.run.copy(
                        isRunning = false,
                        status = event.status,
                        finishedAt = System.currentTimeMillis(),
                        errorMessage = event.error,
                        activeStreamIds = persistentListOf(),
                    )
                )
            }
            else -> Unit
        }
    }

    /** "Test Step" in the inspector. */
    fun testStep() {
        val inspector = _state.value.inspector ?: return
        val step = flow.step(inspector.stepId) ?: return
        _state.update { it.copy(inspector = inspector.copy(isTesting = true, outputError = null)) }
        viewModelScope.launch {
            val result = runCatching {
                flowEngine.testStep(flow, step.id, inspector.inputItems)
            }
            result.onSuccess { stepRun ->
                _state.update { current ->
                    current.copy(
                        inspector = current.inspector?.copy(
                            isTesting = false,
                            outputItems = stepRun.output,
                            outputError = stepRun.error,
                        )
                    )
                }
            }.onFailure { error ->
                _state.update { current ->
                    current.copy(
                        inspector = current.inspector?.copy(
                            isTesting = false,
                            outputError = error.message ?: "The Step failed.",
                        )
                    )
                }
            }
        }
    }

    /** Prompt-to-Flow: turns a sentence into a starter Flow the user can then edit. */
    fun generateFromPrompt(prompt: String) {
        if (prompt.isBlank()) return
        _state.update { it.copy(isPromptGenerating = true) }
        viewModelScope.launch {
            val draft = PromptToFlow.draft(prompt, registry)
            if (draft == null) {
                _state.update { it.copy(isPromptGenerating = false) }
                toast("Try describing a trigger and an action, e.g. \"every morning send me the weather\".", LogLevel.Warn)
                return@launch
            }
            flow = draft.copy(id = flow.id, name = draft.name.ifBlank { "New Flow" })
            commit()
            _state.update {
                it.copy(
                    isPromptGenerating = false,
                    showPromptCard = false,
                    layoutMode = LayoutMode.GuidedPath,
                    toasts = (it.toasts + ToastMessage(System.currentTimeMillis(), "Draft ready — tap a Step to tune it.")).toImmutableList(),
                )
            }
        }
    }

    fun dismissPromptCard() {
        _state.update { it.copy(showPromptCard = false) }
    }

    // ------------------------------------------------------------------ persistence

    private fun commit() {
        refreshValidation()
        syncState()
        scheduleSave()
    }

    private fun scheduleSave() {
        pendingSave?.cancel()
        _state.update { it.copy(isSaving = true) }
        pendingSave = viewModelScope.launch {
            kotlinx.coroutines.delay(450)
            flowRepository.save(flow)
            if (flow.isActive) {
                runCatching { scheduler.schedule(flow) }
            } else {
                runCatching { scheduler.cancel(flow.id) }
            }
            _state.update { it.copy(lastSavedAt = System.currentTimeMillis(), isSaving = false) }
        }
    }

    fun toggleFlowActive() {
        val next = !flow.isActive
        flow = flow.copy(isActive = next)
        commit()
        toast(if (next) "Flow activated for 24/7 background execution" else "Flow deactivated")
    }

    fun openRenameDialog() {
        _state.update { it.copy(showRenameDialog = true) }
    }

    fun dismissRenameDialog() {
        _state.update { it.copy(showRenameDialog = false) }
    }

    fun openInfoDialog() {
        _state.update { it.copy(showInfoDialog = true) }
    }

    fun dismissInfoDialog() {
        _state.update { it.copy(showInfoDialog = false) }
    }

    fun openCreateGroupDialog() {
        _state.update { it.copy(showCreateGroupDialog = true) }
    }

    fun dismissCreateGroupDialog() {
        _state.update { it.copy(showCreateGroupDialog = false) }
    }

    fun editNote(note: StickyNote?) {
        _state.update { it.copy(editingNote = note) }
    }

    fun editGroup(group: FlowGroup?) {
        _state.update { it.copy(editingGroup = group) }
    }

    fun addStickyNote(x: Float = 60f, y: Float = 60f) {
        val note = StickyNote(
            id = UUID.randomUUID().toString(),
            title = "Note",
            content = "Tap to add workflow notes or documentation…",
            x = x,
            y = y,
            color = "gold",
        )
        flow = flow.plusNote(note)
        commit()
        editNote(note)
    }

    fun saveStickyNote(note: StickyNote) {
        flow = flow.withNote(note)
        editNote(null)
        commit()
    }

    fun deleteStickyNote(noteId: String) {
        flow = flow.minusNote(noteId)
        editNote(null)
        commit()
        toast("Note deleted")
    }

    fun moveStickyNote(noteId: String, x: Float, y: Float) {
        val note = flow.notes.firstOrNull { it.id == noteId } ?: return
        flow = flow.withNote(note.copy(x = CanvasGeometry.snap(x), y = CanvasGeometry.snap(y)))
        scheduleSave()
        syncState()
    }

    fun createGroup(name: String, color: String = "amber", stepIds: List<String> = emptyList()) {
        val finalStepIds = if (stepIds.isNotEmpty()) {
            stepIds
        } else if (_state.value.selectedStepId != null) {
            listOf(_state.value.selectedStepId!!)
        } else {
            flow.steps.map { it.id }.take(2)
        }
        val group = FlowGroup(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Group" },
            color = color,
            stepIds = finalStepIds.toImmutableList(),
        )
        flow = flow.plusGroup(group)
        dismissCreateGroupDialog()
        commit()
        toast("Group \"${group.name}\" created")
    }

    fun saveGroup(group: FlowGroup) {
        flow = flow.withGroup(group)
        editGroup(null)
        commit()
    }

    fun deleteGroup(groupId: String) {
        flow = flow.minusGroup(groupId)
        editGroup(null)
        commit()
        toast("Group removed (steps preserved)")
    }

    fun moveGroup(groupId: String, deltaX: Float, deltaY: Float) {
        val group = flow.groups.firstOrNull { it.id == groupId } ?: return
        if (group.stepIds.isEmpty()) return
        val dx = CanvasGeometry.snap(deltaX)
        val dy = CanvasGeometry.snap(deltaY)
        flow = flow.copy(
            steps = flow.steps.map { step ->
                if (step.id in group.stepIds) {
                    step.copy(x = step.x + dx, y = step.y + dy)
                } else {
                    step
                }
            }.toImmutableList(),
            updatedAt = System.currentTimeMillis(),
        )
        scheduleSave()
        syncState()
    }

    private fun refreshValidation(): ValidationReport {
        val report = flowEngine.validate(flow)
        _state.update { it.copy(validation = report) }
        return report
    }

    private fun syncState() {
        val issuesByStep = _state.value.validation.issues.groupBy { it.stepId }
        val steps = flow.steps.map { step ->
            val spec = registry.spec(step.type)
            val stepIssues = issuesByStep[step.id].orEmpty()
            val previous = _state.value.steps.firstOrNull { it.id == step.id }
            StepNodeUiModel(
                id = step.id,
                type = step.type,
                name = step.name,
                subtitle = spec?.subtitle ?: "Unavailable Step",
                category = spec?.category ?: com.kaivoflow.core.model.StepCategory.Transform,
                iconKey = spec?.iconKey ?: "bolt",
                status = previous?.status ?: StepRunStatus.Pending,
                isTrigger = step.isTrigger,
                isDisabled = step.disabled,
                isFrozen = step.frozenOutput != null,
                hasError = stepIssues.any { it.severity == com.kaivoflow.core.engine.IssueSeverity.Blocking },
                hasWarning = stepIssues.any { it.severity == com.kaivoflow.core.engine.IssueSeverity.Warning },
                itemCount = previous?.itemCount,
                latencyMs = previous?.latencyMs,
                paramSummary = StepSummaries.summarize(spec, step.params),
                branchLabels = spec?.outputLabels ?: persistentListOf(),
                outputPorts = spec?.outputs ?: 1,
            )
        }.toImmutableList()

        val streams = flow.streams.map { stream ->
            val sourceCategory = registry.spec(flow.step(stream.fromStepId)?.type ?: "")?.category
            val targetCategory = registry.spec(flow.step(stream.toStepId)?.type ?: "")?.category
            StreamEdgeUiModel(
                id = stream.id,
                fromStepId = stream.fromStepId,
                toStepId = stream.toStepId,
                fromPort = stream.fromPort,
                sourceColorArgb = categoryColorArgb(sourceCategory),
                targetColorArgb = categoryColorArgb(targetCategory),
                label = stream.label,
            )
        }.toImmutableList()

        _state.update {
            it.copy(
                flowId = flow.id,
                flowName = flow.name,
                steps = steps,
                streams = streams,
                groups = flow.groups,
                notes = flow.notes,
                isActive = flow.isActive,
                vars = flow.vars,
                positions = flow.steps.associate { it.id to (it.x to it.y) }.toImmutableMap(),
                paramCache = flow.steps.associate { step ->
                    step.id to step.params.toImmutableMap()
                }.toImmutableMap(),
            )
        }
    }

    private fun categoryColorArgb(category: com.kaivoflow.core.model.StepCategory?): Long = when (category) {
        com.kaivoflow.core.model.StepCategory.Trigger -> 0xFFFFB454
        com.kaivoflow.core.model.StepCategory.Ai -> 0xFFF272B6
        com.kaivoflow.core.model.StepCategory.Saas -> 0xFF5CA8FF
        com.kaivoflow.core.model.StepCategory.Logic -> 0xFF3DD6A3
        com.kaivoflow.core.model.StepCategory.Transform -> 0xFFA97DFF
        com.kaivoflow.core.model.StepCategory.Device -> 0xFF45D9D2
        null -> 0xFFF3C677
    }

    private fun uniqueName(base: String): String {
        if (flow.steps.none { it.name.equals(base, true) }) return base
        var index = 2
        while (flow.steps.any { it.name.equals("$base $index", true) }) index++
        return "$base $index"
    }

    private fun toast(text: String, level: LogLevel = LogLevel.Info) {
        _state.update {
            it.copy(toasts = (it.toasts + ToastMessage(System.currentTimeMillis(), text, level)).toImmutableList())
        }
    }

    fun consumeToast(id: Long) {
        _state.update { it.copy(toasts = it.toasts.filterNot { toast -> toast.id == id }.toImmutableList()) }
    }
}

/**
 * Prompt-to-Flow without a network round-trip.
 *
 * A deterministic phrase matcher covers the common asks ("every morning … weather … telegram"),
 * which means the feature works offline, costs nothing, and never sends the user's words to a
 * server. Anything it cannot match returns null and the UI suggests a phrasing instead of
 * inventing a wrong Flow.
 */
object PromptToFlow {

    fun draft(prompt: String, registry: com.kaivoflow.core.engine.NodeRegistry): Flow? {
        val text = prompt.lowercase()
        val steps = mutableListOf<StepNode>()
        var y = 0f

        fun add(type: String, name: String, params: Map<String, Any?> = emptyMap()): StepNode? {
            val spec = registry.spec(type) ?: return null
            val step = StepNode(
                id = UUID.randomUUID().toString(),
                type = type,
                name = name,
                params = params.toImmutableMap(),
                y = y,
            )
            y += 140f
            steps += step
            return step
        }

        // 1. Trigger
        when {
            text.contains("every morning") || text.contains("every day") || text.contains("daily") -> {
                val hour = when {
                    text.contains("8") || text.contains("eight") -> "08:00"
                    text.contains("7") -> "07:00"
                    text.contains("9") -> "09:00"
                    text.contains("evening") -> "18:00"
                    else -> "08:00"
                }
                add(TriggerSpecs.SCHEDULE, "Every morning", mapOf("frequency" to "daily", "time" to hour))
            }
            text.contains("notification") || text.contains("sms") || text.contains("bank") ->
                add(TriggerSpecs.NOTIFICATION, "When a notification arrives", mapOf("textContains" to "debited"))
            text.contains("share") || text.contains("link") ->
                add(TriggerSpecs.SHARE, "When I share something")
            text.contains("webhook") ->
                add(TriggerSpecs.WEBHOOK, "Incoming webhook", mapOf("path" to "my-flow"))
            else -> add(TriggerSpecs.MANUAL, "When I tap Run")
        }

        // 2. Data + processing
        when {
            text.contains("weather") -> add("saas.weather", "Weather", mapOf("city" to "Bhubaneswar"))
            text.contains("sheet") && !text.contains("summarise") -> add("saas.sheets", "Google Sheets", mapOf("action" to "read"))
        }
        if (text.contains("summar") || text.contains("summarise") || text.contains("summarize") || text.contains("extract")) {
            add(
                "ai.chat",
                if (text.contains("extract")) "Extract fields" else "Summarise",
                mapOf(
                    "provider" to "openai",
                    "model" to "gpt-4o-mini",
                    "prompt" to if (text.contains("sms") || text.contains("bank")) {
                        "Summarise this SMS alert in one line, keeping the merchant, amount and date: {{ \$json.text }}"
                    } else {
                        "Summarise this in one line: {{ \$json.text }}"
                    },
                ),
            )
        }

        // 3. Destination
        when {
            text.contains("telegram") -> add("saas.telegram", "Send to Telegram", mapOf("chatId" to "@my_channel", "text" to "{{ \$json.answer }}"))
            text.contains("sheet") && text.contains("log") -> add("saas.sheets", "Log to Sheets", mapOf("action" to "append"))
            text.contains("notion") -> add("saas.notion", "Save to Notion")
            text.contains("slack") -> add("saas.slack", "Send to Slack")
            text.contains("copy") || text.contains("clipboard") -> add("device.clipboard", "Copy to clipboard", mapOf("text" to "{{ \$json.answer }}"))
            text.contains("file") || text.contains("save") -> add("device.file", "Save to a file", mapOf("fileName" to "notes.txt"))
            else -> add("device.notify", "Tell me", mapOf("title" to "KaivoFlow", "body" to "{{ \$json.answer }}"))
        }

        if (steps.size < 2) return null

        val streams = steps.zipWithNext { from, to ->
            StreamEdge(UUID.randomUUID().toString(), from.id, to.id)
        }
        return Flow(
            id = UUID.randomUUID().toString(),
            name = prompt.take(48).replaceFirstChar { it.uppercase() }.ifBlank { "New Flow" },
            steps = steps.toImmutableList(),
            streams = streams.toImmutableList(),
        )
    }
}
