package com.kaivoflow.core.ai.flowedit

import com.kaivoflow.core.ai.AgentLimits
import com.kaivoflow.core.ai.AgentLoop
import com.kaivoflow.core.ai.AgentOutcome
import com.kaivoflow.core.ai.ApprovalCallback
import com.kaivoflow.core.ai.ApprovalHost
import com.kaivoflow.core.ai.ApprovalPolicy
import com.kaivoflow.core.ai.ChatMessage
import com.kaivoflow.core.ai.ChatModelClient
import com.kaivoflow.core.ai.InMemoryStore
import com.kaivoflow.core.ai.Redactor
import com.kaivoflow.core.ai.ToolRegistry
import com.kaivoflow.core.ai.ToolTrace
import com.kaivoflow.core.ai.Usage
import com.kaivoflow.core.ai.WindowMemory
import com.kaivoflow.core.model.Flow

data class AssistantResult(
    /** What the chat shows, without any fenced block. */
    val text: String,
    /** A Flow the person may apply, or null. Never saved by this class. */
    val proposal: ProposalCheck?,
    val edits: List<DraftEdit>,
    val trace: List<ToolTrace>,
    val usage: Usage,
    /** Null on success; else a safe message (keys are already redacted). */
    val failure: String?,
)

/**
 * One chat turn of the Ask AI assistant: the agent loop with the draft-edit tools over a private copy of the
 * open Flow. It returns the proposal and never writes anywhere; there is no repository in reach.
 */
class AssistantTurn(
    private val model: ChatModelClient,
    private val catalog: AssistantCatalog,
    private val approvals: ApprovalCallback? = null,
    private val redactor: Redactor = Redactor(),
    private val limits: AgentLimits = AgentLimits(maxIterations = 8, maxToolCalls = 40, maxTotalTokens = 80_000, maxWallMs = 120_000, maxOutputTokens = 4_096),
    private val paramCheck: (String, Map<String, Any?>) -> List<String> = { _, _ -> emptyList() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [history] is the earlier conversation as plain text turns, oldest first. */
    suspend fun run(prompt: String, base: Flow?, history: List<ChatMessage>): AssistantResult {
        val start = base ?: Flow(id = newId(), name = "New Flow", createdAt = clock(), updatedAt = clock())
        val draft = FlowDraft(base, start, newId, clock)
        val tools = FlowEditTools(draft, catalog, redactor, paramCheck).all
        val store = InMemoryStore()
        val memory = WindowMemory(store, maxMessages = 12, redactor = redactor)
        val key = "assistant"
        store.write(key, history.filter { it.role != com.kaivoflow.core.ai.Role.Tool }.takeLast(12))
        val loop = AgentLoop(
            model = model,
            tools = ToolRegistry(tools),
            policy = ApprovalPolicy(ApprovalHost.Interactive, approvals, redactor = redactor),
            limits = limits,
            memory = memory,
            redactor = redactor,
            temperature = 0.2,
        )
        val result = loop.run(AssistantPrompt.instructions(catalog), prompt, AssistantPrompt.flowContext(base, redactor), key)
        if (result.outcome != AgentOutcome.Completed && result.text.isBlank() && !draft.changed) {
            return AssistantResult("", null, draft.edits, result.trace, result.usage, result.error?.message ?: "The assistant could not answer.")
        }
        val shown = FlowProposalParser.visibleText(result.text).ifBlank { if (draft.changed) "Here is the proposed change." else "" }
        val proposal: ProposalCheck? = when {
            draft.changed -> {
                val errors = FlowProposalValidator.validate(draft.flow, base, catalog)
                if (errors.isEmpty()) ProposalCheck(draft.flow, emptyList(), draftWarnings(draft.flow)) else ProposalCheck(null, errors, emptyList())
            }
            else -> FlowProposalParser.parse(result.text, base, catalog, newId, clock())
        }
        val warn = if (!result.isSuccess) "The assistant stopped early: ${result.error?.message}" else null
        return AssistantResult(shown, proposal, draft.edits, result.trace, result.usage, if (warn != null && proposal == null) warn else null)
    }

    private fun draftWarnings(flow: Flow): List<String> = buildList {
        if (flow.steps.none { it.isTrigger }) add("The Flow has no Trigger, so it can only be started by hand.")
        flow.steps.filter { !it.isTrigger && flow.streams.none { s -> s.toStepId == it.id } && flow.steps.size > 1 }.forEach { add("\"${it.name}\" has no input connection.") }
    }
}
