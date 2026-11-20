package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.AgentEvent
import com.kaivoflow.core.ai.ApprovalRelay
import com.kaivoflow.core.ai.PriceTable
import com.kaivoflow.core.ai.ChatMessage
import com.kaivoflow.core.ai.ModelException
import com.kaivoflow.core.ai.ProviderEndpoints
import com.kaivoflow.core.ai.Redactor
import com.kaivoflow.core.ai.flowedit.AssistantCatalog
import com.kaivoflow.core.ai.flowedit.AssistantTurn
import com.kaivoflow.core.engine.IssueSeverity as EngineSeverity
import com.kaivoflow.core.model.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow as CoroutineFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    /** The provider's id of the tool call this step shows; null for notices. */
    val callId: String? = null,
    /** True from the moment the model starts the call until the tool has answered. */
    val running: Boolean = false,
    /** How long the tool really took. */
    val durationMs: Long? = null,
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
    /** How the turn that wrote this message ended. Saved with the chat. */
    val status: TurnStatus = TurnStatus.Completed,
    /**
     * `updatedAt` of the open Flow when [flowProposal] was made (null for a brand new Flow). Apply refuses the proposal
     * when the Flow has changed since. Kept in memory only: proposals are not saved with the chat.
     */
    val baseUpdatedAt: Long? = null,
)

/** What a running turn reports to the screen. Every [Progress] is a full snapshot of the message, so a slow screen may skip some. */
sealed interface AgentStreamEvent {
    data class Progress(val message: AiChatMessage) : AgentStreamEvent
    data class Finished(val finalMessage: AiChatMessage) : AgentStreamEvent
    /** The turn failed; the message keeps what was already shown (partial text, real steps) and says why. */
    data class Error(val errorMessage: AiChatMessage) : AgentStreamEvent
}

/** One model call as the usage ledger stores it (the app binds this to `UsageLedger`). Failures must not stop the turn. */
fun interface AssistantUsageRecorder {
    suspend fun record(provider: String, model: String, tokensIn: Long, tokensOut: Long, costMicros: Long, at: Long, runId: String?, flowId: String?)

    companion object {
        val None = AssistantUsageRecorder { _, _, _, _, _, _, _, _ -> }
    }
}

/**
 * The Ask AI assistant's backend, an adapter over `:core:ai`'s agent loop. It shows only what really happens: the
 * loop's events (thinking text the provider sent, tool calls with their real arguments and results, notices for
 * retries) are folded into the chat turn by [AssistantTurnReducer], the clock is real time without the wait for the
 * person's approval, and the model's output arrives while it is produced when the transport streams. It edits a private
 * draft only; it has no repository, so nothing is saved until the person taps Apply.
 */
class AiWorkflowAgent @Inject constructor(
    private val clients: ChatClientProvider,
    private val catalog: AssistantCatalog,
    private val approvals: ApprovalRelay,
    private val usage: AssistantUsageRecorder,
) {
    internal var clock: () -> Long = System::currentTimeMillis
    internal var tickMs: Long = 1_000L

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
     * Runs one assistant turn and reports it as it happens. Cancelling the collector stops the model stream and any
     * running tool; the caller then marks the turn (see [AssistantTurnReducer.markCut]). [journal] receives the real
     * events for the durable log.
     */
    fun executeTurnStream(
        prompt: String,
        currentFlow: Flow?,
        config: AiProviderConfig,
        chatHistory: List<AiChatMessage>,
        messageId: String = UUID.randomUUID().toString(),
        journal: TurnJournalSink = TurnJournalSink.None,
    ): CoroutineFlow<AgentStreamEvent> = channelFlow {
        if (!config.isConfigured) {
            send(AgentStreamEvent.Error(notConfigured(config)))
            return@channelFlow
        }

        val redactor = Redactor(listOf(config.apiKey))
        val log = TurnJournal(journal, clock)
        val lock = Mutex()
        var state = AssistantTurnReducer.start(clock())

        suspend fun progress() = send(AgentStreamEvent.Progress(AssistantTurnReducer.live(state, messageId, clock())))

        val client = try {
            clients.create(ProviderEndpoints.config(config.provider.toKind(), config.model, config.baseUrl, config.apiKey))
        } catch (e: ModelException) {
            val ended = AssistantTurnReducer.endedMessage(state, messageId, TurnStatus.Failed, clock(), providerError(e.status, redactor.redact(e.message.orEmpty()), config, null))
            send(AgentStreamEvent.Error(ended))
            return@channelFlow
        }

        log.started(config.provider.name, config.model, prompt)
        val ticker = launch {
            // Real elapsed time: it only repeats the snapshot so the screen's clock moves between events.
            while (isActive) {
                delay(tickMs)
                lock.withLock { progress() }
            }
        }
        try {
            val history = chatHistory.dropLast(1).takeLast(8)
                .filter { it.sender == MessageSender.User || it.status == TurnStatus.Completed }
                .map { if (it.sender == MessageSender.User) ChatMessage.user(it.text) else ChatMessage.assistant(it.text) }
            val turn = AssistantTurn(client, catalog, approvals, redactor, paramCheck = { name, params ->
                ExpressionGuard.check(params, name).map { "${it.where}: ${it.message}" }
            })
            lock.withLock { progress() }
            val r = turn.run(prompt, currentFlow, history) { ev ->
                lock.withLock {
                    state = AssistantTurnReducer.reduce(state, ev, clock())
                    log.on(ev)
                    if (ev is AgentEvent.StepFinished) recordUsage(ev, config, messageId, currentFlow?.id)
                    progress()
                }
            }
            ticker.cancel()
            lock.withLock {
                val now = clock()
                val failure = r.failure
                if (failure != null) {
                    val msg = AssistantTurnReducer.endedMessage(state, messageId, TurnStatus.Failed, now, failureText(state, failure, config, redactor))
                    log.ended(TurnStatus.Failed, redactor.redact(failure).take(500))
                    send(AgentStreamEvent.Error(msg))
                } else {
                    val msg = AssistantTurnReducer.completed(AssistantTurnReducer.ended(state, TurnStatus.Completed, now), r, messageId, now)
                    log.ended(TurnStatus.Completed)
                    send(AgentStreamEvent.Finished(msg))
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { log.flush() }
            throw e
        } catch (e: Exception) {
            ticker.cancel()
            lock.withLock {
                val safe = redactor.redact(e.message ?: e::class.java.simpleName).take(250)
                val text = "❌ **AI Assistant Communication Error**\n\nThe model could not finish the request:\n\n```\n$safe\n```\n\nPlease verify your connection and provider settings."
                val msg = AssistantTurnReducer.endedMessage(state, messageId, TurnStatus.Failed, clock(), text)
                log.ended(TurnStatus.Failed, safe)
                send(AgentStreamEvent.Error(msg))
            }
        } finally {
            ticker.cancel()
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
                is AgentStreamEvent.Progress -> Unit
            }
        }
        return result ?: AiChatMessage(
            sender = MessageSender.Assistant,
            text = "I encountered an unexpected issue completing this turn.",
            status = TurnStatus.Failed,
        )
    }

    private suspend fun recordUsage(ev: AgentEvent.StepFinished, config: AiProviderConfig, runId: String, flowId: String?) {
        if (ev.usage.isEmpty) return
        val model = ev.model ?: config.model
        val usd = PriceTable.BUNDLED.estimate(model, ev.usage, config.provider.toKind()).usd
        val micros = if (usd == null || usd.isNaN() || usd <= 0.0) 0L else Math.round(usd * 1_000_000.0)
        try {
            usage.record(config.provider.key, model, ev.usage.promptTokens, ev.usage.completionTokens, micros, clock(), runId, flowId)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // The ledger is bookkeeping; a failed write must not end the answer.
        }
    }

    private fun notConfigured(config: AiProviderConfig): AiChatMessage {
        val providerName = config.provider.displayName
        return AiChatMessage(
            sender = MessageSender.Assistant,
            status = TurnStatus.Failed,
            text = "⚠️ **AI Provider Not Configured**\n\n" +
                "To generate workflows, chat with the AI, and plan automations, please configure an API key for **$providerName**.\n\n" +
                "👉 Tap the **key / credential icon** at the top right to select or create a credential (supports OpenAI, NVIDIA NIM, Groq, Google Gemini, Anthropic Claude, or Local Ollama).",
        )
    }

    /** The error text of a turn that failed: the provider's own words when a model call failed, else the limit that was hit. */
    private fun failureText(state: TurnState, failure: String, config: AiProviderConfig, redactor: Redactor): String {
        val err = state.error
        val safe = redactor.redact(failure)
        return if (err != null && (err.status != null || err.modelErrorKind != null)) providerError(err.status, safe, config, err.hint, err.attempts)
        else "⚠️ **AI Assistant Stopped Early**\n\n$safe\n\n*Try rephrasing your prompt or switching models in Settings.*"
    }

    private fun providerError(status: Int?, safeMsg: String, config: AiProviderConfig, hint: String?, attempts: Int = 1): String {
        val isNvidia = config.baseUrl.contains("nvidia.com", ignoreCase = true) || config.model.contains("nvidia", ignoreCase = true)
        return buildString {
            append(if (status != null) "❌ **AI Provider Error (HTTP $status)**\n\n" else "❌ **AI Provider Error**\n\n")
            append("The configured AI provider returned:\n")
            append("> $safeMsg\n\n")
            if (attempts > 1) append("It was tried $attempts times.\n\n")
            if (!hint.isNullOrBlank()) append("$hint\n\n")
            if (status == 403 && isNvidia) {
                append("**NVIDIA NIM Troubleshooting:**\n")
                append("- On NVIDIA Build / NIM, Personal accounts require **Public API Endpoints** access provisioned for chat inference, even if model discovery (`/v1/models`) succeeded.\n")
                append("- Alternatively, you can connect an OpenAI, Google Gemini, Anthropic Claude, or Groq API key, or use local Ollama.\n\n")
            } else if (status == 401 || status == 403) {
                append("**Troubleshooting:**\n")
                append("1. Tap the **key icon** at the top right to verify your API key and base URL.\n")
                append("2. Ensure your API account has active credits and permission to access model `${config.model}`.\n\n")
            }
            append("👉 You can manage your AI providers anytime from **Overview > Credentials**.")
        }
    }
}
