package com.kaivoflow.core.nodes.spark.device_events

import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.nodes.spark.PassThroughTrigger
import kotlinx.collections.immutable.persistentListOf

class WifiTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.WIFI,
        title = "When Wi-Fi changes",
        category = StepCategory.Trigger,
        subtitle = "React when you join or leave a network",
        iconKey = "wifi",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec("ssidContains", "Network name contains", ParamType.Text, placeholder = "Home, Office"),
            ParamSpec(
                key = "event",
                label = "When",
                type = ParamType.Select,
                default = "connected",
                options = persistentListOf(
                    ParamOption("connected", "Connected"),
                    ParamOption("disconnected", "Disconnected"),
                    ParamOption("any", "Either"),
                ),
            ),
        ),
        keywords = persistentListOf("wifi", "network", "ssid", "connect", "home", "internet"),
    )
}
