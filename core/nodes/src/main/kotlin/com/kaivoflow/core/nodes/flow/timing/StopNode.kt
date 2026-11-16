package com.kaivoflow.core.nodes.flow.timing

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.StopSignal
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
                options = persistentListOf(ParamOption("success", "Stopped (no error)"), ParamOption("error", "Failed")),
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
        val message = Values.asString(params["message"])
        message.takeIf { it.isNotBlank() }?.let { ctx.log(it) }
        // The runner cancels every Step that has not finished and reports the Run as Stopped.
        return StepResult(
            outputs = mapOf(0 to ctx.input),
            stop = StopSignal(message.ifBlank { "Stopped by \"${ctx.step.name}\"." }),
        )
    }
}
