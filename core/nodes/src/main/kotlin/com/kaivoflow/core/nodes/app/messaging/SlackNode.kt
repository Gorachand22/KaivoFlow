package com.kaivoflow.core.nodes.app.messaging

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.bearerToken
import kotlinx.collections.immutable.persistentListOf

class SlackNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.slack",
        title = "Send to Slack",
        category = StepCategory.Saas,
        subtitle = "Post to a channel with an incoming webhook",
        iconKey = "slack",
        params = persistentListOf(
            ParamSpec("webhookUrl", "Webhook URL", ParamType.Secret, required = true, hint = "Slack → Apps → Incoming Webhooks."),
            ParamSpec("text", "Message", ParamType.Multiline, required = true),
            ParamSpec("channel", "Override channel", ParamType.Text, advanced = true),
        ),
        keywords = persistentListOf("slack", "channel", "message", "notify", "webhook"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val webhook = Values.asString(params["webhookUrl"]).ifBlank {
            ctx.credential().bearerToken()
        }
        if (webhook.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add the Slack webhook URL")

        ctx.input.forEachIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val response = ctx.http.request(
                HttpRequestSpec(
                    method = "POST",
                    url = webhook,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = buildMap<String, Any?> {
                        put("text", Values.asString(row["text"]))
                        Values.asString(row["channel"]).takeIf { it.isNotBlank() }
                            ?.let { put("channel", it) }
                    },
                    timeoutMs = 30_000,
                )
            )
            if (!response.isSuccess) {
                throw StepExecutionException(ctx.step.id, ctx.step.name, "Slack rejected the message (HTTP ${response.status}): ${response.bodyText.take(200)}")
            }
        }
        return ctx.output(ctx.input)
    }
}
