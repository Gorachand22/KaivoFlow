package com.kaivoflow.core.nodes.data.text

import com.kaivoflow.core.engine.Values

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
