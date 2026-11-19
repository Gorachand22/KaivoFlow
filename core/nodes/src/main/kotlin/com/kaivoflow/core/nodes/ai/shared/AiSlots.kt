package com.kaivoflow.core.nodes.ai.shared

import com.kaivoflow.core.ai.AgentError
import com.kaivoflow.core.ai.AgentResult
import com.kaivoflow.core.ai.ApiKey
import com.kaivoflow.core.ai.ChatModelClient
import com.kaivoflow.core.ai.ChatModels
import com.kaivoflow.core.ai.EndpointPolicy
import com.kaivoflow.core.ai.ModelConfig
import com.kaivoflow.core.ai.ModelException
import com.kaivoflow.core.ai.ProviderKind
import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.model.ParamJson
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.nodes.bearerToken
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException

/** Builds a provider client. Tests pass a fake here; production uses the real adapters over the HTTP gateway. */
fun interface ChatModelFactory {
    fun create(http: HttpGateway, config: ModelConfig): ChatModelClient

    companion object { val Default = ChatModelFactory { http, config -> ChatModels.create(http, config) } }
}

/**
 * A Chat Model as stored on a Step or inside an Agent's `chatModel` slot. The key itself is never
 * stored: [credential] is the id of a vault entry, resolved at run time.
 */
internal data class ModelSlot(
    val provider: ProviderKind,
    val model: String,
    val credential: String,
    val baseUrl: String,
    val temperature: Double,
    val maxTokens: Int,
    val local: String,
)

internal object AiSlots {
    private val KEY_FIELDS = setOf("apikey", "api_key", "key", "token", "secret", "password", "authorization")

    /** The `provider` options of the new AI Steps. The legacy trio keeps its own three-way list. */
    val providerOptions = persistentListOf(
        ParamOption("openai", "OpenAI-compatible"),
        ParamOption("anthropic", "Anthropic (Claude)"),
        ParamOption("gemini", "Google Gemini"),
        ParamOption("ollama", "Ollama (your own machine)"),
    )

    /** Params shared by every Step that owns a model. [baseUrl] never takes an expression, so item data cannot choose where the key goes. */
    fun modelParams(): List<ParamSpec> = listOf(
        ParamSpec("provider", "Provider", ParamType.Select, default = "openai", options = providerOptions),
        ParamSpec("credential", "API key credential", ParamType.Secret, showWhen = mapOf("provider" to listOf("openai", "anthropic", "gemini"))),
        ParamSpec("model", "Model", ParamType.Text, required = true, placeholder = "gpt-4o-mini", supportsExpression = false),
        ParamSpec("baseUrl", "Custom base URL", ParamType.Url, advanced = true, supportsExpression = false,
            hint = "Only https to public hosts, unless you allow a local server below."),
        ParamSpec("localAccess", "Allow local server", ParamType.Select, default = "none", advanced = true, supportsExpression = false,
            hint = "Needed for Ollama or LM Studio on this phone or your network. Cleartext http is only allowed there.",
            options = persistentListOf(ParamOption("none", "No"), ParamOption("loopback", "This phone (localhost)"), ParamOption("lan", "My local network"))),
        ParamSpec("temperature", "Creativity", ParamType.Number, default = 0.2, advanced = true),
        ParamSpec("maxTokens", "Max answer length", ParamType.Number, default = 1024, advanced = true),
    )

    fun asObject(raw: Any?): Map<String, Any?>? = when (raw) {
        is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value }
        is String -> (ParamJson.parse(raw) as? ParamJson.Parsed.Ok)?.value?.let { asObject(it) }
        else -> null
    }

    fun asList(raw: Any?): List<Any?>? = when (raw) {
        is List<*> -> raw
        is String -> (ParamJson.parse(raw) as? ParamJson.Parsed.Ok)?.value as? List<*>
        else -> null
    }

    fun slotFrom(ctx: StepContext, values: Map<String, Any?>): ModelSlot {
        fun fail(msg: String): Nothing = throw StepExecutionException(ctx.step.id, ctx.step.name, msg)
        values.keys.firstOrNull { it.lowercase() in KEY_FIELDS }?.let {
            fail("keys do not belong in the Step: remove \"$it\" and pick a credential from the vault instead")
        }
        val providerKey = values["provider"]?.toString()?.ifBlank { null } ?: "openai"
        val provider = ProviderKind.fromKey(providerKey) ?: fail("unknown provider \"$providerKey\"")
        val model = values["model"]?.toString()?.trim().orEmpty()
        if (model.isBlank()) fail("choose a model")
        val local = values["localAccess"]?.toString()?.ifBlank { null } ?: "none"
        if (local !in setOf("none", "loopback", "lan")) fail("localAccess must be none, loopback or lan")
        return ModelSlot(
            provider = provider,
            model = model,
            credential = values["credential"]?.toString().orEmpty(),
            baseUrl = values["baseUrl"]?.toString().orEmpty().trim(),
            temperature = (values["temperature"] as? Number)?.toDouble() ?: values["temperature"]?.toString()?.toDoubleOrNull() ?: 0.2,
            maxTokens = ((values["maxTokens"] as? Number)?.toInt() ?: values["maxTokens"]?.toString()?.toDoubleOrNull()?.toInt() ?: 1024).coerceIn(16, 32_000),
            local = local,
        )
    }

    fun policyFor(slot: ModelSlot) = EndpointPolicy(
        allowLoopback = slot.local == "loopback" || slot.local == "lan",
        allowPrivateLan = slot.local == "lan",
    )

    /** Resolves the credential through the vault and builds the client. Any provider-side setup error becomes a Step error. */
    suspend fun client(ctx: StepContext, slot: ModelSlot, factory: ChatModelFactory): ChatModelClient {
        var key: ApiKey? = null
        if (slot.credential.isNotBlank()) {
            val creds = try {
                ctx.credentials.resolve(slot.credential)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw StepExecutionException(ctx.step.id, ctx.step.name, "the credential could not be read from the vault")
            }
            key = creds.bearerToken().takeIf { it.isNotBlank() }?.let { ApiKey(it) }
        }
        return try {
            factory.create(ctx.http, ModelConfig(slot.provider, slot.model, slot.baseUrl, key, policy = policyFor(slot)))
        } catch (e: ModelException) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, e.message.orEmpty())
        }
    }

    /** Token, iteration and tool counts for the Step output, as plain JSON-able values. */
    fun meta(r: AgentResult, slot: ModelSlot): Map<String, Any?> = buildMap {
        put("provider", slot.provider.key)
        put("model", slot.model)
        put("outcome", r.outcome.name)
        put("iterations", r.iterations)
        put("toolCalls", r.toolCalls)
        put("promptTokens", r.usage.promptTokens)
        put("completionTokens", r.usage.completionTokens)
        put("totalTokens", r.usage.totalTokens)
        r.estimatedCost?.let { put("estimatedCost", it) }
        if (r.trace.isNotEmpty()) put("tools", r.trace.map { mapOf("iteration" to it.iteration, "tool" to it.tool, "status" to it.status.name, "detail" to it.detail) })
    }

    fun failure(ctx: StepContext, e: AgentError): Nothing = throw StepExecutionException(ctx.step.id, ctx.step.name, e.message)
}
