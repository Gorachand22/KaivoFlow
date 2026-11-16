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

class VibrateNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.vibrate",
        title = "Vibrate",
        category = StepCategory.Device,
        subtitle = "Buzz the phone in a pattern",
        iconKey = "vibrate",
        params = persistentListOf(
            ParamSpec("pattern", "Pattern (ms, comma separated)", ParamType.Text, default = "120,80,120"),
        ),
        keywords = persistentListOf("vibrate", "buzz", "haptic", "alert"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val pattern = Values.asString(ctx.params()["pattern"], "120,80,120")
            .split(',').mapNotNull { it.trim().toLongOrNull() }.ifEmpty { listOf(120L) }
        gateway.vibrate(pattern)
        return ctx.output(ctx.input)
    }
}
