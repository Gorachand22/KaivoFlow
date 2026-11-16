package com.kaivoflow.core.nodes.data.items

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
import com.kaivoflow.core.nodes.resolve
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

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
