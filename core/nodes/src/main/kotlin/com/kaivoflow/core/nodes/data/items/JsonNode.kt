package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.JsonLite
import kotlinx.collections.immutable.persistentListOf

/** Parse / build JSON, including "turn this text into fields". */
class JsonNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.json",
        title = "JSON",
        category = StepCategory.Transform,
        subtitle = "Parse text into fields, or build a JSON body",
        iconKey = "braces",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "parse",
                options = persistentListOf(
                    ParamOption("parse", "Parse text into fields"),
                    ParamOption("stringify", "Turn fields into text"),
                    ParamOption("pick", "Keep only a nested part"),
                ),
            ),
            ParamSpec("source", "From field", ParamType.Text, default = "text", showWhen = mapOf("action" to listOf("parse"))),
            ParamSpec("path", "Nested path", ParamType.Text, placeholder = "data.items", showWhen = mapOf("action" to listOf("pick"))),
            ParamSpec("targetField", "Put the result in", ParamType.Text, default = "json", showWhen = mapOf("action" to listOf("parse"))),
            ParamSpec("unwrapArray", "Create one item per array entry", ParamType.Boolean, default = true, showWhen = mapOf("action" to listOf("parse", "pick"))),
        ),
        keywords = persistentListOf("json", "parse", "stringify", "object"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        return when (Values.asString(params["action"], "parse")) {
            "stringify" -> ctx.output(ctx.input.map { FlowData(mapOf("json" to Values.stringify(it.json))) })
            "pick" -> {
                val path = Values.asString(params["path"])
                val unwrap = Values.asBoolean(params["unwrapArray"] ?: true)
                val picked = ctx.input.flatMap { item ->
                    val value = Values.deepGet(item.json, path)
                    if (unwrap && value is List<*>) value.map { FlowData(Values.asMap(it)) }
                    else listOf(FlowData(mapOf("value" to value)))
                }
                ctx.output(picked)
            }
            else -> {
                val source = Values.asString(params["source"], "text")
                val target = Values.asString(params["targetField"], "json")
                val unwrap = Values.asBoolean(params["unwrapArray"] ?: true)
                val items = ctx.input.flatMap { item ->
                    val text = Values.asString(Values.deepGet(item.json, source))
                    val parsed = JsonLite.parse(text)
                        ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$source\" is not valid JSON")
                    if (unwrap && parsed is List<*>) {
                        parsed.map { FlowData(Values.asMap(it)) }
                    } else {
                        listOf(item.copy(json = item.json + (target to parsed)))
                    }
                }
                ctx.output(items)
            }
        }
    }
}
