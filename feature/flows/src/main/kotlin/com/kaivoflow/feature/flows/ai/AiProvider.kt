package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.ProviderKind

/**
 * AI Providers supported for KaivoFlow's Sovereign Workflow Assistant and Agent Builder.
 * Matches KaivoFlow's architecture:
 * - OpenAI handles both official OpenAI and any OpenAI-compatible base URL (NVIDIA NIM, Groq, OpenRouter, vLLM, etc.)
 * - Google Gemini
 * - Anthropic Claude
 * - Local Ollama
 *
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
        requiresApiKey = true,
    ),
    Claude(
        displayName = "Anthropic Claude",
        defaultModel = "claude-3-5-sonnet-20241022",
        availableModels = listOf("claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022"),
        defaultBaseUrl = "https://api.anthropic.com/v1",
        requiresApiKey = true,
    ),
    Ollama(
        displayName = "Local Ollama",
        defaultModel = "llama3.2",
        availableModels = listOf("llama3.2", "qwen2.5:7b", "mistral", "deepseek-r1:7b"),
        defaultBaseUrl = "http://10.0.2.2:11434",
        requiresApiKey = false,
    ),
    Groq(
        displayName = "Groq",
        defaultModel = "llama-3.3-70b-versatile",
        availableModels = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "deepseek-r1-distill-llama-70b"),
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        requiresApiKey = true,
    ),
    DeepSeek(
        displayName = "DeepSeek",
        defaultModel = "deepseek-chat",
        availableModels = listOf("deepseek-chat", "deepseek-reasoner"),
        defaultBaseUrl = "https://api.deepseek.com/v1",
        requiresApiKey = true,
    ),
    OpenRouter(
        displayName = "OpenRouter",
        defaultModel = "openai/gpt-4o-mini",
        availableModels = listOf("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet", "meta-llama/llama-3.3-70b-instruct", "deepseek/deepseek-r1"),
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        requiresApiKey = true,
    );

    val key: String get() = name.lowercase()

    /** The provider adapter in `:core:ai` that speaks this provider's wire format. */
    fun toKind(): ProviderKind = when (this) {
        Gemini -> ProviderKind.Gemini
        OpenAI, Groq, DeepSeek, OpenRouter -> ProviderKind.OpenAiCompatible
        Claude -> ProviderKind.Anthropic
        Ollama -> ProviderKind.Ollama
    }

    companion object {
        fun fromKey(key: String?): AiProvider =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
                ?: when (key?.lowercase()) {
                    "groq", "groqapi" -> Groq
                    "deepseek", "deepseekapi" -> DeepSeek
                    "openrouter", "openrouterapi" -> OpenRouter
                    "openai", "openaiapi", "customopenai", "nvidianim" -> OpenAI
                    "gemini", "geminiapi", "googlegemini" -> Gemini
                    "claude", "anthropic", "anthropicapi" -> Claude
                    "ollama", "ollamaapi" -> Ollama
                    else -> OpenAI
                }
    }
}

/**
 * Encrypted sovereign configuration for the user's chosen AI provider.
 * Supports auto-discovered models dynamically fetched via `/models` endpoints.
 */
data class AiProviderConfig(
    val provider: AiProvider = AiProvider.OpenAI,
    val model: String = AiProvider.OpenAI.defaultModel,
    val apiKey: String = "",
    val baseUrl: String = AiProvider.OpenAI.defaultBaseUrl,
    val discoveredModels: List<String> = emptyList(),
    val credentialId: String? = null,
    val credentialName: String? = null,
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

    /**
     * What is stored in the `ai_provider_config` row. The key is NOT stored here: it lives only in the named
     * credential, which [credentialId] points to, so rotating it in one place cannot leave a stale copy behind.
     * [fromMap] still reads an old row that carries a key.
     */
    fun toMap(): Map<String, String> = buildMap {
        put("provider", provider.name)
        put("model", model)
        put("baseUrl", baseUrl)
        put("discoveredModels", discoveredModels.joinToString(","))
        credentialId?.let { put("credentialId", it) }
        credentialName?.let { put("credentialName", it) }
    }

    /** A generated data-class toString would print the key into any log line or crash report. */
    override fun toString(): String =
        "AiProviderConfig(provider=$provider, model=$model, apiKey=${if (apiKey.isBlank()) "<none>" else "***"}, baseUrl=$baseUrl, credentialId=$credentialId)"

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
            val credentialId = map["credentialId"]?.takeIf { it.isNotBlank() }
            val credentialName = map["credentialName"]?.takeIf { it.isNotBlank() }
            return AiProviderConfig(
                provider = provider,
                model = model,
                apiKey = apiKey,
                baseUrl = baseUrl,
                discoveredModels = discovered,
                credentialId = credentialId,
                credentialName = credentialName,
            )
        }
    }
}
