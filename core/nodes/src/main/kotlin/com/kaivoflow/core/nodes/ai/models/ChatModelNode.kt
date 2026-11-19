package com.kaivoflow.core.nodes.ai.models

import com.kaivoflow.core.ai.EndpointPolicy
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeRole
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.ai.shared.AiSlots
import kotlinx.collections.immutable.toImmutableList

/**
 * Chat Model: one Step for every provider (OpenAI-compatible, Anthropic, Gemini, Ollama). It is a
 * sub-Step that an Agent or a Basic LLM uses; on its own it only checks the setup and reports it
 * (no network call, no key shown), so a Test Step shows whether the model is ready.
 */
class ChatModelNode : StepExecutor {
    override val spec = NodeSpec(
        id = "ai.chat_model",
        title = "Chat Model",
        category = StepCategory.Ai,
        subtitle = "Pick a provider and model for an agent",
        iconKey = "sparkles",
        params = AiSlots.modelParams().toImmutableList(),
        keywords = listOf("model", "llm", "openai", "claude", "anthropic", "gemini", "ollama", "provider").toImmutableList(),
        role = NodeRole.AiModel,
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val values = ctx.resolved().toMap()
        val slot = AiSlots.slotFrom(ctx, values)
        val verdict = try {
            AiSlots.policyFor(slot).require(slot.baseUrl.ifBlank { slot.provider.defaultBaseUrl })
        } catch (e: com.kaivoflow.core.ai.ModelException) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, e.message.orEmpty())
        }
        var keyFound = false
        if (slot.credential.isNotBlank()) {
            keyFound = try { ctx.credentials.resolve(slot.credential).isNotEmpty() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                throw StepExecutionException(ctx.step.id, ctx.step.name, "the credential could not be read from the vault")
            }
        }
        val needsKey = slot.provider != com.kaivoflow.core.ai.ProviderKind.Ollama && !verdict.isLocal
        if (needsKey && !keyFound) throw StepExecutionException(ctx.step.id, ctx.step.name, "add an API key credential for ${slot.provider.key}")
        return ctx.output(listOf(FlowData(mapOf("chatModel" to mapOf(
            "provider" to slot.provider.key, "model" to slot.model, "host" to verdict.host,
            "local" to verdict.isLocal, "cleartext" to verdict.cleartext, "credentialSet" to keyFound, "ready" to true,
        )))))
    }
}
