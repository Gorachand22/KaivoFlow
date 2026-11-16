package com.kaivoflow.core.nodes.flow.branching

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf

class MergeNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.merge",
        title = "Merge",
        category = StepCategory.Logic,
        subtitle = "Join two branches back together",
        iconKey = "merge",
        params = persistentListOf(
            ParamSpec(
                key = "mode",
                label = "How to combine",
                type = ParamType.Select,
                default = "append",
                options = persistentListOf(
                    ParamOption("append", "One after another"),
                    ParamOption("zip", "Pair them up"),
                    ParamOption("multiply", "Every combination"),
                    ParamOption("combine", "Merge fields of matching items"),
                ),
            ),
            ParamSpec("matchField", "Match items on field", ParamType.Text, showWhen = mapOf("mode" to listOf("combine"))),
        ),
        keywords = persistentListOf("join", "union", "combine", "merge"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val mode = Values.asString(params["mode"], "append")
        // The engine concatenates all incoming Streams into ctx.input in Stream order, so a real
        // merge needs the split point: recompute from the flow's incoming Streams.
        val incoming = ctx.flow.incoming(ctx.step.id).sortedBy { it.fromPort }
        val scopes = incoming.mapNotNull { stream -> ctx.stepOutputs[ctx.flow.step(stream.fromStepId)?.name ?: ""] }
        val groups = if (scopes.size >= 2) scopes else listOf(ctx.input)

        val items = when (mode) {
            "zip" -> groups[0].zip(groups.getOrElse(1) { emptyList() })
                .map { (a, b) -> FlowData(Values.deepMerge(a.json, b.json) as Map<String, Any?>) }
            "multiply" -> groups[0].flatMap { a ->
                groups.getOrElse(1) { emptyList() }.map { b ->
                    FlowData(Values.deepMerge(a.json, b.json) as Map<String, Any?>)
                }
            }
            "combine" -> {
                val matchField = Values.asString(params["matchField"])
                val right = groups.getOrElse(1) { emptyList() }
                groups[0].map { a ->
                    if (matchField.isBlank()) a
                    else {
                        val match = right.firstOrNull { Values.asString(it.json[matchField]) == Values.asString(a.json[matchField]) }
                        if (match == null) a else FlowData(Values.deepMerge(a.json, match.json) as Map<String, Any?>)
                    }
                }
            }
            else -> groups.flatten()
        }
        return ctx.output(items)
    }
}
