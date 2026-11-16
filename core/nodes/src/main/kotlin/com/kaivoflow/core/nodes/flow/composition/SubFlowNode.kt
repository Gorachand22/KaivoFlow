package com.kaivoflow.core.nodes.flow.composition

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
import com.kaivoflow.core.nodes.resolveText
import kotlinx.collections.immutable.persistentListOf

/** Calls another Flow as a Step (one of the original composition tools, with the API connector and change polling). */
class SubFlowNode : StepExecutor {
    override val spec = NodeSpec(
        id = "flow.subflow",
        title = "Run another Flow",
        category = StepCategory.Logic,
        subtitle = "Reuse a Flow and bring its items back",
        iconKey = "flow",
        params = persistentListOf(
            ParamSpec("flowId", "Flow ID", ParamType.Text, required = true,
                hint = "Paste the ID of another Flow saved on this phone."),
            ParamSpec("includeInput", "Send incoming items", ParamType.Boolean, default = true),
            ParamSpec("maxItems", "Max items to send", ParamType.Number, default = 100, advanced = true),
            ParamSpec("outputField", "Wrap result in field", ParamType.Text, advanced = true),
        ),
        keywords = persistentListOf("subflow", "reuse", "call", "compose", "flow", "child"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val invoker = ctx.flowInvoker
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "Sub-Flows are not available in this Run")
        val raw = ctx.step.params
        val flowId = ctx.resolveText(raw["flowId"], 0)
        if (flowId.isBlank()) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "choose the Flow this Step should run")
        }
        val sendItems = if (Values.asBoolean(raw["includeInput"] ?: true)) ctx.input else emptyList()
        val limit = (Values.asDouble(raw["maxItems"]) ?: 100.0).toInt().coerceIn(1, 5_000)
        val result = invoker.invoke(flowId, sendItems.take(limit), ctx.subFlowDepth + 1)
        val field = ctx.resolveText(raw["outputField"], 0)
        val out = if (field.isBlank()) result else result.map { item ->
            FlowData(
                json = mapOf(field to item.json),
                binary = item.binary,
                error = item.error,
            )
        }
        ctx.log("Sub-Flow $flowId returned ${out.size} item(s)")
        return ctx.output(out)
    }
}
