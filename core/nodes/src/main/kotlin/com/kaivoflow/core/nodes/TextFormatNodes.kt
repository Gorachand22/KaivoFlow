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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** CSV in, items out — and items back into CSV. Quoted fields and embedded newlines are handled. */
class CsvNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.csv",
        title = "CSV",
        category = StepCategory.Transform,
        subtitle = "Read a table into items, or write one from items",
        iconKey = "table",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "parse",
                options = selectOptions("parse" to "Read CSV into items", "build" to "Build CSV from items"),
            ),
            textParam("source", "Text field", default = "{{ \$json.csv }}",
                showWhen = mapOf("action" to listOf("parse"))),
            textParam("delimiter", "Column separator", default = ","),
            ParamSpec("hasHeader", "First row holds the column names", ParamType.Boolean, default = true,
                showWhen = mapOf("action" to listOf("parse"))),
            textParam("columns", "Columns in order", placeholder = "date, merchant, amount",
                showWhen = mapOf("action" to listOf("build"))),
            textParam("outputField", "Save CSV text in", default = "csv",
                showWhen = mapOf("action" to listOf("build"))),
        ),
        keywords = persistentListOf("csv", "spreadsheet", "table", "columns", "delimiter", "rows", "export"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val action = Values.asString(params["action"], "parse")
        val delimiter = ctx.resolveText(params["delimiter"], 0, ",").firstOrNull() ?: ','
        if (action == "build") {
            val columns = ctx.resolveText(params["columns"], 0)
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val csv = CsvLite.build(ctx.input.map { it.json }, columns, delimiter)
            val field = ctx.outputName("csv")
            return ctx.output(listOf(FlowData(mapOf(field to csv, "rows" to ctx.input.size))))
        }
        val source = ctx.resolveText(params["source"], 0)
        val hasHeader = Values.asBoolean(params["hasHeader"] ?: true)
        val rows = CsvLite.parse(source, delimiter, hasHeader)
        ctx.log("Read ${rows.size} CSV rows.")
        return ctx.output(rows.map { FlowData(it) })
    }
}

/** Convert between a compact JSON-ish representation and XML. */
class XmlNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.xml",
        title = "XML",
        category = StepCategory.Transform,
        subtitle = "Turn XML into fields, or fields into XML",
        iconKey = "dataObject",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "to_json",
                options = selectOptions("to_json" to "Read XML into fields", "to_xml" to "Build XML from fields"),
            ),
            textParam("source", "XML or data field", default = "{{ \$json.xml }}",
                showWhen = mapOf("action" to listOf("to_json"))),
            textParam("rootTag", "Root element name", default = "root",
                showWhen = mapOf("action" to listOf("to_xml"))),
            textParam("outputField", "Save result in", default = "result"),
        ),
        keywords = persistentListOf("xml", "soap", "parse", "serialize", "tags", "attributes"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val action = Values.asString(params["action"], "to_json")
        val field = ctx.outputName("result")
        val out = ctx.input.mapIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val value: Any? = if (action == "to_xml") {
                val root = Values.asString(row["rootTag"], "root")
                    .replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "root" }
                XmlLite.build(item.json, root)
            } else {
                val text = Values.asString(row["source"])
                XmlLite.parse(text) ?: throw StepExecutionException(
                    ctx.step.id, ctx.step.name, "the XML could not be read (check for unbalanced tags)"
                )
            }
            item.copy(json = item.json + (field to value))
        }
        return ctx.output(out)
    }
}

/** Extract readable text, links or attributes from HTML without a browser. */
class HtmlNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.html",
        title = "HTML tools",
        category = StepCategory.Transform,
        subtitle = "Pull text, links and images out of a web page",
        iconKey = "html",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Get",
                type = ParamType.Select,
                default = "text",
                options = selectOptions(
                    "text" to "Readable text", "links" to "All links", "images" to "All image URLs",
                    "attribute" to "A tag attribute", "title" to "Page title",
                ),
            ),
            textParam("source", "HTML field", default = "{{ \$json.html }}"),
            textParam("tag", "Tag name", default = "a", placeholder = "a, img, meta",
                showWhen = mapOf("action" to listOf("attribute"))),
            textParam("attribute", "Attribute", default = "href",
                showWhen = mapOf("action" to listOf("attribute"))),
            textParam("outputField", "Save result in", default = "text"),
        ),
        keywords = persistentListOf("html", "scrape", "extract", "links", "text", "css", "web page"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val action = Values.asString(params["action"], "text")
        val field = ctx.outputName("text")
        val out = ctx.input.mapIndexed { index, item ->
            val html = ctx.resolveText(params["source"], index)
            val result: Any? = when (action) {
                "links" -> HtmlLite.links(html)
                "images" -> HtmlLite.attributes(html, "img", "src")
                "title" -> HtmlLite.title(html)
                "attribute" -> HtmlLite.attributes(
                    html,
                    ctx.resolveText(params["tag"], index, "a").ifBlank { "a" },
                    ctx.resolveText(params["attribute"], index, "href").ifBlank { "href" },
                )
                else -> HtmlLite.text(html)
            }
            item.copy(json = item.json + (field to result))
        }
        return ctx.output(out)
    }
}

/** Markdown to HTML (and back to plain text) for messages, notes and notifications. */
class MarkdownNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.markdown",
        title = "Markdown",
        category = StepCategory.Transform,
        subtitle = "Turn Markdown notes into HTML or plain text",
        iconKey = "markdown",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "to_html",
                options = selectOptions("to_html" to "Markdown → HTML", "to_text" to "HTML/Markdown → plain text"),
            ),
            textParam("source", "Text field", default = "{{ \$json.markdown }}"),
            textParam("outputField", "Save result in", default = "html"),
        ),
        keywords = persistentListOf("markdown", "html", "format", "notes", "rich text", "headings"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val action = Values.asString(params["action"], "to_html")
        val field = ctx.outputName("html")
        val out = ctx.input.mapIndexed { index, item ->
            val source = ctx.resolveText(params["source"], index)
            val result = if (action == "to_text") MarkdownLite.toText(source) else MarkdownLite.toHtml(source)
            item.copy(json = item.json + (field to result))
        }
        return ctx.output(out)
    }
}

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

// ---- Pure format engines ---------------------------------------------------------------

internal object CsvLite {
    fun parse(text: String, delimiter: Char, hasHeader: Boolean): List<Map<String, Any?>> {
        if (text.length > 2_000_000) return emptyList()
        val rows = mutableListOf<List<String>>()
        var field = StringBuilder()
        val row = mutableListOf<String>()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> field.append(c)
                }
                c == '"' && field.isEmpty() -> quoted = true
                c == delimiter -> { row += field.toString(); field = StringBuilder() }
                c == '\n' -> {
                    row += field.toString().trimEnd('\r')
                    field = StringBuilder()
                    if (row.any { it.isNotEmpty() }) rows += row.toList()
                    row.clear()
                }
                else -> field.append(c)
            }
            i++
        }
        row += field.toString().trimEnd('\r')
        if (row.any { it.isNotEmpty() }) rows += row.toList()
        if (rows.isEmpty()) return emptyList()
        val headers = if (hasHeader) {
            rows.first().mapIndexed { index, name -> name.trim().ifBlank { "column_$index" } }
        } else {
            val width = rows.maxOf { it.size }
            (0 until width).map { "column_$it" }
        }
        val body = if (hasHeader) rows.drop(1) else rows
        return body.map { values ->
            headers.mapIndexed { index, name -> name to (values.getOrNull(index) ?: "") }.toMap()
        }
    }

    fun build(rows: List<Map<String, Any?>>, columns: List<String>, delimiter: Char): String {
        val headers = columns.ifEmpty {
            rows.flatMap { it.keys }.distinct().map { it.toString() }
        }
        val sb = StringBuilder()
        sb.append(headers.joinToString(delimiter.toString()) { escape(it) }).append('\n')
        rows.forEach { row ->
            sb.append(headers.joinToString(delimiter.toString()) { key ->
                escape(Values.asString(row[key]))
            }).append('\n')
        }
        return sb.toString()
    }

    private fun escape(value: String): String {
        val needs = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needs) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}

internal object XmlLite {
    fun parse(text: String): Map<String, Any?>? {
        if (text.length > 1_000_000) return null
        return runCatching { Parser(text).document() }.getOrNull()
    }

    fun build(value: Any?, root: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        writeElement(sb, root, value, 0)
        return sb.toString()
    }

    private fun writeElement(sb: StringBuilder, tag: String, value: Any?, depth: Int) {
        if (depth > 32) return
        val safe = tag.replace(Regex("[^A-Za-z0-9_.:-]"), "_").ifBlank { "item" }
        when (value) {
            is Map<*, *> -> {
                sb.append('<').append(safe)
                value.entries.filter { it.key.toString().startsWith("@") && it.key.toString() != "@name" }
                    .forEach { (k, v) ->
                        sb.append(' ').append(k.toString().removePrefix("@")).append("=\"")
                            .append(escapeAttr(Values.asString(v))).append('"')
                    }
                sb.append('>')
                val text = value.entries.firstOrNull { it.key.toString() == "#text" }?.value
                if (text != null) sb.append(escapeText(Values.asString(text)))
                value.entries.filter { !it.key.toString().startsWith("@") && it.key.toString() != "#text" }
                    .forEach { (k, v) ->
                        if (v is List<*>) v.forEach { child -> writeElement(sb, k.toString(), child, depth + 1) }
                        else writeElement(sb, k.toString(), v, depth + 1)
                    }
                sb.append("</").append(safe).append('>')
            }
            is List<*> -> value.forEach { writeElement(sb, safe, it, depth + 1) }
            null -> Unit
            else -> {
                sb.append('<').append(safe).append('>')
                    .append(escapeText(Values.asString(value)))
                    .append("</").append(safe).append('>')
            }
        }
    }

    private fun escapeText(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttr(value: String): String = escapeText(value).replace("\"", "&quot;")

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): Map<String, Any?>? {
            skipMisc()
            val (name, value) = element(0) ?: return null
            return mapOf(name to value)
        }

        private fun element(depth: Int): Pair<String, Any?>? {
            if (depth > 48 || i >= s.length || s[i] != '<') return null
            i++
            val nameStart = i
            while (i < s.length && s[i] !in " \t\r\n/>") i++
            val name = s.substring(nameStart, i).replace(Regex("[^A-Za-z0-9_.:-]"), "_")
            if (name.isBlank()) return null
            val attrs = LinkedHashMap<String, Any?>()
            while (i < s.length) {
                skipSpaces()
                if (i >= s.length) return null
                when {
                    s.startsWith("/>", i) -> { i += 2; return name to finish(attrs, emptyList(), "") }
                    s[i] == '>' -> { i++; break }
                    else -> {
                        val keyStart = i
                        while (i < s.length && s[i] !in "= \t\r\n/>") i++
                        val key = s.substring(keyStart, i)
                        skipSpaces()
                        if (i >= s.length || s[i] != '=') { i++; continue }
                        i++
                        skipSpaces()
                        if (i >= s.length || (s[i] != '"' && s[i] != '\'')) return null
                        val quote = s[i++]
                        val valueStart = i
                        while (i < s.length && s[i] != quote) i++
                        val attrValue = s.substring(valueStart, i.coerceAtMost(s.length))
                        if (i < s.length) i++
                        if (key.isNotBlank()) attrs["@$key"] = HtmlLite.decode(attrValue)
                    }
                }
            }
            val children = LinkedHashMap<String, Any?>()
            val text = StringBuilder()
            while (i < s.length) {
                when {
                    s.startsWith("</", i) -> {
                        i += 2
                        while (i < s.length && s[i] != '>') i++
                        if (i < s.length) i++
                        return name to finish(attrs, children.entries.map { it.key to it.value }, text.toString())
                    }
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i + 9)
                        if (end < 0) return null
                        text.append(s.substring(i + 9, end))
                        i = end + 3
                    }
                    s.startsWith("<!--", i) -> {
                        val end = s.indexOf("-->", i + 4)
                        if (end < 0) return null
                        i = end + 3
                    }
                    s.startsWith("<?", i) -> {
                        val end = s.indexOf("?>", i + 2)
                        if (end < 0) return null
                        i = end + 2
                    }
                    s[i] == '<' -> {
                        val child = element(depth + 1) ?: return null
                        val key = child.first
                        if (children.containsKey(key)) {
                            val existing = children[key]
                            children[key] = if (existing is List<*>) existing + listOf(child.second) else listOf(existing, child.second)
                        } else {
                            children[key] = child.second
                        }
                    }
                    else -> text.append(s[i++])
                }
            }
            return null // unclosed element
        }

        private fun finish(
            attrs: Map<String, Any?>,
            children: List<Pair<String, Any?>>,
            rawText: String,
        ): Any? {
            val clean = HtmlLite.decode(rawText).trim()
            if (attrs.isEmpty() && children.isEmpty()) return clean.ifEmpty { "" }
            val out = LinkedHashMap<String, Any?>()
            out.putAll(attrs)
            children.forEach { (k, v) -> out[k] = v }
            if (clean.isNotEmpty()) out["#text"] = clean
            return out
        }

        private fun skipSpaces() { while (i < s.length && s[i] in " \t\r\n") i++ }
        private fun skipMisc() {
            while (i < s.length) {
                skipSpaces()
                when {
                    s.startsWith("<?", i) -> { val end = s.indexOf("?>", i + 2); i = if (end < 0) s.length else end + 2 }
                    s.startsWith("<!--", i) -> { val end = s.indexOf("-->", i + 4); i = if (end < 0) s.length else end + 3 }
                    s.startsWith("<!", i) -> { val end = s.indexOf('>', i + 2); i = if (end < 0) s.length else end + 1 }
                    else -> return
                }
            }
        }
    }
}

internal object HtmlLite {
    private val SCRIPT = Regex("""<(script|style)\b[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
    private val COMMENT = Regex("""<!--[\s\S]*?-->""")
    private val TAG = Regex("""<[^>]+>""")
    private val ANCHOR = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""href\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("""<title\b[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)

    fun text(html: String): String {
        val cleaned = COMMENT.replace(SCRIPT.replace(html, " "), " ")
            .replace(Regex("""<(br|/p|/div|/li|/h[1-6])\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        return decode(TAG.replace(cleaned, " "))
            .lines().joinToString("\n") { it.trim().replace(Regex("[ \\t]{2,}"), " ") }
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun links(html: String): List<Map<String, Any?>> = ANCHOR.findAll(html).mapNotNull { match ->
        val href = HREF.find(match.groupValues[1])?.let {
            it.groupValues[2].ifEmpty { it.groupValues[3].ifEmpty { it.groupValues[4] } }
        } ?: return@mapNotNull null
        mapOf("href" to decode(href), "text" to text(match.groupValues[2]))
    }.toList()

    fun attributes(html: String, tag: String, attribute: String): List<String> {
        val safeTag = tag.replace(Regex("[^A-Za-z0-9]"), "")
        val safeAttr = attribute.replace(Regex("[^A-Za-z0-9_-]"), "")
        if (safeTag.isBlank() || safeAttr.isBlank()) return emptyList()
        val tags = Regex("""<$safeTag\b([^>]*)>""", RegexOption.IGNORE_CASE)
        val attr = Regex("""$safeAttr\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
        return tags.findAll(html).mapNotNull { match ->
            attr.find(match.groupValues[1])?.let {
                decode(it.groupValues[2].ifEmpty { it.groupValues[3].ifEmpty { it.groupValues[4] } })
            }
        }.toList()
    }

    fun title(html: String): String = TITLE.find(html)?.groupValues?.get(1)?.let { text(it) }.orEmpty()

    fun decode(value: String): String = value
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value }
        .replace("&amp;", "&")
}

internal object MarkdownLite {
    fun toText(source: String): String = HtmlLite.text(toHtml(source))

    fun toHtml(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val out = StringBuilder()
        var i = 0
        var paragraph = mutableListOf<String>()
        var listType: String? = null

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            out.append("<p>").append(inline(paragraph.joinToString(" "))).append("</p>\n")
            paragraph = mutableListOf()
        }
        fun closeList() {
            if (listType != null) { out.append("</$listType>\n"); listType = null }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flushParagraph(); closeList()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        code.append(escape(lines[i])).append('\n'); i++
                    }
                    out.append("<pre><code>").append(code).append("</code></pre>\n")
                }
                trimmed.isBlank() -> { flushParagraph(); closeList() }
                Regex("""^(-{3,}|\*{3,})$""").matches(trimmed) -> {
                    flushParagraph(); closeList(); out.append("<hr/>\n")
                }
                trimmed.startsWith("#") -> {
                    flushParagraph(); closeList()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    out.append("<h$level>").append(inline(trimmed.drop(level).trim())).append("</h$level>\n")
                }
                trimmed.startsWith("> ") -> {
                    flushParagraph(); closeList()
                    out.append("<blockquote>").append(inline(trimmed.drop(2))).append("</blockquote>\n")
                }
                Regex("""^[-*+]\s+""").containsMatchIn(trimmed) -> {
                    flushParagraph()
                    if (listType != "ul") { closeList(); out.append("<ul>\n"); listType = "ul" }
                    out.append("<li>").append(inline(trimmed.replaceFirst(Regex("""^[-*+]\s+"""), ""))).append("</li>\n")
                }
                Regex("""^\d+\.\s+""").containsMatchIn(trimmed) -> {
                    flushParagraph()
                    if (listType != "ol") { closeList(); out.append("<ol>\n"); listType = "ol" }
                    out.append("<li>").append(inline(trimmed.replaceFirst(Regex("""^\d+\.\s+"""), ""))).append("</li>\n")
                }
                else -> { closeList(); paragraph += trimmed }
            }
            i++
        }
        flushParagraph(); closeList()
        return out.toString().trim()
    }

    private fun inline(text: String): String {
        var result = escape(text)
        result = Regex("""`([^`]+)`""").replace(result) { "<code>${it.groupValues[1]}</code>" }
        result = Regex("""!\[([^\]]*)\]\(([^)\s]+)\)""").replace(result) {
            "<img src=\"${it.groupValues[2]}\" alt=\"${it.groupValues[1]}\"/>"
        }
        result = Regex("""\[([^\]]+)\]\(([^)\s]+)\)""").replace(result) {
            "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
        }
        result = Regex("""\*\*([^*]+)\*\*""").replace(result) { "<strong>${it.groupValues[1]}</strong>" }
        result = Regex("""__([^_]+)__""").replace(result) { "<strong>${it.groupValues[1]}</strong>" }
        result = Regex("""~~([^~]+)~~""").replace(result) { "<del>${it.groupValues[1]}</del>" }
        result = Regex("""(?<!\*)\*([^*]+)\*(?!\*)""").replace(result) { "<em>${it.groupValues[1]}</em>" }
        return result
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
