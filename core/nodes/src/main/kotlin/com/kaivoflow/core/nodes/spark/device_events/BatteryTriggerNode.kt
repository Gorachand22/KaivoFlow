package com.kaivoflow.core.nodes.spark.device_events

import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.nodes.spark.PassThroughTrigger
import kotlinx.collections.immutable.persistentListOf

class BatteryTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.BATTERY_LOW,
        title = "When battery is low",
        category = StepCategory.Trigger,
        subtitle = "React to battery level changes",
        iconKey = "battery",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec("threshold", "Below (%)", ParamType.Number, default = 20),
            ParamSpec("alsoWhenCharging", "Also when charging starts", ParamType.Boolean, default = false),
        ),
        keywords = persistentListOf("battery", "charging", "power", "low"),
    )
}
