package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.engine.HttpRequestSpec
import kotlinx.coroutines.CancellationException

/**
 * Lists the chat models a provider offers. Same rules as chat calls: the base address passes the
 * [EndpointPolicy], the key travels in a header only (never the URL), redirects are not followed and
 * every error text is redacted. Returns a sorted list or throws [ModelException].
 */
class ModelDiscovery(private val http: HttpGateway) {
    suspend fun list(config: ModelConfig): List<String> {
        val base = config.policy.require(config.baseUrl.ifBlank { config.provider.defaultBaseUrl }).baseUrl
        val key = config.apiKey?.takeIf { !it.isBlank }
        val local = config.provider == ProviderKind.Ollama
        if (key == null && !local && config.policy.check(base).let { it is EndpointPolicy.Verdict.Allowed && !it.isLocal }) {
            throw ModelException(ModelErrorKind.Auth, "An API key is needed to list the models of ${config.provider.key}.")
        }
        val (url, headers) = when (config.provider) {
            ProviderKind.OpenAiCompatible, ProviderKind.Ollama ->
                "$base/models" to (key?.let { mapOf("Authorization" to "Bearer ${it.reveal()}") } ?: emptyMap())
            ProviderKind.Anthropic -> "$base/v1/models" to buildMap {
                key?.let { put("x-api-key", it.reveal()) }; put("anthropic-version", "2023-06-01")
            }
            ProviderKind.Gemini -> "$base/v1beta/models" to (key?.let { mapOf("x-goog-api-key" to it.reveal()) } ?: emptyMap())
        }
        val redactor = Redactor(listOfNotNull(key?.reveal()))
        val response = try {
            http.request(HttpRequestSpec(method = "GET", url = url, headers = headers, timeoutMs = 15_000, followRedirects = false, failOnErrorStatus = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ModelException(ModelErrorKind.Network, "The provider could not be reached: ${redactor.redact(e.message ?: e::class.java.simpleName).take(160)}")
        }
        val json = J.body(response)
        if (response.status in 300..399) throw ModelException(ModelErrorKind.Redirect, "The provider answered with a redirect, which is not followed.", response.status)
        if (response.status !in 200..299) {
            val detail = redactor.redact(J.str(J.path(json, "error.message")) ?: J.str(J.path(json, "message")) ?: response.bodyText).replace(Regex("\\s+"), " ").take(150)
            throw ModelException(
                if (response.status == 401 || response.status == 403) ModelErrorKind.Auth else ModelErrorKind.Server,
                "The provider returned HTTP ${response.status}${if (detail.isNotBlank()) ": $detail" else ""}", response.status,
            )
        }
        val ids: List<String> = when (config.provider) {
            ProviderKind.Gemini -> J.list(json?.get("models")).mapNotNull { J.obj(it) }.filter { m ->
                val name = J.str(m["name"]).orEmpty()
                J.list(m["supportedGenerationMethods"]).contains("generateContent") &&
                    listOf("embedding", "aqa", "imagen").none { name.contains(it) }
            }.mapNotNull { J.str(it["name"])?.removePrefix("models/") }
            ProviderKind.Ollama -> J.list(json?.get("models")).mapNotNull { J.str(J.obj(it)?.get("name")) }
                .ifEmpty { J.list(json?.get("data")).mapNotNull { J.str(J.obj(it)?.get("id")) } }
            else -> J.list(json?.get("data")).mapNotNull { J.str(J.obj(it)?.get("id")) ?: J.str(it) }
        }.filter { it.isNotBlank() }
        val chat = ids.filter(::isChatModelId).ifEmpty { ids }
        if (chat.isEmpty()) throw ModelException(ModelErrorKind.Empty, "No chat models were found at that address.")
        return chat.distinct().sorted()
    }

    companion object {
        private val NOT_CHAT = listOf(
            "babbage", "davinci", "dall-e", "text-embedding", "tts", "-tts", "whisper",
            "omni-moderation", "sora", "embed", "embedding", "clip", "detector", "starcoder",
            "rerank", "calibration", "transcribe", "diarize"
        )
        fun isChatModelId(id: String): Boolean {
            val l = id.lowercase()
            return NOT_CHAT.none { l.contains(it) }
        }
    }
}
