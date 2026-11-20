package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.AgentError
import com.kaivoflow.core.ai.AgentEvent
import com.kaivoflow.core.ai.CostEstimate
import com.kaivoflow.core.ai.FinishReason
import com.kaivoflow.core.ai.NoticeKind
import com.kaivoflow.core.ai.ToolStatus
import com.kaivoflow.core.ai.Usage
import com.kaivoflow.core.ai.flowedit.AssistantResult
import com.kaivoflow.core.ai.flowedit.FlowProposalParser

/** How a chat turn ended (or that it has not). Saved with the message, so a turn cut off by a dead process is told apart. */
enum class TurnStatus { Running, Completed, Stopped, Failed, Interrupted }

/**
 * Everything the chat shows about one running turn, built only from what the agent loop reports. There is no
 * scripted step: every step is a real notice or tool call, thinking text is what the provider sent, and the clock is
 * real time with the time spent waiting for the person's approval taken out.
 */
data class TurnState(
    val startedAtMs: Long,
    val status: TurnStatus = TurnStatus.Running,
    val pausedMs: Long = 0,
    val approvalSinceMs: Long? = null,
    /** Elapsed time frozen when the turn ended. */
    val frozenElapsedMs: Long? = null,
    val steps: List<AiActivityStep> = emptyList(),
    val committedThinking: List<String> = emptyList(),
    val stepThinking: String = "",
    /** Text of model steps that ended in tool calls: narration, shown with the reasoning and not as the answer. */
    val narration: List<String> = emptyList(),
    val stepText: String = "",
    val activity: String? = "Contacting the model...",
    val runs: Int = 0,
    val iteration: Int = 0,
    val lastFinish: FinishReason? = null,
    val callSteps: Map<String, String> = emptyMap(),
    val indexSteps: Map<Int, String> = emptyMap(),
    val rawArgs: Map<Int, String> = emptyMap(),
    val usage: Usage = Usage(),
    val cost: CostEstimate? = null,
    val error: AgentError? = null,
    val nextId: Int = 1,
) {
    /** Real working time: the wait for an approval card is not counted, also while it is still open. */
    fun elapsedMs(now: Long): Long {
        frozenElapsedMs?.let { return it }
        val open = approvalSinceMs?.let { (now - it).coerceAtLeast(0) } ?: 0L
        return (now - startedAtMs - pausedMs - open).coerceAtLeast(0)
    }
}

/** Pure functions from the agent's event stream to the chat's turn model. No clock, no I/O: the caller passes `now`. */
object AssistantTurnReducer {
    const val MAX_PAYLOAD_CHARS = 8_000

    fun start(now: Long): TurnState = TurnState(startedAtMs = now)

    fun toolLabel(name: String): String = when (name) {
        "add_step" -> "Adding step >"
        "connect_steps" -> "Connecting steps >"
        "update_step_params" -> "Configuring parameters >"
        else -> "$name >"
    }

    fun reduce(s: TurnState, e: AgentEvent, now: Long): TurnState = when (e) {
        is AgentEvent.RunStarted -> {
            val second = s.runs >= 1
            val withStep = if (second) addStep(s, AiActivityStep(id = "step-${s.nextId}", label = "Checking the draft: asking the model to fix validation problems >")) else s
            withStep.copy(runs = s.runs + 1, activity = if (second) "Fixing validation problems..." else s.activity)
        }

        is AgentEvent.StepStarted -> s.copy(
            iteration = e.iteration,
            callSteps = emptyMap(), indexSteps = emptyMap(), rawArgs = emptyMap(),
            stepThinking = "",
            // After a length cut the next call continues the same answer text.
            stepText = if (s.lastFinish == FinishReason.Length) s.stepText else "",
            activity = if (e.iteration <= 1 && s.runs <= 1) "Waiting for the model..." else "Model is working (step ${e.iteration})...",
        )

        is AgentEvent.ThinkingDelta -> s.copy(stepThinking = s.stepThinking + e.text, activity = "Thinking...")
        is AgentEvent.TextDelta -> s.copy(stepText = s.stepText + e.text, activity = "Writing the answer...")

        is AgentEvent.ToolCallStarted -> {
            val id = "step-${s.nextId}"
            val step = AiActivityStep(id = id, label = toolLabel(e.name), callId = e.id, running = true)
            addStep(s, step).copy(
                callSteps = s.callSteps + (e.id to id),
                indexSteps = s.indexSteps + (e.index to id),
                rawArgs = s.rawArgs + (e.index to ""),
                activity = "Calling ${e.name}...",
            )
        }

        is AgentEvent.ToolCallArgsDelta -> {
            val stepId = s.indexSteps[e.index]
            if (stepId == null) s else {
                val raw = (s.rawArgs[e.index].orEmpty() + e.delta).take(MAX_PAYLOAD_CHARS)
                // Partial JSON is shown as it is; it is the real text received so far.
                updateStep(s, stepId) { it.copy(inputJson = raw) }.copy(rawArgs = s.rawArgs + (e.index to raw))
            }
        }

        is AgentEvent.ToolCallReady -> {
            val (s1, stepId) = stepFor(s, e.id, e.name)
            updateStep(s1, stepId) { it.copy(inputJson = TurnJson.pretty(e.arguments).take(MAX_PAYLOAD_CHARS)) }
        }

        is AgentEvent.StreamReset -> {
            // Everything the failed attempt showed goes: its text, thinking and tool calls that were never run.
            val dropped = s.callSteps.values.toSet() + s.indexSteps.values.toSet()
            s.copy(
                steps = s.steps.filterNot { it.id in dropped && it.running },
                stepThinking = "", stepText = if (s.lastFinish == FinishReason.Length) s.stepText else "",
                callSteps = emptyMap(), indexSteps = emptyMap(), rawArgs = emptyMap(),
                activity = "Retrying the model call...",
            )
        }

        is AgentEvent.ApprovalRequested -> s.copy(approvalSinceMs = now, activity = "Waiting for your approval: ${e.request.toolName}")

        is AgentEvent.ApprovalResolved -> s.copy(
            pausedMs = s.pausedMs + e.waitedMs.coerceAtLeast(0),
            approvalSinceMs = null,
            activity = if (e.approved) "Running ${e.toolName}..." else "${e.toolName} was not allowed",
        )

        is AgentEvent.ToolRunning -> {
            val (s1, stepId) = stepFor(s, e.id, e.name)
            updateStep(s1, stepId) { it.copy(running = true) }.copy(activity = "Running ${e.name}...")
        }

        is AgentEvent.ToolFinished -> {
            val (s1, stepId) = stepFor(s, e.id, e.name)
            val result = e.output.ifBlank { e.preview }.take(MAX_PAYLOAD_CHARS)
            updateStep(s1, stepId) {
                it.copy(running = false, isSuccess = e.status == ToolStatus.Ok, resultJson = result.ifBlank { null }, durationMs = e.durationMs)
            }.copy(activity = if (e.status == ToolStatus.Ok) "${e.name} finished" else "${e.name} failed")
        }

        is AgentEvent.StepFinished -> {
            val thinking = if (s.stepThinking.isBlank()) s.committedThinking else s.committedThinking + s.stepThinking.trim()
            if (e.finish == FinishReason.ToolCalls) {
                s.copy(
                    committedThinking = thinking, stepThinking = "",
                    narration = if (s.stepText.isBlank()) s.narration else s.narration + s.stepText.trim(),
                    stepText = "", lastFinish = e.finish,
                )
            } else {
                s.copy(committedThinking = thinking, stepThinking = "", lastFinish = e.finish)
            }
        }

        is AgentEvent.UsageUpdate -> s.copy(usage = e.usage, cost = e.cost)

        is AgentEvent.Notice -> {
            val ok = e.kind == NoticeKind.Retry || e.kind == NoticeKind.Fallback || e.kind == NoticeKind.Continue
            addStep(s, AiActivityStep(id = "step-${s.nextId}", label = e.message, isSuccess = ok)).copy(activity = e.message)
        }

        is AgentEvent.Error -> s.copy(error = e.error)
        is AgentEvent.Done -> s.copy(usage = e.result.usage, cost = e.result.cost ?: s.cost)
    }

    // ---- building the chat messages ----

    /** The message to show while the turn runs. */
    fun live(s: TurnState, id: String, now: Long): AiChatMessage = AiChatMessage(
        id = id,
        sender = MessageSender.Assistant,
        text = LiveText.visible(s.stepText),
        thought = trace(s, now, active = s.status == TurnStatus.Running),
        isStreaming = s.status == TurnStatus.Running,
        status = s.status,
        timestamp = s.startedAtMs,
    )

    fun trace(s: TurnState, now: Long, active: Boolean): AiThoughtTrace = AiThoughtTrace(
        durationSec = (s.elapsedMs(now) / 1000).toInt(),
        steps = s.steps,
        reasoningText = reasoning(s),
        isActive = active,
        currentActivity = if (active) s.activity else null,
    )

    /** Real thinking text first, then what the model said between tool calls. Null when there is none. */
    fun reasoning(s: TurnState): String? {
        val parts = s.committedThinking + listOf(s.stepThinking.trim()) + s.narration
        return parts.filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }
    }

    /** Ends a turn that stopped for any reason other than finishing: partial text is kept and the end is marked. */
    fun ended(s: TurnState, status: TurnStatus, now: Long): TurnState {
        val closedSteps = s.steps.map { st ->
            if (st.running) st.copy(running = false, isSuccess = false, resultJson = st.resultJson ?: "Not finished: ${endNote(status)}.") else st
        }
        return s.copy(status = status, steps = closedSteps, frozenElapsedMs = s.elapsedMs(now), approvalSinceMs = null, activity = null)
    }

    fun endNote(status: TurnStatus): String = when (status) {
        TurnStatus.Stopped -> "stopped by you"
        TurnStatus.Interrupted -> "the app closed before it finished"
        TurnStatus.Failed -> "the request failed"
        else -> ""
    }

    /**
     * The message of a turn that finished. [s] is the state after [ended] with Completed; the steps and the reasoning
     * are the real ones collected while it ran. A turn that never called the model (the canned greeting) has no
     * thought block at all.
     */
    fun completed(s: TurnState, r: AssistantResult, id: String, now: Long): AiChatMessage {
        val check = r.proposal
        val usable = check?.takeIf { it.isUsable }?.flow
        val records = ArrayList<ToolExecution>()
        r.edits.forEach { records += ToolExecution(it.tool, it.description, it.ok) }
        check?.warnings?.forEach { records += ToolExecution("check", it, isSuccess = false) }
        usable?.let { f -> ExpressionGuard.check(f).forEach { p -> records += ToolExecution("check_expression", "Expression problem in ${p.where}: ${p.message}", isSuccess = false) } }

        val text = buildString {
            append(r.text.ifBlank { if (usable != null) "Here is the updated workflow proposal:" else "" })
            if (check != null && !check.isUsable) {
                append("\n\nI could not use the Flow I wrote, so nothing was changed:\n")
                check.errors.take(5).forEach { append("- ").append(it).append('\n') }
            }
        }.trim()

        val nothingHappened = s.steps.isEmpty() && reasoning(s) == null && s.usage.isEmpty
        return AiChatMessage(
            id = id,
            sender = MessageSender.Assistant,
            text = text.ifBlank { "I have nothing to change." },
            flowProposal = usable,
            toolCalls = records,
            thought = if (nothingHappened) null else trace(s, now, active = false),
            isStreaming = false,
            status = TurnStatus.Completed,
            timestamp = s.startedAtMs,
        )
    }

    /**
     * The message of a turn that did not finish. [s] comes from the last progress the screen got; the partial text stays,
     * with one line that says how it ended.
     */
    fun endedMessage(s: TurnState, id: String, status: TurnStatus, now: Long, errorText: String? = null): AiChatMessage {
        val e = ended(s, status, now)
        val partial = LiveText.visible(e.stepText)
        val mark = when (status) {
            TurnStatus.Stopped -> "*Stopped.*"
            TurnStatus.Interrupted -> "*Interrupted: the app closed before this answer finished.*"
            else -> null
        }
        val text = listOfNotNull(partial.ifBlank { null }, errorText, mark).joinToString("\n\n")
        return AiChatMessage(
            id = id, sender = MessageSender.Assistant, text = text,
            thought = trace(e, now, active = false), isStreaming = false, status = status, timestamp = s.startedAtMs,
        )
    }

    /**
     * Same as [endedMessage] for a message that was saved while its turn ran (no reducer state any more): the
     * process died, so the saved partial text stays and the turn is marked.
     */
    fun markInterrupted(m: AiChatMessage): AiChatMessage {
        if (m.status != TurnStatus.Running) return m
        val note = "*Interrupted: the app closed before this answer finished.*"
        return m.copy(text = if (m.text.isBlank()) note else m.text + "\n\n" + note, thought = null, isStreaming = false, status = TurnStatus.Interrupted)
    }

    /**
     * The message of a turn that was cut short while the app still had it ([status] Stopped or Interrupted), from the
     * last message the screen showed: partial text and the real steps stay, running steps are closed, one line says how
     * it ended.
     */
    fun markCut(m: AiChatMessage, status: TurnStatus): AiChatMessage {
        val t = m.thought
        val note = endNote(status)
        val closed = t?.copy(
            isActive = false, currentActivity = null,
            steps = t.steps.map { st -> if (st.running) st.copy(running = false, isSuccess = false, resultJson = st.resultJson ?: "Not finished: $note.") else st },
        )
        val mark = when (status) {
            TurnStatus.Stopped -> "*Stopped.*"
            TurnStatus.Interrupted -> "*Interrupted: the app closed before this answer finished.*"
            else -> null
        }
        val partial = m.text.trim()
        return m.copy(text = listOfNotNull(partial.ifEmpty { null }, mark).joinToString("\n\n"), thought = closed, isStreaming = false, status = status)
    }

    // ---- helpers ----

    private fun addStep(s: TurnState, step: AiActivityStep): TurnState = s.copy(steps = s.steps + step, nextId = s.nextId + 1)

    private fun updateStep(s: TurnState, id: String, f: (AiActivityStep) -> AiActivityStep): TurnState =
        s.copy(steps = s.steps.map { if (it.id == id) f(it) else it })

    /** The step of call [callId] in this model step, created when the loop reports a call that never streamed. */
    private fun stepFor(s: TurnState, callId: String, name: String): Pair<TurnState, String> {
        s.callSteps[callId]?.let { return s to it }
        val id = "step-${s.nextId}"
        val added = addStep(s, AiActivityStep(id = id, label = toolLabel(name), callId = callId, running = true))
        return added.copy(callSteps = s.callSteps + (callId to id)) to id
    }
}

/** What to show of answer text that is still arriving. */
object LiveText {
    /**
     * The text without a finished ```kaivo flow block and without a flow block that is still open (its JSON is for the
     * proposal card, not for reading). Other code blocks show as they stream.
     */
    fun visible(text: String): String {
        val closedRemoved = FlowProposalParser.visibleText(text)
        val open = Regex("```(kaivo|json)[^\\n]*(\\n|$)", RegexOption.IGNORE_CASE).findAll(closedRemoved).lastOrNull() ?: return closedRemoved
        val fencesBefore = Regex("```").findAll(closedRemoved.substring(0, open.range.first)).count()
        // An even number of fences before it means this one opens a block that has not been closed.
        val closedAfter = closedRemoved.indexOf("```", open.range.last + 1) >= 0
        return if (fencesBefore % 2 == 0 && !closedAfter) closedRemoved.substring(0, open.range.first).trimEnd() else closedRemoved
    }
}
