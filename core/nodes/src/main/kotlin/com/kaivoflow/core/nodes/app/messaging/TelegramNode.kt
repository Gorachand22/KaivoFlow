package com.kaivoflow.core.nodes.app.messaging

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.bearerToken
import kotlinx.collections.immutable.persistentListOf

/**
 * SaaS presets. Each is a thin, guided wrapper over HTTP with the awkward parts (auth shape,
 * required fields, response unwrapping) already handled — but every one still exposes the URL
 * error message a user can act on, and all of them can be replaced by the raw HTTP Step.
 */

class TelegramNode : StepExecutor {
    override val spec = NodeSpec(
        id = "app.telegram",
        title = "Send to Telegram",
        category = StepCategory.Saas,
        subtitle = "Post a message to a chat or channel",
        iconKey = "telegram",
        params = persistentListOf(
            ParamSpec("credential", "Bot credential", ParamType.Secret, required = true, hint = "Stores the bot token from @BotFather."),
            ParamSpec("chatId", "Chat ID or @channel", ParamType.Text, required = true, placeholder = "@my_channel"),
            ParamSpec("text", "Message", ParamType.Multiline, required = true, placeholder = "Today you spent {{ ${'$'}json.total }} INR"),
            ParamSpec("parseMode", "Formatting", ParamType.Select, default = "HTML",
                options = persistentListOf(ParamOption("HTML", "HTML"), ParamOption("MarkdownV2", "Markdown"), ParamOption("", "Plain text"))),
            ParamSpec("silent", "Send silently", ParamType.Boolean, default = false),
        ),
        keywords = persistentListOf("telegram", "message", "chat", "bot", "notify"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val credential = ctx.credential()
        val token = com.kaivoflow.core.model.CredentialFields.botToken(credential)
        if (token.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "the Telegram credential has no bot token")

        val sent = ctx.input.mapIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val text = Values.asString(row["text"])
            if (text.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "the message is empty")
            val response = ctx.http.request(
                HttpRequestSpec(
                    method = "POST",
                    url = "https://api.telegram.org/bot$token/sendMessage",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = buildMap<String, Any?> {
                        put("chat_id", Values.asString(row["chatId"]))
                        put("text", text.take(4096))
                        Values.asString(row["parseMode"]).takeIf { it.isNotBlank() }?.let { put("parse_mode", it) }
                        if (Values.asBoolean(row["silent"] ?: false)) put("disable_notification", true)
                    },
                    timeoutMs = 30_000,
                )
            )
            if (!response.isSuccess) {
                throw StepExecutionException(
                    ctx.step.id, ctx.step.name,
                    "Telegram rejected the message (HTTP ${response.status}). ${Values.asString(Values.deepGet(Values.asMap(response.body), "description"))}"
                )
            }
            item.copy(json = item.json + ("telegramMessageId" to Values.deepGet(Values.asMap(response.body), "result.message_id")))
        }
        return ctx.output(sent)
    }
}
