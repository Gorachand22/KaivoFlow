package com.kaivoflow.core.nodes.device.files_media

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
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.JsonLite
import com.kaivoflow.core.nodes.UserFileStore
import kotlinx.collections.immutable.persistentListOf

class FileNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.file",
        title = "Save to a file",
        category = StepCategory.Device,
        subtitle = "Write or read JSON on this device",
        iconKey = "file",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "write",
                options = persistentListOf(ParamOption("write", "Write"), ParamOption("read", "Read")),
            ),
            ParamSpec("fileName", "File name", ParamType.Text, required = true, default = "state.json"),
            ParamSpec("content", "Content", ParamType.Multiline, showWhen = mapOf("action" to listOf("write"))),
            ParamSpec("parse", "Parse JSON when reading", ParamType.Boolean, default = true, showWhen = mapOf("action" to listOf("read"))),
        ),
        keywords = persistentListOf("file", "save", "storage", "json", "persist"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val fileName = Values.asString(params["fileName"], "state.json")
        try {
            UserFileStore.checkName(fileName)
        } catch (e: IllegalArgumentException) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, e.message ?: "invalid file name")
        }
        return if (Values.asString(params["action"], "write") == "read") {
            val content = gateway.readFile(fileName)
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "there is no saved file called $fileName yet")
            val value = if (Values.asBoolean(params["parse"] ?: true)) JsonLite.parse(content) ?: content else content
            ctx.output(listOf(com.kaivoflow.core.model.FlowData(mapOf("content" to value))))
        } else {
            val content = Values.asString(params["content"])
            gateway.writeFile(fileName, content.ifBlank { Values.stringify(ctx.input.map { it.json }) })
            ctx.output(ctx.input)
        }
    }
}
