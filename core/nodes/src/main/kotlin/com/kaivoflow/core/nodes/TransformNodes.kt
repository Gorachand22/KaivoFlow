package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.ParamResolver
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Set / edit fields on every item — the most-used Step in any automation tool. */
class SetFieldsNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.set",
        title = "Set fields",
        category = StepCategory.Transform,
        subtitle = "Add or overwrite fields on each item",
        iconKey = "edit",
        params = persistentListOf(
            ParamSpec(
                key = "fields",
                label = "Fields",
                type = ParamType.KeyValue,
                required = true,
                hint = "Field name → value. Values can use expressions like {{ ${'$'}json.amount * 2 }}.",
            ),
            ParamSpec(
                key = "keepOtherFields",
                label = "Keep other fields",
                type = ParamType.Boolean,
                default = true,
            ),
        ),
        keywords = persistentListOf("set", "edit", "fields", "assign", "map"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val raw = ctx.params()
        val fields = when (val value = raw["fields"]) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
            is List<*> -> value.mapNotNull { entry ->
                val map = Values.asMap(entry)
                val key = Values.asString(map["key"])
                if (key.isBlank()) null else key to map["value"]
            }.toMap()
            else -> throw StepExecutionException(ctx.step.id, ctx.step.name, "add at least one field")
        }
        if (fields.isEmpty()) return ctx.output(ctx.input)

        val keepOthers = Values.asBoolean(raw["keepOtherFields"] ?: true)
        val resolver = ParamResolver(ctx.evaluator)
        val out = ctx.input.mapIndexed { index, item ->
            val scope = ctx.scope(index)
            val updated = if (keepOthers) LinkedHashMap(item.json) else LinkedHashMap()
            fields.forEach { (key, template) ->
                updated[key] = resolver.resolveValue(template, scope)
            }
            item.copy(json = updated)
        }
        return ctx.output(out)
    }
}

/** Rename, remove or copy fields without writing an expression. */
class FieldsNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.fields",
        title = "Rename or remove fields",
        category = StepCategory.Transform,
        subtitle = "Tidy up the shape of your items",
        iconKey = "rename",
        params = persistentListOf(
            ParamSpec("rename", "Rename (old → new)", ParamType.KeyValue, hint = "from = to"),
            ParamSpec("remove", "Remove fields", ParamType.Text, placeholder = "internalId, debug"),
            ParamSpec("keepOnly", "Keep only these fields", ParamType.Text, placeholder = "id, amount, merchant"),
        ),
        keywords = persistentListOf("rename", "remove", "drop", "keep", "pick"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val rename = Values.asMap(params["rename"]).mapValues { Values.asString(it.value) }
        val remove = Values.asString(params["remove"]).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val keepOnly = Values.asString(params["keepOnly"]).split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val out = ctx.input.map { item ->
            var json = LinkedHashMap(item.json)
            if (keepOnly.isNotEmpty()) json = LinkedHashMap(json.filterKeys { it in keepOnly })
            remove.forEach { json.remove(it) }
            rename.forEach { (from, to) ->
                if (json.containsKey(from) && to.isNotBlank()) {
                    json[to] = json.remove(from)
                }
            }
            item.copy(json = json)
        }
        return ctx.output(out)
    }
}

/** Parse / build JSON, including "turn this text into fields". */
class JsonNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.json",
        title = "JSON",
        category = StepCategory.Transform,
        subtitle = "Parse text into fields, or build a JSON body",
        iconKey = "braces",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "parse",
                options = persistentListOf(
                    ParamOption("parse", "Parse text into fields"),
                    ParamOption("stringify", "Turn fields into text"),
                    ParamOption("pick", "Keep only a nested part"),
                ),
            ),
            ParamSpec("source", "From field", ParamType.Text, default = "text", showWhen = mapOf("action" to listOf("parse"))),
            ParamSpec("path", "Nested path", ParamType.Text, placeholder = "data.items", showWhen = mapOf("action" to listOf("pick"))),
            ParamSpec("targetField", "Put the result in", ParamType.Text, default = "json", showWhen = mapOf("action" to listOf("parse"))),
            ParamSpec("unwrapArray", "Create one item per array entry", ParamType.Boolean, default = true, showWhen = mapOf("action" to listOf("parse", "pick"))),
        ),
        keywords = persistentListOf("json", "parse", "stringify", "object"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        return when (Values.asString(params["action"], "parse")) {
            "stringify" -> ctx.output(ctx.input.map { FlowData(mapOf("json" to Values.stringify(it.json))) })
            "pick" -> {
                val path = Values.asString(params["path"])
                val unwrap = Values.asBoolean(params["unwrapArray"] ?: true)
                val picked = ctx.input.flatMap { item ->
                    val value = Values.deepGet(item.json, path)
                    if (unwrap && value is List<*>) value.map { FlowData(Values.asMap(it)) }
                    else listOf(FlowData(mapOf("value" to value)))
                }
                ctx.output(picked)
            }
            else -> {
                val source = Values.asString(params["source"], "text")
                val target = Values.asString(params["targetField"], "json")
                val unwrap = Values.asBoolean(params["unwrapArray"] ?: true)
                val items = ctx.input.flatMap { item ->
                    val text = Values.asString(Values.deepGet(item.json, source))
                    val parsed = JsonLite.parse(text)
                        ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$source\" is not valid JSON")
                    if (unwrap && parsed is List<*>) {
                        parsed.map { FlowData(Values.asMap(it)) }
                    } else {
                        listOf(item.copy(json = item.json + (target to parsed)))
                    }
                }
                ctx.output(items)
            }
        }
    }
}

/** Plain text templating — emails, messages, documents. */
class TemplateNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.template",
        title = "Write text",
        category = StepCategory.Transform,
        subtitle = "Build a message from your data",
        iconKey = "text",
        params = persistentListOf(
            ParamSpec(
                key = "template",
                label = "Text",
                type = ParamType.Multiline,
                required = true,
                placeholder = "Hi! {{ ${'$'}json.merchant }} charged {{ ${'$'}json.amount }}.",
            ),
            ParamSpec("outputField", "Put the text in", ParamType.Text, default = "text"),
        ),
        keywords = persistentListOf("template", "text", "message", "format", "string"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val template = Values.asString(params["template"])
        val field = Values.asString(params["outputField"], "text")
        val resolver = ParamResolver(ctx.evaluator)
        val out = ctx.input.mapIndexed { index, item ->
            val value = resolver.resolveValue(template, ctx.scope(index))
            item.copy(json = item.json + (field to value))
        }
        return ctx.output(out)
    }
}

/** Date formatting and arithmetic, since mobiles rarely pre-format timestamps. */
class DateNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.date",
        title = "Date & time",
        category = StepCategory.Transform,
        subtitle = "Format, shift or compare dates",
        iconKey = "calendar",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "format",
                options = persistentListOf(
                    ParamOption("format", "Format a date"),
                    ParamOption("shift", "Add or subtract time"),
                    ParamOption("diff", "Difference between two dates"),
                ),
            ),
            ParamSpec("source", "Date", ParamType.Text, default = "{{ ${'$'}json.date }}"),
            ParamSpec("pattern", "Format", ParamType.Text, default = "dd MMM yyyy", showWhen = mapOf("action" to listOf("format"))),
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
            ParamSpec("outputField", "Put the result in", ParamType.Text, default = "date"),
        ),
        keywords = persistentListOf("date", "time", "format", "add days", "difference"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val action = Values.asString(params["action"], "format")
        val field = Values.asString(params["outputField"], "date")
        val out = ctx.input.mapIndexed { index, item ->
            val scope = ctx.scope(index)
            val resolver = ParamResolver(ctx.evaluator)
            val rawDate = Values.asString(resolver.resolveValue(params["source"], scope))
            val zone = runCatching { ZoneId.of(values(scope, "run.timezone")) }.getOrDefault(ZoneId.systemDefault())
            val base = parseInstant(rawDate, zone)

            val result: Any? = when (action) {
                "shift" -> {
                    val amount = (Values.asDouble(params["amount"]) ?: 1.0).toLong()
                    val unit = Values.asString(params["unit"], "days")
                    shift(base, amount, unit).toString()
                }
                "diff" -> {
                    val otherRaw = Values.asString(resolver.resolveValue(params["other"], scope)).ifBlank { rawDate }
                    val other = parseInstant(otherRaw, zone)
                    diff(base, other, Values.asString(params["unit"], "days"))
                }
                else -> {
                    val pattern = Values.asString(params["pattern"], "dd MMM yyyy")
                    runCatching { DateTimeFormatter.ofPattern(pattern).withZone(zone).format(base) }
                        .getOrElse { throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$pattern\" is not a valid date format") }
                }
            }
            item.copy(json = item.json + (field to result))
        }
        return ctx.output(out)
    }

    private fun values(scope: Map<String, Any?>, path: String): String =
        Values.asString(Values.deepGet(scope, path)).ifBlank { "UTC" }

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

/** Group, count and total items — "how much did I spend at each merchant today". */
class AggregateNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.aggregate",
        title = "Group & total",
        category = StepCategory.Transform,
        subtitle = "Group items and total a number field",
        iconKey = "sum",
        params = persistentListOf(
            ParamSpec("groupBy", "Group by field", ParamType.Text, placeholder = "merchant"),
            ParamSpec("sumField", "Total this field", ParamType.Text, placeholder = "amount"),
            ParamSpec("count", "Count items", ParamType.Boolean, default = true),
        ),
        keywords = persistentListOf("group", "sum", "total", "aggregate", "count", "report"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val groupBy = Values.asString(params["groupBy"])
        val sumField = Values.asString(params["sumField"])
        val count = Values.asBoolean(params["count"] ?: true)

        if (groupBy.isBlank() && sumField.isBlank()) {
            return ctx.output(listOf(FlowData(mapOf("count" to ctx.input.size))))
        }

        val groups = LinkedHashMap<String, MutableList<FlowData>>()
        ctx.input.forEach { item ->
            val key = if (groupBy.isBlank()) "all" else Values.asString(Values.deepGet(item.json, groupBy))
            groups.getOrPut(key) { mutableListOf() } += item
        }

        val out = groups.map { (key, items) ->
            val json = LinkedHashMap<String, Any?>()
            if (groupBy.isNotBlank()) json[groupBy] = key
            if (count) json["count"] = items.size
            if (sumField.isNotBlank()) {
                json["total"] = items.sumOf { Values.asDouble(Values.deepGet(it.json, sumField)) ?: 0.0 }
                json["average"] = if (items.isEmpty()) 0.0 else
                    (json["total"] as Double) / items.size
            }
            FlowData(json)
        }
        ctx.log("Grouped ${ctx.input.size} items into ${out.size} rows.")
        return ctx.output(out)
    }
}

/** A tiny JSON parser so parse Steps do not depend on Android or kotlinx. */
internal object JsonLite {
    /** Strict JSON reader with bounds. Null represents either JSON null or a parse failure. */
    fun parse(text: String): Any? {
        if (text.length > 1_000_000) return null
        return runCatching {
            val parser = Parser(text)
            val value = parser.parseValue(0)
            parser.skipWhitespace()
            if (!parser.atEnd()) throw IllegalArgumentException("Trailing JSON content")
            value
        }.getOrNull()
    }

    private class Parser(private val text: String) {
        private var i = 0
        fun atEnd() = i >= text.length
        fun skipWhitespace() { while (!atEnd() && text[i] in " \t\r\n") i++ }
        fun parseValue(depth: Int): Any? {
            require(depth <= 64) { "JSON is nested too deeply" }
            skipWhitespace()
            require(!atEnd()) { "Expected a JSON value" }
            return when (text[i]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }
        private fun expect(word: String) {
            require(text.startsWith(word, i)) { "Invalid literal" }; i += word.length
        }
        private fun parseObject(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[i] == '}') { i++; return out }
            while (true) {
                skipWhitespace()
                require(!atEnd() && text[i] == '"') { "Expected object key" }
                val key = parseString()
                require(!out.containsKey(key)) { "Duplicate object key" }
                skipWhitespace()
                require(!atEnd() && text[i] == ':') { "Expected colon" }; i++
                out[key] = parseValue(depth)
                skipWhitespace()
                require(!atEnd()) { "Unclosed object" }
                when (text[i++]) {
                    '}' -> return out
                    ',' -> Unit
                    else -> throw IllegalArgumentException("Expected comma")
                }
            }
        }
        private fun parseArray(depth: Int): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            skipWhitespace()
            if (!atEnd() && text[i] == ']') { i++; return out }
            while (true) {
                out.add(parseValue(depth))
                skipWhitespace()
                require(!atEnd()) { "Unclosed array" }
                when (text[i++]) {
                    ']' -> return out
                    ',' -> Unit
                    else -> throw IllegalArgumentException("Expected comma")
                }
            }
        }
        private fun parseString(): String {
            require(!atEnd() && text[i] == '"') { "Expected string" }; i++
            val out = StringBuilder()
            while (!atEnd()) {
                val c = text[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        require(!atEnd()) { "Incomplete escape" }
                        when (val escaped = text[i++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                require(i + 4 <= text.length) { "Incomplete unicode escape" }
                                val code = text.substring(i, i + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid unicode escape")
                                out.append(code.toChar()); i += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape")
                        }
                    }
                    c.code < 0x20 -> throw IllegalArgumentException("Control character in string")
                    else -> out.append(c)
                }
            }
            throw IllegalArgumentException("Unclosed string")
        }
        private fun parseNumber(): Double {
            val match = NUMBER.find(text, i)?.takeIf { it.range.first == i }
                ?: throw IllegalArgumentException("Invalid number")
            i = match.range.last + 1
            val value = match.value.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number")
            require(value.isFinite()) { "Number out of range" }
            return value
        }
    }
    private val NUMBER = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")
}
