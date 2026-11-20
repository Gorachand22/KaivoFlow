package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.KaivoException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class Role { User, Assistant, Tool }

/**
 * One tool call asked for by the model. When the model sent arguments that are not a JSON object,
 * [arguments] is empty and [argumentsError] says why; the loop then tells the model instead of crashing.
 *
 * [rawArguments] keeps the text the model sent when it could not be read as an object (capped), so the lenient
 * adapter in [ToolArguments] can still recover a single-parameter call. [providerData] is opaque to everything
 * except the adapter that wrote it: thought signatures and similar items that must travel back unchanged.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val argumentsError: String? = null,
    val rawArguments: String? = null,
    val providerData: Map<String, Any?>? = null,
)

/**
 * The system prompt is not a message: Anthropic and Gemini carry it outside the list.
 * [providerData] on an assistant message holds thinking blocks and similar items to replay (opaque, see [ToolCall]).
 */
data class ChatMessage(
    val role: Role,
    val text: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
    val providerData: Map<String, Any?>? = null,
) {
    companion object {
        fun user(text: String) = ChatMessage(Role.User, text)
        fun assistant(text: String, toolCalls: List<ToolCall> = emptyList(), providerData: Map<String, Any?>? = null) =
            ChatMessage(Role.Assistant, text, toolCalls, providerData = providerData)
        fun tool(call: ToolCall, text: String, isError: Boolean = false) =
            ChatMessage(Role.Tool, text, toolCallId = call.id, toolName = call.name, isError = isError)
    }
}

/**
 * Token counts of one or more calls. [promptTokens] is the whole prompt (cached part included),
 * [completionTokens] the whole answer (reasoning included), so [totalTokens] is what a provider bills as tokens.
 * [cachedPromptTokens] and [reasoningTokens] are parts of those two, for cost. [estimated] is true when at least
 * one part was counted by [TokenEstimator] because the provider reported nothing.
 */
data class Usage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedPromptTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val estimated: Boolean = false,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
    val isEmpty: Boolean get() = totalTokens == 0L
    operator fun plus(o: Usage) = Usage(
        promptTokens + o.promptTokens, completionTokens + o.completionTokens,
        cachedPromptTokens + o.cachedPromptTokens, reasoningTokens + o.reasoningTokens, estimated || o.estimated,
    )
}

/** A tool as the model sees it. [parameters] is a JSON Schema object. */
data class ToolSpec(val name: String, val description: String, val parameters: Map<String, Any?>)

/** Whether and how the model must use tools in one call. Mapped per provider; see [ModelCapabilities]. */
sealed interface ToolChoice {
    /** The model decides. */
    data object Auto : ToolChoice

    /** The model must call at least one tool (OpenAI `required`, Anthropic `any`, Gemini mode `ANY`). */
    data object Required : ToolChoice

    /** The model must not call a tool. */
    data object None : ToolChoice

    /** The model must call this tool. */
    data class Specific(val name: String) : ToolChoice
}

enum class ReasoningEffort { Low, Medium, High }

/**
 * Optional thinking request. [effort] maps to OpenAI `reasoning_effort` (reasoning models only);
 * [budgetTokens] maps to the Anthropic thinking budget and the Gemini thinking budget. Either may be null.
 */
data class ReasoningConfig(val effort: ReasoningEffort? = null, val budgetTokens: Int? = null)

data class ChatRequest(
    val system: String?,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxOutputTokens: Int = 1024,
    val jsonMode: Boolean = false,
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val reasoning: ReasoningConfig? = null,
)

enum class FinishReason { Stop, ToolCalls, Length, Other }

data class ChatResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: Usage = Usage(),
    val finish: FinishReason = FinishReason.Stop,
    /** Visible thinking text, if the provider sent any. Never promoted to the answer. */
    val thinking: String = "",
    /** Opaque items that must be replayed with this assistant turn (thinking blocks, signatures). */
    val providerData: Map<String, Any?>? = null,
    /** The model id that answered, when known (the provider's own field, else the configured id). */
    val model: String? = null,
    /** 0 for the first model of a [FallbackModelClient], 1 for the next, and so on. */
    val servedBy: Int = 0,
) {
    val truncated: Boolean get() = finish == FinishReason.Length
}

/**
 * What went wrong with a model call, in terms a caller can act on.
 * [Quota] (money or plan exhausted, retrying will not help), [RateLimit] (slow down), [ContextLength] (the prompt
 * does not fit), [PayloadTooLarge] (413), [Timeout] (the server did not answer in time), [Unsupported]
 * (the model or endpoint cannot do what was asked, for example tools or streaming).
 */
enum class ModelErrorKind {
    Auth, RateLimit, BadRequest, Server, Network, Redirect, Policy, Malformed, Empty,
    Quota, ContextLength, PayloadTooLarge, Timeout, Unsupported,
}

/**
 * A provider failure with a safe message: keys are already redacted out of it.
 * [retryAfterMs] is the provider's own wait hint, [providerCode] its error code or type (for example
 * `insufficient_quota`), [attempts] how many tries were made before this was thrown.
 */
class ModelException(
    val kind: ModelErrorKind,
    message: String,
    val status: Int? = null,
    val retryAfterMs: Long? = null,
    val providerCode: String? = null,
    val attempts: Int = 1,
) : KaivoException(message) {
    /** Whether trying the same call again can succeed (n8n-style classification, see [ModelErrors]). */
    val retryable: Boolean get() = ModelErrors.isRetryable(this)

    /** One short sentence telling the person what to do next. */
    val hint: String get() = ModelErrors.hint(kind)

    fun withAttempts(n: Int) = ModelException(kind, message.orEmpty(), status, retryAfterMs, providerCode, n)
}

/**
 * One provider conversation turn. Implementations never log, never put keys in URLs, never follow redirects.
 *
 * [stream] is additive: the default runs [complete] and reports the finished answer as one burst of events, so
 * every existing implementation keeps working. HTTP adapters override it with real incremental delivery when the
 * gateway can stream (see [HttpStreamGateway]).
 */
interface ChatModelClient {
    suspend fun complete(request: ChatRequest): ChatResponse

    /** Events end with exactly one [StreamEvent.Done] or throw. Cancelling the collector cancels the call. */
    fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val r = complete(request)
        emitReplay(r)
    }
}
