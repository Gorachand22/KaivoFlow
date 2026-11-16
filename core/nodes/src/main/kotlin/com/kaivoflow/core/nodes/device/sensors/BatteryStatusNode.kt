package com.kaivoflow.core.nodes.device.sensors

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.outputField
import kotlinx.collections.immutable.persistentListOf

class BatteryStatusNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.battery_status",
        title = "Battery status",
        category = StepCategory.Device,
        subtitle = "Get battery level and charging state",
        iconKey = "battery",
        params = persistentListOf(
            ParamSpec("outputField", "Save status in", ParamType.Text, default = "battery"),
        ),
        keywords = persistentListOf("battery", "power", "charging", "level", "device"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val field = Values.asString(params["outputField"], "battery")
        val status = gateway.getBatteryStatus()
        val out = ctx.input.map { item ->
            item.copy(json = item.json + (field to status))
        }
        return ctx.output(out)
    }
}
