package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.HttpRequestSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector

/**
 * What a model call reports while it runs. Every stream ends with exactly one [Done] or throws a [ModelException].
 * Tool call arguments arrive as text pieces ([ToolCallArgsDelta]); the finished, parsed call is in [ToolCallEnd].
 */
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ThinkingDelta(val text: String) : StreamEvent
    data class ToolCallStart(val index: Int, val id: String, val name: String) : StreamEvent
    data class ToolCallArgsDelta(val index: Int, val delta: String) : StreamEvent
    data class ToolCallEnd(val index: Int, val call: ToolCall) : StreamEvent

    /** Running token count of this call (providers report it at the end or at the start and end). */
    data class UsageUpdate(val usage: Usage) : StreamEvent

    /**
     * An attempt failed and another starts after [delayMs]. When [discardedOutput] is true the listener already
     * saw deltas of the failed attempt and must drop them.
     */
    data class Retrying(val retryNumber: Int, val delayMs: Long, val reason: String, val discardedOutput: Boolean) : StreamEvent

    /** The model at [fromIndex] failed with [reason]; the one at [toIndex] takes over (output so far is dropped). */
    data class FallbackUsed(val fromIndex: Int, val toIndex: Int, val reason: String, val discardedOutput: Boolean) : StreamEvent

    data class Done(val response: ChatResponse) : StreamEvent
}

/** Reports a finished answer as events, for clients that cannot stream. Not incremental, and honest about it. */
suspend fun FlowCollector<StreamEvent>.emitReplay(r: ChatResponse) {
    if (r.thinking.isNotEmpty()) emit(StreamEvent.ThinkingDelta(r.thinking))
    if (r.text.isNotEmpty()) emit(StreamEvent.TextDelta(r.text))
    r.toolCalls.forEachIndexed { i, c ->
        emit(StreamEvent.ToolCallStart(i, c.id, c.name))
        val a = if (c.arguments.isEmpty()) "" else J.write(c.arguments)
        if (a.isNotEmpty()) emit(StreamEvent.ToolCallArgsDelta(i, a))
        emit(StreamEvent.ToolCallEnd(i, c))
    }
    if (!r.usage.isEmpty) emit(StreamEvent.UsageUpdate(r.usage))
    emit(StreamEvent.Done(r))
}

/**
 * A transport that can deliver a response body line by line while it arrives. Implemented next to the HTTP client
 * (`:core:network`); the adapters use it when the [com.kaivoflow.core.engine.HttpGateway] they were given also
 * implements it, and otherwise fall back to a replayed answer. Collecting [StreamingHttpResponse.lines] reads the
 * body; cancelling the collector must close the connection. For a non-2xx status the lines hold the error body.
 */
interface HttpStreamGateway {
    suspend fun open(request: HttpRequestSpec): StreamingHttpResponse
}

class StreamingHttpResponse(val status: Int, val headers: Map<String, String>, val lines: Flow<String>)

/** One server-sent event. [event] is the `event:` field or null, [data] the joined `data:` lines. */
data class SseEvent(val event: String?, val data: String)

/**
 * Line-by-line Server-Sent Events reader (WHATWG rules that matter here: `data:` lines joined by newline,
 * a blank line ends an event, lines starting with `:` are comments, one leading space after the colon is dropped).
 */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Feeds one line; returns a finished event or null. Lines come without their line terminator. */
    fun feed(rawLine: String): SseEvent? {
        val line = rawLine.trimEnd('\r')
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> { if (hasData) data.append('\n'); data.append(value); hasData = true }
        }
        return null
    }

    /** Call at end of stream: a final event without the closing blank line still counts. */
    fun end(): SseEvent? = flush()

    private fun flush(): SseEvent? {
        if (!hasData && event == null) return null
        val e = SseEvent(event, data.toString())
        event = null; data.setLength(0); hasData = false
        return e
    }
}

/** Turns decoded stream chunks into events and, at the end, the finished [ChatResponse]. One per call. */
internal interface StreamAccumulator {
    fun accept(chunk: Map<String, Any?>): List<StreamEvent>

    /** True once the provider's own end marker was seen. A stream that stops without it was cut off. */
    val terminated: Boolean
    fun finish(): List<StreamEvent>
}

private class CallBuf(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false)

/** OpenAI Chat Completions chunks (`choices[0].delta`). */
internal class OpenAiAccumulator(private val model: String) : StreamAccumulator {
    private val text = StringBuilder()
    private val thinking = StringBuilder()
    private val calls = java.util.TreeMap<Int, CallBuf>()
    private var usage = Usage()
    private var finish: String? = null
    private var servedModel: String? = null
    override val terminated: Boolean get() = finish != null

    override fun accept(chunk: Map<String, Any?>): List<StreamEvent> {
        J.obj(chunk["error"])?.let { throw ModelErrors.fromStream(it) }
        J.str(chunk["model"])?.let { servedModel = it }
        J.obj(chunk["usage"])?.let { usage = OpenAiWire.usage(it) }
        val choice = J.obj(J.list(chunk["choices"]).firstOrNull()) ?: return emptyList()
        J.str(choice["finish_reason"])?.let { finish = it }
        val delta = J.obj(choice["delta"]) ?: return emptyList()
        val out = ArrayList<StreamEvent>()
        (J.str(delta["reasoning_content"]) ?: J.str(delta["reasoning"]))?.takeIf { it.isNotEmpty() }?.let { thinking.append(it); out += StreamEvent.ThinkingDelta(it) }
        J.str(delta["content"])?.takeIf { it.isNotEmpty() }?.let { text.append(it); out += StreamEvent.TextDelta(it) }
        J.list(delta["tool_calls"]).forEachIndexed { pos, raw ->
            val tc = J.obj(raw) ?: return@forEachIndexed
            val idx = (tc["index"] as? Number)?.toInt() ?: pos
            val b = calls.getOrPut(idx) { CallBuf() }
            J.str(tc["id"])?.takeIf { it.isNotEmpty() }?.let { if (b.id.isEmpty()) b.id = it }
            val fn = J.obj(tc["function"])
            J.str(fn?.get("name"))?.takeIf { it.isNotEmpty() }?.let { if (b.name.isEmpty()) b.name = it }
            if (!b.started && b.name.isNotEmpty()) {
                if (b.id.isEmpty()) b.id = "call_$idx"
                b.started = true
                out += StreamEvent.ToolCallStart(idx, b.id, b.name)
            }
            J.str(fn?.get("arguments"))?.takeIf { it.isNotEmpty() }?.let { b.args.append(it); out += StreamEvent.ToolCallArgsDelta(idx, it) }
        }
        return out
    }

    override fun finish(): List<StreamEvent> {
        val built = calls.entries.filter { it.value.name.isNotEmpty() }.map { (idx, b) ->
            val p = ToolArguments.fromProvider(b.args.toString())
            idx to ToolCall(b.id.ifEmpty { "call_$idx" }, b.name, p.arguments, p.error, p.raw)
        }
        val toolCalls = built.map { it.second }
        val reason = when (finish) {
            "tool_calls", "function_call" -> FinishReason.ToolCalls
            "stop" -> FinishReason.Stop
            "length" -> FinishReason.Length
            else -> if (toolCalls.isNotEmpty()) FinishReason.ToolCalls else FinishReason.Other
        }
        val resp = ChatResponse(text.toString(), toolCalls, usage, reason, thinking.toString(),
            OpenAiWire.messageData(thinking.toString(), toolCalls.isNotEmpty(), model), servedModel ?: model)
        val events = ArrayList<StreamEvent>()
        built.forEachIndexed { i, (idx, _) -> events += StreamEvent.ToolCallEnd(idx, toolCalls[i]) }
        if (!usage.isEmpty) events += StreamEvent.UsageUpdate(usage)
        events += StreamEvent.Done(resp)
        return events
    }
}

/** Anthropic Messages stream events. */
internal class AnthropicAccumulator(private val model: String) : StreamAccumulator {
    private class Block(val type: String, var id: String = "", var name: String = "") {
        val text = StringBuilder(); val json = StringBuilder(); val thinking = StringBuilder()
        var signature: String = ""; var data: String = ""; var started = false
    }
    private val blocks = java.util.TreeMap<Int, Block>()
    private var promptTokens = 0L; private var cacheRead = 0L; private var cacheWrite = 0L; private var outTokens = 0L
    private var stop: String? = null
    private var servedModel: String? = null
    private var stopped = false
    override val terminated: Boolean get() = stopped

    override fun accept(chunk: Map<String, Any?>): List<StreamEvent> {
        val out = ArrayList<StreamEvent>()
        when (J.str(chunk["type"])) {
            "message_stop" -> stopped = true
            "error" -> throw ModelErrors.fromStream(J.obj(chunk["error"]) ?: emptyMap())
            "message_start" -> {
                val m = J.obj(chunk["message"])
                J.str(m?.get("model"))?.let { servedModel = it }
                J.obj(m?.get("usage"))?.let {
                    promptTokens = J.long(it["input_tokens"]); cacheRead = J.long(it["cache_read_input_tokens"]); cacheWrite = J.long(it["cache_creation_input_tokens"])
                    outTokens = J.long(it["output_tokens"])
                }
            }
            "content_block_start" -> {
                val idx = (chunk["index"] as? Number)?.toInt() ?: return out
                val cb = J.obj(chunk["content_block"]) ?: return out
                val b = Block(J.str(cb["type"]).orEmpty(), J.str(cb["id"]).orEmpty(), J.str(cb["name"]).orEmpty())
                if (b.type == "redacted_thinking") b.data = J.str(cb["data"]).orEmpty()
                if (b.type == "text") J.str(cb["text"])?.takeIf { it.isNotEmpty() }?.let { b.text.append(it); out += StreamEvent.TextDelta(it) }
                blocks[idx] = b
                if (b.type == "tool_use") { b.started = true; out += StreamEvent.ToolCallStart(idx, b.id.ifEmpty { "call_$idx" }, b.name) }
            }
            "content_block_delta" -> {
                val idx = (chunk["index"] as? Number)?.toInt() ?: return out
                val b = blocks[idx] ?: return out
                val d = J.obj(chunk["delta"]) ?: return out
                when (J.str(d["type"])) {
                    "text_delta" -> J.str(d["text"])?.takeIf { it.isNotEmpty() }?.let { b.text.append(it); out += StreamEvent.TextDelta(it) }
                    "thinking_delta" -> J.str(d["thinking"])?.takeIf { it.isNotEmpty() }?.let { b.thinking.append(it); out += StreamEvent.ThinkingDelta(it) }
                    "signature_delta" -> b.signature += J.str(d["signature"]).orEmpty()
                    "input_json_delta" -> J.str(d["partial_json"])?.takeIf { it.isNotEmpty() }?.let { b.json.append(it); out += StreamEvent.ToolCallArgsDelta(idx, it) }
                }
            }
            "message_delta" -> {
                J.str(J.obj(chunk["delta"])?.get("stop_reason"))?.let { stop = it }
                J.obj(chunk["usage"])?.let { u -> if (u["output_tokens"] != null) outTokens = J.long(u["output_tokens"]) }
            }
        }
        return out
    }

    override fun finish(): List<StreamEvent> {
        val usage = Usage(promptTokens + cacheRead + cacheWrite, outTokens, cachedPromptTokens = cacheRead)
        val text = StringBuilder(); val thinking = StringBuilder()
        val thinkingBlocks = ArrayList<Map<String, Any?>>()
        val calls = ArrayList<Pair<Int, ToolCall>>()
        for ((idx, b) in blocks) when (b.type) {
            "text" -> text.append(b.text)
            "thinking" -> { thinking.append(b.thinking); thinkingBlocks += mapOf("type" to "thinking", "thinking" to b.thinking.toString(), "signature" to b.signature) }
            "redacted_thinking" -> thinkingBlocks += mapOf("type" to "redacted_thinking", "data" to b.data)
            "tool_use" -> {
                val p = ToolArguments.fromProvider(b.json.toString())
                calls += idx to ToolCall(b.id.ifEmpty { "call_$idx" }, b.name, p.arguments, p.error, p.raw)
            }
        }
        val reason = AnthropicWire.finish(stop, calls.isNotEmpty())
        val resp = ChatResponse(text.toString(), calls.map { it.second }, usage, reason, thinking.toString(),
            AnthropicWire.messageData(thinkingBlocks, model), servedModel ?: model)
        val events = ArrayList<StreamEvent>()
        calls.forEach { (idx, c) -> events += StreamEvent.ToolCallEnd(idx, c) }
        if (!usage.isEmpty) events += StreamEvent.UsageUpdate(usage)
        events += StreamEvent.Done(resp)
        return events
    }
}

/** Gemini `streamGenerateContent?alt=sse`: every chunk is a partial GenerateContentResponse. */
internal class GeminiAccumulator(private val model: String) : StreamAccumulator {
    private val text = StringBuilder(); private val thinking = StringBuilder()
    private val calls = ArrayList<ToolCall>()
    private var usage = Usage()
    private var finish: String? = null
    private var textSignature: String? = null
    private var blocked: String? = null
    private var sawCandidate = false
    override val terminated: Boolean get() = finish != null || blocked != null

    override fun accept(chunk: Map<String, Any?>): List<StreamEvent> {
        J.obj(chunk["error"])?.let { throw ModelErrors.fromStream(it) }
        J.obj(chunk["usageMetadata"])?.let { usage = GeminiWire.usage(it) }
        J.str(J.path(chunk, "promptFeedback.blockReason"))?.let { blocked = it }
        val cand = J.obj(J.list(chunk["candidates"]).firstOrNull()) ?: return emptyList()
        sawCandidate = true
        J.str(cand["finishReason"])?.let { finish = it }
        val out = ArrayList<StreamEvent>()
        for (raw in J.list(J.path(cand, "content.parts"))) {
            val p = J.obj(raw) ?: continue
            val sig = J.str(p["thoughtSignature"])
            val t = J.str(p["text"])
            if (t != null) {
                if (p["thought"] == true) { if (t.isNotEmpty()) { thinking.append(t); out += StreamEvent.ThinkingDelta(t) } }
                else if (t.isNotEmpty()) { text.append(t); out += StreamEvent.TextDelta(t) }
                if (sig != null) textSignature = sig
            }
            J.obj(p["functionCall"])?.let { fc ->
                val name = J.str(fc["name"]) ?: return@let
                val pa = ToolArguments.fromProvider(fc["args"])
                val idx = calls.size
                val call = ToolCall(J.str(fc["id"]) ?: "call_$idx", name, pa.arguments, pa.error, pa.raw, GeminiWire.callData(sig, model))
                calls += call
                out += StreamEvent.ToolCallStart(idx, call.id, name)
                if (call.arguments.isNotEmpty()) out += StreamEvent.ToolCallArgsDelta(idx, J.write(call.arguments))
                out += StreamEvent.ToolCallEnd(idx, call)
            }
        }
        return out
    }

    override fun finish(): List<StreamEvent> {
        if (!sawCandidate && calls.isEmpty()) {
            throw ModelException(ModelErrorKind.Empty, "Gemini returned no answer${blocked?.let { " (blocked: $it)" } ?: " (it may have been blocked)"}.")
        }
        val reason = GeminiWire.finish(finish, calls.isNotEmpty())
        val resp = ChatResponse(text.toString(), calls.toList(), usage, reason, thinking.toString(), GeminiWire.messageData(textSignature, model), model)
        return buildList {
            if (!usage.isEmpty) add(StreamEvent.UsageUpdate(usage))
            add(StreamEvent.Done(resp))
        }
    }
}

/** Ollama native `/api/chat` NDJSON chunks. Tool calls arrive whole and without ids. */
internal class OllamaAccumulator(private val model: String) : StreamAccumulator {
    private val text = StringBuilder(); private val thinking = StringBuilder()
    private val calls = ArrayList<ToolCall>()
    private var usage = Usage()
    private var doneReason: String? = null
    override val terminated: Boolean get() = doneReason != null

    override fun accept(chunk: Map<String, Any?>): List<StreamEvent> {
        (chunk["error"] as? String)?.let { throw ModelException(ModelErrorKind.Server, "The model server reported: ${it.take(200)}") }
        val out = ArrayList<StreamEvent>()
        val m = J.obj(chunk["message"])
        J.str(m?.get("thinking"))?.takeIf { it.isNotEmpty() }?.let { thinking.append(it); out += StreamEvent.ThinkingDelta(it) }
        J.str(m?.get("content"))?.takeIf { it.isNotEmpty() }?.let { text.append(it); out += StreamEvent.TextDelta(it) }
        J.list(m?.get("tool_calls")).forEach { raw ->
            val fn = J.obj(J.obj(raw)?.get("function")) ?: return@forEach
            val name = J.str(fn["name"]) ?: return@forEach
            val pa = ToolArguments.fromProvider(fn["arguments"])
            val idx = calls.size
            val call = ToolCall("call_$idx", name, pa.arguments, pa.error, pa.raw)
            calls += call
            out += StreamEvent.ToolCallStart(idx, call.id, name)
            if (call.arguments.isNotEmpty()) out += StreamEvent.ToolCallArgsDelta(idx, J.write(call.arguments))
            out += StreamEvent.ToolCallEnd(idx, call)
        }
        if (chunk["done"] == true) {
            doneReason = J.str(chunk["done_reason"]) ?: "stop"
            usage = Usage(J.long(chunk["prompt_eval_count"]), J.long(chunk["eval_count"]))
        }
        return out
    }

    override fun finish(): List<StreamEvent> {
        val reason = when {
            calls.isNotEmpty() -> FinishReason.ToolCalls
            doneReason == "length" -> FinishReason.Length
            doneReason == "stop" -> FinishReason.Stop
            else -> FinishReason.Other
        }
        val data = if (thinking.isNotEmpty() && calls.isNotEmpty()) mapOf("ollama.thinking" to thinking.toString(), "_model" to model) else null
        val resp = ChatResponse(text.toString(), calls.toList(), usage, reason, thinking.toString(), data, model)
        return buildList {
            if (!usage.isEmpty) add(StreamEvent.UsageUpdate(usage))
            add(StreamEvent.Done(resp))
        }
    }
}
