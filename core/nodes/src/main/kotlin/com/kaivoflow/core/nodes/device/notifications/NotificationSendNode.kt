package com.kaivoflow.core.nodes.device.notifications

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.DeviceGateway
import kotlinx.collections.immutable.persistentListOf

class NotificationSendNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.notify",
        title = "Show a notification",
        category = StepCategory.Device,
        subtitle = "Post a notification on your phone",
        iconKey = "bell",
        params = persistentListOf(
            ParamSpec("title", "Title", ParamType.Text, required = true, default = "KaivoFlow"),
            ParamSpec("body", "Message", ParamType.Multiline, required = true, placeholder = "{{ ${'$'}json.answer }}"),
            ParamSpec(
                key = "channel",
                label = "Importance",
                type = ParamType.Select,
                default = "default",
                options = persistentListOf(
                    ParamOption("quiet", "Quiet — no sound"),
                    ParamOption("default", "Normal"),
                    ParamOption("urgent", "Urgent — pops up"),
                ),
            ),
            ParamSpec("deepLink", "Open this when tapped", ParamType.Text, advanced = true, placeholder = "https://…"),
        ),
        keywords = persistentListOf("notification", "alert", "notify", "bell", "reminder"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        ctx.input.forEachIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val title = Values.asString(row["title"]).ifBlank { "KaivoFlow" }
            val body = Values.asString(row["body"])
            gateway.sendNotification(title, body, Values.asString(row["channel"], "default"),
                Values.asString(row["deepLink"]).ifBlank { null })
        }
        return ctx.output(ctx.input)
    }
}
