package com.kaivoflow.core.nodes.device.system

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
import kotlinx.collections.immutable.persistentListOf

class VolumeNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.volume",
        title = "Volume",
        category = StepCategory.Device,
        subtitle = "Set the media volume to a percentage",
        iconKey = "speaker",
        params = persistentListOf(
            ParamSpec("level", "Volume (0–100)", ParamType.Number, default = 50),
        ),
        keywords = persistentListOf("volume", "sound", "audio", "media", "loudness"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val level = (Values.asDouble(ctx.step.params["level"]) ?: 50.0).toInt().coerceIn(0, 100)
        if (!gateway.setVolume(level)) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "this phone could not change the volume")
        }
        return ctx.output(ctx.input.ifEmpty { listOf(FlowData(mapOf("volume" to level.toDouble()))) })
    }
}
