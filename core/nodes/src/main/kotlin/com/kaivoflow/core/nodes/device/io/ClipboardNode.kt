package com.kaivoflow.core.nodes.device.io

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** One Step, two operations: put text on the clipboard, or read what is on it (absorbs the old "Read clipboard" Step). */
class ClipboardNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.clipboard",
        title = "Clipboard",
        category = StepCategory.Device,
        subtitle = "Copy text to the clipboard or read it",
        iconKey = "clipboard",
        params = persistentListOf(
            ParamSpec(
                "operation", "Operation", ParamType.Select, default = "write",
                options = selectOptions("write" to "Copy text to clipboard", "read" to "Read clipboard"),
            ),
            ParamSpec("text", "Text", ParamType.Multiline, required = true, showWhen = mapOf("operation" to listOf("write"))),
            textParam("outputField", "Save text in", default = "clipboard", showWhen = mapOf("operation" to listOf("read"))),
        ),
        keywords = persistentListOf("clipboard", "copy", "paste", "read", "copied"),
        replaces = persistentListOf("device.clipboard_read"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val operation = Values.asString(ctx.params()["operation"], "write")
        if (operation == "read") {
            val text = gateway.readClipboard().orEmpty()
            val field = ctx.outputName("clipboard")
            return ctx.output(listOf(FlowData(mapOf(field to text, "empty" to text.isBlank()))))
        }
        val text = Values.asString(ctx.params()["text"])
        gateway.copyToClipboard(text)
        return ctx.output(ctx.input)
    }
}
