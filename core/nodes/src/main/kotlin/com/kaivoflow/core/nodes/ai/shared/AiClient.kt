package com.kaivoflow.core.nodes.ai.shared

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.nodes.JsonLite
import com.kaivoflow.core.nodes.bearerToken

/**
 * AI Steps.
 *
 * Three providers are supported through one code path:
 *  • `openai`   — any OpenAI-compatible endpoint (OpenAI, Groq, Together, OpenRouter, LM Studio)
 *  • `gemini`   — Google Generative Language API
 *  • `ollama`   — a model running on the user's own machine or phone-side LAN IP
 *
 * KaivoFlow never proxies AI traffic: the phone calls the provider directly with the user's own
 * key, which is what the Privacy Centre promises.
 */
internal object AiClient {

    data class Result(val text: String, val json: Any?, val usage: Map<String, Any?>)

    suspend fun chat(
        ctx: StepContext,
        params: Map<String, Any?>,
        prompt: String,
        system: String,
        jsonMode: Boolean,
    ): Result {
        val provider = Values.asString(params["provider"], "openai")
        val model = Values.asString(params["model"])
        val temperature = Values.asDouble(params["temperature"]) ?: 0.2
        val maxTokens = (Values.asDouble(params["maxTokens"]) ?: 800.0).toInt()

        if (model.isBlank()) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "choose a model (or type a custom one)")
        }

        val credential = runCatching { ctx.credential() }.getOrNull().orEmpty()
        val apiKey = credential.bearerToken().ifBlank { Values.asString(params["apiKey"]) }

        return when (provider) {
            "gemini" -> gemini(ctx, params, model, prompt, system, temperature, maxTokens, apiKey, jsonMode)
            "ollama" -> ollama(ctx, params, model, prompt, system, temperature, jsonMode)
            else -> openAiCompatible(ctx, params, model, prompt, system, temperature, maxTokens, apiKey, jsonMode)
        }
    }

    private suspend fun openAiCompatible(
        ctx: StepContext,
        params: Map<String, Any?>,
        model: String,
        prompt: String,
        system: String,
        temperature: Double,
        maxTokens: Int,
        apiKey: String,
        jsonMode: Boolean,
    ): Result {
        val baseUrl = Values.asString(params["baseUrl"]).ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
        if (apiKey.isBlank() && !baseUrl.contains("localhost") && !baseUrl.contains("127.0.0.1")) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "add an API key credential for $model")
        }
        val messages = buildList {
            if (system.isNotBlank()) add(mapOf("role" to "system", "content" to system))
            add(mapOf("role" to "user", "content" to prompt))
        }
        val body = buildMap<String, Any?> {
            put("model", model)
            put("messages", messages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            if (jsonMode) put("response_format", mapOf("type" to "json_object"))
        }
        val response = ctx.http.request(
            HttpRequestSpec(
                method = "POST",
                url = "$baseUrl/chat/completions",
                headers = buildMap {
                    put("Content-Type", "application/json")
                    if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
                },
                body = body,
                timeoutMs = 120_000,
            )
        )
        if (!response.isSuccess) {
            throw StepExecutionException(
                ctx.step.id, ctx.step.name,
                "$model returned HTTP ${response.status}: ${response.bodyText.take(240)}"
            )
        }
        val parsed = Values.asMap(response.body)
        val content = Values.asList(parsed["choices"]).firstOrNull()?.let { choice ->
            Values.asString(Values.deepGet(choice, "message.content"))
        }.orEmpty()
        return Result(content, JsonLite.parse(content), Values.asMap(parsed["usage"]))
    }

    private suspend fun gemini(
        ctx: StepContext,
        params: Map<String, Any?>,
        model: String,
        prompt: String,
        system: String,
        temperature: Double,
        maxTokens: Int,
        apiKey: String,
        jsonMode: Boolean,
    ): Result {
        if (apiKey.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add a Google AI API key")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val body = buildMap<String, Any?> {
            put("contents", listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt)))))
            if (system.isNotBlank()) put("systemInstruction", mapOf("parts" to listOf(mapOf("text" to system))))
            put(
                "generationConfig",
                buildMap<String, Any?> {
                    put("temperature", temperature)
                    put("maxOutputTokens", maxTokens)
                    if (jsonMode) put("responseMimeType", "application/json")
                },
            )
        }
        val response = ctx.http.request(
            HttpRequestSpec(method = "POST", url = url, headers = mapOf("Content-Type" to "application/json"), body = body, timeoutMs = 120_000)
        )
        if (!response.isSuccess) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "$model returned HTTP ${response.status}: ${response.bodyText.take(240)}")
        }
        val parsed = Values.asMap(response.body)
        val content = Values.asList(parsed["candidates"]).firstOrNull()?.let { candidate ->
            Values.asList(Values.deepGet(candidate, "content.parts")).joinToString("") { part ->
                Values.asString(Values.deepGet(part, "text"))
            }
        }.orEmpty()
        return Result(content, JsonLite.parse(content), Values.asMap(parsed["usageMetadata"]))
    }

    private suspend fun ollama(
        ctx: StepContext,
        params: Map<String, Any?>,
        model: String,
        prompt: String,
        system: String,
        temperature: Double,
        jsonMode: Boolean,
    ): Result {
        val baseUrl = Values.asString(params["baseUrl"]).ifBlank { "http://127.0.0.1:11434" }.trimEnd('/')
        val messages = buildList {
            if (system.isNotBlank()) add(mapOf("role" to "system", "content" to system))
            add(mapOf("role" to "user", "content" to prompt))
        }
        val response = ctx.http.request(
            HttpRequestSpec(
                method = "POST",
                url = "$baseUrl/api/chat",
                headers = mapOf("Content-Type" to "application/json"),
                body = mapOf(
                    "model" to model,
                    "messages" to messages,
                    "stream" to false,
                    "format" to if (jsonMode) "json" else null,
                    "options" to mapOf("temperature" to temperature),
                ),
                timeoutMs = 120_000,
            )
        )
        if (!response.isSuccess) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "Could not reach $baseUrl (HTTP ${response.status}). Is Ollama running?")
        }
        val parsed = Values.asMap(response.body)
        val content = Values.asString(Values.deepGet(parsed, "message.content"))
        return Result(content, JsonLite.parse(content), emptyMap())
    }
}
