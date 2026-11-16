package com.kaivoflow.core.nodes.spark.incoming

import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.nodes.secret
import com.kaivoflow.core.nodes.spark.PassThroughTrigger
import kotlinx.collections.immutable.persistentListOf

class WebhookTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.WEBHOOK,
        title = "Incoming webhook",
        category = StepCategory.Trigger,
        subtitle = "Start when a service calls your phone",
        iconKey = "webhook",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec("path", "Path", ParamType.Text, default = "my-flow", required = true, hint = "Becomes https://…/hook/my-flow"),
            ParamSpec("secret", "Shared secret", ParamType.Secret, advanced = true, hint = "Checked against the X-Kaivo-Signature header."),
            ParamSpec("method", "Accept", ParamType.Select, default = "POST",
                options = persistentListOf(ParamOption("POST", "POST"), ParamOption("GET", "GET"), ParamOption("ANY", "Any"))),
            ParamSpec("requireWifi", "Only on Wi-Fi", ParamType.Boolean, default = false, advanced = true),
        ),
        keywords = persistentListOf("webhook", "http", "incoming", "api", "trigger"),
    )
}
