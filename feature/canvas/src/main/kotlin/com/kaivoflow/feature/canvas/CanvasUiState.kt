package com.kaivoflow.feature.canvas

import androidx.compose.runtime.Immutable
import com.kaivoflow.core.engine.ValidationReport
import com.kaivoflow.core.model.FlowGroup
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.LogLevel
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.StickyNote
import com.kaivoflow.feature.canvas.layout.LayoutMode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * Immutable UI models.
 *
 * Everything the canvas renders is immutable and held in [kotlinx.collections.immutable] collections
 * so Compose can skip unchanged Step cards during live Runs. A Run that touches one Step must not
 * recompose the other 999.
 */
@Immutable
data class StepNodeUiModel(
    val id: String,
    val type: String,
    val name: String,
    val subtitle: String,
    val category: StepCategory,
    val iconKey: String,
    val status: StepRunStatus = StepRunStatus.Pending,
    val isTrigger: Boolean = false,
    val isDisabled: Boolean = false,
    val isFrozen: Boolean = false,
    val hasError: Boolean = false,
    val hasWarning: Boolean = false,
    val itemCount: Int? = null,
    val latencyMs: Long? = null,
    val attempts: Int = 1,
    val paramSummary: String = "",
    val branchLabels: ImmutableList<String> = persistentListOf(),
    val outputPorts: Int = 1,
)

@Immutable
data class StreamEdgeUiModel(
    val id: String,
    val fromStepId: String,
    val toStepId: String,
    val fromPort: Int = 0,
    val sourceColorArgb: Long = 0xFFF3C677,
    val targetColorArgb: Long = 0xFFF3C677,
    val label: String = "",
    val isActive: Boolean = false,
    val isComplete: Boolean = false,
    val isDimmed: Boolean = false,
)

@Immutable
data class LiveStreamPulse(val streamId: String, val startedAt: Long)

@Immutable
data class RunUiState(
    val runId: String? = null,
    val isRunning: Boolean = false,
    val status: RunStatus? = null,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
    val currentStepId: String? = null,
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val activeStreamIds: ImmutableList<String> = persistentListOf(),
    val completedStreamIds: ImmutableList<String> = persistentListOf(),
    val failedStepId: String? = null,
    val errorMessage: String? = null,
) {
    val progress: Float
        get() = if (totalSteps == 0) 0f else (completedSteps.toFloat() / totalSteps).coerceIn(0f, 1f)

    val elapsedMs: Long
        get() = if (startedAt == 0L) 0 else (finishedAt ?: System.currentTimeMillis()) - startedAt
}

/** The open Step inspector: which Step, and its current document (Behaviour tab). The live state is in the session. */
@Immutable
data class InspectorUiState(
    val stepId: String,
    val node: StepNode,
)

@Immutable
data class CanvasUiState(
    val flowId: String = "",
    val flowName: String = "",
    val steps: ImmutableList<StepNodeUiModel> = persistentListOf(),
    val streams: ImmutableList<StreamEdgeUiModel> = persistentListOf(),
    val layoutMode: LayoutMode = LayoutMode.GuidedPath,
    val run: RunUiState = RunUiState(),
    val validation: ValidationReport = ValidationReport.Empty,
    val selectedStepId: String? = null,
    val inspector: InspectorUiState? = null,
    /** Non-null while the publish sheet is open for this Flow. */
    val publishFlowId: String? = null,
    val isStepPickerOpen: Boolean = false,
    val pickerInsertAfterStepId: String? = null,
    /** Output port the new Step should be chained from after a port drag. */
    val pickerInsertAfterPort: Int = 0,
    val isSaving: Boolean = false,
    val lastSavedAt: Long? = null,
    val toasts: ImmutableList<ToastMessage> = persistentListOf(),
    /** Step positions, kept here so the screen never re-reads the Flow document. */
    val positions: ImmutableMap<String, Pair<Float, Float>> = persistentMapOf(),
    val vars: ImmutableMap<String, Any?> = persistentMapOf(),
    val groups: ImmutableList<FlowGroup> = persistentListOf(),
    val notes: ImmutableList<StickyNote> = persistentListOf(),
    val isActive: Boolean = false,
    val editingNote: StickyNote? = null,
    val editingGroup: FlowGroup? = null,
    val showInfoDialog: Boolean = false,
    val showRenameDialog: Boolean = false,
    val showCreateGroupDialog: Boolean = false,
    val stepRenaming: Pair<String, String>? = null,
    val streamDisconnectTarget: StreamEdgeUiModel? = null,
    val isExecutionsOpen: Boolean = false,
    val flowRuns: ImmutableList<RunRecord> = persistentListOf(),
    val selectedRunDetails: RunRecord? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val selectedStep: StepNodeUiModel? get() = steps.firstOrNull { it.id == selectedStepId }
    val canRun: Boolean get() = validation.isValid && !run.isRunning
    val triggers: List<StepNodeUiModel> get() = steps.filter { it.isTrigger }
}

@Immutable
data class ToastMessage(
    val id: Long,
    val text: String,
    val level: LogLevel = LogLevel.Info,
)

/** Presentation helpers that turn model objects into what the card shows. */
object StepSummaries {

    /** A human sentence for the card's second line — never an internal id or raw JSON. */
    fun summarize(spec: NodeSpec?, params: Map<String, Any?>): String {
        if (spec == null) return "Unknown Step"
        fun param(key: String): String = params[key]?.toString().orEmpty()
        return when (spec.id) {
            "net.http" -> {
                val method = param("method").ifBlank { "GET" }
                val host = param("url").removePrefix("https://").removePrefix("http://").substringBefore('/')
                if (host.isBlank()) "Choose a URL" else "$method • $host"
            }
            "app.telegram" -> param("chatId").ifBlank { "Choose a chat" }
            "app.google_sheets" -> param("range").ifBlank { "Pick a range" }
            "app.notion" -> param("databaseId").ifBlank { "Pick a database" }
            "ai.chat", "ai.extract", "ai.classify" -> param("model").ifBlank { "Choose a model" }
            "spark.schedule" -> when (param("frequency")) {
                "minutes" -> "Every ${param("interval").ifBlank { "15" }} minutes"
                "hourly" -> "Every hour"
                "weekly" -> "Weekly at ${param("time").ifBlank { "08:00" }}"
                "monthly" -> "Monthly at ${param("time").ifBlank { "08:00" }}"
                else -> "Daily at ${param("time").ifBlank { "08:00" }}"
            }
            "spark.notification" -> param("textContains").ifBlank { "Any notification" }
            "spark.webhook" -> "/hook/${param("path").ifBlank { "my-flow" }}"
            "data.set" -> "${((params["fields"] as? Map<*, *>)?.size ?: 0) + ((params["assignments"] as? List<*>)?.size ?: 0)} fields"
            "code.javascript" -> "JavaScript"
            "flow.if" -> "Branches into 2"
            "flow.switch" -> "Branches into 5"
            "flow.loop" -> "${param("batchSize").ifBlank { "1" }} at a time"
            "flow.wait" -> "Wait ${param("seconds").ifBlank { "1" }}s"
            "device.notify" -> param("title").ifBlank { "Notification" }
            "device.file" -> param("fileName").ifBlank { "state.json" }
            else -> spec.subtitle
        }
    }

    /** True when a Step still needs the user's attention (missing required params). */
    fun needsAttention(spec: NodeSpec?, params: Map<String, Any?>): Boolean {
        if (spec == null) return true
        return spec.params.filter { it.required }.any { params[it.key]?.toString().isNullOrBlank() }
    }
}

/** Maps a spec to the immutable UI model, keeping run status intact. */
fun StepNodeUiModel.withRunStatus(
    status: StepRunStatus,
    itemCount: Int? = null,
    latencyMs: Long? = null,
    attempts: Int = 1,
): StepNodeUiModel = copy(
    status = status,
    itemCount = itemCount ?: this.itemCount,
    latencyMs = latencyMs ?: this.latencyMs,
    attempts = attempts,
)
