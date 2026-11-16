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

class HeadsetTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.HEADSET,
        title = "When headphones are plugged",
        category = StepCategory.Trigger,
        subtitle = "React to a wired headset being connected or removed",
        iconKey = "headset",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec(
                key = "event",
                label = "When",
                type = ParamType.Select,
                default = "plugged",
                options = persistentListOf(
                    ParamOption("plugged", "Plugged in"),
                    ParamOption("unplugged", "Unplugged"),
                    ParamOption("any", "Either"),
                ),
            ),
        ),
        keywords = persistentListOf("headset", "headphones", "jack", "audio", "plug"),
    )
}
