package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.HttpResponseSpec
import kotlinx.coroutines.CancellationException

enum class ProviderKind(val key: String, val defaultBaseUrl: String) {
    OpenAiCompatible("openai", "https://api.openai.com/v1"),
    Anthropic("anthropic", "https://api.anthropic.com"),
    Gemini("gemini", "https://generativelanguage.googleapis.com"),
    Ollama("ollama", "http://localhost:11434/v1"),
    ;

    companion object {
        /** Unknown keys are an error, never a silent fallback to another provider's endpoint. */
        fun fromKey(key: String): ProviderKind? = entries.firstOrNull { it.key.equals(key.trim(), true) }
            ?: if (key.trim().equals("claude", true)) Anthropic else null
    }
}

/** Everything an adapter needs. The key lives in [apiKey] only and is sent in a header, never in a URL. */
data class ModelConfig(
    val provider: ProviderKind,
    val model: String,
    val baseUrl: String = "",
    val apiKey: ApiKey? = null,
    val timeoutMs: Long = 60_000,
    val policy: EndpointPolicy = EndpointPolicy(),
)

object ChatModels {
    fun create(http: HttpGateway, config: ModelConfig): ChatModelClient = when (config.provider) {
        ProviderKind.OpenAiCompatible, ProviderKind.Ollama -> OpenAiCompatibleClient(http, config)
        ProviderKind.Anthropic -> AnthropicClient(http, config)
        ProviderKind.Gemini -> GeminiClient(http, config)
    }
}

/** Shared request plumbing: endpoint check, no redirects, status mapping, redacted errors. */
internal abstract class HttpModelClient(private val http: HttpGateway, protected val config: ModelConfig) : ChatModelClient {
    private val endpoint = config.policy.require(config.baseUrl.ifBlank { config.provider.defaultBaseUrl })
    protected val base: String = endpoint.baseUrl
    private val redactor = Redactor(listOfNotNull(config.apiKey?.reveal()))

    init {
        if (config.model.isBlank()) throw ModelException(ModelErrorKind.BadRequest, "Choose a model.")
    }

    /** Key header name and value, or null for a local server that needs none. */
    protected fun requireKey(): ApiKey? {
        val key = config.apiKey?.takeIf { !it.isBlank }
        if (key == null && !(endpoint.isLocal || config.provider == ProviderKind.Ollama)) {
            throw ModelException(ModelErrorKind.Auth, "No API key is set for ${config.provider.key}. Add a credential.")
        }
        return key
    }

    protected suspend fun post(url: String, headers: Map<String, String>, body: Map<String, Any?>): Map<String, Any?> {
        val response: HttpResponseSpec = try {
            http.request(
                HttpRequestSpec(
                    method = "POST",
                    url = url,
                    headers = headers + ("Content-Type" to "application/json") + ("Accept" to "application/json"),
                    body = body,
                    timeoutMs = config.timeoutMs,
                    followRedirects = false,
                    failOnErrorStatus = false,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelException) {
            throw e
        } catch (e: Throwable) {
            throw ModelException(ModelErrorKind.Network, "The model could not be reached: ${redactor.redact(e.message ?: e::class.java.simpleName).take(200)}")
        }
        val json = J.body(response)
        if (response.status in 300..399) {
            throw ModelException(ModelErrorKind.Redirect, "The provider answered with a redirect, which is not followed.", response.status)
        }
        if (response.status !in 200..299) throw statusError(response.status, json, response.bodyText)
        return json ?: throw ModelException(ModelErrorKind.Malformed, "The provider reply was not JSON.", response.status)
    }

    private fun statusError(status: Int, json: Map<String, Any?>?, text: String): ModelException {
        val detail = (J.str(J.path(json, "error.message")) ?: J.str(J.path(json, "message")) ?: J.str(J.path(json, "error")) ?: text)
        val safe = redactor.redact(detail).replace(Regex("\\s+"), " ").take(200)
        val kind = when {
            status == 401 || status == 403 -> ModelErrorKind.Auth
            status == 429 -> ModelErrorKind.RateLimit
            status in 400..499 -> ModelErrorKind.BadRequest
            else -> ModelErrorKind.Server
        }
        return ModelException(kind, "The provider returned HTTP $status${if (safe.isNotBlank()) ": $safe" else ""}", status)
    }

    protected fun parseArgs(raw: Any?): Pair<Map<String, Any?>, String?> = when (raw) {
        null -> emptyMap<String, Any?>() to null
        is Map<*, *> -> (J.obj(raw) ?: emptyMap()) to null
        is String -> if (raw.isBlank()) emptyMap<String, Any?>() to null else {
            val v = J.parse(raw)
            val m = J.obj(v)
            if (m != null) m to null else emptyMap<String, Any?>() to "arguments are not a valid JSON object"
        }
        else -> emptyMap<String, Any?>() to "arguments are not a valid JSON object"
    }
}

/** OpenAI Chat Completions wire format; also used for Ollama's `/v1` endpoint and compatible servers. */
internal class OpenAiCompatibleClient(http: HttpGateway, config: ModelConfig) : HttpModelClient(http, config) {
    override suspend fun complete(request: ChatRequest): ChatResponse {
        val key = requireKey()
        val headers = if (key != null) mapOf("Authorization" to "Bearer ${key.reveal()}") else emptyMap()
        val messages = ArrayList<Map<String, Any?>>()
        request.system?.takeIf { it.isNotBlank() }?.let { messages += mapOf("role" to "system", "content" to it) }
        for (m in request.messages) messages += when (m.role) {
            Role.User -> mapOf("role" to "user", "content" to m.text)
            Role.Tool -> mapOf("role" to "tool", "tool_call_id" to m.toolCallId, "content" to m.text)
            Role.Assistant -> buildMap {
                put("role", "assistant")
                put("content", m.text.ifEmpty { null })
                if (m.toolCalls.isNotEmpty()) put(
                    "tool_calls",
                    m.toolCalls.map { mapOf("id" to it.id, "type" to "function", "function" to mapOf("name" to it.name, "arguments" to J.write(it.arguments))) },
                )
            }
        }
        val body = buildMap<String, Any?> {
            put("model", config.model)
            put("messages", messages)
            request.temperature?.let { put("temperature", it) }
            put("max_tokens", request.maxOutputTokens)
            if (request.tools.isNotEmpty()) put(
                "tools",
                request.tools.map { mapOf("type" to "function", "function" to mapOf("name" to it.name, "description" to it.description, "parameters" to it.parameters)) },
            )
            if (request.jsonMode && request.tools.isEmpty()) put("response_format", mapOf("type" to "json_object"))
        }
        val json = post("$base/chat/completions", headers, body)
        val choice = J.obj(J.list(json["choices"]).firstOrNull()) ?: throw ModelException(ModelErrorKind.Malformed, "The provider reply has no choices.")
        val msg = J.obj(choice["message"]) ?: emptyMap()
        // Only `content` is the answer. Hidden reasoning text is never promoted to one.
        val text = J.str(msg["content"]).orEmpty()
        val calls = J.list(msg["tool_calls"]).mapIndexedNotNull { i, raw ->
            val c = J.obj(raw) ?: return@mapIndexedNotNull null
            val fn = J.obj(c["function"]) ?: return@mapIndexedNotNull null
            val name = J.str(fn["name"]) ?: return@mapIndexedNotNull null
            val (args, err) = parseArgs(fn["arguments"])
            ToolCall(J.str(c["id"]) ?: "call_$i", name, args, err)
        }
        val usage = J.obj(json["usage"])?.let { Usage(J.long(it["prompt_tokens"]), J.long(it["completion_tokens"])) } ?: Usage()
        val finish = when (J.str(choice["finish_reason"])) {
            "tool_calls", "function_call" -> FinishReason.ToolCalls
            "stop" -> FinishReason.Stop
            "length" -> FinishReason.Length
            else -> if (calls.isNotEmpty()) FinishReason.ToolCalls else FinishReason.Other
        }
        return ChatResponse(text, calls, usage, finish)
    }
}

/** Anthropic Messages API. Key goes in `x-api-key`. */
internal class AnthropicClient(http: HttpGateway, config: ModelConfig) : HttpModelClient(http, config) {
    override suspend fun complete(request: ChatRequest): ChatResponse {
        val key = requireKey()
        val headers = buildMap { if (key != null) put("x-api-key", key.reveal()); put("anthropic-version", "2023-06-01") }
        val messages = ArrayList<Map<String, Any?>>()
        var pendingResults = ArrayList<Map<String, Any?>>()
        fun flush() {
            if (pendingResults.isNotEmpty()) { messages += mapOf("role" to "user", "content" to pendingResults); pendingResults = ArrayList() }
        }
        for (m in request.messages) when (m.role) {
            Role.Tool -> pendingResults += buildMap {
                put("type", "tool_result"); put("tool_use_id", m.toolCallId); put("content", m.text)
                if (m.isError) put("is_error", true)
            }
            Role.User -> { flush(); messages += mapOf("role" to "user", "content" to m.text) }
            Role.Assistant -> {
                flush()
                val blocks = ArrayList<Map<String, Any?>>()
                if (m.text.isNotEmpty()) blocks += mapOf("type" to "text", "text" to m.text)
                m.toolCalls.forEach { blocks += mapOf("type" to "tool_use", "id" to it.id, "name" to it.name, "input" to it.arguments) }
                messages += mapOf("role" to "assistant", "content" to blocks)
            }
        }
        flush()
        val system = listOfNotNull(
            request.system?.takeIf { it.isNotBlank() },
            if (request.jsonMode) "Reply with a single JSON value and nothing else." else null,
        ).joinToString("\n\n")
        val body = buildMap<String, Any?> {
            put("model", config.model)
            put("max_tokens", request.maxOutputTokens)
            if (system.isNotEmpty()) put("system", system)
            put("messages", messages)
            request.temperature?.let { put("temperature", it.coerceIn(0.0, 1.0)) }
            if (request.tools.isNotEmpty()) put("tools", request.tools.map { mapOf("name" to it.name, "description" to it.description, "input_schema" to it.parameters) })
        }
        val json = post("$base/v1/messages", headers, body)
        val text = StringBuilder()
        val calls = ArrayList<ToolCall>()
        J.list(json["content"]).forEachIndexed { i, raw ->
            val b = J.obj(raw) ?: return@forEachIndexed
            when (J.str(b["type"])) {
                "text" -> text.append(J.str(b["text"]).orEmpty())
                "tool_use" -> {
                    val name = J.str(b["name"]) ?: return@forEachIndexed
                    val input = b["input"]
                    val (args, err) = parseArgs(input)
                    calls += ToolCall(J.str(b["id"]) ?: "call_$i", name, args, err)
                }
            }
        }
        val usage = J.obj(json["usage"])?.let { Usage(J.long(it["input_tokens"]), J.long(it["output_tokens"])) } ?: Usage()
        val finish = when (J.str(json["stop_reason"])) {
            "tool_use" -> FinishReason.ToolCalls
            "end_turn", "stop_sequence" -> FinishReason.Stop
            "max_tokens" -> FinishReason.Length
            else -> if (calls.isNotEmpty()) FinishReason.ToolCalls else FinishReason.Other
        }
        return ChatResponse(text.toString(), calls, usage, finish)
    }
}

/** Gemini generateContent. The key goes in `x-goog-api-key`, never in the query string. */
internal class GeminiClient(http: HttpGateway, config: ModelConfig) : HttpModelClient(http, config) {
    init {
        if (!MODEL_RE.matches(config.model)) throw ModelException(ModelErrorKind.BadRequest, "The model name has characters that are not allowed.")
    }

    override suspend fun complete(request: ChatRequest): ChatResponse {
        val key = requireKey()
        val headers = if (key != null) mapOf("x-goog-api-key" to key.reveal()) else emptyMap()
        val contents = ArrayList<Map<String, Any?>>()
        var pending = ArrayList<Map<String, Any?>>()
        fun flush() {
            if (pending.isNotEmpty()) { contents += mapOf("role" to "user", "parts" to pending); pending = ArrayList() }
        }
        for (m in request.messages) when (m.role) {
            Role.Tool -> pending += mapOf("functionResponse" to mapOf("name" to (m.toolName ?: "tool"), "response" to mapOf("result" to m.text)))
            Role.User -> { flush(); contents += mapOf("role" to "user", "parts" to listOf(mapOf("text" to m.text))) }
            Role.Assistant -> {
                flush()
                val parts = ArrayList<Map<String, Any?>>()
                if (m.text.isNotEmpty()) parts += mapOf("text" to m.text)
                m.toolCalls.forEach { parts += mapOf("functionCall" to mapOf("name" to it.name, "args" to it.arguments)) }
                contents += mapOf("role" to "model", "parts" to parts)
            }
        }
        flush()
        val body = buildMap<String, Any?> {
            request.system?.takeIf { it.isNotBlank() }?.let { put("systemInstruction", mapOf("parts" to listOf(mapOf("text" to it)))) }
            put("contents", contents)
            if (request.tools.isNotEmpty()) put("tools", listOf(mapOf("functionDeclarations" to request.tools.map {
                mapOf("name" to it.name, "description" to it.description, "parameters" to stripUnsupported(it.parameters))
            })))
            put("generationConfig", buildMap<String, Any?> {
                request.temperature?.let { put("temperature", it) }
                put("maxOutputTokens", request.maxOutputTokens)
                if (request.jsonMode && request.tools.isEmpty()) put("responseMimeType", "application/json")
            })
        }
        val json = post("$base/v1beta/models/${config.model}:generateContent", headers, body)
        val cand = J.obj(J.list(json["candidates"]).firstOrNull())
            ?: throw ModelException(ModelErrorKind.Empty, "Gemini returned no answer (it may have been blocked).")
        val text = StringBuilder()
        val calls = ArrayList<ToolCall>()
        J.list(J.path(cand, "content.parts")).forEachIndexed { i, raw ->
            val p = J.obj(raw) ?: return@forEachIndexed
            J.str(p["text"])?.let { text.append(it) }
            J.obj(p["functionCall"])?.let { fc ->
                val name = J.str(fc["name"]) ?: return@forEachIndexed
                val (args, err) = parseArgs(fc["args"])
                calls += ToolCall("call_$i", name, args, err)
            }
        }
        val usage = J.obj(json["usageMetadata"])?.let { Usage(J.long(it["promptTokenCount"]), J.long(it["candidatesTokenCount"])) } ?: Usage()
        val finish = when {
            calls.isNotEmpty() -> FinishReason.ToolCalls
            J.str(cand["finishReason"]) == "MAX_TOKENS" -> FinishReason.Length
            J.str(cand["finishReason"]) == "STOP" -> FinishReason.Stop
            else -> FinishReason.Other
        }
        return ChatResponse(text.toString(), calls, usage, finish)
    }

    private fun stripUnsupported(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.filter { it.key != "additionalProperties" && it.key != "\$schema" }.associate { it.key.toString() to stripUnsupported(it.value) }
        is List<*> -> v.map { stripUnsupported(it) }
        else -> v
    }

    private companion object { val MODEL_RE = Regex("[A-Za-z0-9._\\-]{1,100}") }
}
