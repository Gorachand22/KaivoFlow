package com.kaivoflow.core.nodes.spark.start

import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.nodes.spark.PassThroughTrigger
import kotlinx.collections.immutable.persistentListOf

class ManualTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.MANUAL,
        title = "Manual Spark",
        category = StepCategory.Trigger,
        subtitle = "Starts when you tap Run",
        iconKey = "touch",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        keywords = persistentListOf("manual", "start", "button", "on demand"),
    )
}
