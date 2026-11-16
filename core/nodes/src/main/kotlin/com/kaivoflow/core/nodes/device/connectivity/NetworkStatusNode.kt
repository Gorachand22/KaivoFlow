package com.kaivoflow.core.nodes.device.connectivity

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

class NetworkStatusNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.network_status",
        title = "Network status",
        category = StepCategory.Device,
        subtitle = "Check Wi-Fi, cellular and internet connectivity",
        iconKey = "globe",
        params = persistentListOf(
            ParamSpec("outputField", "Save status in", ParamType.Text, default = "network"),
        ),
        keywords = persistentListOf("wifi", "cellular", "internet", "network", "ping", "online"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val field = Values.asString(params["outputField"], "network")
        val status = gateway.getNetworkStatus()
        val out = ctx.input.map { item ->
            item.copy(json = item.json + (field to status))
        }
        return ctx.output(out)
    }
}
