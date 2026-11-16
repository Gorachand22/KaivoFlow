package com.kaivoflow.core.nodes.device.io

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** The rest of the phone capabilities exposed as small, single-purpose Steps. */
class ClipboardReadNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.clipboard_read",
        title = "Read clipboard",
        category = StepCategory.Device,
        subtitle = "Use the text currently copied on this phone",
        iconKey = "clipboard",
        params = persistentListOf(
            textParam("outputField", "Save text in", default = "clipboard"),
        ),
        keywords = persistentListOf("clipboard", "paste", "copied", "text", "read"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val text = gateway.readClipboard().orEmpty()
        val field = ctx.outputName("clipboard")
        return ctx.output(listOf(FlowData(mapOf(field to text, "empty" to text.isBlank()))))
    }
}
