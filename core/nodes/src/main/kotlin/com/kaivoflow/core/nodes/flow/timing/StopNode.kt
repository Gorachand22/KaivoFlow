package com.kaivoflow.core.nodes.flow.timing

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf

class StopNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.stop",
        title = "Stop",
        category = StepCategory.Logic,
        subtitle = "End the Run here and return these items",
        iconKey = "stop",
        params = persistentListOf(
            ParamSpec(
                key = "status",
                label = "Report the Run as",
                type = ParamType.Select,
                default = "success",
                options = persistentListOf(ParamOption("success", "Succeeded"), ParamOption("error", "Failed")),
            ),
            ParamSpec("message", "Message", ParamType.Text, placeholder = "Optional note shown in the Run log"),
        ),
        keywords = persistentListOf("end", "finish", "abort", "return"),
        producesItems = true,
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        if (Values.asString(params["status"]) == "error") {
            throw StepExecutionException(
                ctx.step.id, ctx.step.name,
                Values.asString(params["message"]).ifBlank { "stopped with an error" }
            )
        }
        Values.asString(params["message"]).takeIf { it.isNotBlank() }?.let { ctx.log(it) }
        // Downstream Steps are allowed to run: Stop simply ends *its* branch by producing items.
        return ctx.output(ctx.input)
    }
}
