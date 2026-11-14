package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.engine.ValidationIssue
import com.kaivoflow.core.engine.IssueSeverity
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf

/**
 * Sparks (triggers).
 *
 * A Spark executor does almost nothing at run time — it simply forwards the items the Run was
 * triggered with. All the interesting work (registering a schedule, receiving a webhook, listening
 * for notifications) happens in `:core:work`, driven by the same params. Keeping the two in sync
 * through [TriggerSpecs] is what stops "the trigger is configured but never fires" bugs.
 */
object TriggerSpecs {
    const val MANUAL = "trigger.manual"
    const val SCHEDULE = "trigger.schedule"
    const val WEBHOOK = "trigger.webhook"
    const val NOTIFICATION = "trigger.notification"
    const val SMS = "trigger.sms"
    const val SHARE = "trigger.share"
    const val BATTERY_LOW = "trigger.battery"

    val all = listOf(MANUAL, SCHEDULE, WEBHOOK, NOTIFICATION, SMS, SHARE, BATTERY_LOW)

    val timeZones = listOf("Asia/Kolkata", "Asia/Dubai", "Asia/Singapore", "Europe/London", "America/New_York", "UTC")
}

/** Base for the Spark executors: forwards trigger items. */
abstract class PassThroughTrigger : StepExecutor {
    override suspend fun execute(ctx: StepContext): StepResult =
        StepResult(mapOf(0 to ctx.input))
}

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

class ScheduleTriggerNode : PassThroughTrigger() {
    override val spec = NodeSpec(
        id = TriggerSpecs.SCHEDULE,
        title = "On a schedule",
        category = StepCategory.Trigger,
        subtitle = "Run every day, hour or at a set time",
        iconKey = "calendar",
        kind = NodeKind.Trigger,
        canStartFlow = true,
        params = persistentListOf(
            ParamSpec(
                key = "frequency",
                label = "How often",
                type = ParamType.Select,
                default = "daily",
                options = persistentListOf(
                    ParamOption("minutes", "Every N minutes"),
                    ParamOption("hourly", "Every hour"),
                    ParamOption("daily", "Every day"),
                    ParamOption("weekly", "Every week"),
                    ParamOption("monthly", "Every month"),
                ),
            ),
            ParamSpec("interval", "Every", ParamType.Number, default = 15, showWhen = mapOf("frequency" to listOf("minutes"))),
            ParamSpec("time", "At what time", ParamType.Text, default = "08:00", placeholder = "08:00",
                showWhen = mapOf("frequency" to listOf("daily", "weekly", "monthly"))),
            ParamSpec(
                key = "daysOfWeek",
                label = "On these days",
                type = ParamType.Text,
                default = "1,2,3,4,5,6,7",
                showWhen = mapOf("frequency" to listOf("weekly")),
                hint = "1 = Monday … 7 = Sunday",
            ),
            ParamSpec("dayOfMonth", "Day of month", ParamType.Number, default = 1, showWhen = mapOf("frequency" to listOf("monthly"))),
            ParamSpec("timezone", "Time zone", ParamType.Select, default = "Asia/Kolkata",
                options = persistentListOf(*TriggerSpecs.timeZones.map { ParamOption(it, it) }.toTypedArray())),
            ParamSpec("runOnlyOnWifi", "Only when on Wi-Fi", ParamType.Boolean, default = false),
        ),
        keywords = persistentListOf("schedule", "cron", "timer", "every day", "morning", "alarm"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val frequency = Values.asString(params["frequency"], "daily")
        if (frequency in listOf("daily", "weekly", "monthly")) {
            val time = Values.asString(params["time"], "08:00")
            val valid = TIME_OF_DAY.matches(time)
            if (!valid) throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$time\" is not a time. Use 24-hour HH:MM, like 08:00.")
        }
        return super.execute(ctx)
    }

    private companion object {
        val TIME_OF_DAY = Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")
    }
}

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
