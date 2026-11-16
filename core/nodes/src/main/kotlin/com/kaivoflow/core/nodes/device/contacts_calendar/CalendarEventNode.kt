package com.kaivoflow.core.nodes.device.contacts_calendar

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

class CalendarEventNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.calendar_event",
        title = "Add calendar event",
        category = StepCategory.Device,
        subtitle = "Put a reminder into the phone's calendar",
        iconKey = "eventNote",
        requiresSensitivePermission = "android.permission.WRITE_CALENDAR",
        params = persistentListOf(
            textParam("title", "Title", default = "{{ \$json.title }}", required = true),
            textParam("start", "Starts at (epoch ms or ISO)", default = "{{ \$json.start }}", required = true),
            ParamSpec("durationMinutes", "Lasts (minutes)", ParamType.Number, default = 30),
            textParam("description", "Notes", placeholder = "{{ \$json.notes }}"),
            textParam("outputField", "Save result in", default = "event"),
        ),
        keywords = persistentListOf("calendar", "event", "meeting", "reminder", "schedule", "appointment"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val field = ctx.outputName("event")
        val duration = (Values.asDouble(params["durationMinutes"]) ?: 30.0).toLong().coerceIn(1, 60 * 24 * 30)
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        return ctx.output(sources.mapIndexed { index, item ->
            val start = parseMillis(ctx, ctx.resolveText(params["start"], index))
            val id = gateway.addCalendarEvent(
                title = ctx.resolveText(params["title"], index, "KaivoFlow event"),
                startMillis = start,
                endMillis = start + duration * 60_000L,
                description = ctx.resolveText(params["description"], index),
            ) ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the calendar could not save this event")
            item.copy(json = item.json + (field to mapOf(
                "id" to id,
                "start" to start.toDouble(),
                "end" to (start + duration * 60_000L).toDouble(),
            )))
        })
    }

    private fun parseMillis(ctx: StepContext, text: String): Long {
        val trimmed = text.trim()
        trimmed.toLongOrNull()?.let { return it }
        runCatching { return java.time.Instant.parse(trimmed).toEpochMilli() }
        runCatching {
            return java.time.LocalDateTime.parse(trimmed.replace(' ', 'T'))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$trimmed\" is not a date-time this Step understands")
    }
}
