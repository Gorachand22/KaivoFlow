package com.kaivoflow.core.nodes.flow.branching

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.ConditionGroup
import com.kaivoflow.core.nodes.LogicParams
import kotlinx.collections.immutable.persistentListOf

/**
 * Control flow. Every branching Step here routes items per output port, which is what makes
 * the canvas' branch rendering and the engine's per-port liveness work.
 */
class IfNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.if",
        title = "If",
        category = StepCategory.Logic,
        subtitle = "Send items down the true or false branch",
        iconKey = "if",
        outputs = 2,
        outputLabels = persistentListOf("true", "false"),
        params = LogicParams.conditionParams(),
        keywords = persistentListOf("condition", "branch", "filter", "gate"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        // Raw params: condition templates are resolved per item inside ConditionGroup.
        val group = ConditionGroup.fromParams(ctx.step.params)
        val scope = ctx.scope()
        val resolver: (String, Map<String, Any?>) -> Any? = { template, itemJson ->
            ctx.evaluator.interpolate(template, scope + ("json" to itemJson))
        }
        val (yes, no) = ctx.input.partition { item -> group.matches(item.json, resolver) }
        return StepResult(mapOf(0 to yes, 1 to no))
    }
}
