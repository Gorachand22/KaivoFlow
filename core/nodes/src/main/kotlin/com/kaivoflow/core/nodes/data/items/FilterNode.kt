package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.ConditionGroup
import com.kaivoflow.core.nodes.LogicParams
import kotlinx.collections.immutable.persistentListOf

class FilterNode : StepExecutor {
    override val spec = NodeSpec(
        id = "data.filter",
        title = "Filter",
        category = StepCategory.Logic,
        subtitle = "Keep only items that match a condition",
        iconKey = "filter",
        params = LogicParams.conditionParams(),
        keywords = persistentListOf("where", "keep", "condition"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val group = ConditionGroup.fromParams(ctx.step.params)
        val scope = ctx.scope()
        val resolver: (String, Map<String, Any?>) -> Any? = { template, json ->
            ctx.evaluator.interpolate(template, scope + ("json" to json))
        }
        val kept = ctx.input.filter { group.matches(it.json, resolver) }
        ctx.log("Kept ${kept.size} of ${ctx.input.size} items.")
        return ctx.output(kept)
    }
}
