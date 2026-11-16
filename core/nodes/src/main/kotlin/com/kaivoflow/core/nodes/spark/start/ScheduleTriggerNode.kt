package com.kaivoflow.core.nodes.spark.start

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.nodes.spark.PassThroughTrigger
import kotlinx.collections.immutable.persistentListOf

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
