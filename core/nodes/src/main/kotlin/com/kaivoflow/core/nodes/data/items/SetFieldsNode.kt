package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.ParamResolver
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.JsonShape
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.withKinds
import com.kaivoflow.core.nodes.MiniJson
import com.kaivoflow.core.nodes.delegate
import kotlinx.collections.immutable.persistentListOf

/**
 * Edit Fields, the data hub. In one Step:
 *  1. assign fields (`assignments`: name, type, value; or the quick `fields` map),
 *  2. run helpers in order (`operations`: text, math, json, url),
 *  3. keep only, remove, then rename fields.
 * It replaces the former Rename or remove fields, Write text, JSON, Math, Text, URL and Run details
 * Steps. The helpers reuse the retired Steps' engines unchanged ([FieldsNode], [JsonNode], [MathNode],
 * [TextNode], [UrlNode]); they are no longer registered in the catalog.
 */
class SetFieldsNode : StepExecutor {
    override val spec = NodeSpec(
        id = "data.set",
        title = "Edit fields",
        category = StepCategory.Transform,
        subtitle = "Add, change, rename or remove fields",
        iconKey = "edit",
        params = persistentListOf(
            ParamSpec(
                key = "assignments",
                label = "Fields to set",
                type = ParamType.Json,
                hint = "Rows of name, type (text, number, boolean, list, object, any) and value. Values can use expressions like {{ ${'$'}json.amount * 2 }}.",
            ),
            ParamSpec(
                key = "fields",
                label = "Quick fields",
                type = ParamType.KeyValue,
                hint = "Field name → value. Values can use expressions like {{ ${'$'}json.amount * 2 }}.",
            ),
            ParamSpec(
                key = "keepOtherFields",
                label = "Keep other fields",
                type = ParamType.Boolean,
                default = true,
            ),
            ParamSpec(
                key = "operations",
                label = "Helpers",
                type = ParamType.Json,
                advanced = true,
                hint = "List of helpers run in order after the fields are set. Each row has an \"op\" (text, math, json or url) plus that helper's settings.",
            ),
            ParamSpec("rename", "Rename (old → new)", ParamType.KeyValue, advanced = true, hint = "from = to"),
            ParamSpec("remove", "Remove fields", ParamType.Text, advanced = true, placeholder = "internalId, debug"),
            ParamSpec("keepOnly", "Keep only these fields", ParamType.Text, advanced = true, placeholder = "id, amount, merchant"),
        ),
        keywords = persistentListOf(
            "set", "edit", "fields", "assign", "map", "rename", "remove", "keep", "template", "text", "math", "json", "url", "run", "details",
        ),
        replaces = persistentListOf(
            "transform.fields", "transform.template", "transform.json", "transform.math", "transform.text", "transform.url", "logic.run_info",
        ),
    ).withKinds(
        "assignments" to ParamKind.Assignments(),
        "operations" to ParamKind.Json(JsonShape.List),
    )

    private val helpers: Map<String, StepExecutor> = mapOf(
        "json" to JsonNode(), "math" to MathNode(), "text" to TextNode(), "url" to UrlNode(),
    )
    private val cleanup = FieldsNode()

    override suspend fun execute(ctx: StepContext): StepResult {
        val raw = ctx.step.params
        val hasAssignments = raw["assignments"] is List<*> && (raw["assignments"] as List<*>).isNotEmpty()
        val quick = quickFields(raw["fields"])
        val ops = (raw["operations"] as? List<*>).orEmpty()
        val hasCleanup = !Values.isBlank(raw["rename"]) || !Values.isBlank(raw["remove"]) || !Values.isBlank(raw["keepOnly"])
        if (!hasAssignments && quick == null && ops.isEmpty() && !hasCleanup) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "add at least one field")
        }

        var items: List<FlowData> = ctx.input
        if (hasAssignments || !quick.isNullOrEmpty()) items = assign(ctx, quick.orEmpty(), hasAssignments)
        for ((n, row) in ops.withIndex()) {
            val map = Values.asMap(row)
            val op = Values.asString(map["op"])
            val engine = helpers[op]
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "helper ${n + 1} has an unknown op \"$op\" (use text, math, json or url)")
            items = ctx.delegate(engine, map.filterKeys { it != "op" }, items).outputs[0].orEmpty()
        }
        if (hasCleanup) {
            val params = listOf("rename", "remove", "keepOnly").mapNotNull { k -> raw[k]?.let { k to it } }.toMap()
            items = ctx.delegate(cleanup, params, items).outputs[0].orEmpty()
        }
        return ctx.output(items)
    }

    /** Quick `fields` map or list of key/value rows; null when the param is absent. */
    private fun quickFields(value: Any?): Map<String, Any?>? = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
        is List<*> -> value.mapNotNull { entry ->
            val map = Values.asMap(entry)
            val key = Values.asString(map["key"]).ifBlank { Values.asString(map["name"]) }
            if (key.isBlank()) null else key to map["value"]
        }.toMap()
        else -> null
    }

    private fun assign(ctx: StepContext, quick: Map<String, Any?>, hasAssignments: Boolean): List<FlowData> {
        val keepOthers = Values.asBoolean(ctx.step.params["keepOtherFields"] ?: true)
        val resolver = ParamResolver(ctx.evaluator)
        return ctx.input.mapIndexed { index, item ->
            val scope = ctx.scope(index)
            val updated: MutableMap<String, Any?> = if (keepOthers) LinkedHashMap(item.json) else LinkedHashMap()
            quick.forEach { (key, template) -> updated[key] = resolver.resolveValue(template, scope) }
            if (hasAssignments) {
                (ctx.resolvedFor(index)["assignments"] as? List<*>).orEmpty().forEach { row ->
                    val map = Values.asMap(row)
                    val name = Values.asString(map["name"]).trim()
                    if (name.isEmpty()) throw StepExecutionException(ctx.step.id, ctx.step.name, "a field to set has no name")
                    updated[name] = coerce(ctx, name, Values.asString(map["type"], "any"), map["value"])
                }
            }
            item.copy(json = updated)
        }
    }

    private fun coerce(ctx: StepContext, name: String, type: String, value: Any?): Any? = when (type.lowercase()) {
        "text" -> if (value == null) "" else Values.asString(value)
        "number" -> when (value) {
            null -> null
            is Number -> value.toDouble()
            else -> Values.asDouble(value)
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$name\" is not a number: ${Values.asString(value)}")
        }
        "boolean" -> if (value == null) null else Values.asBoolean(value)
        "list" -> when (value) {
            is List<*> -> value
            is String -> MiniJson.parse(value) as? List<*>
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$name\" is not a JSON list")
            else -> value
        }
        "object" -> when (value) {
            is Map<*, *> -> value
            is String -> MiniJson.parse(value) as? Map<*, *>
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$name\" is not a JSON object")
            else -> value
        }
        else -> value
    }
}
