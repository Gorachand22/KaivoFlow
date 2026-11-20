package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.HttpResponseSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTest {
    private val key = ApiKey("sk-test-123456")
    private val ask = ChatRequest("sys", listOf(ChatMessage.user("go")), listOf(ToolSpec("lookup", "d", mapOf("type" to "object"))))
    private fun cfg(p: ProviderKind, model: String, retry: RetryPolicy = RetryPolicy.None) = ModelConfig(p, model, apiKey = key, policy = ProviderEndpoints.policyFor(p), retry = retry)
    private fun ok(lines: Flow<String>) = StreamingHttpResponse(200, emptyMap(), lines)
    private suspend fun events(c: ChatModelClient, r: ChatRequest = ask) = withTimeout(5_000) { c.stream(r).toList() }
    private fun done(e: List<StreamEvent>) = (e.last() as StreamEvent.Done).response

    // ---- SSE reader ----

    @Test fun `sse parser follows the framing rules`() {
        val p = SseParser()
        val out = ArrayList<SseEvent>()
        listOf(": keep-alive", "event: message_start", "data: {\"a\":1}", "", "data: line1", "data:line2", "", "data: tail\r").forEach { p.feed(it)?.let(out::add) }
        p.end()?.let(out::add)
        assertEquals(listOf(SseEvent("message_start", "{\"a\":1}"), SseEvent(null, "line1\nline2"), SseEvent(null, "tail")), out)
    }

    // ---- per provider fixtures: the same answer streamed and not streamed ----

    @Test fun `openai stream reassembles a tool call split over many chunks`() = runBlocking {
        val gw = FakeStreamGateway { ok(sse(
            """{"model":"gpt-4o-2024-08-06","choices":[{"delta":{"role":"assistant","content":"Let me "}}]}""",
            """{"choices":[{"delta":{"content":"check."}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"lookup","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"q\":"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"cats\"}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","function":{"name":"other","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":4}}}""",
            "[DONE]")) }
        val e = events(ChatModels.create(gw, cfg(ProviderKind.OpenAiCompatible, "gpt-4o")))
        assertEquals(listOf("Let me ", "check."), e.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        assertEquals(listOf("{\"q\":", "\"cats\"}", "{}"), e.filterIsInstance<StreamEvent.ToolCallArgsDelta>().map { it.delta })
        assertEquals(listOf("call_a", "call_b"), e.filterIsInstance<StreamEvent.ToolCallStart>().map { it.id })
        val r = done(e)
        assertEquals("Let me check.", r.text); assertEquals(listOf(ToolCall("call_a", "lookup", mapOf("q" to "cats")), ToolCall("call_b", "other")), r.toolCalls)
        assertEquals(Usage(12, 5, cachedPromptTokens = 4), r.usage); assertEquals(FinishReason.ToolCalls, r.finish); assertEquals("gpt-4o-2024-08-06", r.model)
        val req = gw.requests.single()
        assertEquals("text/event-stream", req.headers["Accept"]); assertEquals(true, (req.body as Map<*, *>)["stream"])
        assertEquals(mapOf("include_usage" to true), (req.body as Map<*, *>)["stream_options"]); assertFalse(req.followRedirects)
    }

    @Test fun `anthropic stream carries thinking, signature and partial json`() = runBlocking {
        val gw = FakeStreamGateway { ok(sse(
            """{"type":"message_start","message":{"model":"claude-sonnet-4-5","usage":{"input_tokens":20,"cache_read_input_tokens":5,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hm"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"m"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG=="}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"ok"}}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tu1","name":"lookup","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"q\": \"d"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"ogs\"}"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":30}}""",
            """{"type":"message_stop"}""")) }
        val e = events(ChatModels.create(gw, cfg(ProviderKind.Anthropic, "claude-sonnet-4-5")))
        assertEquals("hmm", e.filterIsInstance<StreamEvent.ThinkingDelta>().joinToString("") { it.text })
        val r = done(e)
        assertEquals(listOf(ToolCall("tu1", "lookup", mapOf("q" to "dogs"))), r.toolCalls); assertEquals("ok", r.text); assertEquals("hmm", r.thinking)
        assertEquals(Usage(25, 30, cachedPromptTokens = 5), r.usage)
        assertEquals(listOf(mapOf("type" to "thinking", "thinking" to "hmm", "signature" to "SIG==")), r.providerData!!["anthropic.thinking_blocks"])
        assertEquals(true, (gw.requests.single().body as Map<*, *>)["stream"])
    }

    @Test fun `gemini stream uses the sse url, key in header, and keeps signatures`() = runBlocking {
        val gw = FakeStreamGateway { ok(sse(
            """{"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"lo"},{"functionCall":{"name":"lookup","args":{"q":"z"}},"thoughtSignature":"gSig"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":3}}""")) }
        val e = events(ChatModels.create(gw, cfg(ProviderKind.Gemini, "gemini-2.0-flash")))
        val r = done(e)
        assertEquals("Hello", r.text); assertEquals("gSig", r.toolCalls.single().providerData!!["gemini.thoughtSignature"]); assertEquals(Usage(9, 3), r.usage)
        val req = gw.requests.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:streamGenerateContent", req.url)
        assertEquals(mapOf("alt" to "sse"), req.query); assertEquals(key.reveal(), req.headers["x-goog-api-key"]); assertFalse(req.url.contains(key.reveal()))
    }

    @Test fun `ollama streams ndjson through the native endpoint`() = runBlocking {
        val gw = FakeStreamGateway { ok(linesOf(
            """{"message":{"role":"assistant","thinking":"hm"},"done":false}""",
            """{"message":{"role":"assistant","content":"Hi "},"done":false}""",
            "",
            """{"message":{"role":"assistant","content":"there","tool_calls":[{"function":{"name":"lookup","arguments":{"q":"x"}}}]},"done":false}""",
            """{"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":11,"eval_count":6}""")) }
        val c = ChatModels.create(gw, ModelConfig(ProviderKind.Ollama, "qwen3", "http://192.168.1.5:11434/v1", policy = ProviderEndpoints.policyFor(ProviderKind.Ollama)))
        val r = done(events(c))
        assertEquals("Hi there", r.text); assertEquals("hm", r.thinking); assertEquals(ToolCall("call_0", "lookup", mapOf("q" to "x")), r.toolCalls.single()); assertEquals(Usage(11, 6), r.usage)
        assertEquals("http://192.168.1.5:11434/api/chat", gw.requests.single().url)
        assertEquals("application/x-ndjson", gw.requests.single().headers["Accept"])
    }

    // ---- failures and cancellation ----

    @Test fun `a stream cut before its end marker is an error, not a short answer`() = runBlocking {
        val gw = FakeStreamGateway { ok(sse("""{"choices":[{"delta":{"content":"half"}}]}""")) }
        val e = runCatching { events(ChatModels.create(gw, cfg(ProviderKind.OpenAiCompatible, "gpt-4o"))) }.exceptionOrNull() as ModelException
        assertEquals(ModelErrorKind.Network, e.kind)
    }

    @Test fun `an error status on open maps to the taxonomy and reads the body`() = runBlocking {
        val gw = FakeStreamGateway { StreamingHttpResponse(429, mapOf("retry-after" to "3"), linesOf("""{"error":{"message":"slow","code":"rate_limit"}}""")) }
        val e = runCatching { events(ChatModels.create(gw, cfg(ProviderKind.OpenAiCompatible, "gpt-4o"))) }.exceptionOrNull() as ModelException
        assertEquals(ModelErrorKind.RateLimit, e.kind); assertEquals(3_000L, e.retryAfterMs)
    }

    @Test fun `an error event inside the stream is mapped`() = runBlocking {
        val gw = FakeStreamGateway { ok(sse("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")) }
        val e = runCatching { events(ChatModels.create(gw, cfg(ProviderKind.Anthropic, "claude-sonnet-4"))) }.exceptionOrNull() as ModelException
        assertEquals(ModelErrorKind.Server, e.kind); assertTrue(e.retryable)
    }

    @Test fun `a mid stream failure retries and tells the listener to drop what it saw`() = runBlocking {
        var n = 0
        val gw = FakeStreamGateway {
            if (n++ == 0) ok(flow { emit("data: {\"choices\":[{\"delta\":{\"content\":\"par\"}}]}"); emit(""); throw java.io.IOException("connection reset") })
            else ok(sse("""{"choices":[{"delta":{"content":"whole"},"finish_reason":"stop"}]}""", "[DONE]"))
        }
        val c = ChatModels.create(gw, cfg(ProviderKind.OpenAiCompatible, "gpt-4o", RetryPolicy(maxRetries = 2, sleep = {})))
        val e = events(c)
        val retry = e.filterIsInstance<StreamEvent.Retrying>().single()
        assertTrue(retry.discardedOutput); assertEquals(1, retry.retryNumber); assertEquals("whole", done(e).text); assertEquals(2, gw.requests.size)
    }

    @Test fun `cancelling the collector cancels the connection`() = runBlocking {
        val opened = CompletableDeferred<Unit>(); val closed = CompletableDeferred<Unit>()
        val gw = FakeStreamGateway {
            ok(flow { emit("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}"); emit(""); opened.complete(Unit); awaitCancellation() }.onCompletion { closed.complete(Unit) })
        }
        val job = async(Dispatchers.Default) { ChatModels.create(gw, cfg(ProviderKind.OpenAiCompatible, "gpt-4o")).stream(ask).collect { } }
        opened.await(); job.cancel()
        withTimeout(1_000) { closed.await() }
        assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test fun `a gateway that cannot stream still gives events, as one burst`() = runBlocking {
        val http = FakeGateway { HttpResponseSpec(200, body = mapOf("choices" to listOf(mapOf("finish_reason" to "stop", "message" to mapOf("content" to "plain"))))) }
        val e = events(ChatModels.create(http, cfg(ProviderKind.OpenAiCompatible, "gpt-4o")))
        assertEquals(listOf("plain"), e.filterIsInstance<StreamEvent.TextDelta>().map { it.text }); assertEquals("plain", done(e).text)
        assertFalse((http.requests.single().body as Map<*, *>).containsKey("stream"))
    }

    @Test fun `keys never appear in stream errors`() = runBlocking {
        val gw = FakeStreamGateway { StreamingHttpResponse(401, emptyMap(), linesOf("""{"error":{"message":"bad key ${key.reveal()}"}}""")) }
        val e = runCatching { events(ChatModels.create(gw, cfg(ProviderKind.OpenAiCompatible, "gpt-4o"))) }.exceptionOrNull() as ModelException
        assertFalse(e.message!!.contains(key.reveal()))
    }
}
