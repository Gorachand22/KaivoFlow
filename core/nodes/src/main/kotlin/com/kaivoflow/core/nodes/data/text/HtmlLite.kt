package com.kaivoflow.core.nodes.data.text

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
