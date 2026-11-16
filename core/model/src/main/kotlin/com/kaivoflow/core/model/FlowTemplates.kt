package com.kaivoflow.core.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import java.util.UUID

/**
 * Built-in, original starter Flows. They are ordinary immutable [Flow] values, so importing a
 * template produces a fully editable Flow with fresh Step/Stream IDs — no special runtime path.
 */
data class FlowTemplate(
    val id: String,
    val name: String,
    val description: String,
    val iconKey: String,
    val build: () -> Flow,
)

object FlowTemplates {
    val all: List<FlowTemplate> = listOf(
        FlowTemplate(
            id = "morning_briefing",
            name = "Morning briefing",
            description = "Tap to build a short message and post it as a phone notification.",
            iconKey = "sparkles",
            build = {
                val trigger = step("spark.manual", "Tap to run", 0f, 0f)
                val text = step("data.set", "Write briefing", 0f, 180f,
                    "assignments" to listOf(mapOf("name" to "text", "type" to "any", "value" to "Good morning! Today is {{ ${'$'}today }}. You have {{ ${'$'}json.items.size }} thing(s) to check.")))
                val notify = step("device.notify", "Show briefing", 0f, 360f,
                    "title" to "Morning briefing",
                    "body" to "{{ ${'$'}json.text }}",
                    "channel" to "default")
                flow("Morning briefing", trigger, text, notify,
                    stream(trigger, text), stream(text, notify))
            },
        ),
        FlowTemplate(
            id = "webhook_alert",
            name = "Webhook to phone",
            description = "Turn any incoming webhook payload into an on-device notification.",
            iconKey = "webhook",
            build = {
                val trigger = step("spark.webhook", "Incoming webhook", 0f, 0f,
                    "path" to "phone-alert", "method" to "POST")
                val notify = step("device.notify", "Show alert", 0f, 220f,
                    "title" to "Webhook received",
                    "body" to "{{ ${'$'}json.body }}",
                    "channel" to "urgent")
                flow("Webhook to phone", trigger, notify, stream(trigger, notify))
            },
        ),
        FlowTemplate(
            id = "battery_saver",
            name = "Low-battery heads-up",
            description = "When the battery drops below 20%, write a message and notify once.",
            iconKey = "battery",
            build = {
                val trigger = step("spark.battery", "Battery low", 0f, 0f,
                    "threshold" to 20, "alsoWhenCharging" to false)
                val text = step("data.set", "Write reminder", 0f, 180f,
                    "assignments" to listOf(mapOf("name" to "text", "type" to "any", "value" to "Battery is at {{ ${'$'}json.level }}%. Plug in soon.")))
                val notify = step("device.notify", "Notify me", 0f, 360f,
                    "title" to "Battery low",
                    "body" to "{{ ${'$'}json.text }}",
                    "channel" to "default")
                flow("Low-battery heads-up", trigger, text, notify,
                    stream(trigger, text), stream(text, notify))
            },
        ),
        FlowTemplate(
            id = "expense_log",
            name = "Expense capture",
            description = "Turn a bank/merchant notification into a line and append it to a local file.",
            iconKey = "file",
            build = {
                val trigger = step("spark.notification", "Bank notification", 0f, 0f,
                    "textContains" to "debited")
                val text = step("data.set", "Format entry", 0f, 180f,
                    "assignments" to listOf(mapOf("name" to "entry", "type" to "any", "value" to "{{ ${'$'}today }} | {{ ${'$'}json.title }} | {{ ${'$'}json.text }}")))
                val file = step("device.file", "Append log", 0f, 360f,
                    "action" to "write",
                    "fileName" to "expenses.txt",
                    "content" to "{{ ${'$'}json.entry }}")
                flow("Expense capture", trigger, text, file,
                    stream(trigger, text), stream(text, file))
            },
        ),
        FlowTemplate(
            id = "api_to_phone",
            name = "API to phone",
            description = "Call a REST endpoint, pick the list from its response and notify per item.",
            iconKey = "plug",
            build = {
                val trigger = step("spark.schedule", "Every morning", 0f, 0f,
                    "frequency" to "daily", "time" to "08:00")
                val call = step("net.http", "Fetch updates", 0f, 180f,
                    "preset" to "paged",
                    "method" to "GET",
                    "url" to "https://api.example.com/updates",
                    "responsePath" to "data.items",
                    "maxPages" to 1)
                val notify = step("device.notify", "Post each update", 0f, 360f,
                    "title" to "New update",
                    "body" to "{{ ${'$'}json.title }}",
                    "channel" to "default")
                flow("API to phone", trigger, call, notify,
                    stream(trigger, call), stream(call, notify))
            },
        ),
    )

    fun byId(id: String): FlowTemplate? = all.firstOrNull { it.id == id }

    private fun step(
        type: String,
        name: String,
        x: Float,
        y: Float,
        vararg params: Pair<String, Any?>,
    ): StepNode = StepNode(
        id = UUID.randomUUID().toString(),
        type = type,
        name = name,
        params = persistentMapOf(*params),
        x = x,
        y = y,
    )

    private fun stream(from: StepNode, to: StepNode, fromPort: Int = 0): StreamEdge = StreamEdge(
        id = UUID.randomUUID().toString(),
        fromStepId = from.id,
        toStepId = to.id,
        fromPort = fromPort,
    )

    private fun flow(name: String, vararg parts: Any): Flow {
        val allParts = parts.toList()
        val steps = allParts.filterIsInstance<StepNode>()
        val streams = allParts.filterIsInstance<StreamEdge>()
        val now = System.currentTimeMillis()
        return Flow(
            id = UUID.randomUUID().toString(),
            name = name,
            steps = persistentListOf(*steps.toTypedArray()),
            streams = persistentListOf(*streams.toTypedArray()),
            tags = persistentListOf("template"),
            createdAt = now,
            updatedAt = now,
        )
    }
}
