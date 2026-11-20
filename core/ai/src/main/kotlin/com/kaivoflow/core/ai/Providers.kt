package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.HttpResponseSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URI

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
    val retry: RetryPolicy = RetryPolicy(),
)

object ChatModels {
    /** Builds one client. When [http] also implements [HttpStreamGateway], [ChatModelClient.stream] is incremental. */
    fun create(http: HttpGateway, config: ModelConfig): ChatModelClient = when (config.provider) {
        ProviderKind.OpenAiCompatible -> OpenAiCompatibleClient(http, config)
        ProviderKind.Ollama -> OllamaClient(http, config)
        ProviderKind.Anthropic -> AnthropicClient(http, config)
        ProviderKind.Gemini -> GeminiClient(http, config)
    }

    /** [primary] first; on any failure the next of [fallbacks] gets the same request. */
    fun withFallbacks(primary: ChatModelClient, fallbacks: List<ChatModelClient>): ChatModelClient =
        if (fallbacks.isEmpty()) primary else FallbackModelClient(listOf(primary) + fallbacks)
}

/** One HTTP call as built by an adapter. */
internal class WireCall(val url: String, val headers: Map<String, String>, val body: Map<String, Any?>, val query: Map<String, String> = emptyMap())

/**
 * Shared request plumbing: endpoint check, no redirects, status mapping with the error taxonomy, retry with
 * n8n-style classification, redacted errors, and the streaming loop. Adapters only build the request, read a
 * finished reply and provide a stream accumulator.
 */
internal abstract class HttpModelClient(private val http: HttpGateway, protected val config: ModelConfig) : ChatModelClient {
    private val endpoint = config.policy.require(config.baseUrl.ifBlank { config.provider.defaultBaseUrl })
    protected val base: String = endpoint.baseUrl
    private val redactor = Redactor(listOfNotNull(config.apiKey?.reveal()))
    private val streamGateway: HttpStreamGateway? = http as? HttpStreamGateway
    protected val caps: ModelCaps = ModelCapabilities.of(config.provider, config.model, runCatching { URI(base).host.orEmpty() }.getOrDefault(""))

    init {
        if (config.model.isBlank()) throw ModelException(ModelErrorKind.BadRequest, "Choose a model.")
    }

    protected abstract fun buildCall(request: ChatRequest, stream: Boolean): WireCall
    protected abstract fun parse(json: Map<String, Any?>): ChatResponse
    protected abstract fun newAccumulator(): StreamAccumulator

    /** True for newline-delimited JSON (Ollama), false for server-sent events. */
    protected open val ndjson: Boolean = false

    /** The output cap actually sent: never below what the model needs to think and still answer. */
    protected fun outputCap(request: ChatRequest): Int = maxOf(request.maxOutputTokens, caps.minOutputTokens)

    /** Key header name and value, or null for a local server that needs none. */
    protected fun requireKey(): ApiKey? {
        val key = config.apiKey?.takeIf { !it.isBlank }
        if (key == null && !(endpoint.isLocal || config.provider == ProviderKind.Ollama)) {
            throw ModelException(ModelErrorKind.Auth, "No API key is set for ${config.provider.key}. Add a credential.")
        }
        return key
    }

    /** Whether the replay of [data] is safe: only data written by this provider for this very model comes back. */
    protected fun replayable(data: Map<String, Any?>?): Map<String, Any?>? =
        data?.takeIf { it["_model"] == config.model }

    override suspend fun complete(request: ChatRequest): ChatResponse {
        checkTools(request)
        return config.retry.execute { _ ->
            val call = buildCall(request, false)
            val json = post(call)
            parse(json).let { if (it.model == null) it.copy(model = config.model) else it }
        }
    }

    private fun checkTools(request: ChatRequest) {
        val bad = request.tools.firstOrNull { !ToolRegistry.NAME_RE.matches(it.name) }
        if (bad != null) throw ModelException(ModelErrorKind.BadRequest, "The tool name \"${bad.name.take(40)}\" is not allowed by providers.")
    }

    private fun spec(call: WireCall, accept: String) = HttpRequestSpec(
        method = "POST",
        url = call.url,
        headers = call.headers + ("Content-Type" to "application/json") + ("Accept" to accept),
        query = call.query,
        body = call.body,
        timeoutMs = config.timeoutMs,
        followRedirects = false,
        failOnErrorStatus = false,
    )

    private fun transport(e: Throwable): ModelException {
        val raw = e.message ?: e::class.java.simpleName
        val safe = redactor.redact(raw).take(200)
        val timedOut = e is java.net.SocketTimeoutException || raw.contains("too long to answer", true) || raw.contains("timed out", true)
        return if (timedOut) ModelException(ModelErrorKind.Timeout, "The model did not answer in time: $safe")
        else ModelException(ModelErrorKind.Network, "The model could not be reached: $safe")
    }

    private suspend fun post(call: WireCall): Map<String, Any?> {
        val response: HttpResponseSpec = try {
            http.request(spec(call, "application/json"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelException) {
            throw e
        } catch (e: Throwable) {
            throw transport(e)
        }
        val json = J.body(response)
        if (response.status in 300..399) {
            throw ModelException(ModelErrorKind.Redirect, "The provider answered with a redirect, which is not followed.", response.status)
        }
        if (response.status !in 200..299) throw statusError(response.status, response.headers, json, response.bodyText)
        return json ?: throw ModelException(ModelErrorKind.Malformed, "The provider reply was not JSON.", response.status)
    }

    private fun statusError(status: Int, headers: Map<String, String>, json: Map<String, Any?>?, text: String): ModelException {
        val detail = (J.str(J.path(json, "error.message")) ?: J.str(J.path(json, "message")) ?: J.str(J.path(json, "error")) ?: text)
        val safe = redactor.redact(detail).replace(Regex("\\s+"), " ").take(200)
        return ModelErrors.fromStatus(status, headers, json, redactor.redact(text).take(2_000), safe)
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> {
        val sg = streamGateway ?: return super.stream(request)
        return flow {
            checkTools(request)
            var emitted = false
            config.retry.execute(onRetry = { info ->
                emit(StreamEvent.Retrying(info.retryNumber, info.delayMs, info.reason, emitted))
                emitted = false
            }) { _ ->
                val call = buildCall(request, true)
                val resp = try {
                    sg.open(spec(call, if (ndjson) "application/x-ndjson" else "text/event-stream"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelException) {
                    throw e
                } catch (e: Throwable) {
                    throw transport(e)
                }
                if (resp.status in 300..399) throw ModelException(ModelErrorKind.Redirect, "The provider answered with a redirect, which is not followed.", resp.status)
                if (resp.status !in 200..299) {
                    val sb = StringBuilder()
                    try {
                        resp.lines.collect { if (sb.length < 4_000) sb.append(it).append('\n') }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) { /* the status is what matters */ }
                    val text = sb.toString()
                    throw statusError(resp.status, resp.headers, J.obj(J.parse(text)), text)
                }
                val acc = newAccumulator()
                val sse = SseParser()
                var chunks = 0
                var finished = false
                suspend fun handle(data: String): Boolean {
                    if (data == "[DONE]") return true
                    val obj = J.obj(J.parse(data)) ?: return false
                    chunks++
                    for (ev in acc.accept(obj)) { emitted = true; emit(ev) }
                    return false
                }
                try {
                    resp.lines.collect { line ->
                        if (finished) return@collect
                        if (ndjson) {
                            if (line.isNotBlank() && handle(line.trim())) finished = true
                        } else {
                            val ev = sse.feed(line)
                            if (ev != null && ev.data.isNotEmpty() && handle(ev.data)) finished = true
                        }
                    }
                    if (!finished && !ndjson) sse.end()?.let { if (it.data.isNotEmpty()) handle(it.data) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelException) {
                    throw e
                } catch (e: java.io.IOException) {
                    throw transport(e)
                } catch (e: com.kaivoflow.core.engine.KaivoException) {
                    throw transport(e)
                }
                if (chunks == 0) throw ModelException(ModelErrorKind.Malformed, "The provider stream had no readable data.", resp.status)
                if (!acc.terminated && !finished) throw ModelException(ModelErrorKind.Network, "The provider stream ended before the answer was complete.")
                for (ev in acc.finish()) {
                    emitted = true
                    emit(if (ev is StreamEvent.Done && ev.response.model == null) StreamEvent.Done(ev.response.copy(model = config.model)) else ev)
                }
            }
        }
    }
}

/** OpenAI Chat Completions wire helpers shared by the finished-reply parser and the stream accumulator. */
internal object OpenAiWire {
    fun usage(u: Map<String, Any?>) = Usage(
        J.long(u["prompt_tokens"]), J.long(u["completion_tokens"]),
        cachedPromptTokens = J.long(J.obj(u["prompt_tokens_details"])?.get("cached_tokens")),
        reasoningTokens = J.long(J.obj(u["completion_tokens_details"])?.get("reasoning_tokens")),
    )

    /** Servers such as DeepSeek want their hidden reasoning text back during a tool round; kept only for that. */
    fun messageData(thinking: String, hasToolCalls: Boolean, model: String): Map<String, Any?>? =
        if (thinking.isNotEmpty() && hasToolCalls) mapOf("openai.reasoning_content" to thinking, "_model" to model) else null
}

/** OpenAI Chat Completions wire format; also used for compatible servers (vLLM, LM Studio, Groq, OpenRouter, ...). */
internal class OpenAiCompatibleClient(http: HttpGateway, config: ModelConfig) : HttpModelClient(http, config) {
    override fun newAccumulator(): StreamAccumulator = OpenAiAccumulator(config.model)

    override fun buildCall(request: ChatRequest, stream: Boolean): WireCall {
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
                if (m.toolCalls.isNotEmpty()) {
                    put("tool_calls", m.toolCalls.map { mapOf("id" to it.id, "type" to "function", "function" to mapOf("name" to it.name, "arguments" to J.write(it.arguments))) })
                    (replayable(m.providerData)?.get("openai.reasoning_content") as? String)?.let { put("reasoning_content", it) }
                }
            }
        }
        val body = buildMap<String, Any?> {
            put("model", config.model)
            put("messages", messages)
            if (caps.acceptsTemperature) request.temperature?.let { put("temperature", it) }
            put(if (caps.tokenParam == TokenParam.MaxCompletionTokens) "max_completion_tokens" else "max_tokens", outputCap(request))
            if (request.tools.isNotEmpty()) {
                put("tools", request.tools.map { mapOf("type" to "function", "function" to mapOf("name" to it.name, "description" to it.description, "parameters" to it.parameters)) })
                if (caps.acceptsToolChoice) when (val c = request.toolChoice) {
                    ToolChoice.Auto -> Unit
                    ToolChoice.Required -> put("tool_choice", "required")
                    ToolChoice.None -> put("tool_choice", "none")
                    is ToolChoice.Specific -> put("tool_choice", mapOf("type" to "function", "function" to mapOf("name" to c.name)))
                }
            }
            if (request.jsonMode && request.tools.isEmpty()) put("response_format", mapOf("type" to "json_object"))
            val effort = request.reasoning?.effort
            if (effort != null && caps.acceptsReasoningEffort) put("reasoning_effort", effort.name.lowercase())
            if (stream) { put("stream", true); put("stream_options", mapOf("include_usage" to true)) }
        }
        return WireCall("$base/chat/completions", headers, body)
    }

    override fun parse(json: Map<String, Any?>): ChatResponse {
        J.obj(json["error"])?.let { throw ModelErrors.fromStream(it) }
        val choice = J.obj(J.list(json["choices"]).firstOrNull()) ?: throw ModelException(ModelErrorKind.Malformed, "The provider reply has no choices.")
        val msg = J.obj(choice["message"]) ?: emptyMap()
        // Only `content` is the answer. Hidden reasoning text is never promoted to one.
        val text = J.str(msg["content"]).orEmpty()
        val thinking = (J.str(msg["reasoning_content"]) ?: J.str(msg["reasoning"])).orEmpty()
        val calls = J.list(msg["tool_calls"]).mapIndexedNotNull { i, raw ->
            val c = J.obj(raw) ?: return@mapIndexedNotNull null
            val fn = J.obj(c["function"]) ?: return@mapIndexedNotNull null
            val name = J.str(fn["name"]) ?: return@mapIndexedNotNull null
            val p = ToolArguments.fromProvider(fn["arguments"])
            ToolCall(J.str(c["id"]) ?: "call_$i", name, p.arguments, p.error, p.raw)
        }
        val usage = J.obj(json["usage"])?.let { OpenAiWire.usage(it) } ?: Usage()
        val finish = when (J.str(choice["finish_reason"])) {
            "tool_calls", "function_call" -> FinishReason.ToolCalls
            "stop" -> FinishReason.Stop
            "length" -> FinishReason.Length
            else -> if (calls.isNotEmpty()) FinishReason.ToolCalls else FinishReason.Other
        }
        return ChatResponse(text, calls, usage, finish, thinking, OpenAiWire.messageData(thinking, calls.isNotEmpty(), config.model), J.str(json["model"]))
    }
}

internal object AnthropicWire {
    fun finish(stop: String?, hasCalls: Boolean) = when (stop) {
        "tool_use" -> FinishReason.ToolCalls
        "end_turn", "stop_sequence" -> FinishReason.Stop
        "max_tokens" -> FinishReason.Length
        else -> if (hasCalls) FinishReason.ToolCalls else FinishReason.Other
    }

    fun messageData(blocks: List<Map<String, Any?>>, model: String): Map<String, Any?>? =
        if (blocks.isEmpty()) null else mapOf("anthropic.thinking_blocks" to blocks, "_model" to model)

    fun usage(u: Map<String, Any?>): Usage {
        val read = J.long(u["cache_read_input_tokens"]); val write = J.long(u["cache_creation_input_tokens"])
        return Usage(J.long(u["input_tokens"]) + read + write, J.long(u["output_tokens"]), cachedPromptTokens = read)
    }
}

/** Anthropic Messages API. Key goes in `x-api-key`. */
internal class AnthropicClient(http: HttpGateway, config: ModelConfig) : HttpModelClient(http, config) {
    override fun newAccumulator(): StreamAccumulator = AnthropicAccumulator(config.model)

    private fun budgetFor(r: ReasoningConfig?): Int? {
        r ?: return null
        val b = r.budgetTokens ?: when (r.effort) {
            ReasoningEffort.Low -> 1_024; ReasoningEffort.Medium -> 4_096; ReasoningEffort.High -> 12_000; null -> return null
        }
        return b.coerceAtLeast(1_024)
    }

    override fun buildCall(request: ChatRequest, stream: Boolean): WireCall {
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
                // Signed thinking blocks go back first and unchanged, or the API refuses the next turn.
                (replayable(m.providerData)?.get("anthropic.thinking_blocks") as? List<*>)?.forEach { b -> J.obj(b)?.let { blocks += it } }
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
        val budget = budgetFor(request.reasoning)
        val body = buildMap<String, Any?> {
            put("model", config.model)
            put("max_tokens", if (budget != null) maxOf(outputCap(request), budget + 1_024) else outputCap(request))
            if (system.isNotEmpty()) put("system", system)
            put("messages", messages)
            if (budget != null) put("thinking", mapOf("type" to "enabled", "budget_tokens" to budget))
            else if (caps.acceptsTemperature) request.temperature?.let { put("temperature", it.coerceIn(0.0, 1.0)) }
            if (request.tools.isNotEmpty()) {
                put("tools", request.tools.map { mapOf("name" to it.name, "description" to it.description, "input_schema" to it.parameters) })
                // With thinking on, Anthropic accepts only auto and none: forcing is dropped rather than failing the call.
                when (val c = request.toolChoice) {
                    ToolChoice.Auto -> Unit
                    ToolChoice.None -> put("tool_choice", mapOf("type" to "none"))
                    ToolChoice.Required -> if (budget == null) put("tool_choice", mapOf("type" to "any"))
                    is ToolChoice.Specific -> if (budget == null) put("tool_choice", mapOf("type" to "tool", "name" to c.name))
                }
            }
            if (stream) put("stream", true)
        }
        return WireCall("$base/v1/messages", headers, body)
    }

    override fun parse(json: Map<String, Any?>): ChatResponse {
        if (J.str(json["type"]) == "error") throw ModelErrors.fromStream(J.obj(json["error"]) ?: emptyMap())
        val text = StringBuilder(); val thinking = StringBuilder()
        val thinkingBlocks = ArrayList<Map<String, Any?>>()
        val calls = ArrayList<ToolCall>()
        J.list(json["content"]).forEachIndexed { i, raw ->
            val b = J.obj(raw) ?: return@forEachIndexed
            when (J.str(b["type"])) {
                "text" -> text.append(J.str(b["text"]).orEmpty())
                "thinking" -> { thinking.append(J.str(b["thinking"]).orEmpty()); thinkingBlocks += b }
                "redacted_thinking" -> thinkingBlocks += b
                "tool_use" -> {
                    val name = J.str(b["name"]) ?: return@forEachIndexed
                    val p = ToolArguments.fromProvider(b["input"])
                    calls += ToolCall(J.str(b["id"]) ?: "call_$i", name, p.arguments, p.error, p.raw)
                }
            }
        }
        val usage = J.obj(json["usage"])?.let { AnthropicWire.usage(it) } ?: Usage()
        return ChatResponse(text.toString(), calls, usage, AnthropicWire.finish(J.str(json["stop_reason"]), calls.isNotEmpty()),
            thinking.toString(), AnthropicWire.messageData(thinkingBlocks, config.model), J.str(json["model"]))
    }
}

internal object GeminiWire {
    fun usage(u: Map<String, Any?>): Usage {
        val thoughts = J.long(u["thoughtsTokenCount"])
        return Usage(J.long(u["promptTokenCount"]), J.long(u["candidatesTokenCount"]) + thoughts,
            cachedPromptTokens = J.long(u["cachedContentTokenCount"]), reasoningTokens = thoughts)
    }

    fun finish(reason: String?, hasCalls: Boolean) = when {
        hasCalls -> FinishReason.ToolCalls
        reason == "MAX_TOKENS" -> FinishReason.Length
        reason == "STOP" -> FinishReason.Stop
        else -> FinishReason.Other
    }

    fun callData(signature: String?, model: String): Map<String, Any?>? =
        signature?.let { mapOf("gemini.thoughtSignature" to it, "_model" to model) }

    fun messageData(textSignature: String?, model: String): Map<String, Any?>? =
        textSignature?.let { mapOf("gemini.textSignature" to it, "_model" to model) }
}

/** Gemini generateContent. The key goes in `x-goog-api-key`, never in the query string. */
internal class GeminiClient(http: HttpGateway, config: ModelConfig) : HttpModelClient(http, config) {
    init {
        if (!MODEL_RE.matches(config.model)) throw ModelException(ModelErrorKind.BadRequest, "The model name has characters that are not allowed.")
    }

    override fun newAccumulator(): StreamAccumulator = GeminiAccumulator(config.model)

    override fun buildCall(request: ChatRequest, stream: Boolean): WireCall {
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
                if (m.text.isNotEmpty()) {
                    val sig = replayable(m.providerData)?.get("gemini.textSignature") as? String
                    parts += buildMap { put("text", m.text); if (sig != null) put("thoughtSignature", sig) }
                }
                m.toolCalls.forEach {
                    val sig = replayable(it.providerData)?.get("gemini.thoughtSignature") as? String
                    parts += buildMap { put("functionCall", mapOf("name" to it.name, "args" to it.arguments)); if (sig != null) put("thoughtSignature", sig) }
                }
                contents += mapOf("role" to "model", "parts" to parts)
            }
        }
        flush()
        val body = buildMap<String, Any?> {
            request.system?.takeIf { it.isNotBlank() }?.let { put("systemInstruction", mapOf("parts" to listOf(mapOf("text" to it)))) }
            put("contents", contents)
            if (request.tools.isNotEmpty()) {
                put("tools", listOf(mapOf("functionDeclarations" to request.tools.map {
                    mapOf("name" to it.name, "description" to it.description, "parameters" to stripUnsupported(it.parameters))
                })))
                val cfg: Map<String, Any?>? = when (val c = request.toolChoice) {
                    ToolChoice.Auto -> null
                    ToolChoice.Required -> mapOf("mode" to "ANY")
                    ToolChoice.None -> mapOf("mode" to "NONE")
                    is ToolChoice.Specific -> mapOf("mode" to "ANY", "allowedFunctionNames" to listOf(c.name))
                }
                if (cfg != null) put("toolConfig", mapOf("functionCallingConfig" to cfg))
            }
            put("generationConfig", buildMap<String, Any?> {
                if (caps.acceptsTemperature) request.temperature?.let { put("temperature", it) }
                put("maxOutputTokens", outputCap(request))
                if (request.jsonMode && request.tools.isEmpty()) put("responseMimeType", "application/json")
                request.reasoning?.let { r ->
                    val budget = r.budgetTokens ?: when (r.effort) { ReasoningEffort.Low -> 1_024; ReasoningEffort.Medium -> 4_096; ReasoningEffort.High -> 12_000; null -> null }
                    put("thinkingConfig", buildMap<String, Any?> { put("includeThoughts", true); if (budget != null) put("thinkingBudget", budget) })
                }
            })
        }
        val method = if (stream) "streamGenerateContent" else "generateContent"
        return WireCall("$base/v1beta/models/${config.model}:$method", headers, body, if (stream) mapOf("alt" to "sse") else emptyMap())
    }

    override fun parse(json: Map<String, Any?>): ChatResponse {
        J.obj(json["error"])?.let { throw ModelErrors.fromStream(it) }
        val acc = GeminiAccumulator(config.model)
        val cand = J.obj(J.list(json["candidates"]).firstOrNull())
        if (cand == null) throw ModelException(ModelErrorKind.Empty, "Gemini returned no answer (it may have been blocked).")
        acc.accept(json)
        return (acc.finish().last() as StreamEvent.Done).response
    }

    private fun stripUnsupported(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.filter { it.key != "additionalProperties" && it.key != "\$schema" }.associate { it.key.toString() to stripUnsupported(it.value) }
        is List<*> -> v.map { stripUnsupported(it) }
        else -> v
    }

    private companion object { val MODEL_RE = Regex("[A-Za-z0-9._\\-]{1,100}") }
}

/**
 * Ollama through its native `/api/chat` endpoint (newline-delimited JSON when streaming). The configured base may
 * carry the `/v1` of the compatible API; it is removed here. Tool choice is not offered by Ollama and is ignored.
 */
internal class OllamaClient(http: HttpGateway, config: ModelConfig) : HttpModelClient(http, config) {
    override val ndjson: Boolean = true
    override fun newAccumulator(): StreamAccumulator = OllamaAccumulator(config.model)

    override fun buildCall(request: ChatRequest, stream: Boolean): WireCall {
        val key = requireKey()
        val headers = if (key != null) mapOf("Authorization" to "Bearer ${key.reveal()}") else emptyMap()
        val messages = ArrayList<Map<String, Any?>>()
        request.system?.takeIf { it.isNotBlank() }?.let { messages += mapOf("role" to "system", "content" to it) }
        for (m in request.messages) messages += when (m.role) {
            Role.User -> mapOf("role" to "user", "content" to m.text)
            Role.Tool -> buildMap { put("role", "tool"); put("content", m.text); m.toolName?.let { put("tool_name", it) } }
            Role.Assistant -> buildMap {
                put("role", "assistant"); put("content", m.text)
                if (m.toolCalls.isNotEmpty()) {
                    put("tool_calls", m.toolCalls.map { mapOf("function" to mapOf("name" to it.name, "arguments" to it.arguments)) })
                    (replayable(m.providerData)?.get("ollama.thinking") as? String)?.let { put("thinking", it) }
                }
            }
        }
        val body = buildMap<String, Any?> {
            put("model", config.model)
            put("messages", messages)
            put("stream", stream)
            put("options", buildMap<String, Any?> {
                request.temperature?.let { put("temperature", it) }
                put("num_predict", outputCap(request))
            })
            if (request.tools.isNotEmpty()) put("tools", request.tools.map { mapOf("type" to "function", "function" to mapOf("name" to it.name, "description" to it.description, "parameters" to it.parameters)) })
            else if (request.jsonMode) put("format", "json")
            request.reasoning?.let { put("think", true) }
        }
        return WireCall("${base.removeSuffix("/v1")}/api/chat", headers, body)
    }

    override fun parse(json: Map<String, Any?>): ChatResponse {
        val acc = OllamaAccumulator(config.model)
        acc.accept(json)
        if (!acc.terminated) throw ModelException(ModelErrorKind.Malformed, "The model server reply was incomplete.")
        return (acc.finish().last() as StreamEvent.Done).response
    }
}
