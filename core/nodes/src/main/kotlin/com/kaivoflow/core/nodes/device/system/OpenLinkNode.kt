package com.kaivoflow.core.nodes.device.system

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.DeviceGateway
import kotlinx.collections.immutable.persistentListOf

class OpenLinkNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.open",
        title = "Open a link or app",
        category = StepCategory.Device,
        subtitle = "Launch a URL, deep link or installed app",
        iconKey = "external",
        params = persistentListOf(
            ParamSpec("uri", "URL or deep link", ParamType.Text, required = true, placeholder = "https://…"),
            ParamSpec("packageName", "App package", ParamType.Text, advanced = true, placeholder = "com.whatsapp"),
        ),
        keywords = persistentListOf("open", "launch", "deep link", "intent", "app"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val uri = Values.asString(params["uri"])
        if (uri.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add a URL or deep link")
        gateway.openUrlOrApp(uri, Values.asString(params["packageName"]).ifBlank { null })
        return ctx.output(ctx.input)
    }
}
