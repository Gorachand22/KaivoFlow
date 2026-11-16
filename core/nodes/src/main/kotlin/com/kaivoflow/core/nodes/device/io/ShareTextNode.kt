package com.kaivoflow.core.nodes.device.io

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

class ShareTextNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.share",
        title = "Share sheet",
        category = StepCategory.Device,
        subtitle = "Hand text to another app on this phone",
        iconKey = "share",
        params = persistentListOf(
            textParam("text", "Text to share", default = "{{ \$json.text }}", required = true),
            textParam("title", "Share title", default = "Shared from KaivoFlow"),
        ),
        keywords = persistentListOf("share", "send", "sheet", "another app", "text"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        sources.forEachIndexed { index, _ ->
            gateway.shareText(
                ctx.resolveText(params["text"], index),
                ctx.resolveText(params["title"], index, "Shared from KaivoFlow"),
            )
        }
        return ctx.output(sources)
    }
}
