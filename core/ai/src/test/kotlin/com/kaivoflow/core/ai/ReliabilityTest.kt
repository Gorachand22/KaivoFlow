package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.HttpResponseSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityTest {
    private val slept = mutableListOf<Long>()
    private val policy = RetryPolicy(maxRetries = 2, baseDelayMs = 100, maxDelayMs = 1_000, jitter = 0.0, random = { 0.5 }, sleep = { slept += it })
    private val ask = ChatRequest(null, listOf(ChatMessage.user("hi")))
    private val ok = HttpResponseSpec(200, body = mapOf("choices" to listOf(mapOf("finish_reason" to "stop", "message" to mapOf("content" to "fine")))))

    private fun client(http: FakeGateway, retry: RetryPolicy = policy, model: String = "gpt-x", base: String = "") =
        ChatModels.create(http, ModelConfig(ProviderKind.OpenAiCompatible, model, base, ApiKey("sk-test-123456"), retry = retry))

    private fun errBody(msg: String, code: String? = null) = mapOf("error" to buildMap<String, Any?> { put("message", msg); if (code != null) put("code", code) })

    @Test fun `429 with Retry-After 2s retries once and waits that long`() = runBlocking {
        var n = 0
        val http = FakeGateway { if (n++ == 0) HttpResponseSpec(429, headers = mapOf("Retry-After" to "2"), body = errBody("slow down")) else ok }
        assertEquals("fine", client(http).complete(ask).text)
        assertEquals(2, http.requests.size)
        assertEquals(1, slept.size); assertTrue(slept[0] in 2_000..2_300)
    }

    @Test fun `auth, bad request and too large are never retried`() = runBlocking {
        for (status in listOf(400, 401, 402, 403, 404, 405, 406, 407, 409, 413)) {
            slept.clear()
            val http = FakeGateway { HttpResponseSpec(status, body = errBody("no")) }
            val e = runCatching { client(http).complete(ask) }.exceptionOrNull() as ModelException
            assertEquals("status $status", 1, http.requests.size); assertTrue(slept.isEmpty()); assertEquals(status, e.status); assertFalse(e.retryable)
        }
    }

    @Test fun `taxonomy keeps kinds apart`() = runBlocking {
        suspend fun kind(status: Int, msg: String, code: String? = null, headers: Map<String, String> = emptyMap()): ModelException {
            val http = FakeGateway { HttpResponseSpec(status, headers = headers, body = errBody(msg, code)) }
            return runCatching { client(http, RetryPolicy.None).complete(ask) }.exceptionOrNull() as ModelException
        }
        assertEquals(ModelErrorKind.Auth, kind(401, "bad key").kind)
        assertEquals(ModelErrorKind.Quota, kind(402, "pay").kind)
        assertEquals(ModelErrorKind.PayloadTooLarge, kind(413, "big").kind)
        assertEquals(ModelErrorKind.Quota, kind(429, "You exceeded your current quota, please check your plan and billing details.", "insufficient_quota").kind)
        assertEquals(ModelErrorKind.RateLimit, kind(429, "Rate limit reached for requests per minute").kind)
        assertEquals(ModelErrorKind.ContextLength, kind(400, "This model's maximum context length is 8192 tokens", "context_length_exceeded").kind)
        assertEquals(ModelErrorKind.BadRequest, kind(404, "model not found").kind)
        assertEquals(ModelErrorKind.Server, kind(529, "overloaded").kind)
        assertEquals("insufficient_quota", kind(429, "quota", "insufficient_quota").providerCode)
        val rl = kind(429, "wait", headers = mapOf("retry-after-ms" to "1500"))
        assertEquals(1500L, rl.retryAfterMs); assertTrue(rl.hint.isNotBlank())
        assertEquals(30_000L, kind(429, "Please retry in 30s").retryAfterMs)
    }

    @Test fun `429 quota text and a Retry-After over 60s stop at once`() = runBlocking {
        val quota = FakeGateway { HttpResponseSpec(429, body = errBody("You exceeded your current quota", "insufficient_quota")) }
        val e1 = runCatching { client(quota).complete(ask) }.exceptionOrNull() as ModelException
        assertEquals(ModelErrorKind.Quota, e1.kind); assertEquals(1, quota.requests.size)
        val long = FakeGateway { HttpResponseSpec(429, headers = mapOf("retry-after" to "120"), body = errBody("slow")) }
        val e2 = runCatching { client(long).complete(ask) }.exceptionOrNull() as ModelException
        assertEquals(ModelErrorKind.RateLimit, e2.kind); assertEquals(1, long.requests.size); assertTrue(slept.isEmpty())
    }

    @Test fun `5xx and network failures back off with growing delays and stop after two retries`() = runBlocking {
        val http = FakeGateway { HttpResponseSpec(503, body = errBody("down")) }
        val e = runCatching { client(http).complete(ask) }.exceptionOrNull() as ModelException
        assertEquals(3, http.requests.size); assertEquals(listOf(100L, 200L), slept); assertEquals(3, e.attempts); assertEquals(ModelErrorKind.Server, e.kind)
        slept.clear()
        var n = 0
        val net = FakeGateway { if (n++ < 2) throw java.io.IOException("reset by peer") else ok }
        assertEquals("fine", client(net).complete(ask).text); assertEquals(2, slept.size)
    }

    @Test fun `jitter keeps delays inside the band`() {
        val p = RetryPolicy(baseDelayMs = 1_000, maxDelayMs = 8_000, jitter = 0.25, random = { 0.0 })
        val e = ModelException(ModelErrorKind.Server, "x", 500)
        assertEquals(750L, p.delayFor(0, e))
        assertEquals(2_500L, RetryPolicy(baseDelayMs = 1_000, jitter = 0.25, random = { 1.0 }).delayFor(1, e))
        assertEquals(8_000L, RetryPolicy(baseDelayMs = 1_000, maxDelayMs = 8_000, jitter = 0.0).delayFor(10, e))
    }

    @Test fun `cancelling during a backoff wait ends the call at once`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val retry = RetryPolicy(maxRetries = 2, sleep = { started.complete(Unit); awaitCancellation() })
        val http = FakeGateway { HttpResponseSpec(503, body = errBody("down")) }
        val job = async(Dispatchers.Default) { client(http, retry).complete(ask) }
        started.await()
        val t0 = System.nanoTime(); job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 100); assertEquals(1, http.requests.size)
    }

    // ---- capability table ----

    private fun body(model: String, base: String = "", req: ChatRequest = ChatRequest(null, listOf(ChatMessage.user("x")), temperature = 0.4, maxOutputTokens = 512), provider: ProviderKind = ProviderKind.OpenAiCompatible): Map<*, *> {
        val http = FakeGateway { ok }
        runBlocking { runCatching { ChatModels.create(http, ModelConfig(provider, model, base, ApiKey("sk-test-123456"), policy = ProviderEndpoints.policyFor(provider), retry = RetryPolicy.None)).complete(req) } }
        return http.requests.single().body as Map<*, *>
    }

    @Test fun `openai reasoning models get max_completion_tokens, no temperature and room to think`() {
        for (m in listOf("o3", "o3-mini", "o4-mini", "o1", "gpt-5", "gpt-5-mini")) {
            val b = body(m)
            assertFalse(m, b.containsKey("temperature")); assertFalse(b.containsKey("max_tokens"))
            assertEquals(m, 4_096, b["max_completion_tokens"])
        }
        val big = body("o3", req = ChatRequest(null, listOf(ChatMessage.user("x")), maxOutputTokens = 9_000))
        assertEquals(9_000, big["max_completion_tokens"])
        val eff = body("o3", req = ChatRequest(null, listOf(ChatMessage.user("x")), reasoning = ReasoningConfig(effort = ReasoningEffort.High)))
        assertEquals("high", eff["reasoning_effort"])
        assertFalse(body("gpt-4o", req = ChatRequest(null, listOf(ChatMessage.user("x")), reasoning = ReasoningConfig(effort = ReasoningEffort.High))).containsKey("reasoning_effort"))
    }

    @Test fun `ordinary openai models keep temperature, compatible servers keep max_tokens`() {
        val b = body("gpt-4o"); assertEquals(0.4, b["temperature"]); assertEquals(512, b["max_completion_tokens"])
        val other = body("gpt-5", base = "https://openrouter.ai/api/v1")
        assertEquals(0.4, other["temperature"]); assertEquals(512, other["max_tokens"]); assertFalse(other.containsKey("max_completion_tokens"))
        assertTrue(body("gpt-5-chat-latest").containsKey("temperature"))
    }

    @Test fun `anthropic sampling follows the allow list`() {
        fun temp(m: String) = body(m, provider = ProviderKind.Anthropic).containsKey("temperature")
        assertTrue(temp("claude-3-5-sonnet-20241022")); assertTrue(temp("claude-sonnet-4-5")); assertTrue(temp("claude-opus-4-5-20251101")); assertTrue(temp("my-gateway-model"))
        assertFalse(temp("claude-opus-4-7")); assertFalse(temp("claude-sonnet-5")); assertFalse(temp("claude-future-9"))
    }

    @Test fun `anthropic thinking drops temperature, raises max_tokens and never forces a tool`() {
        val req = ChatRequest(null, listOf(ChatMessage.user("x")), listOf(ToolSpec("t", "d", mapOf("type" to "object"))), 0.5, 512,
            toolChoice = ToolChoice.Required, reasoning = ReasoningConfig(budgetTokens = 2_000))
        val b = body("claude-sonnet-4-5", req = req, provider = ProviderKind.Anthropic)
        assertEquals(mapOf("type" to "enabled", "budget_tokens" to 2_000), b["thinking"])
        assertFalse(b.containsKey("temperature")); assertEquals(3_024, b["max_tokens"]); assertFalse(b.containsKey("tool_choice"))
    }

    @Test fun `tool choice maps for every provider`() {
        val tools = listOf(ToolSpec("lookup", "d", mapOf("type" to "object")))
        fun req(c: ToolChoice) = ChatRequest(null, listOf(ChatMessage.user("x")), tools, toolChoice = c)
        assertEquals("required", body("gpt-4o", req = req(ToolChoice.Required))["tool_choice"])
        assertEquals("none", body("gpt-4o", req = req(ToolChoice.None))["tool_choice"])
        assertEquals(mapOf("type" to "function", "function" to mapOf("name" to "lookup")), body("gpt-4o", req = req(ToolChoice.Specific("lookup")))["tool_choice"])
        assertFalse(body("gpt-4o", req = req(ToolChoice.Auto)).containsKey("tool_choice"))
        val a = ProviderKind.Anthropic
        assertEquals(mapOf("type" to "any"), body("claude-sonnet-4", req = req(ToolChoice.Required), provider = a)["tool_choice"])
        assertEquals(mapOf("type" to "tool", "name" to "lookup"), body("claude-sonnet-4", req = req(ToolChoice.Specific("lookup")), provider = a)["tool_choice"])
        assertEquals(mapOf("type" to "none"), body("claude-sonnet-4", req = req(ToolChoice.None), provider = a)["tool_choice"])
        val g = ProviderKind.Gemini
        fun cfg(c: ToolChoice) = (body("gemini-2.5-flash", req = req(c), provider = g)["toolConfig"] as Map<*, *>?)?.get("functionCallingConfig")
        assertEquals(mapOf("mode" to "ANY"), cfg(ToolChoice.Required))
        assertEquals(mapOf("mode" to "ANY", "allowedFunctionNames" to listOf("lookup")), cfg(ToolChoice.Specific("lookup")))
        assertEquals(mapOf("mode" to "NONE"), cfg(ToolChoice.None)); assertNull(cfg(ToolChoice.Auto))
        val o = body("llama3.2", base = "http://localhost:11434/v1", req = req(ToolChoice.Required), provider = ProviderKind.Ollama)
        assertFalse(o.containsKey("tool_choice"))
    }

    @Test fun `gemini 2_5 gets head-room and empty tool arguments are sent as an object`() {
        val b = body("gemini-2.5-pro", provider = ProviderKind.Gemini)
        assertEquals(4_096, (b["generationConfig"] as Map<*, *>)["maxOutputTokens"])
        val msgs = listOf(ChatMessage.user("x"), ChatMessage.assistant("", listOf(ToolCall("a", "t"))), ChatMessage.tool(ToolCall("a", "t"), "r"))
        val oa = body("gpt-4o", req = ChatRequest(null, msgs))
        val call = ((oa["messages"] as List<*>)[1] as Map<*, *>)["tool_calls"] as List<*>
        assertEquals("{}", ((call[0] as Map<*, *>)["function"] as Map<*, *>)["arguments"])
    }

    // ---- fallback chain ----

    private class Boom(val kind: ModelErrorKind = ModelErrorKind.Server) : ChatModelClient {
        var calls = 0
        override suspend fun complete(request: ChatRequest): ChatResponse { calls++; throw ModelException(kind, "primary down", 503) }
    }

    @Test fun `primary failure moves to the fallback and the loop reports it`() = runBlocking {
        val fallback = ScriptedModel(say("from fallback"))
        val r = AgentLoop(Boom(), fallbacks = listOf(fallback), nonceSource = { "n" }).run(null, "go")
        assertTrue(r.isSuccess); assertEquals("from fallback", r.text); assertTrue(r.fallbackUsed)
        val plain = AgentLoop(ScriptedModel(say("primary")), fallbacks = listOf(fallback), nonceSource = { "n" }).run(null, "go")
        assertFalse(plain.fallbackUsed)
    }

    @Test fun `cancellation never triggers the fallback`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val hang = object : ChatModelClient { override suspend fun complete(request: ChatRequest): ChatResponse { started.complete(Unit); awaitCancellation() } }
        val fb = Boom()
        val job = async(Dispatchers.Default) { FallbackModelClient(listOf(hang, fb)).complete(ask) }
        started.await(); job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(0, fb.calls)
    }

    @Test fun `all models failing reports the first error plus the last`() = runBlocking {
        val e = runCatching { FallbackModelClient(listOf(Boom(ModelErrorKind.Auth), Boom())).complete(ask) }.exceptionOrNull() as ModelException
        assertEquals(ModelErrorKind.Auth, e.kind); assertTrue(e.message!!.contains("fallback model also failed"))
    }

    @Test fun `a stream announces the switch and drops partial output`() = runBlocking {
        val half = object : ChatModelClient {
            override suspend fun complete(request: ChatRequest) = error("unused")
            override fun stream(request: ChatRequest) = kotlinx.coroutines.flow.flow<StreamEvent> { emit(StreamEvent.TextDelta("par")); throw ModelException(ModelErrorKind.Network, "cut") }
        }
        val events = withTimeout(5_000) { FallbackModelClient(listOf(half, ScriptedModel(say("whole")))).stream(ask).toList() }
        val sw = events.filterIsInstance<StreamEvent.FallbackUsed>().single()
        assertTrue(sw.discardedOutput); assertEquals(1, (events.last() as StreamEvent.Done).response.servedBy)
    }
}
