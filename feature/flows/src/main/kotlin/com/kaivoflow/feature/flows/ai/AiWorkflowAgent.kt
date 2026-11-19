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

data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val flowProposal: Flow? = null,
    val diagnosisReport: AiDiagnosisReport? = null,
    val toolCalls: List<ToolExecution> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * `updatedAt` of the open Flow when [flowProposal] was made (null for a brand new Flow). Apply refuses the proposal
     * when the Flow has changed since. Kept in memory only: proposals are not saved with the chat.
     */
    val baseUpdatedAt: Long? = null,
)

/**
 * The Ask AI assistant's backend, now a thin adapter over `:core:ai`.
 *
 * The model runs in `AgentLoop` (iteration, token and time limits, redaction, untrusted-data fencing) with edit
 * tools that change a private draft copy of the open Flow. The result is a PROPOSAL: this class never saves, and
 * it has no repository. The view model keeps the proposal in memory and saves only when the person taps Apply.
 * Step ids, settings and the prompt's Step list come from the real catalog ([AssistantCatalog]); the provider
 * clients, base-URL guard and key handling are `:core:ai`'s.
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
     * Executes an AI assistant turn using the configured model and tools.
     * With no configured model or on provider error, returns an honest diagnostic message.
     */
    suspend fun executeTurn(
        prompt: String,
        currentFlow: Flow?,
        config: AiProviderConfig,
        chatHistory: List<AiChatMessage>,
    ): AiChatMessage {
        if (!config.isConfigured) {
            val providerName = config.provider.displayName
            return AiChatMessage(
                sender = MessageSender.Assistant,
                text = "⚠️ **AI Provider Not Configured**\n\n" +
                    "To generate workflows, chat with the AI, and plan automations, please configure an API key for **$providerName**.\n\n" +
                    "👉 Tap the **key / credential icon** at the top right to select or create a credential (supports OpenAI, NVIDIA NIM, Groq, Google Gemini, Anthropic Claude, or Local Ollama).",
            )
        }

        val redactor = Redactor(listOf(config.apiKey))
        return try {
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
            val failure = r.failure
            if (failure != null) {
                return formatFailureMessage(failure, config)
            }
            toMessage(r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelException) {
            formatModelExceptionMessage(e, config, redactor)
        } catch (e: Exception) {
            val safeMsg = redactor.redact(e.message ?: e::class.java.simpleName).take(250)
            AiChatMessage(
                sender = MessageSender.Assistant,
                text = "❌ **AI Assistant Communication Error**\n\n" +
                    "The model could not finish the request:\n\n" +
                    "```\n$safeMsg\n```\n\n" +
                    "Please verify your connection and provider settings.",
            )
        }
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

    private fun toMessage(r: com.kaivoflow.core.ai.flowedit.AssistantResult): AiChatMessage {
        val check = r.proposal
        val usable = check?.takeIf { it.isUsable }?.flow
        val records = ArrayList<ToolExecution>()
        r.edits.forEach { records += ToolExecution(it.tool, it.description, it.ok) }
        check?.warnings?.forEach { records += ToolExecution("check", it, isSuccess = false) }
        // Spec P3: a syntax error in an AI-written expression is shown, never silently applied.
        usable?.let { f -> ExpressionGuard.check(f).forEach { p -> records += ToolExecution("check_expression", "Expression problem in ${p.where}: ${p.message}", isSuccess = false) } }
        val text = buildString {
            append(r.text.ifBlank { if (usable != null) "Here is the updated workflow proposal:" else "" })
            if (check != null && !check.isUsable) {
                append("\n\nI could not use the Flow I wrote, so nothing was changed:\n")
                check.errors.take(5).forEach { append("- ").append(it).append('\n') }
            }
        }.trim()
        return AiChatMessage(sender = MessageSender.Assistant, text = text.ifBlank { "I have nothing to change." }, flowProposal = usable, toolCalls = records)
    }
}
