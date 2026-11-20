package com.kaivoflow.core.ai

enum class NoticeKind { Retry, Fallback, Continue, Truncated, Budget }

/**
 * What an agent run reports while it works, in the order it happens. A listener (the chat screen, a log) shows real
 * progress from these and nothing else; every run that is not cancelled ends with [Done], preceded by [Error] when
 * it failed. Text of a step that ends in tool calls is narration, not the answer: see [StepFinished.finish].
 */
sealed interface AgentEvent {
    data object RunStarted : AgentEvent

    /** A model call begins. [iteration] counts from 1. */
    data class StepStarted(val iteration: Int) : AgentEvent

    data class TextDelta(val text: String) : AgentEvent
    data class ThinkingDelta(val text: String) : AgentEvent

    /** The model began a tool call; arguments follow as text pieces. */
    data class ToolCallStarted(val index: Int, val id: String, val name: String) : AgentEvent
    data class ToolCallArgsDelta(val index: Int, val delta: String) : AgentEvent

    /** The tool call is complete. [arguments] are redacted for display. */
    data class ToolCallReady(val id: String, val name: String, val arguments: Map<String, Any?>) : AgentEvent

    /** Output shown for this step so far must be dropped (a retry or a fallback model starts over). */
    data object StreamReset : AgentEvent

    data class ApprovalRequested(val request: ApprovalRequest) : AgentEvent
    data class ApprovalResolved(val toolName: String, val approved: Boolean, val feedback: String?, val waitedMs: Long) : AgentEvent

    data class ToolRunning(val id: String, val name: String, val risk: ToolRisk) : AgentEvent
    data class ToolFinished(
        val id: String,
        val name: String,
        val status: ToolStatus,
        val preview: String,
        val durationMs: Long,
        /** What the tool returned (or the error text), redacted and cut to 4,000 characters; empty when it never ran. */
        val output: String = "",
    ) : AgentEvent

    /** A model call ended. [usage] is this call's own; [model] the id that answered. */
    data class StepFinished(val iteration: Int, val finish: FinishReason, val usage: Usage, val model: String?) : AgentEvent

    /** Totals of the run so far. [cost] is honest about unknown prices and estimated tokens. */
    data class UsageUpdate(val usage: Usage, val cost: CostEstimate) : AgentEvent

    data class Notice(val kind: NoticeKind, val message: String) : AgentEvent

    data class Error(val error: AgentError) : AgentEvent

    /** Always the last event of a run that was not cancelled. */
    data class Done(val result: AgentResult) : AgentEvent
}
