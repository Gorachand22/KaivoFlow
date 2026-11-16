package com.kaivoflow.core.nodes.data.text

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Build a standards-compliant calendar invite (.ics) that any calendar app can open. */
class IcalNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.ical",
        title = "Calendar invite",
        category = StepCategory.Transform,
        subtitle = "Create an .ics event from your items",
        iconKey = "eventNote",
        params = persistentListOf(
            textParam("title", "Event title", default = "{{ \$json.title }}"),
            textParam("start", "Starts at", default = "{{ \$json.start }}",
                hint = "An ISO date-time like 2026-11-14T09:00:00Z, or epoch milliseconds."),
            ParamSpec("durationMinutes", "Lasts (minutes)", ParamType.Number, default = 30),
            textParam("description", "Description", placeholder = "{{ \$json.notes }}"),
            textParam("location", "Location", placeholder = "{{ \$json.place }}"),
            textParam("outputField", "Save invite in", default = "ics"),
        ),
        keywords = persistentListOf("calendar", "ics", "invite", "event", "meeting", "reminder"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val field = ctx.outputName("ics")
        val duration = (Values.asDouble(params["durationMinutes"]) ?: 30.0).toLong().coerceIn(1, 60 * 24 * 30)
        val out = ctx.input.mapIndexed { index, item ->
            val startText = ctx.resolveText(params["start"], index)
            val start = parseInstant(ctx, startText)
            val end = start.plusSeconds(duration * 60)
            val uid = UUID.randomUUID().toString() + "@kaivoflow"
            val ics = buildString {
                appendLine("BEGIN:VCALENDAR")
                appendLine("VERSION:2.0")
                appendLine("PRODID:-//KaivoFlow//Flow Automation//EN")
                appendLine("CALSCALE:GREGORIAN")
                appendLine("METHOD:PUBLISH")
                appendLine("BEGIN:VEVENT")
                appendLine("UID:$uid")
                appendLine("DTSTAMP:${stamp(Instant.ofEpochMilli(ctx.clock()))}")
                appendLine("DTSTART:${stamp(start)}")
                appendLine("DTEND:${stamp(end)}")
                appendLine("SUMMARY:" + escape(ctx.resolveText(params["title"], index, "KaivoFlow event")))
                ctx.resolveText(params["description"], index).takeIf { it.isNotBlank() }
                    ?.let { appendLine("DESCRIPTION:" + escape(it)) }
                ctx.resolveText(params["location"], index).takeIf { it.isNotBlank() }
                    ?.let { appendLine("LOCATION:" + escape(it)) }
                appendLine("END:VEVENT")
                appendLine("END:VCALENDAR")
            }.replace("\n", "\r\n")
            item.copy(json = item.json + (field to ics))
        }
        return ctx.output(out)
    }

    private fun parseInstant(ctx: StepContext, text: String): Instant {
        val trimmed = text.trim()
        trimmed.toLongOrNull()?.let { return Instant.ofEpochMilli(it) }
        runCatching { return Instant.parse(trimmed) }
        runCatching { return java.time.LocalDateTime.parse(trimmed.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }
        runCatching { return java.time.LocalDate.parse(trimmed).atStartOfDay().toInstant(ZoneOffset.UTC) }
        throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$trimmed\" is not a date-time this Step understands")
    }

    private fun stamp(instant: Instant): String =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(instant)

    private fun escape(text: String): String = text
        .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace("\r\n", "\\n").replace("\n", "\\n").take(2_000)
}
