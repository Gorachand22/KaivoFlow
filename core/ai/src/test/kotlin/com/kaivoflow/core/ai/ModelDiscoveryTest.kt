package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.HttpResponseSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDiscoveryTest {
    private val key = "AIzaSyD-test-key-0123456789abcdef"

    private fun discover(kind: ProviderKind, base: String, body: Map<String, Any?>, status: Int = 200, apiKey: String? = key): Pair<List<String>, FakeGateway> {
        val http = FakeGateway { HttpResponseSpec(status, body = body) }
        val out = runBlocking { ModelDiscovery(http).list(ProviderEndpoints.config(kind, "m", base, apiKey)) }
        return out to http
    }

    @Test fun `gemini key goes in a header and never in the url`() {
        val (ids, http) = discover(ProviderKind.Gemini, "https://generativelanguage.googleapis.com", mapOf("models" to listOf(
            mapOf("name" to "models/gemini-2.0-flash", "supportedGenerationMethods" to listOf("generateContent")),
            mapOf("name" to "models/text-embedding-004", "supportedGenerationMethods" to listOf("embedContent")),
        )))
        assertEquals(listOf("gemini-2.0-flash"), ids)
        val r = http.requests.single()
        assertFalse(r.url, r.url.contains("key=") || r.url.contains(key))
        assertEquals(key, r.headers["x-goog-api-key"])
        assertFalse(r.followRedirects)
    }

    @Test fun `openai lists only chat models on the official host and keeps everything on a custom one`() {
        val data = mapOf("data" to listOf("gpt-4o-mini", "whisper-1", "text-embedding-3-small", "dall-e-3").map { mapOf("id" to it) })
        assertEquals(listOf("gpt-4o-mini"), discover(ProviderKind.OpenAiCompatible, "https://api.openai.com/v1", data, apiKey = "sk-abcdefghijklmnop").first)
        assertEquals(listOf("dall-e-3", "gpt-4o-mini", "text-embedding-3-small", "whisper-1"), discover(ProviderKind.OpenAiCompatible, "https://integrate.api.nvidia.com/v1", data, apiKey = "nvapi-abcdefghijkl").first)
    }

    @Test fun `claude base with v1 is normalised and the key uses its own header`() {
        val (ids, http) = discover(ProviderKind.Anthropic, "https://api.anthropic.com/v1", mapOf("data" to listOf(mapOf("id" to "claude-x"))), apiKey = "sk-ant-abcdefghijkl")
        assertEquals(listOf("claude-x"), ids)
        assertEquals("https://api.anthropic.com/v1/models", http.requests.single().url)
        assertEquals("sk-ant-abcdefghijkl", http.requests.single().headers["x-api-key"])
    }

    @Test fun `ollama on the emulator host works over http, without a key`() {
        val (ids, http) = discover(ProviderKind.Ollama, "http://10.0.2.2:11434", mapOf("models" to listOf(mapOf("name" to "llama3.2"))), apiKey = null)
        assertEquals(listOf("llama3.2"), ids)
        assertEquals("http://10.0.2.2:11434/v1/models", http.requests.single().url)
        assertTrue(http.requests.single().headers.isEmpty())
    }

    @Test fun `a private address is refused for a cloud provider before any request`() {
        val http = FakeGateway()
        val e = runCatching { runBlocking { ModelDiscovery(http).list(ProviderEndpoints.config(ProviderKind.OpenAiCompatible, "m", "http://192.168.1.5/v1", "sk-abcdefghijkl")) } }.exceptionOrNull() as ModelException
        assertEquals(ModelErrorKind.Policy, e.kind)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `an error body that echoes the key is masked`() {
        val e = runCatching { discover(ProviderKind.Gemini, "https://generativelanguage.googleapis.com", mapOf("error" to mapOf("message" to "API key $key is not valid")), status = 400) }.exceptionOrNull() as ModelException
        assertFalse(e.message!!, e.message!!.contains(key))
        assertTrue(e.message!!.contains("HTTP 400"))
    }

    @Test fun `a missing key for a cloud provider is an auth error and sends nothing`() {
        val http = FakeGateway()
        val e = runCatching { runBlocking { ModelDiscovery(http).list(ProviderEndpoints.config(ProviderKind.Gemini, "m", "", null)) } }.exceptionOrNull() as ModelException
        assertEquals(ModelErrorKind.Auth, e.kind)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `endpoint normalisation`() {
        assertEquals("https://api.anthropic.com", ProviderEndpoints.normalizeBase(ProviderKind.Anthropic, "https://api.anthropic.com/v1/"))
        assertEquals("http://10.0.2.2:11434/v1", ProviderEndpoints.normalizeBase(ProviderKind.Ollama, "http://10.0.2.2:11434"))
        assertEquals("http://h:1/v1", ProviderEndpoints.normalizeBase(ProviderKind.Ollama, "http://h:1/v1"))
        assertEquals("", ProviderEndpoints.normalizeBase(ProviderKind.OpenAiCompatible, "  "))
    }
}
