package com.kaivoflow.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

enum class RunStatus { Queued, Running, Succeeded, Failed, Canceled, PartialSuccess }

enum class StepRunStatus { Pending, Running, Succeeded, Failed, Skipped, Frozen, Waiting }

/** A completed or in-flight Run of a Flow. */
data class RunRecord(
    val id: String,
    val flowId: String,
    val flowName: String,
    val status: RunStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val trigger: String = "Manual",
    val stepRuns: ImmutableList<StepRun> = persistentListOf(),
    val error: String? = null,
    val triggerData: ImmutableMap<String, Any?> = persistentMapOf(),
) {
    val durationMs: Long get() = (finishedAt ?: System.currentTimeMillis()) - startedAt
    val itemsOut: Int get() = stepRuns.maxOfOrNull { it.itemsOut } ?: 0
}

data class StepRun(
    val stepId: String,
    val stepName: String,
    val status: StepRunStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val itemsIn: Int = 0,
    val itemsOut: Int = 0,
    val error: String? = null,
    val output: ImmutableList<FlowData> = persistentListOf(),
    val attempts: Int = 1,
    /** Items per output port (port index to count); empty for records written before lineage existed. */
    val portCounts: ImmutableMap<Int, Int> = persistentMapOf(),
    /** Input items this Step could not process; 0 unless the error policy let the Step continue. */
    val itemsFailed: Int = 0,
    /** Which execution of the Step inside the Run this record describes. */
    val runIndex: Int = 0,
) {
    /** True when some, but not all, items failed and the Step still delivered output. */
    val isPartial: Boolean get() = itemsFailed > 0 && status == StepRunStatus.Succeeded
    val durationMs: Long? get() = finishedAt?.let { it - startedAt }
}

/** Events emitted by the runner; the UI renders the Golden Current animation from these. */
sealed interface RunEvent {
    val runId: String

    data class RunStarted(override val runId: String, val flowId: String, val totalSteps: Int) : RunEvent
    data class StepStarted(override val runId: String, val stepId: String, val attempt: Int) : RunEvent
    data class StepFinished(
        override val runId: String,
        val stepId: String,
        val itemsIn: Int,
        val itemsOut: Int,
        val durationMs: Long,
        val frozen: Boolean = false,
        val output: ImmutableList<FlowData> = persistentListOf(),
        /** Items per output port (port index to count). */
        val portCounts: ImmutableMap<Int, Int> = persistentMapOf(),
        /** Items that failed under a Continue or Error Flow policy. */
        val itemsFailed: Int = 0,
        val runIndex: Int = 0,
    ) : RunEvent

    data class StepFailed(override val runId: String, val stepId: String, val message: String, val willRetry: Boolean) : RunEvent
    data class StepSkipped(override val runId: String, val stepId: String, val reason: String) : RunEvent
    data class StreamFlowing(override val runId: String, val streamId: String) : RunEvent
    data class RunFinished(
        override val runId: String,
        val status: RunStatus,
        val error: String? = null,
        val durationMs: Long = 0,
    ) : RunEvent
    data class Log(override val runId: String, val message: String, val level: LogLevel = LogLevel.Info) : RunEvent
}

enum class LogLevel { Debug, Info, Warn, Error }

/** A credential reference — the value itself lives in the encrypted vault. */
data class CredentialRef(val id: String, val type: String, val label: String)

/** Data passed into a Run. */
data class RunTrigger(
    val kind: String = "manual",
    /** Items injected into the trigger Step (webhook body, notification text, schedule tick...). */
    val items: ImmutableList<FlowData> = persistentListOf(),
    val meta: ImmutableMap<String, Any?> = persistentMapOf(),
    /** When set on a whole Run, identifies the enabled Spark that received the event. */
    val startAtStepId: String? = null,
    /** Ignore Freeze Output for this Run so the user tests live data. */
    val ignoreFrozen: Boolean = false,
    /** Cap items processed, for interactive testing. */
    val itemLimit: Int? = null,
)
