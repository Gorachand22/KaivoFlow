package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf

/** Group, count and total items — "how much did I spend at each merchant today". */
class AggregateNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.aggregate",
        title = "Group & total",
        category = StepCategory.Transform,
        subtitle = "Group items and total a number field",
        iconKey = "sum",
        params = persistentListOf(
            ParamSpec("groupBy", "Group by field", ParamType.Text, placeholder = "merchant"),
            ParamSpec("sumField", "Total this field", ParamType.Text, placeholder = "amount"),
            ParamSpec("count", "Count items", ParamType.Boolean, default = true),
        ),
        keywords = persistentListOf("group", "sum", "total", "aggregate", "count", "report"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val groupBy = Values.asString(params["groupBy"])
        val sumField = Values.asString(params["sumField"])
        val count = Values.asBoolean(params["count"] ?: true)

        if (groupBy.isBlank() && sumField.isBlank()) {
            return ctx.output(listOf(FlowData(mapOf("count" to ctx.input.size))))
        }

        val groups = LinkedHashMap<String, MutableList<FlowData>>()
        ctx.input.forEach { item ->
            val key = if (groupBy.isBlank()) "all" else Values.asString(Values.deepGet(item.json, groupBy))
            groups.getOrPut(key) { mutableListOf() } += item
        }

        val out = groups.map { (key, items) ->
            val json = LinkedHashMap<String, Any?>()
            if (groupBy.isNotBlank()) json[groupBy] = key
            if (count) json["count"] = items.size
            if (sumField.isNotBlank()) {
                json["total"] = items.sumOf { Values.asDouble(Values.deepGet(it.json, sumField)) ?: 0.0 }
                json["average"] = if (items.isEmpty()) 0.0 else
                    (json["total"] as Double) / items.size
            }
            FlowData(json)
        }
        ctx.log("Grouped ${ctx.input.size} items into ${out.size} rows.")
        return ctx.output(out)
    }
}
