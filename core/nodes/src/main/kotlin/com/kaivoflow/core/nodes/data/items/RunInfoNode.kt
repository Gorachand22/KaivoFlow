package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** Metadata about the current Run — handy for logs, filenames and dedupe keys. */
class RunInfoNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.run_info",
        title = "Run details",
        category = StepCategory.Logic,
        subtitle = "Add the Run id, time, attempt and item count",
        iconKey = "timeline",
        params = persistentListOf(
            textParam("outputField", "Save details in", default = "run"),
        ),
        producesItems = true,
        keywords = persistentListOf("run", "execution", "metadata", "time", "attempt", "id", "debug"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val field = ctx.outputName("run")
        val info = mapOf(
            "runId" to ctx.runId,
            "flowId" to ctx.flow.id,
            "flowName" to ctx.flow.name,
            "stepId" to ctx.step.id,
            "stepName" to ctx.step.name,
            "mode" to ctx.mode.name,
            "attempt" to ctx.attempt.toDouble(),
            "now" to ctx.clock().toDouble(),
            "timezone" to ctx.timezone,
            "inputItems" to ctx.input.size.toDouble(),
        )
        return ctx.output(listOf(FlowData(mapOf(field to info))))
    }
}
