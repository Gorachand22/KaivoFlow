package com.kaivoflow.feature.flows.ai

/**
 * AI Providers supported for KaivoFlow's Sovereign Workflow Assistant and Agent Builder.
 *
 * Each provider communicates directly from the phone using the user's sovereign credentials.
 * Keys are stored locally in the hardware-backed Android Keystore and never pass through any proxy.
 */
enum class AiProvider(
    val displayName: String,
    val defaultModel: String,
    val availableModels: List<String>,
    val defaultBaseUrl: String,
    val requiresApiKey: Boolean = true,
) {
    Gemini(
        displayName = "Google Gemini",
        defaultModel = "gemini-2.0-flash",
        availableModels = listOf("gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro"),
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
    ),
    OpenAI(
        displayName = "OpenAI",
        defaultModel = "gpt-4o-mini",
        availableModels = listOf("gpt-4o-mini", "gpt-4o", "o3-mini"),
        defaultBaseUrl = "https://api.openai.com/v1",
    ),
    NvidiaNim(
        displayName = "NVIDIA NIM",
        defaultModel = "meta/llama-3.3-70b-instruct",
        availableModels = listOf(
            "meta/llama-3.3-70b-instruct",
            "mistralai/mistral-large-2407",
            "nvidia/llama-3.1-nemotron-70b-instruct",
            "deepseek-ai/deepseek-r1",
        ),
        defaultBaseUrl = "https://integrate.api.nvidia.com/v1",
        requiresApiKey = true,
    ),
    Claude(
        displayName = "Anthropic Claude",
        defaultModel = "claude-3-5-sonnet-20241022",
        availableModels = listOf("claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022"),
        defaultBaseUrl = "https://api.anthropic.com/v1",
    ),
    Groq(
        displayName = "Groq",
        defaultModel = "llama-3.3-70b-versatile",
        availableModels = listOf("llama-3.3-70b-versatile", "mixtral-8x7b-32768", "llama-3.1-8b-instant"),
        defaultBaseUrl = "https://api.groq.com/openai/v1",
    ),
    OpenRouter(
        displayName = "OpenRouter",
        defaultModel = "google/gemini-2.0-flash-exp:free",
        availableModels = listOf(
            "google/gemini-2.0-flash-exp:free",
            "meta-llama/llama-3.3-70b-instruct",
            "anthropic/claude-3.5-sonnet",
        ),
        defaultBaseUrl = "https://openrouter.ai/api/v1",
    ),
    Mistral(
        displayName = "Mistral AI",
        defaultModel = "mistral-small-latest",
        availableModels = listOf("mistral-small-latest", "mistral-large-latest", "codestral-latest"),
        defaultBaseUrl = "https://api.mistral.ai/v1",
    ),
    Ollama(
        displayName = "Local Ollama",
        defaultModel = "llama3.2",
        availableModels = listOf("llama3.2", "qwen2.5:7b", "mistral", "deepseek-r1:7b"),
        defaultBaseUrl = "http://10.0.2.2:11434",
        requiresApiKey = false,
    ),
    CustomOpenAi(
        displayName = "OpenAI Compatible / Custom",
        defaultModel = "",
        availableModels = emptyList(),
        defaultBaseUrl = "http://10.0.2.2:8000/v1",
        requiresApiKey = false,
    );

    companion object {
        fun fromKey(key: String?): AiProvider =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: Gemini
    }
}

/**
 * Encrypted sovereign configuration for the user's chosen AI provider.
 * Supports auto-discovered models dynamically fetched via `/models` endpoints.
 */
data class AiProviderConfig(
    val provider: AiProvider = AiProvider.Gemini,
    val model: String = AiProvider.Gemini.defaultModel,
    val apiKey: String = "",
    val baseUrl: String = AiProvider.Gemini.defaultBaseUrl,
    val discoveredModels: List<String> = emptyList(),
) {
    val isConfigured: Boolean get() = !provider.requiresApiKey || apiKey.isNotBlank()

    /**
     * Effective list of models: discovered models if available, otherwise provider defaults.
     */
    val effectiveModels: List<String>
        get() = (discoveredModels.ifEmpty { provider.availableModels }).let { list ->
            if (model.isNotBlank() && !list.contains(model)) {
                listOf(model) + list
            } else {
                list
            }
        }

    fun toMap(): Map<String, String> = mapOf(
        "provider" to provider.name,
        "model" to model,
        "apiKey" to apiKey,
        "baseUrl" to baseUrl,
        "discoveredModels" to discoveredModels.joinToString(","),
    )

    companion object {
        fun fromMap(map: Map<String, String>?): AiProviderConfig {
            if (map == null || map.isEmpty()) return AiProviderConfig()
            val provider = AiProvider.fromKey(map["provider"])
            val model = map["model"]?.takeIf { it.isNotBlank() } ?: provider.defaultModel
            val apiKey = map["apiKey"].orEmpty()
            val baseUrl = map["baseUrl"]?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl
            val discovered = map["discoveredModels"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            return AiProviderConfig(
                provider = provider,
                model = model,
                apiKey = apiKey,
                baseUrl = baseUrl,
                discoveredModels = discovered,
            )
        }
    }
}
