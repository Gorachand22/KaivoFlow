package com.kaivoflow.core.nodes

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
import kotlinx.collections.immutable.persistentListOf
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Numeric work without a scripting language: the maths Step the backlog asked for. */
class MathNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.math",
        title = "Math",
        category = StepCategory.Transform,
        subtitle = "Calculate with numbers from your items",
        iconKey = "calculate",
        params = persistentListOf(
            ParamSpec(
                key = "operation",
                label = "Operation",
                type = ParamType.Select,
                default = "add",
                options = selectOptions(
                    "add" to "Add", "subtract" to "Subtract", "multiply" to "Multiply",
                    "divide" to "Divide", "modulo" to "Remainder", "power" to "Power of",
                    "percent" to "Percentage of", "round" to "Round", "ceil" to "Round up",
                    "floor" to "Round down", "abs" to "Absolute value", "negate" to "Negate",
                    "sqrt" to "Square root", "log" to "Logarithm", "min" to "Smaller of",
                    "max" to "Larger of", "average" to "Average", "clamp" to "Keep between",
                    "random" to "Random number", "sum_fields" to "Total listed fields",
                ),
            ),
            textParam("a", "First value", default = "{{ \$json.value }}", placeholder = "{{ \$json.amount }}"),
            textParam("b", "Second value", default = "0", showWhen = mapOf("operation" to listOf(
                "add", "subtract", "multiply", "divide", "modulo", "power", "percent",
                "min", "max", "average", "clamp", "random",
            ))),
            textParam("fields", "Fields to total", default = "amount", placeholder = "amount, tax, shipping",
                showWhen = mapOf("operation" to listOf("sum_fields"))),
            ParamSpec("precision", "Decimal places", ParamType.Number, default = 2,
                showWhen = mapOf("operation" to listOf("round"))),
            textParam("maximum", "Upper bound", default = "100", showWhen = mapOf("operation" to listOf("clamp"))),
            textParam("outputField", "Save result in", default = "result"),
        ),
        keywords = persistentListOf("math", "calculate", "sum", "round", "min", "max", "percent", "number"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val operation = Values.asString(params["operation"], "add")
        val field = ctx.outputName("result")
        val precision = (Values.asDouble(params["precision"]) ?: 2.0).toInt().coerceIn(0, 12)
        val out = ctx.input.mapIndexed { index, item ->
            val a = ctx.resolveNumber(params["a"], index, 0.0)
            val b = ctx.resolveNumber(params["b"], index, 0.0)
            val result: Double = when (operation) {
                "add" -> a + b
                "subtract" -> a - b
                "multiply" -> a * b
                "divide" -> divide(ctx, a, b)
                "modulo" -> if (b == 0.0) throw zeroDivision(ctx) else a % b
                "power" -> a.pow(b)
                "percent" -> if (b == 0.0) throw zeroDivision(ctx) else a / b * 100.0
                "round" -> {
                    val factor = 10.0.pow(precision)
                    Math.round(a * factor).toDouble() / factor
                }
                "ceil" -> ceil(a)
                "floor" -> floor(a)
                "abs" -> abs(a)
                "negate" -> -a
                "sqrt" -> if (a < 0) throw badNumber(ctx, "square root of a negative number") else sqrt(a)
                "log" -> if (a <= 0) throw badNumber(ctx, "logarithm of zero or a negative number") else log10(a)
                "ln" -> if (a <= 0) throw badNumber(ctx, "logarithm of zero or a negative number") else ln(a)
                "min" -> min(a, b)
                "max" -> max(a, b)
                "average" -> (a + b) / 2.0
                "clamp" -> {
                    val upper = ctx.resolveNumber(params["maximum"], index, 100.0)
                    a.coerceIn(min(b, upper), max(b, upper))
                }
                "random" -> {
                    val lo = min(a, b)
                    val hi = max(a, b)
                    lo + Math.random() * (hi - lo)
                }
                "sum_fields" -> {
                    val fields = ctx.resolveText(params["fields"], index)
                        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (fields.isEmpty()) throw badNumber(ctx, "list at least one field to total")
                    fields.sumOf { name -> Values.asDouble(Values.deepGet(item.json, name)) ?: 0.0 }
                }
                else -> a
            }
            if (!result.isFinite()) throw badNumber(ctx, "the result is not a finite number")
            item.copy(json = item.json + (field to result))
        }
        return ctx.output(out)
    }

    private fun divide(ctx: StepContext, a: Double, b: Double): Double =
        if (b == 0.0) throw zeroDivision(ctx) else a / b

    private fun zeroDivision(ctx: StepContext) =
        StepExecutionException(ctx.step.id, ctx.step.name, "the second value is zero, so division is not possible")

    private fun badNumber(ctx: StepContext, detail: String) =
        StepExecutionException(ctx.step.id, ctx.step.name, "cannot compute $detail")
}

/** String surgery — replace, case, regex, encodes, splits and slugs, with no scripting. */
class TextNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.text",
        title = "Text tools",
        category = StepCategory.Transform,
        subtitle = "Clean up, split and reshape text",
        iconKey = "textFormat",
        params = persistentListOf(
            ParamSpec(
                key = "operation",
                label = "Operation",
                type = ParamType.Select,
                default = "trim",
                options = selectOptions(
                    "upper" to "UPPERCASE", "lower" to "lowercase", "title" to "Title Case",
                    "trim" to "Trim whitespace", "length" to "Length", "reverse" to "Reverse",
                    "slug" to "Make a URL slug", "snake" to "snake_case", "camel" to "camelCase",
                    "replace" to "Replace text", "regex_replace" to "Replace by pattern",
                    "regex_extract" to "Extract by pattern", "split" to "Split into a list",
                    "join" to "Join a list", "substring" to "Take a slice", "truncate" to "Shorten",
                    "pad_left" to "Pad on the left", "pad_right" to "Pad on the right",
                    "contains" to "Contains", "starts_with" to "Starts with", "ends_with" to "Ends with",
                    "word_count" to "Count words", "url_encode" to "URL encode", "url_decode" to "URL decode",
                    "html_escape" to "Escape HTML", "html_unescape" to "Unescape HTML",
                    "base64_encode" to "Base64 encode", "base64_decode" to "Base64 decode",
                    "ascii" to "Remove accents",
                ),
            ),
            textParam("input", "Text", default = "{{ \$json.text }}"),
            textParam("argument", "With", placeholder = "search text, pattern, separator or length"),
            textParam("replacement", "Replace with", showWhen = mapOf("operation" to listOf("replace", "regex_replace"))),
            textParam("outputField", "Save result in", default = "text"),
        ),
        keywords = persistentListOf("text", "string", "replace", "regex", "split", "slug", "uppercase", "trim", "encode"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val operation = Values.asString(params["operation"], "trim")
        val field = ctx.outputName("text")
        val out = ctx.input.mapIndexed { index, item ->
            val raw = ctx.resolve(params["input"], index)
            val text = Values.asString(raw)
            val arg = ctx.resolveText(params["argument"], index)
            val replacement = ctx.resolveText(params["replacement"], index)
            val result: Any? = when (operation) {
                "upper" -> text.uppercase()
                "lower" -> text.lowercase()
                "title" -> text.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
                "trim" -> text.trim()
                "length" -> text.length.toDouble()
                "reverse" -> text.reversed()
                "slug" -> slugify(text)
                "snake" -> words(text).joinToString("_") { it.lowercase() }
                "camel" -> words(text).mapIndexed { i, w ->
                    val lower = w.lowercase()
                    if (i == 0) lower else lower.replaceFirstChar { c -> c.uppercase() }
                }.joinToString("")
                "replace" -> text.replace(arg, replacement, ignoreCase = false)
                "regex_replace" -> regex(ctx, arg).replace(text, replacement)
                "regex_extract" -> regex(ctx, arg).find(text)?.let { m ->
                    if (m.groupValues.size > 1) m.groupValues.drop(1) else listOf(m.value)
                } ?: emptyList<String>()
                "split" -> if (arg.isEmpty()) text.map { it.toString() } else text.split(arg)
                "join" -> Values.asList(raw).joinToString(arg.ifEmpty { ", " }) { Values.asString(it) }
                "substring" -> substring(text, arg)
                "truncate" -> {
                    val limit = arg.toIntOrNull()?.coerceAtLeast(0) ?: 80
                    if (text.length <= limit) text else text.take(limit - 1) + "…"
                }
                "pad_left" -> text.padStart(arg.toIntOrNull()?.coerceIn(0, 500) ?: 10, ' ')
                "pad_right" -> text.padEnd(arg.toIntOrNull()?.coerceIn(0, 500) ?: 10, ' ')
                "contains" -> text.contains(arg, ignoreCase = true)
                "starts_with" -> text.startsWith(arg, ignoreCase = true)
                "ends_with" -> text.endsWith(arg, ignoreCase = true)
                "word_count" -> words(text).size.toDouble()
                "url_encode" -> URLEncoder.encode(text, "UTF-8")
                "url_decode" -> runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
                "html_escape" -> text
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;")
                "html_unescape" -> text
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
                "base64_encode" -> Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                "base64_decode" -> runCatching {
                    String(Base64.getDecoder().decode(text), Charsets.UTF_8)
                }.getOrDefault("")
                "ascii" -> java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}"), "")
                else -> text
            }
            item.copy(json = item.json + (field to result))
        }
        return ctx.output(out)
    }

    private fun regex(ctx: StepContext, pattern: String): Regex = runCatching {
        Regex(pattern)
    }.getOrElse {
        throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$pattern\" is not a valid pattern")
    }

    private fun substring(text: String, spec: String): String {
        val parts = spec.split(',').map { it.trim().toIntOrNull() }
        val start = (parts.getOrNull(0) ?: 0).coerceAtLeast(0).coerceAtMost(text.length)
        val end = (parts.getOrNull(1) ?: text.length).coerceIn(start, text.length)
        return text.substring(start, end)
    }

    private fun words(text: String): List<String> =
        text.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }

    private fun slugify(text: String): String = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}

/** One summary item for a numeric field across the whole incoming list. */
class StatsNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.stats",
        title = "Number summary",
        category = StepCategory.Transform,
        subtitle = "Count, total, min, max, mean, median and spread",
        iconKey = "queryStats",
        params = persistentListOf(
            textParam("field", "Number field", placeholder = "amount", required = true),
            textParam("operations", "Include", default = "count,sum,min,max,mean,median,stddev"),
            textParam("outputField", "Save summary in", default = "stats"),
        ),
        keywords = persistentListOf("statistics", "average", "median", "deviation", "sum", "min", "max", "count"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val field = ctx.resolveText(params["field"], 0)
        if (field.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "choose a number field")
        val values = ctx.input.mapNotNull { item ->
            Values.asDouble(Values.deepGet(item.json, field))
        }
        val wanted = ctx.resolveText(params["operations"], 0, "count,sum,min,max,mean,median,stddev")
            .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.ifEmpty { listOf("count") }
        val sorted = values.sorted()
        val mean = if (values.isEmpty()) 0.0 else values.average()
        val stats = LinkedHashMap<String, Any?>()
        wanted.forEach { op ->
            stats[op] = when (op) {
                "count" -> values.size.toDouble()
                "sum", "total" -> values.sum()
                "min" -> sorted.firstOrNull() ?: 0.0
                "max" -> sorted.lastOrNull() ?: 0.0
                "mean", "average", "avg" -> mean
                "median" -> median(sorted)
                "stddev", "stdev" -> if (values.size < 2) 0.0 else {
                    sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
                }
                "variance" -> if (values.size < 2) 0.0 else {
                    values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
                }
                else -> null
            }
        }
        stats.removeWhereNull()
        val outField = ctx.outputName("stats")
        return ctx.output(listOf(FlowData(mapOf(outField to stats, "samples" to values.size))))
    }

    private fun median(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 0.0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun MutableMap<String, Any?>.removeWhereNull() {
        val keys = entries.filter { it.value == null }.map { it.key }
        keys.forEach { remove(it) }
    }
}

/** Turn one item into many: split an array field, split text, or chunk the whole list. */
class SplitNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.split",
        title = "Split items",
        category = StepCategory.Transform,
        subtitle = "Turn a list or a line of text into separate items",
        iconKey = "unfoldMore",
        params = persistentListOf(
            ParamSpec(
                key = "mode",
                label = "Split",
                type = ParamType.Select,
                default = "array",
                options = selectOptions(
                    "array" to "A list field", "text" to "Text by a separator", "chunks" to "Items into batches",
                ),
            ),
            textParam("source", "From field", default = "{{ \$json.items }}",
                showWhen = mapOf("mode" to listOf("array", "text"))),
            textParam("delimiter", "Separator", default = ",", showWhen = mapOf("mode" to listOf("text"))),
            ParamSpec("chunkSize", "Items per batch", ParamType.Number, default = 10,
                showWhen = mapOf("mode" to listOf("chunks"))),
            textParam("outputField", "Put each value in", default = "value"),
            ParamSpec("keepOtherFields", "Keep the other fields", ParamType.Boolean, default = true),
        ),
        keywords = persistentListOf("split", "explode", "flatten", "batch", "chunk", "array", "items"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val mode = Values.asString(params["mode"], "array")
        val field = ctx.outputName("value")
        val keep = Values.asBoolean(params["keepOtherFields"] ?: true)
        if (mode == "chunks") {
            val size = (Values.asDouble(params["chunkSize"]) ?: 10.0).toInt().coerceAtLeast(1)
            val batches = ctx.input.chunked(size).mapIndexed { i, batch ->
                FlowData(
                    mapOf(
                        "batchIndex" to i,
                        "batchSize" to batch.size,
                        field to batch.map { it.json },
                    )
                )
            }
            return ctx.output(batches)
        }
        val out = ctx.input.flatMapIndexed { index, item ->
            val raw = ctx.resolve(params["source"], index)
            val values = when (mode) {
                "text" -> {
                    val separator = ctx.resolveText(params["delimiter"], index, ",")
                    Values.asString(raw).split(separator).map { it.trim() }
                }
                else -> Values.asList(raw)
            }
            values.mapIndexed { i, value ->
                val base = if (keep) LinkedHashMap(item.json) else LinkedHashMap()
                base[field] = value
                base["splitIndex"] = i
                item.copy(json = base)
            }
        }
        return ctx.output(out)
    }
}

/** Parse a URL into fields, or build a URL from a base and query parameters. */
class UrlNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.url",
        title = "URL tools",
        category = StepCategory.Transform,
        subtitle = "Read a link apart or build one with query parameters",
        iconKey = "external",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "parse",
                options = selectOptions("parse" to "Take a link apart", "build" to "Build a link"),
            ),
            textParam("url", "Link", default = "{{ \$json.url }}", showWhen = mapOf("action" to listOf("parse"))),
            textParam("base", "Base address", default = "https://example.com/search",
                showWhen = mapOf("action" to listOf("build"))),
            ParamSpec("query", "Query parameters", ParamType.KeyValue,
                showWhen = mapOf("action" to listOf("build"))),
            textParam("outputField", "Save result in", default = "url"),
        ),
        keywords = persistentListOf("url", "link", "query", "parse", "encode", "domain", "host"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val action = Values.asString(params["action"], "parse")
        val field = ctx.outputName("url")
        val resolver = com.kaivoflow.core.engine.ParamResolver(ctx.evaluator)
        val out = ctx.input.mapIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            if (action == "build") {
                val base = Values.asString(row["base"])
                if (base.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add a base address")
                val query = Values.asMap(params["query"]).entries.mapNotNull { (key, raw) ->
                    val value = Values.asString(resolver.resolveValue(raw, scope))
                    if (key.isBlank() || value.isBlank()) null
                    else "${encode(key)}=${encode(value)}"
                }
                val separator = if (base.contains('?')) "&" else "?"
                val url = base + (if (query.isEmpty()) "" else separator + query.joinToString("&"))
                item.copy(json = item.json + (field to url))
            } else {
                val text = Values.asString(row["url"])
                val parsed = runCatching { URI(text) }.getOrElse {
                    throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$text\" is not a valid link")
                }
                val queryMap = LinkedHashMap<String, Any?>()
                parsed.rawQuery?.split('&')?.forEach { pair ->
                    if (pair.isBlank()) return@forEach
                    val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    queryMap[decode(k)] = decode(v)
                }
                item.copy(
                    json = item.json + (field to mapOf(
                        "scheme" to (parsed.scheme ?: ""),
                        "host" to (parsed.host ?: ""),
                        "port" to parsed.port.toDouble(),
                        "path" to (parsed.rawPath ?: ""),
                        "query" to queryMap,
                        "fragment" to (parsed.fragment ?: ""),
                    ))
                )
            }
        }
        return ctx.output(out)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
