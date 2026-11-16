package com.kaivoflow.core.nodes.data.time

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
import com.kaivoflow.core.nodes.outputField
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Date formatting and arithmetic, since mobiles rarely pre-format timestamps. */
class DateNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.date",
        title = "Date & time",
        category = StepCategory.Transform,
        subtitle = "Parse, format, shift, compare or convert dates",
        iconKey = "calendar",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "format",
                options = persistentListOf(
                    ParamOption("parse", "Read a date"),
                    ParamOption("format", "Format a date"),
                    ParamOption("shift", "Add or subtract time"),
                    ParamOption("diff", "Difference between two dates"),
                    ParamOption("timezone", "Convert time zone"),
                ),
            ),
            ParamSpec("source", "Date", ParamType.Text, default = "{{ ${'$'}json.date }}"),
            ParamSpec(
                "pattern", "Format", ParamType.Text, default = "dd MMM yyyy",
                showWhen = mapOf("action" to listOf("format", "timezone")),
            ),
            ParamSpec("amount", "Amount", ParamType.Number, default = 1, showWhen = mapOf("action" to listOf("shift"))),
            ParamSpec(
                key = "unit", label = "Unit", type = ParamType.Select, default = "days",
                options = persistentListOf(
                    ParamOption("minutes", "Minutes"), ParamOption("hours", "Hours"),
                    ParamOption("days", "Days"), ParamOption("weeks", "Weeks"), ParamOption("months", "Months"),
                ),
                showWhen = mapOf("action" to listOf("shift", "diff")),
            ),
            ParamSpec("other", "Other date", ParamType.Text, placeholder = "{{ ${'$'}now }}", showWhen = mapOf("action" to listOf("diff"))),
            ParamSpec(
                "targetZone", "Time zone", ParamType.Text, default = "UTC",
                hint = "An IANA time zone such as Asia/Kolkata or Europe/Berlin.",
                showWhen = mapOf("action" to listOf("timezone")),
            ),
            ParamSpec("outputField", "Put the result in", ParamType.Text, default = "date"),
        ),
        keywords = persistentListOf("date", "time", "parse", "format", "add days", "difference", "timezone", "convert"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val action = Values.asString(params["action"], "format")
        val field = Values.asString(params["outputField"], "date")
        val out = ctx.input.mapIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val rawDate = Values.asString(row["source"])
            val zone = runCatching { ZoneId.of(values(scope, "run.timezone")) }.getOrDefault(ZoneId.systemDefault())
            val base = parseInstant(rawDate, zone)

            val result: Any? = when (action) {
                "parse" -> base.toString()
                "shift" -> {
                    val amount = (Values.asDouble(row["amount"]) ?: 1.0).toLong()
                    val unit = Values.asString(row["unit"], "days")
                    shift(base, amount, unit).toString()
                }
                "diff" -> {
                    val otherRaw = Values.asString(row["other"]).ifBlank { rawDate }
                    val other = parseInstant(otherRaw, zone)
                    diff(base, other, Values.asString(row["unit"], "days"))
                }
                "timezone" -> {
                    val rawZone = Values.asString(row["targetZone"]).ifBlank { "UTC" }
                    val targetZone = runCatching { ZoneId.of(rawZone) }.getOrElse {
                        throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$rawZone\" is not a time zone KaivoFlow understands")
                    }
                    render(base, Values.asString(row["pattern"], "dd MMM yyyy"), targetZone, ctx)
                }
                else -> render(base, Values.asString(row["pattern"], "dd MMM yyyy"), zone, ctx)
            }
            item.copy(json = item.json + (field to result))
        }
        return ctx.output(out)
    }

    private fun values(scope: Map<String, Any?>, path: String): String =
        Values.asString(Values.deepGet(scope, path)).ifBlank { "UTC" }

    private fun render(base: Instant, pattern: String, zone: ZoneId, ctx: StepContext): String =
        runCatching { DateTimeFormatter.ofPattern(pattern).withZone(zone).format(base) }
            .getOrElse { throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$pattern\" is not a valid date format") }

    private fun parseInstant(text: String, zone: ZoneId): Instant {
        val trimmed = text.trim()
        runCatching { return Instant.parse(trimmed) }
        runCatching { return java.time.LocalDate.parse(trimmed).atStartOfDay(zone).toInstant() }
        runCatching {
            return java.time.LocalDateTime.parse(trimmed.replace(' ', 'T')).atZone(zone).toInstant()
        }
        throw StepExecutionException("", "", "\"$trimmed\" is not a date this Step understands")
    }

    private fun shift(base: Instant, amount: Long, unit: String): Instant {
        val zoned = base.atZone(ZoneId.of("UTC"))
        return when (unit) {
            "minutes" -> zoned.plusMinutes(amount)
            "hours" -> zoned.plusHours(amount)
            "weeks" -> zoned.plusWeeks(amount)
            "months" -> zoned.plusMonths(amount)
            else -> zoned.plusDays(amount)
        }.toInstant()
    }

    private fun diff(a: Instant, b: Instant, unit: String): Long {
        val ms = b.toEpochMilli() - a.toEpochMilli()
        return when (unit) {
            "minutes" -> ms / 60_000
            "hours" -> ms / 3_600_000
            "weeks" -> ms / 604_800_000
            "months" -> ms / 2_592_000_000L
            else -> ms / 86_400_000
        }
    }
}
