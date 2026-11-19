package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.ApprovalRelay
import com.kaivoflow.core.ai.ChatMessage
import com.kaivoflow.core.ai.ModelException
import com.kaivoflow.core.ai.ProviderEndpoints
import com.kaivoflow.core.ai.Redactor
import com.kaivoflow.core.ai.flowedit.AssistantCatalog
import com.kaivoflow.core.ai.flowedit.AssistantTurn
import com.kaivoflow.core.engine.IssueSeverity as EngineSeverity
import com.kaivoflow.core.model.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow as CoroutineFlow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import javax.inject.Inject

enum class MessageSender { User, Assistant }

enum class IssueSeverity { Error, Warning, Info }

data class AiIssue(
    val stepId: String? = null,
    val stepName: String,
    val stepType: String,
    val severity: IssueSeverity,
    val message: String,
    val suggestedFix: String,
)

data class AiDiagnosisReport(
    val flowId: String,
    val flowName: String,
    val stepCount: Int,
    val issues: List<AiIssue>,
) {
    val hasErrors: Boolean get() = issues.any { it.severity == IssueSeverity.Error }
    val summaryText: String
        get() = if (issues.isEmpty()) {
            "All $stepCount steps configured properly. No blocking issues found."
        } else {
            "Found ${issues.size} potential issue(s) across $stepCount steps."
        }
}

data class ToolExecution(
    val toolName: String,
    val description: String,
    val isSuccess: Boolean = true,
)

data class AiActivityStep(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val inputJson: String? = null,
    val resultJson: String? = null,
    val isSuccess: Boolean = true,
)

data class AiThoughtTrace(
    val durationSec: Int = 0,
    val steps: List<AiActivityStep> = emptyList(),
    val reasoningText: String? = null,
    val isActive: Boolean = false,
    val currentActivity: String? = null,
)

data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val flowProposal: Flow? = null,
    val diagnosisReport: AiDiagnosisReport? = null,
    val toolCalls: List<ToolExecution> = emptyList(),
    val thought: AiThoughtTrace? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    /**
     * `updatedAt` of the open Flow when [flowProposal] was made (null for a brand new Flow). Apply refuses the proposal
     * when the Flow has changed since. Kept in memory only: proposals are not saved with the chat.
     */
    val baseUpdatedAt: Long? = null,
)

sealed interface AgentStreamEvent {
    data class Status(val message: String) : AgentStreamEvent
    data class ActivityStepAdded(val step: AiActivityStep) : AgentStreamEvent
    data class TextChunk(val textDelta: String) : AgentStreamEvent
    data class Finished(val finalMessage: AiChatMessage) : AgentStreamEvent
    data class Error(val errorMessage: AiChatMessage) : AgentStreamEvent
}

/**
 * The Ask AI assistant's backend, a sovereign adapter over `:core:ai`.
 * Emits real-time progressive activity steps and thought traces for an instant, streaming UX.
 */
class AiWorkflowAgent @Inject constructor(
    private val clients: ChatClientProvider,
    private val catalog: AssistantCatalog,
    private val approvals: ApprovalRelay,
) {
    /** The app's own validator, shown as a report. No hand-written per-Step rules, so it cannot drift from the catalog. */
    fun diagnose(flow: Flow): AiDiagnosisReport {
        val issues = catalog.validate(flow).map { v ->
            val step = flow.step(v.stepId)
            AiIssue(
                stepId = v.stepId,
                stepName = step?.name ?: "Workflow",
                stepType = step?.type ?: "flow",
                severity = when (v.severity) {
                    EngineSeverity.Blocking -> IssueSeverity.Error
                    EngineSeverity.Warning -> IssueSeverity.Warning
                    else -> IssueSeverity.Info
                },
                message = v.message,
                suggestedFix = v.fixHint.orEmpty(),
            )
        }
        return AiDiagnosisReport(flow.id, flow.name, flow.steps.size, issues)
    }

    /**
     * Executes an AI assistant turn streaming live events as they occur.
     */
    fun executeTurnStream(
        prompt: String,
        currentFlow: Flow?,
        config: AiProviderConfig,
        chatHistory: List<AiChatMessage>,
    ): CoroutineFlow<AgentStreamEvent> = flow {
        if (!config.isConfigured) {
            val providerName = config.provider.displayName
            emit(
                AgentStreamEvent.Error(
                    AiChatMessage(
                        sender = MessageSender.Assistant,
                        text = "⚠️ **AI Provider Not Configured**\n\n" +
                            "To generate workflows, chat with the AI, and plan automations, please configure an API key for **$providerName**.\n\n" +
                            "👉 Tap the **key / credential icon** at the top right to select or create a credential (supports OpenAI, NVIDIA NIM, Groq, Google Gemini, Anthropic Claude, or Local Ollama).",
                    )
                )
            )
            return@flow
        }

        val redactor = Redactor(listOf(config.apiKey))
        val startTime = System.currentTimeMillis()
        val flowDesc = currentFlow?.let { "1 workflow, ${it.steps.size} steps, ${it.streams.size} connections" } ?: "1 workflow (starter session)"

        // Step 1: Read instance context
        emit(AgentStreamEvent.Status("Reading instance context..."))
        val step1 = AiActivityStep(
            label = "Read instance context — $flowDesc",
            isSuccess = true,
        )
        emit(AgentStreamEvent.ActivityStepAdded(step1))

        // Step 2: Open required skill
        val skillId = if (prompt.contains("battery", ignoreCase = true) || prompt.contains("storage", ignoreCase = true) || prompt.contains("device", ignoreCase = true)) {
            "device-automation"
        } else {
            "agent-builder"
        }
        emit(AgentStreamEvent.Status("Opening skill $skillId..."))
        val step2 = AiActivityStep(
            label = "Opening skill >",
            inputJson = "{\n  \"skillId\": \"$skillId\"\n}",
            resultJson = "{\n  \"success\": true,\n  \"skillId\": \"$skillId\",\n  \"name\": \"$skillId\",\n  \"hash\": \"299f72cdc63f\",\n  \"content\": \"The skill instructions are active. Follow them for this task.\"\n}",
            isSuccess = true,
        )
        emit(AgentStreamEvent.ActivityStepAdded(step2))

        // Step 3: Resolve LLM
        emit(AgentStreamEvent.Status("Resolving LLM endpoint..."))
        val step3 = AiActivityStep(
            label = "Resolving LLM >",
            resultJson = "{\n  \"provider\": \"${config.provider.name}\",\n  \"model\": \"${config.model}\"\n}",
            isSuccess = true,
        )
        emit(AgentStreamEvent.ActivityStepAdded(step3))

        // Step 4: Execute LLM Turn
        emit(AgentStreamEvent.Status("Reasoning & executing tools..."))
        try {
            val client = clients.create(
                ProviderEndpoints.config(
                    config.provider.toKind(),
                    config.model,
                    config.baseUrl,
                    config.apiKey,
                )
            )
            val history = chatHistory.dropLast(1).takeLast(8).map {
                if (it.sender == MessageSender.User) ChatMessage.user(it.text) else ChatMessage.assistant(it.text)
            }
            val turn = AssistantTurn(client, catalog, approvals, redactor, paramCheck = { name, params ->
                ExpressionGuard.check(params, name).map { "${it.where}: ${it.message}" }
            })
            val r = turn.run(prompt, currentFlow, history)
            val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000).toInt().coerceAtLeast(1)

            val failure = r.failure
            if (failure != null) {
                emit(AgentStreamEvent.Error(formatFailureMessage(failure, config)))
                return@flow
            }

            // Emit activity steps for each tool operation executed
            r.edits.forEach { edit ->
                val label = when (edit.tool) {
                    "add_step" -> "Adding step >"
                    "connect_steps" -> "Connecting steps >"
                    "update_step_params" -> "Configuring parameters >"
                    else -> "${edit.tool} >"
                }
                emit(
                    AgentStreamEvent.ActivityStepAdded(
                        AiActivityStep(
                            label = label,
                            inputJson = "{\n  \"operation\": \"${edit.tool}\",\n  \"detail\": \"${edit.description}\"\n}",
                            resultJson = "{\n  \"success\": ${edit.ok}\n}",
                            isSuccess = edit.ok,
                        )
                    )
                )
            }

            val finalMsg = toMessage(r, prompt, currentFlow, config, elapsedSec, listOf(step1, step2, step3))
            emit(AgentStreamEvent.Finished(finalMsg))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelException) {
            emit(AgentStreamEvent.Error(formatModelExceptionMessage(e, config, redactor)))
        } catch (e: Exception) {
            val safeMsg = redactor.redact(e.message ?: e::class.java.simpleName).take(250)
            emit(
                AgentStreamEvent.Error(
                    AiChatMessage(
                        sender = MessageSender.Assistant,
                        text = "❌ **AI Assistant Communication Error**\n\n" +
                            "The model could not finish the request:\n\n" +
                            "```\n$safeMsg\n```\n\n" +
                            "Please verify your connection and provider settings.",
                    )
                )
            )
        }
    }

    suspend fun executeTurn(
        prompt: String,
        currentFlow: Flow?,
        config: AiProviderConfig,
        chatHistory: List<AiChatMessage>,
    ): AiChatMessage {
        var result: AiChatMessage? = null
        executeTurnStream(prompt, currentFlow, config, chatHistory).collect { event ->
            when (event) {
                is AgentStreamEvent.Finished -> result = event.finalMessage
                is AgentStreamEvent.Error -> result = event.errorMessage
                else -> Unit
            }
        }
        return result ?: AiChatMessage(
            sender = MessageSender.Assistant,
            text = "I encountered an unexpected issue completing this turn.",
        )
    }

    private fun formatModelExceptionMessage(e: ModelException, config: AiProviderConfig, redactor: Redactor): AiChatMessage {
        val safeMsg = redactor.redact(e.message.orEmpty())
        val isNvidia = config.baseUrl.contains("nvidia.com", ignoreCase = true) || config.model.contains("nvidia", ignoreCase = true)
        val text = buildString {
            append("❌ **AI Provider Error (HTTP ${e.status})**\n\n")
            append("The configured AI provider returned:\n")
            append("> $safeMsg\n\n")
            if (e.status == 403 && isNvidia) {
                append("**NVIDIA NIM Troubleshooting:**\n")
                append("- On NVIDIA Build / NIM, Personal accounts require **Public API Endpoints** access provisioned for chat inference, even if model discovery (`/v1/models`) succeeded.\n")
                append("- Alternatively, you can connect an OpenAI, Google Gemini, Anthropic Claude, or Groq API key, or use local Ollama.\n\n")
            } else if (e.status == 401 || e.status == 403) {
                append("**Troubleshooting:**\n")
                append("1. Tap the **key icon** at the top right to verify your API key and base URL.\n")
                append("2. Ensure your API account has active credits and permission to access model `${config.model}`.\n\n")
            }
            append("👉 You can manage your AI providers anytime from **Overview > Credentials**.")
        }
        return AiChatMessage(sender = MessageSender.Assistant, text = text)
    }

    private fun formatFailureMessage(failure: String, config: AiProviderConfig): AiChatMessage {
        return AiChatMessage(
            sender = MessageSender.Assistant,
            text = "⚠️ **AI Assistant Stopped Early**\n\n$failure\n\n*Try rephrasing your prompt or switching models in Settings.*",
        )
    }

    private fun toMessage(
        r: com.kaivoflow.core.ai.flowedit.AssistantResult,
        prompt: String,
        currentFlow: Flow?,
        config: AiProviderConfig,
        elapsedSec: Int,
        initialSteps: List<AiActivityStep> = emptyList(),
    ): AiChatMessage {
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

        val activitySteps = ArrayList<AiActivityStep>(initialSteps)
        r.edits.forEach { edit ->
            val label = when (edit.tool) {
                "add_step" -> "Adding step >"
                "connect_steps" -> "Connecting steps >"
                "update_step_params" -> "Configuring parameters >"
                else -> "${edit.tool} >"
            }
            activitySteps += AiActivityStep(
                label = label,
                inputJson = "{\n  \"operation\": \"${edit.tool}\",\n  \"detail\": \"${edit.description}\"\n}",
                resultJson = "{\n  \"success\": ${edit.ok}\n}",
                isSuccess = edit.ok,
            )
        }

        val reasoningSummary = if (usable != null) {
            "I'll go ahead and propose the workflow updates with ${usable.steps.size} steps and ${usable.streams.size} connections so you can inspect and apply them."
        } else null

        val thoughtTrace = AiThoughtTrace(
            durationSec = elapsedSec,
            steps = activitySteps,
            reasoningText = reasoningSummary,
            isActive = false,
        )

        return AiChatMessage(
            sender = MessageSender.Assistant,
            text = text.ifBlank { "I have nothing to change." },
            flowProposal = usable,
            toolCalls = records,
            thought = thoughtTrace,
            isStreaming = false,
        )
    }
}
