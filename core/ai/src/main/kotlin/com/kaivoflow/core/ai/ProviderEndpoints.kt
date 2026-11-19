package com.kaivoflow.core.ai

/**
 * Turns the address a person typed into the base the adapters expect, and picks the matching host policy.
 * The app's screens store `https://api.anthropic.com/v1` for Claude and `http://10.0.2.2:11434` for Ollama;
 * the adapters add `/v1/messages` and `/chat/completions` themselves, so the base must not already carry them.
 */
object ProviderEndpoints {
    fun normalizeBase(kind: ProviderKind, typed: String): String {
        val t = typed.trim().trimEnd('/')
        if (t.isEmpty()) return ""
        return when (kind) {
            ProviderKind.Anthropic -> t.removeSuffix("/v1")
            ProviderKind.Ollama -> if (t.endsWith("/v1")) t else "$t/v1"
            ProviderKind.Gemini -> t.removeSuffix("/v1beta").removeSuffix("/v1")
            ProviderKind.OpenAiCompatible -> t
        }
    }

    /**
     * A person chose the provider and typed the address on their own screen, so only the explicit local provider
     * (Ollama) may reach this phone or the home network; every other provider needs a public https host.
     */
    fun policyFor(kind: ProviderKind): EndpointPolicy =
        if (kind == ProviderKind.Ollama) EndpointPolicy(allowLoopback = true, allowPrivateLan = true) else EndpointPolicy()

    /** One call that builds a [ModelConfig] with a normalised base and the right policy. */
    fun config(kind: ProviderKind, model: String, typedBaseUrl: String, key: String?): ModelConfig = ModelConfig(
        provider = kind,
        model = model,
        baseUrl = normalizeBase(kind, typedBaseUrl),
        apiKey = key?.takeIf { it.isNotBlank() }?.let { ApiKey(it) },
        policy = policyFor(kind),
    )
}
