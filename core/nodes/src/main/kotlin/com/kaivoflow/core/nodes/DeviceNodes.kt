package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.ParamResolver
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
import kotlinx.collections.immutable.persistentListOf

/**
 * Android capabilities, expressed as Steps.
 *
 * The executors are pure Kotlin and talk to [DeviceGateway]; the Android implementation lives in
 * `:core:work`. That keeps every Step unit-testable and keeps permission-handling code out of the
 * node definitions.
 */
interface DeviceGateway {
    suspend fun sendNotification(title: String, body: String, channel: String, deepLink: String?)
    suspend fun sendSms(to: String, body: String, simSlot: Int?)
    suspend fun copyToClipboard(text: String)
    suspend fun openUrlOrApp(uri: String, packageName: String?)
    suspend fun speak(text: String, language: String)
    suspend fun writeFile(fileName: String, content: String): String
    suspend fun readFile(fileName: String): String?
    suspend fun vibrate(pattern: List<Long>)
    suspend fun isNotificationAccessGranted(): Boolean
    suspend fun isSmsPermissionGranted(): Boolean
}

/** A gateway that refuses everything, used by tests and previews. */
object NoopDeviceGateway : DeviceGateway {
    override suspend fun sendNotification(title: String, body: String, channel: String, deepLink: String?) = Unit
    override suspend fun sendSms(to: String, body: String, simSlot: Int?) {
        throw StepExecutionException("", "", "sending SMS is not available in this build")
    }
    override suspend fun copyToClipboard(text: String) = Unit
    override suspend fun openUrlOrApp(uri: String, packageName: String?) = Unit
    override suspend fun speak(text: String, language: String) = Unit
    override suspend fun writeFile(fileName: String, content: String): String = fileName
    override suspend fun readFile(fileName: String): String? = null
    override suspend fun vibrate(pattern: List<Long>) = Unit
    override suspend fun isNotificationAccessGranted(): Boolean = false
    override suspend fun isSmsPermissionGranted(): Boolean = false
}

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
        val resolver = ParamResolver(ctx.evaluator)
        ctx.input.forEachIndexed { index, item ->
            val scope = ctx.scope(index)
            val title = Values.asString(resolver.resolveValue(params["title"], scope)).ifBlank { "KaivoFlow" }
            val body = Values.asString(resolver.resolveValue(params["body"], scope))
            gateway.sendNotification(title, body, Values.asString(params["channel"], "default"),
                Values.asString(params["deepLink"]).ifBlank { null })
        }
        return ctx.output(ctx.input)
    }
}

class SmsSendNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.sms",
        title = "Send an SMS",
        category = StepCategory.Device,
        subtitle = "Text someone from your phone",
        iconKey = "sms",
        params = persistentListOf(
            ParamSpec("to", "Send to", ParamType.Text, required = true, placeholder = "+91…"),
            ParamSpec("body", "Message", ParamType.Multiline, required = true),
            ParamSpec("simSlot", "SIM", ParamType.Number, default = 0, advanced = true),
        ),
        keywords = persistentListOf("sms", "text", "message", "send"),
        requiresSensitivePermission = "android.permission.SEND_SMS",
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        if (!gateway.isSmsPermissionGranted()) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "KaivoFlow does not have SMS permission. Open Settings → Privacy centre to grant it.")
        }
        val params = ctx.params()
        val resolver = ParamResolver(ctx.evaluator)
        ctx.input.forEachIndexed { index, item ->
            val scope = ctx.scope(index)
            val to = Values.asString(resolver.resolveValue(params["to"], scope))
            if (to.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "no phone number to send to")
            gateway.sendSms(to, Values.asString(resolver.resolveValue(params["body"], scope)),
                (Values.asDouble(params["simSlot"]) ?: 0.0).toInt())
        }
        return ctx.output(ctx.input)
    }
}

class ClipboardNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.clipboard",
        title = "Copy to clipboard",
        category = StepCategory.Device,
        subtitle = "Put text on the clipboard",
        iconKey = "clipboard",
        params = persistentListOf(ParamSpec("text", "Text", ParamType.Multiline, required = true)),
        keywords = persistentListOf("clipboard", "copy", "paste"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val resolver = ParamResolver(ctx.evaluator)
        val text = Values.asString(resolver.resolveValue(ctx.params()["text"], ctx.scope()))
        gateway.copyToClipboard(text)
        return ctx.output(ctx.input)
    }
}

class OpenLinkNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.open",
        title = "Open a link or app",
        category = StepCategory.Device,
        subtitle = "Launch a URL, deep link or installed app",
        iconKey = "external",
        params = persistentListOf(
            ParamSpec("uri", "URL or deep link", ParamType.Text, required = true, placeholder = "https://…"),
            ParamSpec("packageName", "App package", ParamType.Text, advanced = true, placeholder = "com.whatsapp"),
        ),
        keywords = persistentListOf("open", "launch", "deep link", "intent", "app"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val resolver = ParamResolver(ctx.evaluator)
        val uri = Values.asString(resolver.resolveValue(params["uri"], ctx.scope()))
        if (uri.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add a URL or deep link")
        gateway.openUrlOrApp(uri, Values.asString(params["packageName"]).ifBlank { null })
        return ctx.output(ctx.input)
    }
}

class SpeakNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.speak",
        title = "Speak out loud",
        category = StepCategory.Device,
        subtitle = "Read text with the phone's voice",
        iconKey = "speaker",
        params = persistentListOf(
            ParamSpec("text", "What to say", ParamType.Multiline, required = true, placeholder = "{{ ${'$'}json.answer }}"),
            ParamSpec("language", "Language", ParamType.Text, default = "en-IN", advanced = true),
        ),
        keywords = persistentListOf("speak", "tts", "voice", "say", "read aloud"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val resolver = ParamResolver(ctx.evaluator)
        val text = Values.asString(resolver.resolveValue(params["text"], ctx.scope()))
        if (text.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "there is nothing to say")
        gateway.speak(text, Values.asString(params["language"], "en-IN"))
        return ctx.output(ctx.input)
    }
}

class VibrateNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.vibrate",
        title = "Vibrate",
        category = StepCategory.Device,
        subtitle = "Buzz the phone in a pattern",
        iconKey = "vibrate",
        params = persistentListOf(
            ParamSpec("pattern", "Pattern (ms, comma separated)", ParamType.Text, default = "120,80,120"),
        ),
        keywords = persistentListOf("vibrate", "buzz", "haptic", "alert"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val pattern = Values.asString(ctx.params()["pattern"], "120,80,120")
            .split(',').mapNotNull { it.trim().toLongOrNull() }.ifEmpty { listOf(120L) }
        gateway.vibrate(pattern)
        return ctx.output(ctx.input)
    }
}

class FileNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.file",
        title = "Save to a file",
        category = StepCategory.Device,
        subtitle = "Write or read JSON on this device",
        iconKey = "file",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "write",
                options = persistentListOf(ParamOption("write", "Write"), ParamOption("read", "Read")),
            ),
            ParamSpec("fileName", "File name", ParamType.Text, required = true, default = "state.json"),
            ParamSpec("content", "Content", ParamType.Multiline, showWhen = mapOf("action" to listOf("write"))),
            ParamSpec("parse", "Parse JSON when reading", ParamType.Boolean, default = true, showWhen = mapOf("action" to listOf("read"))),
        ),
        keywords = persistentListOf("file", "save", "storage", "json", "persist"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val fileName = Values.asString(params["fileName"], "state.json")
        return if (Values.asString(params["action"], "write") == "read") {
            val content = gateway.readFile(fileName)
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "there is no saved file called $fileName yet")
            val value = if (Values.asBoolean(params["parse"] ?: true)) JsonLite.parse(content) ?: content else content
            ctx.output(listOf(com.kaivoflow.core.model.FlowData(mapOf("content" to value))))
        } else {
            val resolver = ParamResolver(ctx.evaluator)
            val content = Values.asString(resolver.resolveValue(params["content"], ctx.scope()))
            gateway.writeFile(fileName, content.ifBlank { Values.stringify(ctx.input.map { it.json }) })
            ctx.output(ctx.input)
        }
    }
}
