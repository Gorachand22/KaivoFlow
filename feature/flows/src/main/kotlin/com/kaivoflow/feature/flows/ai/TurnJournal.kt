package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.AgentEvent

/** Where a turn's durable events go (the app binds it to the session's event log). Failures must not stop the turn. */
fun interface TurnJournalSink {
    suspend fun append(kind: String, payload: String)

    companion object {
        val None = TurnJournalSink { _, _ -> }
    }
}

/**
 * Writes the real events of a turn to the durable log, so a screen can replay what happened and a turn that was cut
 * off by a dead process can be told from a finished one (its last entry is not `turn_ended`). Text and thinking arrive
 * in many small pieces: they are written as one entry when the piece type changes, when about [flushChars] have
 * collected, or when [flushMs] passed. Store-side redaction and caps still apply; this only avoids one row per token.
 */
class TurnJournal(
    private val sink: TurnJournalSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val flushChars: Int = 2_000,
    private val flushMs: Long = 1_000,
) {
    private var pendingKind: String? = null
    private val pending = StringBuilder()
    private var pendingSince = 0L

    suspend fun started(provider: String, model: String, prompt: String) =
        write("turn_started", TurnJson.obj("provider" to provider, "model" to model, "prompt" to prompt.take(2_000)))

    suspend fun on(e: AgentEvent) {
        when (e) {
            is AgentEvent.ThinkingDelta -> buffer("thinking", e.text)
            is AgentEvent.TextDelta -> buffer("text", e.text)
            is AgentEvent.StreamReset -> { pendingKind = null; pending.setLength(0); write("stream_reset", "{}") }
            is AgentEvent.ToolCallReady -> write("tool_call", TurnJson.obj("id" to e.id, "name" to e.name, "arguments" to e.arguments))
            is AgentEvent.ToolFinished -> write("tool_result", TurnJson.obj("id" to e.id, "name" to e.name, "status" to e.status.name, "durationMs" to e.durationMs, "output" to e.output.ifBlank { e.preview }.take(2_000)))
            is AgentEvent.ApprovalRequested -> write("approval_requested", TurnJson.obj("tool" to e.request.toolName, "risk" to e.request.risk.name, "arguments" to e.request.arguments))
            is AgentEvent.ApprovalResolved -> write("approval_resolved", TurnJson.obj("tool" to e.toolName, "approved" to e.approved, "waitedMs" to e.waitedMs))
            is AgentEvent.Notice -> write("notice", TurnJson.obj("kind" to e.kind.name, "message" to e.message))
            is AgentEvent.StepFinished -> write("step", TurnJson.obj("iteration" to e.iteration, "finish" to e.finish.name, "model" to e.model, "tokensIn" to e.usage.promptTokens, "tokensOut" to e.usage.completionTokens))
            is AgentEvent.Error -> write("error", TurnJson.obj("code" to e.error.code, "message" to e.error.message, "status" to e.error.status, "attempts" to e.error.attempts))
            else -> Unit
        }
    }

    /** Last entry of a turn; [status] is one of the [TurnStatus] names in lower case. */
    suspend fun ended(status: TurnStatus, detail: String? = null) =
        write("turn_ended", TurnJson.obj("status" to status.name.lowercase(), "detail" to detail))

    suspend fun flush() = flushPending()

    private suspend fun buffer(kind: String, text: String) {
        if (pendingKind != null && pendingKind != kind) flushPending()
        if (pending.isEmpty()) pendingSince = clock()
        pendingKind = kind
        pending.append(text)
        if (pending.length >= flushChars || clock() - pendingSince >= flushMs) flushPending()
    }

    private suspend fun flushPending() {
        val kind = pendingKind ?: return
        if (pending.isEmpty()) { pendingKind = null; return }
        val text = pending.toString()
        pending.setLength(0); pendingKind = null
        sink.safe(kind, TurnJson.obj("text" to text))
    }

    private suspend fun write(kind: String, payload: String) {
        flushPending()
        sink.safe(kind, payload)
    }

    private suspend fun TurnJournalSink.safe(kind: String, payload: String) {
        try { append(kind, payload) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { /* the log is best effort */ }
    }
}
