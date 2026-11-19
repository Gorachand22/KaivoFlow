package com.kaivoflow.core.nodes.ai.parsers

import com.kaivoflow.core.ai.ParseResult
import com.kaivoflow.core.ai.SchemaCheck
import com.kaivoflow.core.ai.StructuredOutputParser
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.NodeRole
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.toImmutableList

/**
 * Output Parser (structured): checks text against a JSON schema and keeps the parsed value. An Agent
 * or Basic LLM uses the same schema through its own slot, with a repair retry; this Step is the
 * model-free form, handy after a Webhook or an HTTP call. A bad item fails alone.
 */
class StructuredParserNode : StepExecutor {
    override val spec = NodeSpec(
        id = "ai.parser.structured",
        title = "Output Parser",
        category = StepCategory.Ai,
        subtitle = "Check JSON text against a schema",
        iconKey = "sparkles",
        params = listOf(
            ParamSpec("schema", "Schema", ParamType.Json, required = true, supportsExpression = false,
                hint = "{\"city\":\"string\",\"temp\":\"number\"} or a JSON Schema"),
            ParamSpec("text", "Text to parse", ParamType.Multiline, required = true, placeholder = "{{ \$json.answer }}"),
            ParamSpec("outputField", "Put the result in", ParamType.Text, default = "parsed"),
        ).toImmutableList(),
        keywords = listOf("parser", "json", "schema", "structured", "validate", "ai").toImmutableList(),
        role = NodeRole.AiParser,
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val schema = SchemaCheck.normalize(ctx.step.params["schema"])
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "add a schema: a JSON Schema or field names with types")
        val parser = StructuredOutputParser(schema)
        val outField = ctx.resolved().string("outputField", "parsed")
        return ctx.perItem { index, item ->
            val text = ctx.resolvedFor(index).string("text", "")
            if (text.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "there is no text to parse")
            when (val r = parser.parse(text)) {
                is ParseResult.Ok -> listOf(item.copy(json = item.json + (outField to r.value)))
                is ParseResult.Invalid -> throw StepExecutionException(ctx.step.id, ctx.step.name, "the text does not match the schema: ${r.errors.take(3).joinToString("; ")}")
            }
        }
    }
}
