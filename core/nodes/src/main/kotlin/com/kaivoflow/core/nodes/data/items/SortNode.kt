package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant

/** Stable ordering by a field, with numeric, text and date-time awareness. */
class SortNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.sort",
        title = "Sort items",
        category = StepCategory.Logic,
        subtitle = "Put your items in the order you want",
        iconKey = "sortByAlpha",
        params = persistentListOf(
            textParam("field", "Sort by field", placeholder = "date", required = true),
            ParamSpec(
                key = "direction",
                label = "Order",
                type = ParamType.Select,
                default = "asc",
                options = selectOptions("asc" to "Smallest / A first", "desc" to "Largest / Z first"),
            ),
            ParamSpec(
                key = "as",
                label = "Treat values as",
                type = ParamType.Select,
                default = "auto",
                options = selectOptions("auto" to "Automatic", "number" to "Numbers", "text" to "Text", "date" to "Date-time"),
            ),
        ),
        keywords = persistentListOf("sort", "order", "ascending", "descending", "rank", "arrange"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val field = Values.asString(params["field"])
        if (field.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "choose a field to sort by")
        val direction = Values.asString(params["direction"], "asc")
        val asType = Values.asString(params["as"], "auto")
        val indexed = ctx.input.mapIndexed { index, item -> index to item }
        val comparator = Comparator<Pair<Int, FlowData>> { left, right ->
            val a = Values.deepGet(left.second.json, field)
            val b = Values.deepGet(right.second.json, field)
            val result = compareValues(a, b, asType)
            // Stable sort: equal values keep their incoming order.
            if (result == 0) left.first.compareTo(right.first) else if (direction == "desc") -result else result
        }
        return ctx.output(indexed.sortedWith(comparator).map { it.second })
    }

    private fun compareValues(a: Any?, b: Any?, asType: String): Int {
        if (a == null && b == null) return 0
        if (a == null) return 1
        if (b == null) return -1
        if (asType == "number" || (asType == "auto" && Values.asDouble(a) != null && Values.asDouble(b) != null)) {
            val x = Values.asDouble(a) ?: 0.0
            val y = Values.asDouble(b) ?: 0.0
            return x.compareTo(y)
        }
        if (asType == "date") {
            val x = dateValue(a) ?: 0L
            val y = dateValue(b) ?: 0L
            return x.compareTo(y)
        }
        return Values.asString(a).compareTo(Values.asString(b), ignoreCase = true)
    }

    private fun dateValue(value: Any?): Long? {
        (value as? Number)?.let { return it.toLong() }
        val text = Values.asString(value)
        text.toLongOrNull()?.let { return it }
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
    }
}
