package com.kaivoflow.core.ai

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SSE framing edge cases, as they reach the model client after the gateway split the body into lines. */
class StreamingEdgeTest {
    private val ask = ChatRequest("sys", listOf(ChatMessage.user("go")))
    private fun client(vararg lines: String) = ChatModels.create(
        FakeStreamGateway { StreamingHttpResponse(200, emptyMap(), flow { lines.forEach { emit(it) } }) },
        ModelConfig(ProviderKind.OpenAiCompatible, "gpt-4o", apiKey = ApiKey("sk-test-123456"), policy = ProviderEndpoints.policyFor(ProviderKind.OpenAiCompatible)),
    )
    private suspend fun run(c: ChatModelClient) = withTimeout(5_000) { c.stream(ask).toList() }
    private val a = """{"choices":[{"delta":{"content":"Hel"}}]}"""
    private val b = """{"choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}"""

    @Test fun `comments, crlf and a missing space after the colon are accepted`() = runBlocking {
        val e = run(client(": ping\r", "data:$a\r", "\r", ": another comment", "data: $b\r", "\r", "data: [DONE]\r", "\r"))
        assertEquals("Hello", (e.last() as StreamEvent.Done).response.text)
    }

    @Test fun `one json value split over several data lines is joined with newlines`() = runBlocking {
        val e = run(client("data: {\"choices\":[{\"delta\":", "data: {\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}", "", "data: [DONE]", ""))
        assertEquals("Hi", (e.last() as StreamEvent.Done).response.text)
    }

    @Test fun `done marker ends the stream and later lines are ignored`() = runBlocking {
        val e = run(client("data: $b", "", "data: [DONE]", "", "data: {not json", ""))
        assertEquals("lo", (e.last() as StreamEvent.Done).response.text)
    }

    @Test fun `last event without the closing blank line still counts`() = runBlocking {
        val e = run(client("data: $a", "", "data: $b"))
        assertEquals("Hello", (e.last() as StreamEvent.Done).response.text)
    }

    @Test fun `unknown fields, ids and retry hints are ignored`() = runBlocking {
        val e = run(client("id: 7", "retry: 3000", "event: message", "data: $b", "", "data: [DONE]", ""))
        assertEquals("lo", (e.last() as StreamEvent.Done).response.text)
    }

    @Test fun `a body that ends without any finish marker is an error`() = runBlocking {
        val r = runCatching { run(client("data: $a", "")) }.exceptionOrNull()
        assertTrue(r is ModelException)
    }

    @Test fun `a partial json line then end of body is reported, not swallowed`() = runBlocking {
        val r = runCatching { run(client("data: $a", "", "data: {\"choices\":[{\"delta\":{\"cont")) }.exceptionOrNull()
        assertTrue(r is ModelException)
    }
}
