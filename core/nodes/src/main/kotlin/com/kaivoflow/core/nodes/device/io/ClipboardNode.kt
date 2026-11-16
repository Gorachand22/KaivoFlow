package com.kaivoflow.core.nodes.device.io

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.DeviceGateway
import kotlinx.collections.immutable.persistentListOf

class ClipboardNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.clipboard",
        title = "Copy to clipboard",
        category = StepCategory.Device,
        subtitle = "Put text on the clipboard",
        iconKey = "clipboard",
        params = persistentListOf(ParamSpec("text", "Text", ParamType.Multiline, required = true)),
        keywords = persistentListOf("clipboard", "copy", "paste"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val text = Values.asString(ctx.params()["text"])
        gateway.copyToClipboard(text)
        return ctx.output(ctx.input)
    }
}
