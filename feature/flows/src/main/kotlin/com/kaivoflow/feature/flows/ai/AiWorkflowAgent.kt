package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.ApprovalRelay
import com.kaivoflow.core.ai.ChatMessage
import com.kaivoflow.core.ai.ModelException
import com.kaivoflow.core.ai.ProviderEndpoints
import com.kaivoflow.core.ai.Redactor
import com.kaivoflow.core.ai.flowedit.AssistantCatalog
import com.kaivoflow.core.ai.flowedit.AssistantTurn
import com.kaivoflow.core.ai.flowedit.OfflineFlows
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

    /** Asks the configured model; with no usable model, or when the call fails, falls back to the offline helper. */
    suspend fun executeTurn(
        prompt: String,
        currentFlow: Flow?,
        config: AiProviderConfig,
        chatHistory: List<AiChatMessage>,
    ): AiChatMessage {
        if (!config.isConfigured) return runOfflineHelper(prompt, currentFlow)
        val redactor = Redactor(listOf(config.apiKey))
        return try {
            val client = clients.create(ProviderEndpoints.config(config.provider.toKind(), config.model, config.baseUrl, config.apiKey))
            val history = chatHistory.dropLast(1).takeLast(8).map {
                if (it.sender == MessageSender.User) ChatMessage.user(it.text) else ChatMessage.assistant(it.text)
            }
            val turn = AssistantTurn(client, catalog, approvals, redactor, paramCheck = { name, params ->
                ExpressionGuard.check(params, name).map { "${it.where}: ${it.message}" }
            })
            val r = turn.run(prompt, currentFlow, history)
            if (r.failure != null) return offlineAfterFailure(r.failure, prompt, currentFlow)
            toMessage(r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelException) {
            offlineAfterFailure(redactor.redact(e.message.orEmpty()), prompt, currentFlow)
        } catch (e: Exception) {
            offlineAfterFailure("The assistant could not finish: " + redactor.redact(e.message ?: e::class.java.simpleName).take(160), prompt, currentFlow)
        }
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

    private fun offlineAfterFailure(reason: String, prompt: String, flow: Flow?): AiChatMessage {
        val fallback = runOfflineHelper(prompt, flow)
        return fallback.copy(text = "*(Offline helper: $reason)*\n\n" + fallback.text)
    }

    /** Keyword helper that runs on the phone. Its Flows are built from catalog ids (golden-tested) and are proposals only. */
    private fun runOfflineHelper(prompt: String, currentFlow: Flow?): AiChatMessage {
        val lower = prompt.lowercase()
        val now = System.currentTimeMillis()
        fun newId() = UUID.randomUUID().toString()

        if (currentFlow != null && (lower.contains("diagnose") || lower.contains("issue") || lower.contains("error") || lower.contains("fix") || lower.contains("check"))) {
            val report = diagnose(currentFlow)
            val text = buildString {
                append("### Check of \"${currentFlow.name}\"\n\n")
                if (report.issues.isEmpty()) append("All ${report.stepCount} steps are configured and connected.\n")
                else report.issues.forEach { i ->
                    val tag = when (i.severity) { IssueSeverity.Error -> "Problem"; IssueSeverity.Warning -> "Warning"; IssueSeverity.Info -> "Note" }
                    append("- **$tag** \"${i.stepName}\": ${i.message}")
                    if (i.suggestedFix.isNotBlank()) append(" _${i.suggestedFix}_")
                    append('\n')
                }
            }
            return AiChatMessage(sender = MessageSender.Assistant, text = text, diagnosisReport = report, toolCalls = listOf(ToolExecution("check_flow", report.summaryText, !report.hasErrors)))
        }

        if (currentFlow != null && (lower.contains("notify") || lower.contains("notification") || lower.contains("alert"))) {
            val proposal = OfflineFlows.withNotification(currentFlow, "step_${newId().take(6)}", "stream_${newId().take(6)}", now)
            return AiChatMessage(
                sender = MessageSender.Assistant,
                text = "I prepared a **Send Notification** step after your last step. Review it, then tap **Apply to Canvas**. Your Flow is not changed until you do.",
                flowProposal = proposal,
                toolCalls = listOf(ToolExecution("add_step", "Added \"Send Notification\" (device.notify)"), ToolExecution("connect_steps", "Connected it after the last step")),
            )
        }

        if (lower.contains("battery") || lower.contains("charge")) {
            return AiChatMessage(
                sender = MessageSender.Assistant,
                text = "Here is a **Low Battery Alert**. It starts when the battery drops below 20% and sends a notification.",
                flowProposal = OfflineFlows.batteryAlert(newId(), now),
                toolCalls = listOf(ToolExecution("add_step", "Battery Low (spark.battery)"), ToolExecution("add_step", "Low Battery Warning (device.notify)"), ToolExecution("connect_steps", "Connected the two steps")),
            )
        }

        if (lower.contains("webhook") || lower.contains("http") || lower.contains("api")) {
            return AiChatMessage(
                sender = MessageSender.Assistant,
                text = "Here is an **Incoming Webhook to Notification** Flow. It listens for a webhook on your phone and shows the message.",
                flowProposal = OfflineFlows.webhookToNotification(newId(), now),
                toolCalls = listOf(ToolExecution("add_step", "Webhook (spark.webhook)"), ToolExecution("add_step", "Show Alert (device.notify)"), ToolExecution("connect_steps", "Connected the two steps")),
            )
        }

        val reply = if (currentFlow != null) {
            "I am ready to help with **\"${currentFlow.name}\"** (${currentFlow.steps.size} steps). You can ask me to:\n" +
                "- **Check this flow** for problems\n- **Add a notification** step\n\n" +
                "To unlock full planning, tap the settings icon at the top right and add an API key for Gemini, Claude, OpenAI or a local Ollama."
        } else {
            "Welcome to **Ask AI**! What would you like to automate?\n\n" +
                "Try asking:\n- *\"Create a flow that alerts me when the battery is low\"*\n- *\"Build a webhook that shows a notification\"*\n\n" +
                "Add your AI provider any time with the settings button at the top right."
        }
        return AiChatMessage(sender = MessageSender.Assistant, text = reply)
    }
}
