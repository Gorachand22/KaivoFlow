package com.kaivoflow.core.nodes.spark.device_events

import com.kaivoflow.core.engine.IssueSeverity
import com.kaivoflow.core.engine.ValidationIssue
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.nodes.spark.PassThroughTrigger
import kotlinx.collections.immutable.persistentListOf

class NotificationTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.NOTIFICATION,
        title = "When a notification arrives",
        category = StepCategory.Trigger,
        subtitle = "Read bank SMS alerts, WhatsApp and app notifications",
        iconKey = "eye",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec("packageNames", "From apps", ParamType.Text, placeholder = "com.whatsapp, com.android.messaging",
                hint = "Leave empty to watch every app."),
            ParamSpec("titleContains", "Title contains", ParamType.Text),
            ParamSpec("textContains", "Text contains", ParamType.Text, placeholder = "debited, credited, OTP"),
            ParamSpec("textMatches", "Text matches pattern", ParamType.Text, advanced = true, placeholder = "(?i)(credited|debited)"),
            ParamSpec(
                key = "ignoreOngoing",
                label = "Ignore ongoing notifications",
                type = ParamType.Boolean,
                default = true,
                advanced = true,
            ),
            ParamSpec("ignoreOwnApp", "Ignore KaivoFlow's own notifications", ParamType.Boolean, default = true, advanced = true),
        ),
        keywords = persistentListOf("notification", "sms", "bank", "alert", "whatsapp", "otp", "listener"),
        requiresSensitivePermission = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    )
    override fun validate(step: StepNode, spec: NodeSpec): List<ValidationIssue> =
        if (step.paramString("textMatches").isNotBlank()) listOf(
            ValidationIssue("regex_not_available", "Pattern matching is not available yet. Remove the pattern and use a text filter.", IssueSeverity.Blocking, step.id)
        ) else emptyList()

}
