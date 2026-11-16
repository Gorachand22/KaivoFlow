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

class BluetoothTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.BLUETOOTH,
        title = "When a Bluetooth device connects",
        category = StepCategory.Trigger,
        subtitle = "React to headphones, car kits and watches",
        iconKey = "bluetooth",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec("deviceNameContains", "Device name contains", ParamType.Text, placeholder = "Car, Buds"),
            ParamSpec("deviceAddress", "Exact device address", ParamType.Text, advanced = true, placeholder = "AA:BB:CC:DD:EE:FF"),
            ParamSpec(
                key = "event",
                label = "When",
                type = ParamType.Select,
                default = "connected",
                options = persistentListOf(
                    ParamOption("connected", "Connects"),
                    ParamOption("disconnected", "Disconnects"),
                    ParamOption("any", "Either"),
                ),
            ),
        ),
        keywords = persistentListOf("bluetooth", "headphones", "car", "connect", "device", "watch"),
        requiresSensitivePermission = "android.permission.BLUETOOTH_CONNECT",
    )
}
