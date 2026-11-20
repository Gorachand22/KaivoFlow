package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.HttpResponseSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {
    private val key = "sk-test-SECRET-123456"
    private val tool = ToolSpec("lookup", "Look up a thing", mapOf("type" to "object", "properties" to mapOf("q" to mapOf("type" to "string")), "additionalProperties" to false))
    private val convo = listOf(
        ChatMessage.user("find it"),
        ChatMessage.assistant("", listOf(ToolCall("t1", "lookup", mapOf("q" to "x")))),
        ChatMessage.tool(ToolCall("t1", "lookup"), "found"),
    )

    private fun openAi(http: FakeGateway, base: String = "", provider: ProviderKind = ProviderKind.OpenAiCompatible, k: String? = key, policy: EndpointPolicy = EndpointPolicy()) =
        ChatModels.create(http, ModelConfig(provider, "gpt-x", base, k?.let { ApiKey(it) }, policy = policy, retry = RetryPolicy.None))

    @Test fun `openai request shape, key in header only, redirects off`() = runBlocking {
        val http = FakeGateway { HttpResponseSpec(200, body = mapOf(
            "choices" to listOf(mapOf("finish_reason" to "tool_calls", "message" to mapOf("content" to null, "tool_calls" to listOf(
                mapOf("id" to "a1", "type" to "function", "function" to mapOf("name" to "lookup", "arguments" to "{\"q\":\"cats\"}")))))),
            "usage" to mapOf("prompt_tokens" to 12, "completion_tokens" to 3))) }
        val r = openAi(http).complete(ChatRequest("sys", convo, listOf(tool), 0.2, 256))
        val req = http.requests.single()
        assertEquals("https://api.openai.com/v1/chat/completions", req.url)
        assertEquals("Bearer $key", req.headers["Authorization"])
        assertFalse(req.url.contains("key")); assertTrue(req.query.isEmpty())
        assertFalse(req.followRedirects); assertFalse(req.failOnErrorStatus)
        val body = req.body as Map<*, *>
        val msgs = body["messages"] as List<*>
        assertEquals("system", (msgs[0] as Map<*, *>)["role"]); assertEquals("tool", (msgs[3] as Map<*, *>)["role"])
        assertEquals("t1", (msgs[3] as Map<*, *>)["tool_call_id"])
        assertEquals(256, body["max_completion_tokens"]); assertFalse(body.containsKey("max_tokens")); assertTrue(body.containsKey("tools"))
        assertEquals(ToolCall("a1", "lookup", mapOf("q" to "cats")), r.toolCalls.single())
        assertEquals(Usage(12, 3), r.usage); assertEquals(FinishReason.ToolCalls, r.finish)
    }

    @Test fun `openai json mode and a reasoning only reply is not promoted to an answer`() = runBlocking {
        val http = FakeGateway { HttpResponseSpec(200, bodyText = """{"choices":[{"finish_reason":"stop","message":{"content":"","reasoning_content":"secret chain of thought"}}]}""") }
        val r = openAi(http).complete(ChatRequest(null, listOf(ChatMessage.user("hi")), jsonMode = true))
        assertEquals("", r.text)
        assertEquals(mapOf("type" to "json_object"), (http.requests.single().body as Map<*, *>)["response_format"])
    }

    @Test fun `malformed tool arguments are surfaced not thrown`() = runBlocking {
        val http = FakeGateway { HttpResponseSpec(200, body = mapOf("choices" to listOf(mapOf("message" to mapOf("tool_calls" to listOf(
            mapOf("id" to "a", "function" to mapOf("name" to "lookup", "arguments" to "{\"q\": ")))))))) }
        val c = openAi(http).complete(ChatRequest(null, listOf(ChatMessage.user("hi")))).toolCalls.single()
        assertTrue(c.argumentsError != null); assertTrue(c.arguments.isEmpty())
    }

    @Test fun `ollama needs no key, a remote openai server does`() = runBlocking {
        val http = FakeGateway { HttpResponseSpec(200, body = mapOf("message" to mapOf("role" to "assistant", "content" to "ok"), "done" to true, "done_reason" to "stop", "prompt_eval_count" to 5, "eval_count" to 2)) }
        val ollama = openAi(http, "http://localhost:11434/v1", ProviderKind.Ollama, k = null, policy = EndpointPolicy(allowLoopback = true))
        val r = ollama.complete(ChatRequest(null, listOf(ChatMessage.user("x")), temperature = 0.3, maxOutputTokens = 64))
        assertEquals("ok", r.text); assertEquals(Usage(5, 2), r.usage)
        val req = http.requests.single()
        assertEquals("http://localhost:11434/api/chat", req.url)
        assertFalse(req.headers.containsKey("Authorization"))
        assertEquals(false, (req.body as Map<*, *>)["stream"]); assertEquals(64, ((req.body as Map<*, *>)["options"] as Map<*, *>)["num_predict"])
        val e = runCatching { openAi(http, k = null).complete(ChatRequest(null, listOf(ChatMessage.user("x")))) }.exceptionOrNull() as ModelException
        assertEquals(ModelErrorKind.Auth, e.kind)
    }

    @Test fun `a blocked base url fails at construction before any request`() {
        val http = FakeGateway()
        val e = runCatching { openAi(http, "http://169.254.169.254/v1") }.exceptionOrNull() as ModelException
        assertEquals(ModelErrorKind.Policy, e.kind); assertTrue(http.requests.isEmpty())
        assertTrue(runCatching { openAi(http, "http://localhost:11434/v1", ProviderKind.Ollama, null) }.exceptionOrNull() is ModelException)
    }

    @Test fun `anthropic request and parse`() = runBlocking {
        val http = FakeGateway { HttpResponseSpec(200, body = mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to "thinking aloud"), mapOf("type" to "tool_use", "id" to "tu1", "name" to "lookup", "input" to mapOf("q" to "dogs"))),
            "stop_reason" to "tool_use", "usage" to mapOf("input_tokens" to 20, "output_tokens" to 5))) }
        val c = ChatModels.create(http, ModelConfig(ProviderKind.Anthropic, "claude-sonnet-4-20250514", apiKey = ApiKey(key), retry = RetryPolicy.None))
        val r = c.complete(ChatRequest("sys", convo + ChatMessage.tool(ToolCall("t2", "lookup"), "second", isError = true), listOf(tool), 1.7, 300, jsonMode = true))
        val req = http.requests.single()
        assertEquals("https://api.anthropic.com/v1/messages", req.url)
        assertEquals(key, req.headers["x-api-key"]); assertEquals("2023-06-01", req.headers["anthropic-version"]); assertFalse(req.headers.containsKey("Authorization"))
        val body = req.body as Map<*, *>
        assertEquals(300, body["max_tokens"]); assertEquals(1.0, body["temperature"])
        assertTrue((body["system"] as String).startsWith("sys"))
        val msgs = body["messages"] as List<*>
        assertEquals(3, msgs.size) // user, assistant(tool_use), user(tool_result)
        assertEquals("user", (msgs[2] as Map<*, *>)["role"])
        assertEquals("tool", ((body["tools"] as List<*>)[0] as Map<*, *>).let { "tool" }) // tools present
        assertEquals("thinking aloud", r.text); assertEquals(ToolCall("tu1", "lookup", mapOf("q" to "dogs")), r.toolCalls.single())
        assertEquals(Usage(20, 5), r.usage); assertEquals(FinishReason.ToolCalls, r.finish)
    }

    @Test fun `gemini puts the key in a header and never in the url`() = runBlocking {
        val http = FakeGateway { HttpResponseSpec(200, body = mapOf(
            "candidates" to listOf(mapOf("finishReason" to "STOP", "content" to mapOf("parts" to listOf(mapOf("functionCall" to mapOf("name" to "lookup", "args" to mapOf("q" to "z"))))))),
            "usageMetadata" to mapOf("promptTokenCount" to 7, "candidatesTokenCount" to 2))) }
        val c = ChatModels.create(http, ModelConfig(ProviderKind.Gemini, "gemini-2.0-flash", apiKey = ApiKey(key), retry = RetryPolicy.None))
        val r = c.complete(ChatRequest("sys", convo, listOf(tool), 0.3, 128, jsonMode = false))
        val req = http.requests.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent", req.url)
        assertFalse(req.url.contains(key)); assertFalse(req.url.contains("key=")); assertTrue(req.query.isEmpty())
        assertEquals(key, req.headers["x-goog-api-key"])
        val body = req.body as Map<*, *>
        assertFalse(J.write(body).contains("additionalProperties"))
        assertEquals(ToolCall("call_0", "lookup", mapOf("q" to "z")), r.toolCalls.single()); assertEquals(Usage(7, 2), r.usage)
    }

    @Test fun `gemini rejects model names that could change the path`() {
        val e = runCatching { ChatModels.create(FakeGateway(), ModelConfig(ProviderKind.Gemini, "x/../../admin", apiKey = ApiKey(key))) }.exceptionOrNull()
        assertTrue(e is ModelException)
    }

    @Test fun `http errors map to kinds and never echo the key`() = runBlocking {
        suspend fun errorFor(status: Int, message: String): ModelException {
            val http = FakeGateway { HttpResponseSpec(status, body = mapOf("error" to mapOf("message" to message))) }
            return runCatching { openAi(http).complete(ChatRequest(null, listOf(ChatMessage.user("x")))) }.exceptionOrNull() as ModelException
        }
        val auth = errorFor(401, "Incorrect API key provided: $key. You can find it at...")
        assertEquals(ModelErrorKind.Auth, auth.kind); assertFalse(auth.message!!.contains(key))
        assertEquals(ModelErrorKind.RateLimit, errorFor(429, "slow down").kind)
        assertEquals(ModelErrorKind.BadRequest, errorFor(400, "bad").kind)
        assertEquals(ModelErrorKind.Server, errorFor(503, "down").kind)
    }

    @Test fun `redirects are refused and network exceptions are redacted`() = runBlocking {
        val redirect = FakeGateway { HttpResponseSpec(302, headers = mapOf("Location" to "https://evil.example")) }
        assertEquals(ModelErrorKind.Redirect, (runCatching { openAi(redirect).complete(ChatRequest(null, listOf(ChatMessage.user("x")))) }.exceptionOrNull() as ModelException).kind)
        val boom = FakeGateway { throw java.io.IOException("connect failed for https://x/?key=$key") }
        val e = runCatching { openAi(boom).complete(ChatRequest(null, listOf(ChatMessage.user("x")))) }.exceptionOrNull() as ModelException
        assertEquals(ModelErrorKind.Network, e.kind); assertFalse(e.message!!.contains(key))
    }

    @Test fun `api key never prints and provider keys parse strictly`() {
        assertEquals("ApiKey(***)", ApiKey(key).toString())
        assertFalse(ModelConfig(ProviderKind.Gemini, "m", apiKey = ApiKey(key)).toString().contains(key))
        assertEquals(ProviderKind.Anthropic, ProviderKind.fromKey("claude")); assertNull(ProviderKind.fromKey("slack"))
    }
}
