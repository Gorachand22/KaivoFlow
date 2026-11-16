package com.kaivoflow.core.nodes.spark.incoming

import com.kaivoflow.core.engine.IssueSeverity
import com.kaivoflow.core.engine.ValidationIssue
import com.kaivoflow.core.engine.alwayson.ResponsePlan
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.StepNode
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
            ParamSpec(
                key = "responseMode",
                label = "Answer the caller",
                type = ParamType.Select,
                default = "immediate",
                advanced = true,
                options = persistentListOf(
                    ParamOption("immediate", "Right away (the Run continues in the background)"),
                    ParamOption("wait", "After the Run finishes (with its last result)"),
                ),
            ),
            ParamSpec(
                key = "responseTimeoutSec",
                label = "Wait at most (seconds)",
                type = ParamType.Number,
                default = 10,
                advanced = true,
                hint = "Between 1 and 55. If the Run is not done by then the caller gets a \"still running\" answer.",
                showWhen = mapOf("responseMode" to listOf("wait")),
            ),
        ),
        keywords = persistentListOf("webhook", "http", "incoming", "api", "trigger"),
    )

    override fun validate(step: StepNode, spec: NodeSpec): List<ValidationIssue> {
        val mode = step.paramString("responseMode", "immediate").ifBlank { "immediate" }
        if (mode != "immediate" && mode != "wait") {
            return listOf(ValidationIssue("bad_response_mode", "Answer the caller must be \"Right away\" or \"After the Run finishes\".", IssueSeverity.Blocking, step.id))
        }
        if (mode == "wait") {
            val sec = step.paramNumber("responseTimeoutSec", ResponsePlan.DEFAULT_TIMEOUT_SEC)
            if (!sec.isFinite() || sec < ResponsePlan.MIN_TIMEOUT_SEC || sec > ResponsePlan.MAX_TIMEOUT_SEC) {
                return listOf(ValidationIssue("bad_response_timeout", "Wait at most must be between 1 and 55 seconds.", IssueSeverity.Blocking, step.id))
            }
        }
        return emptyList()
    }
}
