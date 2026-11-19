package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.KaivoException

enum class Role { User, Assistant, Tool }

/**
 * One tool call asked for by the model. When the model sent arguments that are not a JSON object,
 * [arguments] is empty and [argumentsError] says why; the loop then tells the model instead of crashing.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val argumentsError: String? = null,
)

/** The system prompt is not a message: Anthropic and Gemini carry it outside the list. */
data class ChatMessage(
    val role: Role,
    val text: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val isError: Boolean = false,
) {
    companion object {
        fun user(text: String) = ChatMessage(Role.User, text)
        fun assistant(text: String, toolCalls: List<ToolCall> = emptyList()) = ChatMessage(Role.Assistant, text, toolCalls)
        fun tool(call: ToolCall, text: String, isError: Boolean = false) =
            ChatMessage(Role.Tool, text, toolCallId = call.id, toolName = call.name, isError = isError)
    }
}

data class Usage(val promptTokens: Long = 0, val completionTokens: Long = 0) {
    val totalTokens: Long get() = promptTokens + completionTokens
    operator fun plus(o: Usage) = Usage(promptTokens + o.promptTokens, completionTokens + o.completionTokens)
}

/** A tool as the model sees it. [parameters] is a JSON Schema object. */
data class ToolSpec(val name: String, val description: String, val parameters: Map<String, Any?>)

data class ChatRequest(
    val system: String?,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxOutputTokens: Int = 1024,
    val jsonMode: Boolean = false,
)

enum class FinishReason { Stop, ToolCalls, Length, Other }

data class ChatResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: Usage = Usage(),
    val finish: FinishReason = FinishReason.Stop,
)

enum class ModelErrorKind { Auth, RateLimit, BadRequest, Server, Network, Redirect, Policy, Malformed, Empty }

/** A provider failure with a safe message: keys are already redacted out of it. */
class ModelException(val kind: ModelErrorKind, message: String, val status: Int? = null) : KaivoException(message)

/** One provider conversation turn. Implementations never log, never put keys in URLs, never follow redirects. */
interface ChatModelClient {
    suspend fun complete(request: ChatRequest): ChatResponse
}
