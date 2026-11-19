package com.kaivoflow.core.nodes.ai.chains

import com.kaivoflow.core.ai.AgentLimits
import com.kaivoflow.core.ai.AgentLoop
import com.kaivoflow.core.ai.SchemaCheck
import com.kaivoflow.core.ai.StructuredOutputParser
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.ai.shared.AiSlots
import com.kaivoflow.core.nodes.ai.shared.ChatModelFactory
import kotlinx.collections.immutable.toImmutableList

/**
 * Basic LLM: one prompt in, one answer out, per item. It runs the same bounded loop as the Agent
 * with no tools and one step, so redaction, the time limit, untrusted-data fencing, structured output
 * with repair and token counts behave exactly the same.
 */
class BasicLlmNode(private val factory: ChatModelFactory = ChatModelFactory.Default) : StepExecutor {
    override val spec = NodeSpec(
        id = "ai.basic_chain",
        title = "Basic LLM",
        category = StepCategory.Ai,
        subtitle = "Send a prompt to a model and keep the answer",
        iconKey = "sparkles",
        params = (AiSlots.modelParams() + listOf(
            ParamSpec("prompt", "Prompt", ParamType.Multiline, required = true, placeholder = "Summarise this in one line"),
            ParamSpec("system", "Instructions", ParamType.Multiline, advanced = true),
            ParamSpec("data", "Data to read", ParamType.Multiline,
                hint = "Item data, for example {{ \$json.text }}. It is fenced as untrusted data, so text inside it cannot give orders."),
            ParamSpec("outputSchema", "Answer format", ParamType.Json, advanced = true, supportsExpression = false,
                hint = "Optional. {\"city\":\"string\",\"temp\":\"number\"} or a JSON Schema. The answer is checked and repaired."),
            ParamSpec("timeoutSeconds", "Time limit (seconds)", ParamType.Number, default = 60, advanced = true),
            ParamSpec("outputField", "Put the answer in", ParamType.Text, default = "answer"),
        )).toImmutableList(),
        keywords = listOf("llm", "prompt", "ai", "chain", "ask", "gpt", "claude", "gemini").toImmutableList(),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val global = ctx.resolved()
        val slot = AiSlots.slotFrom(ctx, global.toMap())
        val client = AiSlots.client(ctx, slot, factory)
        val parser = ctx.step.params["outputSchema"]?.takeIf { it.toString().isNotBlank() }?.let {
            StructuredOutputParser(SchemaCheck.normalize(it) ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the answer format is not valid"))
        }
        val limits = AgentLimits(maxIterations = 1, maxToolCalls = 0, maxOutputTokens = slot.maxTokens, maxWallMs = global.long("timeoutSeconds", 60).coerceIn(1, 3_600) * 1000)
        val outField = global.string("outputField", "answer")
        return ctx.perItem { index, item ->
            val row = ctx.resolvedFor(index)
            val prompt = row.string("prompt", "")
            if (prompt.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "the prompt is empty")
            val result = AgentLoop(client, limits = limits, parser = parser, temperature = slot.temperature)
                .run(row.stringOrNull("system"), prompt, row.stringOrNull("data"))
            if (!result.isSuccess) AiSlots.failure(ctx, result.error!!)
            val answer: Any? = if (parser != null) result.structured else result.text
            listOf(item.copy(json = item.json + (outField to answer) + ("_ai" to AiSlots.meta(result, slot))))
        }
    }
}
