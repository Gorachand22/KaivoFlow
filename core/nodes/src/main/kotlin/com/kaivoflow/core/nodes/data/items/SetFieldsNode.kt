package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.ParamResolver
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf

/** Set / edit fields on every item — the most-used Step in any automation tool. */
class SetFieldsNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.set",
        title = "Set fields",
        category = StepCategory.Transform,
        subtitle = "Add or overwrite fields on each item",
        iconKey = "edit",
        params = persistentListOf(
            ParamSpec(
                key = "fields",
                label = "Fields",
                type = ParamType.KeyValue,
                required = true,
                hint = "Field name → value. Values can use expressions like {{ ${'$'}json.amount * 2 }}.",
            ),
            ParamSpec(
                key = "keepOtherFields",
                label = "Keep other fields",
                type = ParamType.Boolean,
                default = true,
            ),
        ),
        keywords = persistentListOf("set", "edit", "fields", "assign", "map"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        // Raw (unresolved) params: each field template is resolved once per item below.
        val raw = ctx.step.params
        val fields = when (val value = raw["fields"]) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
            is List<*> -> value.mapNotNull { entry ->
                val map = Values.asMap(entry)
                val key = Values.asString(map["key"])
                if (key.isBlank()) null else key to map["value"]
            }.toMap()
            else -> throw StepExecutionException(ctx.step.id, ctx.step.name, "add at least one field")
        }
        if (fields.isEmpty()) return ctx.output(ctx.input)

        val keepOthers = Values.asBoolean(raw["keepOtherFields"] ?: true)
        val resolver = ParamResolver(ctx.evaluator)
        val out = ctx.input.mapIndexed { index, item ->
            val scope = ctx.scope(index)
            val updated = if (keepOthers) LinkedHashMap(item.json) else LinkedHashMap()
            fields.forEach { (key, template) ->
                updated[key] = resolver.resolveValue(template, scope)
            }
            item.copy(json = updated)
        }
        return ctx.output(out)
    }
}
