package com.kaivoflow.core.nodes.ai.chains

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.nodes.ai.shared.AiClient
import com.kaivoflow.core.nodes.ai.shared.aiSpec
import com.kaivoflow.core.nodes.outputField

/** Ask the model anything and store the answer on the item. */
class AiChatNode : StepExecutor {
    override val spec = aiSpec(
        id = "ai.chat",
        title = "Ask AI",
        subtitle = "Send a prompt and keep the answer",
        iconKey = "sparkles",
        extraParams = listOf(
            ParamSpec(
                key = "prompt",
                label = "Prompt",
                type = ParamType.Multiline,
                required = true,
                placeholder = "Summarise this in one line: {{ ${'$'}json.text }}",
            ),
            ParamSpec("system", "System instructions", ParamType.Multiline, advanced = true),
            ParamSpec("outputField", "Put the answer in", ParamType.Text, default = "answer"),
        ),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val outputs = ctx.input.mapIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val prompt = Values.asString(row["prompt"])
            if (prompt.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "the prompt is empty")
            val system = Values.asString(row["system"])
            val result = AiClient.chat(ctx, row, prompt, system, jsonMode = false)
            val field = Values.asString(row["outputField"], "answer")
            item.copy(json = item.json + (field to result.text))
        }
        return ctx.output(outputs)
    }
}
