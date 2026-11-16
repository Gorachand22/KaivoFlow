package com.kaivoflow.core.nodes.spark.incoming

import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.nodes.spark.PassThroughTrigger
import kotlinx.collections.immutable.persistentListOf

class ShareTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.SHARE,
        title = "When you share something",
        category = StepCategory.Trigger,
        subtitle = "Catch links and text from the share sheet",
        iconKey = "share",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec("types", "Accept", ParamType.Select, default = "text",
                options = persistentListOf(ParamOption("text", "Text and links"), ParamOption("image", "Images"), ParamOption("any", "Anything"))),
        ),
        keywords = persistentListOf("share", "share sheet", "send to", "links"),
    )
}
