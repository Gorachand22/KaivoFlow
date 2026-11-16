package com.kaivoflow.core.nodes.spark.device_events

import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.nodes.spark.PassThroughTrigger
import kotlinx.collections.immutable.persistentListOf

class BootTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.BOOT,
        title = "After the phone restarts",
        category = StepCategory.Trigger,
        subtitle = "Run once when the device boots",
        iconKey = "power",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec("delaySeconds", "Wait after boot (seconds)", ParamType.Number, default = 20, advanced = true),
        ),
        keywords = persistentListOf("boot", "restart", "startup", "reboot", "after restart"),
    )
}
