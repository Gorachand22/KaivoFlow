package com.kaivoflow.core.nodes.data.text

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
