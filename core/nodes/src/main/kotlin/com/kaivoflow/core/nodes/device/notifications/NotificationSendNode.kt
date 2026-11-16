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
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.selectOptions
import kotlinx.collections.immutable.persistentListOf

class NotificationSendNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.notify",
        title = "Show a notification",
        category = StepCategory.Device,
        subtitle = "Post a notification on your phone",
        iconKey = "bell",
        params = persistentListOf(
            ParamSpec(
                "mode", "Show as", ParamType.Select, default = "notification",
                options = selectOptions("notification" to "Notification", "toast" to "Quick on-screen message"),
            ),
            ParamSpec("text", "Message", ParamType.Text, required = true, default = "{{ ${'$'}json.message }}", showWhen = mapOf("mode" to listOf("toast"))),
            ParamSpec("title", "Title", ParamType.Text, required = true, default = "KaivoFlow", showWhen = mapOf("mode" to listOf("notification"))),
            ParamSpec("body", "Message", ParamType.Multiline, required = true, placeholder = "{{ ${'$'}json.answer }}", showWhen = mapOf("mode" to listOf("notification"))),
            ParamSpec(
                key = "channel",
                label = "Importance",
                type = ParamType.Select,
                default = "default",
                showWhen = mapOf("mode" to listOf("notification")),
                options = persistentListOf(
                    ParamOption("quiet", "Quiet — no sound"),
                    ParamOption("default", "Normal"),
                    ParamOption("urgent", "Urgent — pops up"),
                ),
            ),
            ParamSpec("deepLink", "Open this when tapped", ParamType.Text, advanced = true, placeholder = "https://…", showWhen = mapOf("mode" to listOf("notification"))),
        ),
        keywords = persistentListOf("notification", "alert", "notify", "bell", "reminder", "toast", "popup", "message"),
        replaces = persistentListOf("device.toast"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        if (Values.asString(ctx.params()["mode"], "notification") == "toast") {
            val sources = ctx.input.ifEmpty { listOf(FlowData()) }
            sources.forEachIndexed { index, _ ->
                gateway.showToast(ctx.resolveText(ctx.step.params["text"], index).take(300))
            }
            return ctx.output(sources)
        }
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
