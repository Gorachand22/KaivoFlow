package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderConfigTest {
    private val key = "sk-live-abcdefghijklmnop1234"
    private val config = AiProviderConfig(provider = AiProvider.Gemini, model = "gemini-2.0-flash", apiKey = key, credentialId = "c1", credentialName = "Mine")

    @Test fun `the stored pointer row never carries the key`() {
        assertFalse(config.toMap().values.any { it.contains(key) })
        assertFalse(config.toMap().containsKey("apiKey"))
        assertEquals("c1", config.toMap()["credentialId"])
    }

    @Test fun `an old row that still has a key is read, so nothing breaks on update`() {
        val old = AiProviderConfig.fromMap(mapOf("provider" to "Gemini", "model" to "m", "apiKey" to key))
        assertEquals(key, old.apiKey)
    }

    @Test fun `printing the config never shows the key`() {
        assertFalse(config.toString().contains(key))
        assertFalse("$config".contains(key))
        assertTrue(config.toString().contains("***"))
    }

    @Test fun `each provider maps to its core adapter`() {
        assertEquals(ProviderKind.Gemini, AiProvider.Gemini.toKind())
        assertEquals(ProviderKind.OpenAiCompatible, AiProvider.OpenAI.toKind())
        assertEquals(ProviderKind.Anthropic, AiProvider.Claude.toKind())
        assertEquals(ProviderKind.Ollama, AiProvider.Ollama.toKind())
    }
}
