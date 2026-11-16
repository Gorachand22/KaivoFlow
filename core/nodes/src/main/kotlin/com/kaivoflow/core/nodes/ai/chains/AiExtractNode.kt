package com.kaivoflow.core.nodes.ai.chains

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.nodes.ai.shared.AiClient
import com.kaivoflow.core.nodes.ai.shared.AiExtractionSchema
import com.kaivoflow.core.nodes.ai.shared.aiSpec

/** Extract fields using a shallow type contract, rejecting malformed or unexpected output. */
class AiExtractNode : StepExecutor {
    override val spec = aiSpec(
        id = "ai.extract",
        title = "Extract fields with AI",
        subtitle = "Turn messy text into clean fields",
        iconKey = "extract",
        extraParams = listOf(
            ParamSpec("text", "Text to read", ParamType.Multiline, required = true, placeholder = "{{ ${'$'}json.text }}"),
            ParamSpec(
                key = "schema",
                label = "Fields to extract",
                type = ParamType.Json,
                required = true,
                default = """{"merchant":"string","amount":"number","date":"string","category":"string"}""",
            ),
            ParamSpec("instructions", "Extra instructions", ParamType.Multiline, advanced = true),
            ParamSpec("mergeIntoItem", "Add fields to the item", ParamType.Boolean, default = true),
        ),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val schema = try { AiExtractionSchema.parse(params["schema"]) }
        catch (e: IllegalArgumentException) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, e.message ?: "the field types are invalid")
        }
        val outputs = ctx.input.mapIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val text = Values.asString(row["text"])
            val extra = Values.asString(row["instructions"])
            val prompt = buildString {
                appendLine("Extract data from the text below.")
                appendLine("Return ONLY a JSON object with exactly these keys and types: ${Values.stringify(schema)}")
                appendLine("Use null when a value is not present. Do not invent values.")
                if (extra.isNotBlank()) appendLine(extra)
                appendLine()
                appendLine("TEXT:")
                append(text.take(12_000))
            }
            val result = AiClient.chat(ctx, row, prompt, "You are a precise data extraction engine. Reply with JSON only.", jsonMode = true)
            val extracted = result.json as? Map<*, *>
                ?: throw StepExecutionException(
                    ctx.step.id, ctx.step.name,
                    "the model did not return valid JSON. Try a larger model, or lower the creativity setting."
                )
            AiExtractionSchema.validate(schema, extracted)?.let { problem ->
                throw StepExecutionException(ctx.step.id, ctx.step.name, problem)
            }
            if (Values.asBoolean(row["mergeIntoItem"] ?: true)) {
                item.copy(json = Values.deepMerge(item.json, extracted) as Map<String, Any?>)
            } else {
                FlowData(Values.asMap(extracted))
            }
        }
        return ctx.output(outputs)
    }
}
