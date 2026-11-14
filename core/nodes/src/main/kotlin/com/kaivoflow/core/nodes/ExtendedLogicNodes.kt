package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.BinaryDataRef
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/** Stable ordering by a field, with numeric, text and date-time awareness. */
class SortNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.sort",
        title = "Sort items",
        category = StepCategory.Logic,
        subtitle = "Put your items in the order you want",
        iconKey = "sortByAlpha",
        params = persistentListOf(
            textParam("field", "Sort by field", placeholder = "date", required = true),
            ParamSpec(
                key = "direction",
                label = "Order",
                type = ParamType.Select,
                default = "asc",
                options = selectOptions("asc" to "Smallest / A first", "desc" to "Largest / Z first"),
            ),
            ParamSpec(
                key = "as",
                label = "Treat values as",
                type = ParamType.Select,
                default = "auto",
                options = selectOptions("auto" to "Automatic", "number" to "Numbers", "text" to "Text", "date" to "Date-time"),
            ),
        ),
        keywords = persistentListOf("sort", "order", "ascending", "descending", "rank", "arrange"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val field = Values.asString(params["field"])
        if (field.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "choose a field to sort by")
        val direction = Values.asString(params["direction"], "asc")
        val asType = Values.asString(params["as"], "auto")
        val indexed = ctx.input.mapIndexed { index, item -> index to item }
        val comparator = Comparator<Pair<Int, FlowData>> { left, right ->
            val a = Values.deepGet(left.second.json, field)
            val b = Values.deepGet(right.second.json, field)
            val result = compareValues(a, b, asType)
            // Stable sort: equal values keep their incoming order.
            if (result == 0) left.first.compareTo(right.first) else if (direction == "desc") -result else result
        }
        return ctx.output(indexed.sortedWith(comparator).map { it.second })
    }

    private fun compareValues(a: Any?, b: Any?, asType: String): Int {
        if (a == null && b == null) return 0
        if (a == null) return 1
        if (b == null) return -1
        if (asType == "number" || (asType == "auto" && Values.asDouble(a) != null && Values.asDouble(b) != null)) {
            val x = Values.asDouble(a) ?: 0.0
            val y = Values.asDouble(b) ?: 0.0
            return x.compareTo(y)
        }
        if (asType == "date") {
            val x = dateValue(a) ?: 0L
            val y = dateValue(b) ?: 0L
            return x.compareTo(y)
        }
        return Values.asString(a).compareTo(Values.asString(b), ignoreCase = true)
    }

    private fun dateValue(value: Any?): Long? {
        (value as? Number)?.let { return it.toLong() }
        val text = Values.asString(value)
        text.toLongOrNull()?.let { return it }
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
    }
}

/** Keep only the first, last or a slice of the incoming list. */
class LimitNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.limit",
        title = "Limit items",
        category = StepCategory.Logic,
        subtitle = "Take just the items you need",
        iconKey = "filterList",
        params = persistentListOf(
            ParamSpec(
                key = "mode",
                label = "Keep",
                type = ParamType.Select,
                default = "first",
                options = selectOptions(
                    "first" to "First N", "last" to "Last N", "skip" to "Skip the first N",
                    "slice" to "A slice", "sample" to "A random sample",
                ),
            ),
            ParamSpec("count", "How many", ParamType.Number, default = 10),
            ParamSpec("offset", "Start after", ParamType.Number, default = 0,
                showWhen = mapOf("mode" to listOf("slice"))),
        ),
        keywords = persistentListOf("limit", "take", "first", "last", "skip", "slice", "sample", "truncate"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val mode = Values.asString(params["mode"], "first")
        val count = (Values.asDouble(params["count"]) ?: 10.0).toInt().coerceAtLeast(0)
        val offset = (Values.asDouble(params["offset"]) ?: 0.0).toInt().coerceAtLeast(0)
        val out = when (mode) {
            "last" -> ctx.input.takeLast(count)
            "skip" -> ctx.input.drop(count)
            "slice" -> ctx.input.drop(offset).take(count)
            "sample" -> {
                val random = SecureRandom()
                ctx.input.shuffled(random).take(count)
            }
            else -> ctx.input.take(count)
        }
        ctx.log("Kept ${out.size} of ${ctx.input.size} items.")
        return ctx.output(out)
    }
}

/** Compare the incoming items with a saved baseline and describe what changed. */
class CompareNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.compare",
        title = "Compare lists",
        category = StepCategory.Logic,
        subtitle = "Find added, removed, changed and matching rows",
        iconKey = "difference",
        params = persistentListOf(
            textParam("key", "Match rows by field", placeholder = "id", required = true),
            ParamSpec("baseline", "Earlier list (JSON array)", ParamType.Json,
                default = emptyList<Any?>(), hint = "Paste the earlier items, or connect a table read."),
            textParam("skipFields", "Ignore fields", placeholder = "updatedAt, syncedAt"),
            ParamSpec("fuzzy", "Ignore case and punctuation in text", ParamType.Boolean, default = true),
            textParam("outputField", "Save result in", default = "changes"),
        ),
        keywords = persistentListOf("compare", "diff", "changed", "added", "removed", "dataset", "match", "fuzzy"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val key = Values.asString(params["key"])
        if (key.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "choose the field that identifies a row")
        val baselineRaw = params["baseline"]
        val baseline = Values.asList(baselineRaw).map { Values.asMap(it) }
        val current = ctx.input.map { it.json }
        val skip = Values.asString(params["skipFields"])
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val fuzzy = Values.asBoolean(params["fuzzy"] ?: true)
        val before = index(baseline, key)
        val after = index(current, key)

        fun normal(row: Map<String, Any?>): Map<String, Any?> =
            row.filterKeys { it !in skip }.mapValues { (_, value) ->
                if (fuzzy && value is String) value.lowercase().replace(Regex("[^a-z0-9]"), "") else value
            }

        val rows = mutableListOf<Map<String, Any?>>()
        (before.keys + after.keys).distinct().forEach { id ->
            val old = before[id]
            val new = after[id]
            val status = when {
                old == null -> "added"
                new == null -> "removed"
                normal(old) == normal(new) -> "same"
                else -> "changed"
            }
            val changedFields = if (status == "changed") {
                (old!!.keys + new!!.keys).distinct().filter { field ->
                    field !in skip && normal(mapOf(field to old[field])) != normal(mapOf(field to new[field]))
                }
            } else emptyList()
            rows += mapOf(
                "key" to id,
                "status" to status,
                "changedFields" to changedFields,
                "before" to old,
                "after" to new,
            )
        }
        val summary = mapOf(
            "added" to rows.count { it["status"] == "added" }.toDouble(),
            "removed" to rows.count { it["status"] == "removed" }.toDouble(),
            "changed" to rows.count { it["status"] == "changed" }.toDouble(),
            "same" to rows.count { it["status"] == "same" }.toDouble(),
        )
        val field = ctx.outputName("changes")
        return ctx.output(listOf(FlowData(mapOf(field to rows, "summary" to summary))))
    }

    private fun index(rows: List<Map<String, Any?>>, key: String): Map<String, Map<String, Any?>> =
        rows.mapNotNull { row ->
            val id = Values.asString(Values.deepGet(row, key))
            if (id.isBlank()) null else id to row
        }.toMap()
}

/** A tiny, durable table stored inside the app's private files — no server and no account. */
class TableNode(private val gateway: DeviceGateway = NoopDeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.table",
        title = "Local table",
        category = StepCategory.Logic,
        subtitle = "Remember rows between Runs on this device",
        iconKey = "storage",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "put",
                options = selectOptions(
                    "put" to "Save a row", "get" to "Read a row", "delete" to "Delete a row",
                    "list" to "List all rows", "increment" to "Add to a number",
                ),
            ),
            textParam("table", "Table name", default = "memory", required = true),
            textParam("key", "Row key", default = "{{ \$json.id }}",
                showWhen = mapOf("action" to listOf("put", "get", "delete", "increment"))),
            ParamSpec("value", "Row value (JSON)", ParamType.Json,
                showWhen = mapOf("action" to listOf("put"))),
            textParam("numberField", "Number field", default = "count",
                showWhen = mapOf("action" to listOf("increment"))),
            ParamSpec("step", "Add", ParamType.Number, default = 1,
                showWhen = mapOf("action" to listOf("increment"))),
            textParam("outputField", "Save result in", default = "row"),
        ),
        keywords = persistentListOf("table", "database", "storage", "remember", "state", "key", "value", "persistent"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val table = Values.asString(params["table"], "memory").ifBlank { "memory" }
        val action = Values.asString(params["action"], "put")
        val field = ctx.outputName("row")
        val rows = read(table).toMutableMap()
        val results = mutableListOf<FlowData>()

        if (action == "list") {
            rows.forEach { (key, value) -> results += FlowData(mapOf("key" to key, field to value)) }
            if (results.isEmpty()) results += FlowData(mapOf("key" to "", field to null, "empty" to true))
            return ctx.output(results)
        }

        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        sources.forEachIndexed { index, item ->
            val key = ctx.resolveText(params["key"], index)
            if (key.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "each row needs a key")
            when (action) {
                "put" -> {
                    val value = ctx.resolve(params["value"], index) ?: item.json
                    rows[key] = value
                    results += item.copy(json = item.json + (field to mapOf("key" to key, "value" to value)))
                }
                "get" -> {
                    val value = rows[key]
                    results += item.copy(json = item.json + mapOf(field to value, "found" to (value != null)))
                }
                "delete" -> {
                    val removed = rows.remove(key)
                    results += item.copy(json = item.json + mapOf(field to removed, "deleted" to (removed != null)))
                }
                "increment" -> {
                    val numberField = Values.asString(params["numberField"], "count").ifBlank { "count" }
                    val amount = Values.asDouble(params["step"]) ?: 1.0
                    val existing = Values.asMap(rows[key])
                    val next = (Values.asDouble(Values.deepGet(existing, numberField)) ?: 0.0) + amount
                    val updated = existing + (numberField to next)
                    rows[key] = updated
                    results += item.copy(json = item.json + (field to updated))
                }
                else -> results += item
            }
        }
        if (rows.size > MAX_ROWS) throw StepExecutionException(ctx.step.id, ctx.step.name, "a local table holds at most $MAX_ROWS rows")
        write(table, rows)
        return ctx.output(results)
    }

    private suspend fun read(table: String): Map<String, Any?> {
        val text = gateway.readFile(fileName(table)) ?: return emptyMap()
        return MiniJson.parse(text)?.let { Values.asMap(it) } ?: emptyMap()
    }

    private suspend fun write(table: String, rows: Map<String, Any?>) {
        gateway.writeFile(fileName(table), Values.stringify(rows))
    }

    private fun fileName(table: String): String =
        "kaivo_table_" + BinarySupport.safeFileName(table).removeSuffix(".json") + ".json"

    private companion object { const val MAX_ROWS = 5_000 }
}

/** Metadata about the current Run — handy for logs, filenames and dedupe keys. */
class RunInfoNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.run_info",
        title = "Run details",
        category = StepCategory.Logic,
        subtitle = "Add the Run id, time, attempt and item count",
        iconKey = "timeline",
        params = persistentListOf(
            textParam("outputField", "Save details in", default = "run"),
        ),
        producesItems = true,
        keywords = persistentListOf("run", "execution", "metadata", "time", "attempt", "id", "debug"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val field = ctx.outputName("run")
        val info = mapOf(
            "runId" to ctx.runId,
            "flowId" to ctx.flow.id,
            "flowName" to ctx.flow.name,
            "stepId" to ctx.step.id,
            "stepName" to ctx.step.name,
            "mode" to ctx.mode.name,
            "attempt" to ctx.attempt.toDouble(),
            "now" to ctx.clock().toDouble(),
            "timezone" to ctx.timezone,
            "inputItems" to ctx.input.size.toDouble(),
        )
        return ctx.output(listOf(FlowData(mapOf(field to info))))
    }
}

/** A deliberately transparent pass-through Step for notes, branching and debugging. */
class NoopNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.noop",
        title = "Pass through",
        category = StepCategory.Logic,
        subtitle = "Leave items untouched, optionally add a note",
        iconKey = "syncAlt",
        params = persistentListOf(
            textParam("note", "Note", placeholder = "Saved for later"),
            textParam("outputField", "Save the note in", default = "note"),
        ),
        keywords = persistentListOf("noop", "pass", "do nothing", "annotation", "debug", "marker"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val note = Values.asString(params["note"])
        if (note.isBlank()) return ctx.output(ctx.input)
        val field = ctx.outputName("note")
        val out = ctx.input.mapIndexed { index, item ->
            item.copy(json = item.json + (field to ctx.resolveText(params["note"], index)))
        }
        ctx.log("Passed ${out.size} items through.")
        return ctx.output(out)
    }
}

/** Build a crisp SVG chart from items — no chart library, no network, no tracking. */
class ChartNode(private val gateway: DeviceGateway = NoopDeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.chart",
        title = "Quick chart",
        category = StepCategory.Transform,
        subtitle = "Draw a bar or line chart from your items",
        iconKey = "queryStats",
        params = persistentListOf(
            ParamSpec(
                key = "style",
                label = "Chart",
                type = ParamType.Select,
                default = "bar",
                options = selectOptions("bar" to "Bars", "line" to "Line", "area" to "Area"),
            ),
            textParam("labelField", "Label field", default = "name", placeholder = "month"),
            textParam("valueField", "Value field", default = "value", placeholder = "amount"),
            ParamSpec("width", "Width (px)", ParamType.Number, default = 640),
            ParamSpec("height", "Height (px)", ParamType.Number, default = 320),
            textParam("outputField", "Save SVG in", default = "svg"),
        ),
        keywords = persistentListOf("chart", "graph", "svg", "bar", "line", "visualise", "plot", "report"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val labelField = Values.asString(params["labelField"], "name")
        val valueField = Values.asString(params["valueField"], "value")
        val width = (Values.asDouble(params["width"]) ?: 640.0).toInt().coerceIn(240, 1600)
        val height = (Values.asDouble(params["height"]) ?: 320.0).toInt().coerceIn(160, 1000)
        val style = Values.asString(params["style"], "bar")
        val points = ctx.input.mapIndexed { index, item ->
            val label = Values.asString(Values.deepGet(item.json, labelField), "Item ${index + 1}")
            val value = Values.asDouble(Values.deepGet(item.json, valueField)) ?: 0.0
            ChartPoint(label, value)
        }.take(200)
        if (points.isEmpty()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add at least one item to chart")
        val svg = ChartSvg.render(points, width, height, style)
        val base64 = Base64.getEncoder().encodeToString(svg.toByteArray(Charsets.UTF_8))
        val map = gateway.storeBinary("chart.svg", "image/svg+xml", base64)
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the chart could not be saved")
        val ref = BinarySupport.ref(map)
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the chart reference is incomplete")
        val field = ctx.outputName("svg")
        return ctx.output(
            listOf(
                FlowData(
                    mapOf(
                        field to svg,
                        "fileName" to ref.fileName,
                        "points" to points.map { mapOf("label" to it.label, "value" to it.value) },
                    ),
                ).withBinary("chart", ref)
            )
        )
    }
}

private data class ChartPoint(val label: String, val value: Double)

private object ChartSvg {
    fun render(points: List<ChartPoint>, width: Int, height: Int, style: String): String {
        val left = 52.0
        val right = 16.0
        val top = 20.0
        val bottom = 44.0
        val plotW = width - left - right
        val plotH = height - top - bottom
        val maxValue = max(1.0, points.maxOf { abs(it.value) })
        val baseline = top + plotH
        fun x(index: Int): Double = left + (index + 0.5) * plotW / points.size
        fun y(value: Double): Double = baseline - value / maxValue * plotH
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
            .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ")
            .append(width).append(' ').append(height).append("\">")
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#0B0F19\"/>")
        sb.append("<g stroke=\"#2A3142\" stroke-width=\"1\">")
        for (i in 0..4) {
            val gy = top + plotH * i / 4.0
            sb.append("<line x1=\"$left\" y1=\"$gy\" x2=\"${left + plotW}\" y2=\"$gy\"/>")
        }
        sb.append("</g>")
        sb.append("<g fill=\"#8A93A8\" font-family=\"sans-serif\" font-size=\"11\">")
        for (i in 0..4) {
            val value = maxValue * (4 - i) / 4.0
            val gy = top + plotH * i / 4.0
            sb.append("<text x=\"8\" y=\"${gy + 4}\">").append(escape(fmt(value))).append("</text>")
        }
        sb.append("</g>")
        when (style) {
            "line", "area" -> {
                val path = points.mapIndexed { index, point ->
                    (if (index == 0) "M" else "L") + "${fmt(x(index))},${fmt(y(point.value))}"
                }.joinToString(" ")
                if (style == "area") {
                    sb.append("<path d=\"$path L${fmt(x(points.size - 1))},$baseline L${fmt(x(0))},$baseline Z\" fill=\"#D4AF37\" fill-opacity=\"0.16\"/>")
                }
                sb.append("<path d=\"$path\" fill=\"none\" stroke=\"#E5C158\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>")
                points.forEachIndexed { index, point ->
                    sb.append("<circle cx=\"${fmt(x(index))}\" cy=\"${fmt(y(point.value))}\" r=\"4\" fill=\"#F59E0B\"/>")
                }
            }
            else -> {
                val barW = plotW / points.size * 0.62
                points.forEachIndexed { index, point ->
                    val px = x(index) - barW / 2.0
                    val py = y(point.value)
                    sb.append("<rect x=\"${fmt(px)}\" y=\"${fmt(py)}\" width=\"${fmt(barW)}\"")
                        .append(" height=\"").append(fmt(baseline - py))
                        .append("\" rx=\"3\" fill=\"#D4AF37\"/>")
                }
            }
        }
        sb.append("<g fill=\"#C9D0E0\" font-family=\"sans-serif\" font-size=\"11\" text-anchor=\"middle\">")
        points.forEachIndexed { index, point ->
            sb.append("<text x=\"${fmt(x(index))}\" y=\"${baseline + 20}\">")
                .append(escape(point.label.take(14))).append("</text>")
        }
        sb.append("</g></svg>")
        return sb.toString()
    }

    private fun fmt(value: Double): String =
        if (abs(value - value.toLong()) < 0.001) value.toLong().toString() else String.format("%.2f", value)

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
}

/** Cryptographically useful randomness, entirely on-device. */
class RandomNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.random",
        title = "Random value",
        category = StepCategory.Transform,
        subtitle = "Create IDs, numbers, strings or a random pick",
        iconKey = "shuffle",
        params = persistentListOf(
            ParamSpec(
                key = "kind",
                label = "Create",
                type = ParamType.Select,
                default = "uuid",
                options = selectOptions(
                    "uuid" to "Unique ID", "number" to "A number", "string" to "A secret-like string",
                    "pick" to "Pick one of a list", "shuffle" to "Shuffle the incoming items",
                ),
            ),
            textParam("minimum", "Smallest number", default = "0", showWhen = mapOf("kind" to listOf("number"))),
            textParam("maximum", "Largest number", default = "100", showWhen = mapOf("kind" to listOf("number"))),
            ParamSpec("length", "String length", ParamType.Number, default = 24,
                showWhen = mapOf("kind" to listOf("string"))),
            textParam("list", "Pick from (JSON list)", default = "[]",
                showWhen = mapOf("kind" to listOf("pick"))),
            textParam("outputField", "Save value in", default = "value"),
        ),
        keywords = persistentListOf("random", "uuid", "id", "token", "string", "number", "pick", "shuffle", "sample"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val kind = Values.asString(params["kind"], "uuid")
        val field = ctx.outputName("value")
        val random = SecureRandom()
        if (kind == "shuffle") return ctx.output(ctx.input.shuffled(random))
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        return ctx.output(sources.mapIndexed { index, item ->
            val value: Any? = when (kind) {
                "number" -> {
                    val lo = ctx.resolveNumber(params["minimum"], index, 0.0)
                    val hi = ctx.resolveNumber(params["maximum"], index, 100.0)
                    val min = kotlin.math.min(lo, hi)
                    val max = kotlin.math.max(lo, hi)
                    min + random.nextDouble() * (max - min)
                }
                "string" -> {
                    val length = (Values.asDouble(params["length"]) ?: 24.0).toInt().coerceIn(1, 256)
                    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
                    buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
                }
                "pick" -> {
                    val choices = Values.asList(ctx.resolve(params["list"], index))
                    if (choices.isEmpty()) throw StepExecutionException(ctx.step.id, ctx.step.name, "the list to pick from is empty")
                    choices[random.nextInt(choices.size)]
                }
                else -> UUID.randomUUID().toString()
            }
            item.copy(json = item.json + (field to value))
        })
    }
}
