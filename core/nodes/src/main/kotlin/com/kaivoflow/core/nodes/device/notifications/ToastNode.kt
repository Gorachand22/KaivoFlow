package com.kaivoflow.core.nodes.device.notifications

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

class ToastNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.toast",
        title = "Quick message",
        category = StepCategory.Device,
        subtitle = "Flash a short on-screen message",
        iconKey = "info",
        params = persistentListOf(
            textParam("text", "Message", default = "{{ \$json.message }}", required = true),
        ),
        keywords = persistentListOf("toast", "message", "popup", "flash", "notice"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        sources.forEachIndexed { index, _ ->
            gateway.showToast(ctx.resolveText(params["text"], index).take(300))
        }
        return ctx.output(sources)
    }
}
