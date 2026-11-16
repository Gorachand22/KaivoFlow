package com.kaivoflow.core.nodes.device.io

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
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.selectOptions
import kotlinx.collections.immutable.persistentListOf

class TorchNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.torch",
        title = "Torch",
        category = StepCategory.Device,
        subtitle = "Turn the camera light on or off",
        iconKey = "flashOn",
        requiresSensitivePermission = "android.permission.CAMERA",
        params = persistentListOf(
            ParamSpec(
                key = "state",
                label = "Torch",
                type = ParamType.Select,
                default = "on",
                options = selectOptions("on" to "Turn on", "off" to "Turn off", "toggle" to "Toggle"),
            ),
        ),
        keywords = persistentListOf("torch", "flashlight", "camera light", "light", "hardware"),
    )

    private var lastOn = false

    override suspend fun execute(ctx: StepContext): StepResult {
        val state = Values.asString(ctx.step.params["state"], "on")
        val on = when (state) {
            "off" -> false
            "toggle" -> !lastOn
            else -> true
        }
        if (!gateway.setTorch(on)) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "this phone could not control the torch")
        }
        lastOn = on
        return ctx.output(ctx.input.ifEmpty { listOf(FlowData(mapOf("torch" to on))) })
    }
}
