package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.engine.HttpRequestSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Service to dynamically discover available models from any AI provider
 * (OpenAI, NVIDIA NIM, Groq, OpenRouter, Mistral, Ollama, Gemini, Anthropic).
 */
class ModelDiscoveryService(
    private val http: HttpGateway,
) {
    suspend fun discoverModels(
        provider: AiProvider,
        baseUrl: String,
        apiKey: String,
    ): Result<List<String>> = runCatching {
        when (provider) {
            AiProvider.Gemini -> fetchGeminiModels(baseUrl, apiKey)
            AiProvider.Claude -> fetchAnthropicModels(baseUrl, apiKey)
            AiProvider.Ollama -> fetchOllamaModels(baseUrl)
            else -> fetchOpenAiCompatibleModels(baseUrl, apiKey)
        }
    }

    private suspend fun fetchOpenAiCompatibleModels(
        baseUrl: String,
        apiKey: String,
    ): List<String> {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val endpoint = "$cleanBase/models"
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (apiKey.isNotBlank()) {
            headers["Authorization"] = "Bearer ${apiKey.trim()}"
        }

        val response = http.request(
            HttpRequestSpec(
                method = "GET",
                url = endpoint,
                headers = headers,
                timeoutMs = 15_000,
            )
        )

        if (!response.isSuccess) {
            val errorSnippet = response.bodyText.take(150)
            error("Provider returned HTTP ${response.status}: $errorSnippet")
        }

        val json = JSONObject(response.bodyText)
        val data = json.optJSONArray("data") ?: JSONArray()
        val isCustomApi = !cleanBase.contains("api.openai.com")

        val models = mutableListOf<String>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i)
            val id = item?.optString("id") ?: data.optString(i)
            if (!id.isNullOrBlank() && shouldIncludeModel(id, isCustomApi)) {
                models.add(id)
            }
        }

        if (models.isEmpty()) {
            error("No chat models found in response from $endpoint")
        }

        return models.sorted()
    }

    private suspend fun fetchGeminiModels(
        baseUrl: String,
        apiKey: String,
    ): List<String> {
        require(apiKey.isNotBlank()) { "API key is required for Google Gemini model discovery" }
        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com" }
        val endpoint = "$cleanBase/v1beta/models?key=${apiKey.trim()}"

        val response = http.request(
            HttpRequestSpec(
                method = "GET",
                url = endpoint,
                headers = mapOf("Content-Type" to "application/json"),
                timeoutMs = 15_000,
            )
        )

        if (!response.isSuccess) {
            error("Gemini returned HTTP ${response.status}: ${response.bodyText.take(150)}")
        }

        val json = JSONObject(response.bodyText)
        val modelsArray = json.optJSONArray("models") ?: JSONArray()
        val models = mutableListOf<String>()

        for (i in 0 until modelsArray.length()) {
            val item = modelsArray.optJSONObject(i) ?: continue
            val name = item.optString("name", "")
            val methods = item.optJSONArray("supportedGenerationMethods")
            val supportsChat = methods != null && (0 until methods.length()).any {
                methods.optString(it) == "generateContent"
            }

            if (supportsChat && !name.contains("embedding") && !name.contains("aqa") && !name.contains("imagen")) {
                val cleanId = name.removePrefix("models/")
                models.add(cleanId)
            }
        }

        return models.sorted()
    }

    private suspend fun fetchAnthropicModels(
        baseUrl: String,
        apiKey: String,
    ): List<String> {
        require(apiKey.isNotBlank()) { "API key is required for Claude model discovery" }
        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { "https://api.anthropic.com/v1" }
        val endpoint = if (cleanBase.endsWith("/v1")) "$cleanBase/models" else "$cleanBase/v1/models"

        val response = http.request(
            HttpRequestSpec(
                method = "GET",
                url = endpoint,
                headers = mapOf(
                    "x-api-key" to apiKey.trim(),
                    "anthropic-version" to "2023-06-01",
                    "Content-Type" to "application/json",
                ),
                timeoutMs = 15_000,
            )
        )

        if (!response.isSuccess) {
            error("Claude returned HTTP ${response.status}: ${response.bodyText.take(150)}")
        }

        val json = JSONObject(response.bodyText)
        val data = json.optJSONArray("data") ?: JSONArray()
        val models = mutableListOf<String>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i)
            val id = item?.optString("id")
            if (!id.isNullOrBlank()) {
                models.add(id)
            }
        }
        return models.sorted()
    }

    private suspend fun fetchOllamaModels(baseUrl: String): List<String> {
        val cleanBase = baseUrl.trim().trimEnd('/').ifBlank { "http://10.0.2.2:11434" }
        val endpoint = "$cleanBase/api/tags"

        val response = http.request(
            HttpRequestSpec(
                method = "GET",
                url = endpoint,
                headers = mapOf("Content-Type" to "application/json"),
                timeoutMs = 10_000,
            )
        )

        if (!response.isSuccess) {
            error("Ollama returned HTTP ${response.status}: ${response.bodyText.take(150)}")
        }

        val json = JSONObject(response.bodyText)
        val modelsArray = json.optJSONArray("models") ?: JSONArray()
        val models = mutableListOf<String>()
        for (i in 0 until modelsArray.length()) {
            val item = modelsArray.optJSONObject(i)
            val name = item?.optString("name")
            if (!name.isNullOrBlank()) {
                models.add(name)
            }
        }
        return models.sorted()
    }

    private fun shouldIncludeModel(modelId: String, isCustomApi: Boolean): Boolean {
        if (isCustomApi) return true
        val lower = modelId.lowercase()
        return !(lower.startsWith("babbage") ||
            lower.startsWith("davinci") ||
            lower.startsWith("dall-e") ||
            lower.startsWith("text-embedding") ||
            lower.startsWith("tts") ||
            lower.contains("-tts") ||
            lower.startsWith("whisper") ||
            lower.startsWith("omni-moderation") ||
            lower.startsWith("sora"))
    }
}
