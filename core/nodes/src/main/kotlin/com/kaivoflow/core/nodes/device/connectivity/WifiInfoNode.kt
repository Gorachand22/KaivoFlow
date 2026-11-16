package com.kaivoflow.core.nodes.device.connectivity

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

class WifiInfoNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.wifi_info",
        title = "Wi-Fi details",
        category = StepCategory.Device,
        subtitle = "Read the current network name and connection state",
        iconKey = "wifi",
        params = persistentListOf(
            textParam("outputField", "Save details in", default = "wifi"),
        ),
        keywords = persistentListOf("wifi", "network", "ssid", "connection", "signal", "details"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val info = gateway.getWifiInfo()
        val field = ctx.outputName("wifi")
        return ctx.output(listOf(FlowData(mapOf(field to info, "available" to info.isNotEmpty()))))
    }
}
