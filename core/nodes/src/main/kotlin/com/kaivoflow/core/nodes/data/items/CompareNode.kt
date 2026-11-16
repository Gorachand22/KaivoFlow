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
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** Compare the incoming items with a saved baseline and describe what changed. */
class CompareNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.compare",
        title = "Compare lists",
        category = StepCategory.Logic,
        subtitle = "Find added, removed, changed and matching rows",
        iconKey = "difference",
        params = persistentListOf(
            textParam("key", "Match rows by field", placeholder = "id", required = true),
            ParamSpec("baseline", "Earlier list (JSON array)", ParamType.Json,
                default = emptyList<Any?>(), hint = "Paste the earlier items, or connect a table read."),
            textParam("skipFields", "Ignore fields", placeholder = "updatedAt, syncedAt"),
            ParamSpec("fuzzy", "Ignore case and punctuation in text", ParamType.Boolean, default = true),
            textParam("outputField", "Save result in", default = "changes"),
        ),
        keywords = persistentListOf("compare", "diff", "changed", "added", "removed", "dataset", "match", "fuzzy"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val key = Values.asString(params["key"])
        if (key.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "choose the field that identifies a row")
        val baselineRaw = params["baseline"]
        val baseline = Values.asList(baselineRaw).map { Values.asMap(it) }
        val current = ctx.input.map { it.json }
        val skip = Values.asString(params["skipFields"])
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val fuzzy = Values.asBoolean(params["fuzzy"] ?: true)
        val before = index(baseline, key)
        val after = index(current, key)

        fun normal(row: Map<String, Any?>): Map<String, Any?> =
            row.filterKeys { it !in skip }.mapValues { (_, value) ->
                if (fuzzy && value is String) value.lowercase().replace(Regex("[^a-z0-9]"), "") else value
            }

        val rows = mutableListOf<Map<String, Any?>>()
        (before.keys + after.keys).distinct().forEach { id ->
            val old = before[id]
            val new = after[id]
            val status = when {
                old == null -> "added"
                new == null -> "removed"
                normal(old) == normal(new) -> "same"
                else -> "changed"
            }
            val changedFields = if (status == "changed") {
                (old!!.keys + new!!.keys).distinct().filter { field ->
                    field !in skip && normal(mapOf(field to old[field])) != normal(mapOf(field to new[field]))
                }
            } else emptyList()
            rows += mapOf(
                "key" to id,
                "status" to status,
                "changedFields" to changedFields,
                "before" to old,
                "after" to new,
            )
        }
        val summary = mapOf(
            "added" to rows.count { it["status"] == "added" }.toDouble(),
            "removed" to rows.count { it["status"] == "removed" }.toDouble(),
            "changed" to rows.count { it["status"] == "changed" }.toDouble(),
            "same" to rows.count { it["status"] == "same" }.toDouble(),
        )
        val field = ctx.outputName("changes")
        return ctx.output(listOf(FlowData(mapOf(field to rows, "summary" to summary))))
    }

    private fun index(rows: List<Map<String, Any?>>, key: String): Map<String, Map<String, Any?>> =
        rows.mapNotNull { row ->
            val id = Values.asString(Values.deepGet(row, key))
            if (id.isBlank()) null else id to row
        }.toMap()
}
