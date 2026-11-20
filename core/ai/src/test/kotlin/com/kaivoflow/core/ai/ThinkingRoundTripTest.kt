package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.HttpResponseSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thinking blocks and thought signatures leave the provider, ride on the message as opaque data and come back unchanged. */
class ThinkingRoundTripTest {
    private val key = ApiKey("sk-test-123456")
    private val none = RetryPolicy.None

    private val thinkingBlock = mapOf("type" to "thinking", "thinking" to "let me think\nabout it", "signature" to "EqQBCkYIAxgC+/==signature")
    private val redacted = mapOf("type" to "redacted_thinking", "data" to "EmwKAhgB-opaque")

    @Test fun `anthropic thinking blocks come back byte equal before the tool_use`() = runBlocking {
        val first = HttpResponseSpec(200, body = mapOf(
            "model" to "claude-sonnet-4-5-20250929", "stop_reason" to "tool_use",
            "content" to listOf(thinkingBlock, redacted, mapOf("type" to "text", "text" to "checking"), mapOf("type" to "tool_use", "id" to "tu1", "name" to "lookup", "input" to mapOf("q" to "x"))),
            "usage" to mapOf("input_tokens" to 10, "output_tokens" to 4, "cache_read_input_tokens" to 6)))
        val http = FakeGateway { first }
        val c = ChatModels.create(http, ModelConfig(ProviderKind.Anthropic, "claude-sonnet-4-5", apiKey = key, retry = none))
        val r1 = c.complete(ChatRequest(null, listOf(ChatMessage.user("go")), reasoning = ReasoningConfig(budgetTokens = 1_024)))
        assertEquals("let me think\nabout it", r1.thinking); assertEquals("checking", r1.text)
        assertEquals(Usage(16, 4, cachedPromptTokens = 6), r1.usage)
        assertNotNull(r1.providerData)
        val history = listOf(ChatMessage.user("go"), ChatMessage.assistant(r1.text, r1.toolCalls, r1.providerData), ChatMessage.tool(r1.toolCalls[0], "found"))
        c.complete(ChatRequest(null, history, reasoning = ReasoningConfig(budgetTokens = 1_024)))
        val sent = (http.requests[1].body as Map<*, *>)["messages"] as List<*>
        val blocks = (sent[1] as Map<*, *>)["content"] as List<*>
        assertEquals(thinkingBlock, blocks[0]); assertEquals(redacted, blocks[1])
        assertEquals("text", (blocks[2] as Map<*, *>)["type"]); assertEquals("tool_use", (blocks[3] as Map<*, *>)["type"])
        assertEquals(J.write(thinkingBlock), J.write(blocks[0]))
    }

    @Test fun `thinking data of another model is never replayed`() = runBlocking {
        val http = FakeGateway { HttpResponseSpec(200, body = mapOf("content" to listOf(mapOf("type" to "text", "text" to "ok")), "stop_reason" to "end_turn")) }
        val c = ChatModels.create(http, ModelConfig(ProviderKind.Anthropic, "claude-haiku-4-5", apiKey = key, retry = none))
        val foreign = mapOf("anthropic.thinking_blocks" to listOf(thinkingBlock), "_model" to "claude-sonnet-4-5")
        c.complete(ChatRequest(null, listOf(ChatMessage.user("go"), ChatMessage.assistant("t", listOf(ToolCall("a", "t")), foreign), ChatMessage.tool(ToolCall("a", "t"), "r"))))
        val blocks = ((http.requests[0].body as Map<*, *>)["messages"] as List<*>).let { ((it[1] as Map<*, *>)["content"] as List<*>) }
        assertTrue(blocks.none { (it as Map<*, *>)["type"] == "thinking" })
    }

    @Test fun `gemini thought signatures ride on the tool call and the text`() = runBlocking {
        val reply = HttpResponseSpec(200, body = mapOf(
            "candidates" to listOf(mapOf("finishReason" to "STOP", "content" to mapOf("parts" to listOf(
                mapOf("text" to "I will look", "thoughtSignature" to "sigText=="),
                mapOf("functionCall" to mapOf("name" to "lookup", "args" to mapOf("q" to "z")), "thoughtSignature" to "sigCall/+=="),
                mapOf("functionCall" to mapOf("name" to "other", "args" to emptyMap<String, Any?>())))))),
            "usageMetadata" to mapOf("promptTokenCount" to 7, "candidatesTokenCount" to 2, "thoughtsTokenCount" to 30)))
        val http = FakeGateway { reply }
        val c = ChatModels.create(http, ModelConfig(ProviderKind.Gemini, "gemini-2.5-flash", apiKey = key, retry = none))
        val r = c.complete(ChatRequest(null, listOf(ChatMessage.user("go"))))
        assertEquals(Usage(7, 32, reasoningTokens = 30), r.usage)
        assertEquals("sigCall/+==", r.toolCalls[0].providerData!!["gemini.thoughtSignature"]); assertNull(r.toolCalls[1].providerData)
        val h = listOf(ChatMessage.user("go"), ChatMessage.assistant(r.text, r.toolCalls, r.providerData), ChatMessage.tool(r.toolCalls[0], "a"), ChatMessage.tool(r.toolCalls[1], "b"))
        c.complete(ChatRequest(null, h))
        val parts = (((http.requests[1].body as Map<*, *>)["contents"] as List<*>)[1] as Map<*, *>)["parts"] as List<*>
        assertEquals("sigText==", (parts[0] as Map<*, *>)["thoughtSignature"])
        assertEquals("sigCall/+==", (parts[1] as Map<*, *>)["thoughtSignature"]); assertFalse((parts[2] as Map<*, *>).containsKey("thoughtSignature"))
    }

    @Test fun `gemini thought text is thinking, not the answer`() = runBlocking {
        val http = FakeGateway { HttpResponseSpec(200, body = mapOf("candidates" to listOf(mapOf("finishReason" to "STOP", "content" to mapOf("parts" to listOf(mapOf("text" to "hmm", "thought" to true), mapOf("text" to "42")))))))}
        val r = ChatModels.create(http, ModelConfig(ProviderKind.Gemini, "gemini-2.5-flash", apiKey = key, retry = none)).complete(ChatRequest(null, listOf(ChatMessage.user("q"), ), reasoning = ReasoningConfig(budgetTokens = 512)))
        assertEquals("42", r.text); assertEquals("hmm", r.thinking)
        assertEquals(mapOf("includeThoughts" to true, "thinkingBudget" to 512), ((http.requests[0].body as Map<*, *>)["generationConfig"] as Map<*, *>)["thinkingConfig"])
    }

    @Test fun `openai compatible reasoning_content is replayed only with tool calls`() = runBlocking {
        val reply = HttpResponseSpec(200, body = mapOf("choices" to listOf(mapOf("finish_reason" to "tool_calls", "message" to mapOf("content" to null, "reasoning_content" to "thinking...",
            "tool_calls" to listOf(mapOf("id" to "a", "function" to mapOf("name" to "t", "arguments" to "{}")))))), "model" to "deepseek-reasoner"))
        val http = FakeGateway { reply }
        val c = ChatModels.create(http, ModelConfig(ProviderKind.OpenAiCompatible, "deepseek-reasoner", "https://api.deepseek.com", key, retry = none))
        val r = c.complete(ChatRequest(null, listOf(ChatMessage.user("go"))))
        c.complete(ChatRequest(null, listOf(ChatMessage.user("go"), ChatMessage.assistant("", r.toolCalls, r.providerData), ChatMessage.tool(r.toolCalls[0], "x"))))
        val asst = ((http.requests[1].body as Map<*, *>)["messages"] as List<*>)[1] as Map<*, *>
        assertEquals("thinking...", asst["reasoning_content"])
        val plain = ChatMessage.assistant("done", emptyList(), r.providerData)
        c.complete(ChatRequest(null, listOf(ChatMessage.user("go"), plain)))
        assertFalse(((http.requests[2].body as Map<*, *>)["messages"] as List<*>)[1].let { (it as Map<*, *>).containsKey("reasoning_content") })
    }

    @Test fun `the loop puts provider data on the assistant message it sends back`() = runBlocking {
        val data = mapOf("anthropic.thinking_blocks" to listOf(thinkingBlock), "_model" to "m")
        val model = ScriptedModel(call("search", mapOf("q" to "x")).copy(providerData = data), say("done"))
        AgentLoop(model, ToolRegistry(listOf(FakeTool("search"))), nonceSource = { "n" }).run(null, "go")
        assertEquals(data, model.requests[1].messages[1].providerData)
    }
}
