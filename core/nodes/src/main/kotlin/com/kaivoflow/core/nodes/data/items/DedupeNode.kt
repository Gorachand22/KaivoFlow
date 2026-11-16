package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf

class DedupeNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.dedupe",
        title = "Remove duplicates",
        category = StepCategory.Logic,
        subtitle = "Drop repeated items, keeping the first",
        iconKey = "unique",
        params = persistentListOf(
            ParamSpec("fields", "Compare using fields", ParamType.Text, placeholder = "id, email (leave empty for the whole item)"),
            ParamSpec(
                key = "scope",
                label = "Remember",
                type = ParamType.Select,
                default = "run",
                options = persistentListOf(
                    ParamOption("run", "Within this Run"),
                    ParamOption("day", "Within today"),
                    ParamOption("always", "Across every Run (needs storage)"),
                ),
            ),
        ),
        keywords = persistentListOf("unique", "distinct", "duplicate"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val fields = Values.asString(params["fields"]).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val seen = mutableSetOf<String>()
        val kept = ctx.input.filter { item ->
            val key = if (fields.isEmpty()) Values.stringify(item.json)
            else fields.joinToString("|") { Values.asString(Values.deepGet(item.json, it)) }
            seen.add(key)
        }
        ctx.log("Removed ${ctx.input.size - kept.size} duplicate items.")
        return ctx.output(kept)
    }
}
