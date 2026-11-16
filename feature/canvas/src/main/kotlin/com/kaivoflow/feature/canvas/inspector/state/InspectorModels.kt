package com.kaivoflow.feature.canvas.inspector.state

import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.model.ParamIssue
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.ParamValue
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList

// Pure state of the Step inspector (spec P2 section 4.2). No Android, no Compose.

enum class InspectorPage { Input, Parameters, Output }
enum class ParamsTab { Parameters, Behaviour, About }
enum class PanelSide { Input, Output }
enum class DataViewMode { Schema, Table, Json, Binary }
enum class ParentPolicy { Ask, RunThem, UsePinnedOrLast }

/** Which stored execution a panel shows. */
sealed interface RunSelection {
    data object Latest : RunSelection
    data class Specific(val runId: String, val runIndex: Int = 0) : RunSelection
    data object Pinned : RunSelection
}

data class PageRequest(val offset: Int = 0, val size: Int = DEFAULT_SIZE) {
    companion object {
        const val DEFAULT_SIZE = 25
        val Sizes = listOf(10, 25, 50, 100)
    }
}

data class SourceChip(val stepId: String, val name: String, val itemCount: Int? = null, val lastRunAt: Long? = null)
data class PortChip(val port: Int, val label: String? = null, val itemCount: Int? = null)
data class RunChip(val runId: String, val startedAt: Long, val failed: Boolean = false)

/** What a data load found; the items themselves are paged by the UI layer, not kept here. */
data class DataSummary(
    val itemCount: Int,
    val storedCount: Int,
    val truncated: Boolean = false,
    val hasBinary: Boolean = false,
)

sealed interface DataStatus {
    /** Input side with no parent connected. */
    data object NoConnection : DataStatus
    data object Idle : DataStatus
    data object Loading : DataStatus
    data class Loaded(val summary: DataSummary) : DataStatus
    data class Failed(val message: String) : DataStatus
}

data class DataPanelState(
    val side: PanelSide,
    /** Selected parent (Input) or the open Step (Output). */
    val sourceStepId: String? = null,
    val sources: ImmutableList<SourceChip> = persistentListOf(),
    val port: Int = 0,
    val ports: ImmutableList<PortChip> = persistentListOf(),
    val run: RunSelection = RunSelection.Latest,
    val runs: ImmutableList<RunChip> = persistentListOf(),
    val mode: DataViewMode = DataViewMode.Table,
    val page: PageRequest = PageRequest(),
    /** Text in the search box as typed. */
    val search: String = "",
    /** The search text the shown data is filtered by (set after the debounce). */
    val appliedSearch: String = "",
    val status: DataStatus = DataStatus.Idle,
    /** Bumped for every load so a late result of an older load is ignored. */
    val loadToken: Int = 0,
) {
    val summary: DataSummary? get() = (status as? DataStatus.Loaded)?.summary
}

/** What a data load is asked to fetch. */
data class DataRequest(
    val side: PanelSide,
    val stepId: String?,
    val run: RunSelection,
    val port: Int,
    val page: PageRequest,
    val search: String,
)

data class PinInfo(val itemCount: Int, val bytes: Long, val pinnedAt: Long)

/** The last parameter edit, kept so it can be undone. */
data class ParamEdit(val key: String, val before: ParamValue?, val after: ParamValue?, val reason: EditReason)
enum class EditReason { User, Toggle, Drop, Insert }

sealed interface PreviewState {
    data class Value(val rendered: String, val type: String) : PreviewState
    data class Pending(val message: String) : PreviewState
    data class Error(val message: String, val start: Int? = null, val end: Int? = null) : PreviewState
}

/** A mapping drag in progress. [reference] is the text a drop would insert. */
data class DragState(
    val source: MapSource,
    val reference: String,
    val x: Float,
    val y: Float,
    val hoverTarget: ParamPath? = null,
    val eligibleTargets: Set<ParamPath> = emptySet(),
)

sealed interface TestState {
    data object Idle : TestState
    data class Running(val stepIds: List<String>) : TestState
    data class NeedsParents(val stepIds: List<String>) : TestState
    data class Finished(val executionKey: String?) : TestState
    data class Failed(val message: String, val itemIndex: Int? = null, val hasPartialData: Boolean = false) : TestState
}

data class InspectorState(
    val stepId: String,
    /** Non-null when a past Run is shown (replay): everything that edits is disabled. */
    val readOnlyRun: String? = null,
    val page: InspectorPage = InspectorPage.Parameters,
    val paramsTab: ParamsTab = ParamsTab.Parameters,
    val schema: ParamSchema = ParamSchema.Empty,
    val credentialsFilled: Set<String> = emptySet(),
    val values: ImmutableMap<String, ParamValue?> = persistentMapOf(),
    val lastEdit: ParamEdit? = null,
    val issues: ImmutableList<ParamIssue> = persistentListOf(),
    val previews: ImmutableMap<String, PreviewState> = persistentMapOf(),
    val previewItem: Int = 0,
    val focusedField: ParamPath? = null,
    val mapperVisible: Boolean = false,
    val drag: DragState? = null,
    val input: DataPanelState = DataPanelState(PanelSide.Input),
    val output: DataPanelState = DataPanelState(PanelSide.Output, sourceStepId = stepId),
    val pin: PinInfo? = null,
    val test: TestState = TestState.Idle,
) {
    val readOnly: Boolean get() = readOnlyRun != null
    val issueCount: Int get() = issues.size
    val canUndo: Boolean get() = lastEdit != null && !readOnly

    fun panel(side: PanelSide): DataPanelState = if (side == PanelSide.Input) input else output
    fun withPanel(side: PanelSide, p: DataPanelState): InspectorState = if (side == PanelSide.Input) copy(input = p) else copy(output = p)

    companion object {
        /** Fresh state for opening [stepId]. With [replayRunId] both panels start on that Run and the inspector is read-only. */
        fun initial(stepId: String, replayRunId: String? = null): InspectorState {
            val run: RunSelection = if (replayRunId != null) RunSelection.Specific(replayRunId) else RunSelection.Latest
            return InspectorState(
                stepId = stepId,
                readOnlyRun = replayRunId,
                page = if (replayRunId != null) InspectorPage.Output else InspectorPage.Parameters,
                input = DataPanelState(PanelSide.Input, run = run),
                output = DataPanelState(PanelSide.Output, sourceStepId = stepId, run = run),
            )
        }
    }
}

enum class InspectorMessage { DropRejected, ExpressionNotAllowed, FixedNotAllowed, PinRefusedTruncated, NothingToPin, PinFailed, ReadOnly }

sealed interface InspectorTestEvent {
    data class Progress(val stepIds: List<String>) : InspectorTestEvent
    data class NeedsParents(val stepIds: List<String>) : InspectorTestEvent
    data class Finished(val executionKey: String?) : InspectorTestEvent
    data class Failed(val message: String, val itemIndex: Int? = null, val hasPartialData: Boolean = false) : InspectorTestEvent
}

sealed interface InspectorIntent {
    data class SelectPage(val page: InspectorPage) : InspectorIntent
    data class SelectParamsTab(val tab: ParamsTab) : InspectorIntent
    /** Reload both data panels (for example right after opening). */
    data object Refresh : InspectorIntent

    // parameters
    data class ParamsLoaded(val schema: ParamSchema, val values: Map<String, ParamValue?>, val credentialsFilled: Set<String> = emptySet()) : InspectorIntent
    data class EditParam(val key: String, val value: ParamValue?) : InspectorIntent
    data class SwitchToExpression(val key: String) : InspectorIntent
    /** [literal] is the value to keep as fixed (the evaluated preview, or null to clear). */
    data class SwitchToFixed(val key: String, val literal: Any?) : InspectorIntent
    data object UndoParamEdit : InspectorIntent
    data class FocusField(val path: ParamPath?) : InspectorIntent
    data object JumpToNextIssue : InspectorIntent
    data class PreviewComputed(val key: String, val preview: PreviewState) : InspectorIntent
    data class SelectPreviewItem(val index: Int) : InspectorIntent

    // mapping
    data class SetMapperVisible(val visible: Boolean) : InspectorIntent
    data class BeginDrag(val source: MapSource, val x: Float = 0f, val y: Float = 0f) : InspectorIntent
    data class DragMoved(val x: Float, val y: Float, val hover: ParamPath?) : InspectorIntent
    /** Drop on the hovered target; [selStart] and [selEnd] are the caret or selection in that field, -1 when unknown. */
    data class DragDropped(val selStart: Int = -1, val selEnd: Int = -1) : InspectorIntent
    data object DragCancelled : InspectorIntent
    data class DragEdgeDwell(val page: InspectorPage) : InspectorIntent
    /** Tap-to-insert: produces exactly what a drop on [target] would. */
    data class InsertReference(val target: ParamPath, val source: MapSource, val selStart: Int = -1, val selEnd: Int = -1) : InspectorIntent

    // data panels
    data class ParentsChanged(val parents: List<SourceChip>) : InspectorIntent
    data class SelectInputParent(val stepId: String) : InspectorIntent
    data class RunsChanged(val side: PanelSide, val runs: List<RunChip>) : InspectorIntent
    data class SelectRun(val side: PanelSide, val run: RunSelection) : InspectorIntent
    data class PortsChanged(val side: PanelSide, val ports: List<PortChip>) : InspectorIntent
    data class SelectPort(val side: PanelSide, val port: Int) : InspectorIntent
    data class SetViewMode(val side: PanelSide, val mode: DataViewMode) : InspectorIntent
    data class SetPageRequest(val side: PanelSide, val offset: Int, val size: Int = PageRequest.DEFAULT_SIZE) : InspectorIntent
    data class SearchChanged(val side: PanelSide, val text: String) : InspectorIntent
    data class SearchCommitted(val side: PanelSide, val text: String) : InspectorIntent
    data class DataLoaded(val side: PanelSide, val token: Int, val summary: DataSummary) : InspectorIntent
    data class DataFailed(val side: PanelSide, val token: Int, val message: String) : InspectorIntent
    /** A Run wrote new data for [stepId]. */
    data class StepDataChanged(val stepId: String) : InspectorIntent

    // pin and test
    data object PinCurrent : InspectorIntent
    data object Unpin : InspectorIntent
    data class PinChanged(val info: PinInfo?) : InspectorIntent
    data class TestStepRequested(val policy: ParentPolicy = ParentPolicy.Ask) : InspectorIntent
    data class TestEventReceived(val event: InspectorTestEvent) : InspectorIntent
    /** Stops a running or waiting Test Step and returns to idle. */
    data object CancelTest : InspectorIntent
}

/** Work the reducer asks the holder to do; the reducer itself stays pure. */
sealed interface InspectorEffect {
    data class LoadData(val request: DataRequest, val token: Int) : InspectorEffect
    data class ScheduleSearchCommit(val side: PanelSide, val text: String, val delayMs: Long) : InspectorEffect
    data class EvaluatePreview(val key: String, val text: String, val itemIndex: Int) : InspectorEffect
    data class PersistParam(val key: String, val value: ParamValue?) : InspectorEffect
    data class RunTest(val stepId: String, val policy: ParentPolicy) : InspectorEffect
    data object CancelTest : InspectorEffect
    data class PinCurrent(val request: DataRequest) : InspectorEffect
    data object Unpin : InspectorEffect
    data class Notify(val message: InspectorMessage) : InspectorEffect
    /** A drop on a container (assignments, key-value list, conditions, repeater): the widget creates the row. */
    data class CreateRowFromDrop(val target: ParamPath, val source: MapSource, val reference: String) : InspectorEffect
}

internal fun <T> List<T>.imm(): ImmutableList<T> = toImmutableList()
