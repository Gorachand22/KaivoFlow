package com.kaivoflow.core.nodes.flow.branching

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
import com.kaivoflow.core.nodes.resolve
import kotlinx.collections.immutable.persistentListOf

class SwitchNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.switch",
        title = "Switch",
        category = StepCategory.Logic,
        subtitle = "Route items into up to four branches by value",
        iconKey = "switch",
        outputs = 5,
        outputLabels = persistentListOf("1", "2", "3", "4", "else"),
        params = persistentListOf(
            ParamSpec("field", "Value to check", ParamType.Text, required = true, placeholder = "{{ ${'$'}json.type }}"),
            ParamSpec("case1", "Branch 1 when it is", ParamType.Text, placeholder = "invoice"),
            ParamSpec("case2", "Branch 2 when it is", ParamType.Text),
            ParamSpec("case3", "Branch 3 when it is", ParamType.Text),
            ParamSpec("case4", "Branch 4 when it is", ParamType.Text),
        ),
        keywords = persistentListOf("route", "cases", "branch"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val rawValue = Values.asString(params["field"])
        val compiled = ParamResolverLite(rawValue)
        val outputs = MutableList(spec.outputs) { mutableListOf<FlowData>() }
        val cases = (1..4).map { Values.asString(params["case$it"]) }
        ctx.input.forEach { item ->
            val value = Values.asString(compiled.resolve(item.json) { template, json ->
                ctx.evaluator.interpolate(template, ctx.scope() + ("json" to json))
            })
            val index = cases.indexOfFirst { it.isNotBlank() && it.equals(value, ignoreCase = true) }
            outputs[if (index >= 0) index else 4] += item
        }
        if (rawValue.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "choose a value to switch on")
        return StepResult(outputs.mapIndexed { i, items -> i to items }.toMap())
    }
}

/** Tiny helper so the same template resolution is used in every branch Step. */
private class ParamResolverLite(private val template: String) {
    fun resolve(item: Map<String, Any?>, interpolate: (String, Map<String, Any?>) -> Any?): Any? =
        if (template.contains("{{")) interpolate(template, item) else Values.deepGet(item, template.removePrefix("${'$'}json."))
}
