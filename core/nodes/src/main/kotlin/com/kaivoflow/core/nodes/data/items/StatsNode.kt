package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One summary item for a numeric field across the whole incoming list. */
class StatsNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.stats",
        title = "Number summary",
        category = StepCategory.Transform,
        subtitle = "Count, total, min, max, mean, median and spread",
        iconKey = "queryStats",
        params = persistentListOf(
            textParam("field", "Number field", placeholder = "amount", required = true),
            textParam("operations", "Include", default = "count,sum,min,max,mean,median,stddev"),
            textParam("outputField", "Save summary in", default = "stats"),
        ),
        keywords = persistentListOf("statistics", "average", "median", "deviation", "sum", "min", "max", "count"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val field = ctx.resolveText(params["field"], 0)
        if (field.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "choose a number field")
        val values = ctx.input.mapNotNull { item ->
            Values.asDouble(Values.deepGet(item.json, field))
        }
        val wanted = ctx.resolveText(params["operations"], 0, "count,sum,min,max,mean,median,stddev")
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.ifEmpty { listOf("count") }
        val sorted = values.sorted()
        val mean = if (values.isEmpty()) 0.0 else values.average()
        val stats = LinkedHashMap<String, Any?>()
        wanted.forEach { op ->
            stats[op] = when (op) {
                "count" -> values.size.toDouble()
                "sum", "total" -> values.sum()
                "min" -> sorted.firstOrNull() ?: 0.0
                "max" -> sorted.lastOrNull() ?: 0.0
                "mean", "average", "avg" -> mean
                "median" -> median(sorted)
                "stddev", "stdev" -> if (values.size < 2) 0.0 else {
                    sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
                }
                "variance" -> if (values.size < 2) 0.0 else {
                    values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
                }
                else -> null
            }
        }
        stats.removeWhereNull()
        val outField = ctx.outputName("stats")
        return ctx.output(listOf(FlowData(mapOf(outField to stats, "samples" to values.size))))
    }

    private fun median(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 0.0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun MutableMap<String, Any?>.removeWhereNull() {
        val keys = entries.filter { it.value == null }.map { it.key }
        keys.forEach { remove(it) }
    }
}
