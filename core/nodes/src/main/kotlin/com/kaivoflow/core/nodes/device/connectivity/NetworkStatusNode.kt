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
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.nodes.selectOptions
import kotlinx.collections.immutable.persistentListOf

/** Connection info: connectivity per transport, or the current Wi-Fi details (absorbs the old "Wi-Fi details" Step). */
class NetworkStatusNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.network_status",
        title = "Connection info",
        category = StepCategory.Device,
        subtitle = "Check Wi-Fi, cellular and internet connectivity",
        iconKey = "globe",
        params = persistentListOf(
            ParamSpec(
                "detail", "What to read", ParamType.Select, default = "status",
                options = selectOptions("status" to "Connectivity status", "wifi" to "Wi-Fi details"),
            ),
            ParamSpec(
                "outputField", "Save in", ParamType.Text,
                hint = "Leave empty for \"network\" (status) or \"wifi\" (Wi-Fi details).",
            ),
        ),
        keywords = persistentListOf("wifi", "cellular", "internet", "network", "ping", "online", "ssid", "connection"),
        replaces = persistentListOf("device.wifi_info"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        if (Values.asString(params["detail"], "status") == "wifi") {
            val info = gateway.getWifiInfo()
            val field = Values.asString(params["outputField"]).ifBlank { "wifi" }
            return ctx.output(listOf(FlowData(mapOf(field to info, "available" to info.isNotEmpty()))))
        }
        val field = Values.asString(params["outputField"]).ifBlank { "network" }
        val status = gateway.getNetworkStatus()
        val out = ctx.input.map { item ->
            item.copy(json = item.json + (field to status))
        }
        return ctx.output(out)
    }
}
