package com.kaivoflow.core.ai

/** Which request field carries the output cap. */
enum class TokenParam { MaxTokens, MaxCompletionTokens }

/**
 * What a model accepts, decided in one place so adapters stay free of model-name checks.
 * Allow-lists are used where a provider keeps removing parameters (a deny-list would need an edit per release).
 *
 * [minOutputTokens] is head-room for models whose output cap also counts hidden reasoning: with the old
 * 1,024 default such a model can spend the whole cap thinking and return an empty answer. The adapter raises a
 * smaller request to this value; the answer length is still limited by the prompt, and the cost cap still applies.
 */
data class ModelCaps(
    val tokenParam: TokenParam = TokenParam.MaxTokens,
    val acceptsTemperature: Boolean = true,
    val acceptsToolChoice: Boolean = true,
    val reasoningModel: Boolean = false,
    val acceptsReasoningEffort: Boolean = false,
    val minOutputTokens: Int = 0,
)

object ModelCapabilities {
    private val OPENAI_REASONING = Regex("^(o[1-9][0-9]*(-.*)?|gpt-5.*)$")
    private val OPENAI_EFFORT_OFF = Regex("^o1-(mini|preview).*$")

    fun of(provider: ProviderKind, model: String, baseHost: String = ""): ModelCaps {
        val id = model.trim().lowercase().substringAfterLast('/')
        return when (provider) {
            ProviderKind.OpenAiCompatible -> openAi(id, baseHost.equals("api.openai.com", true))
            ProviderKind.Ollama -> ModelCaps(acceptsToolChoice = false)
            ProviderKind.Anthropic -> ModelCaps(acceptsTemperature = anthropicAcceptsSampling(model.trim().lowercase()))
            ProviderKind.Gemini -> ModelCaps(minOutputTokens = if (Regex("^gemini-(2\\.5|[3-9]).*").matches(id)) 4_096 else 0, reasoningModel = id.startsWith("gemini-2.5") || id.startsWith("gemini-3"))
        }
    }

    private fun openAi(id: String, officialHost: Boolean): ModelCaps {
        if (!officialHost) return ModelCaps() // compatible servers keep the classic names
        val reasoning = OPENAI_REASONING.matches(id) && !id.contains("chat")
        return ModelCaps(
            tokenParam = TokenParam.MaxCompletionTokens,
            acceptsTemperature = !reasoning,
            reasoningModel = reasoning,
            acceptsReasoningEffort = reasoning && !OPENAI_EFFORT_OFF.matches(id),
            minOutputTokens = if (reasoning) 4_096 else 0,
        )
    }

    /** Older Claude families accept temperature; newer generations reject it. Unknown `claude-` ids are assumed not to. */
    fun anthropicAcceptsSampling(id: String): Boolean {
        if (!id.startsWith("claude-")) return true // a gateway with its own naming
        if (id.startsWith("claude-2") || id.startsWith("claude-instant") || id.startsWith("claude-3")) return true
        if (id.startsWith("claude-sonnet-4") || id.startsWith("claude-haiku-4")) return true
        return Regex("^claude-opus-4(-[0-6])?(-[0-9]{8})?$").matches(id)
    }
}
