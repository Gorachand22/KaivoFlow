package com.kaivoflow.core.nodes.spark.device_events

import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.nodes.spark.PassThroughTrigger
import kotlinx.collections.immutable.persistentListOf

class SmsTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.SMS,
        title = "When an SMS arrives",
        category = StepCategory.Trigger,
        subtitle = "Watch incoming texts for a keyword",
        iconKey = "sms",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec("senderContains", "From (contains)", ParamType.Text, placeholder = "HDFC, ICICI"),
            ParamSpec("bodyContains", "Text contains", ParamType.Text, placeholder = "debited"),
        ),
        keywords = persistentListOf("sms", "text", "message", "bank", "otp"),
        requiresSensitivePermission = "android.permission.RECEIVE_SMS",
    )
}
