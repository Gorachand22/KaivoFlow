package com.kaivoflow.core.nodes.files.document

import com.kaivoflow.core.nodes.BinarySupport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

internal object PdfLite {
    private val STREAM = Regex("""stream\r?\n([\s\S]*?)\r?\nendstream""")
    private val STRING_LITERAL = Regex("""\(((?:\\.|[^\\)])*)\)\s*Tj""")
    private val ARRAY_LITERAL = Regex("""\[([\s\S]*?)\]\s*TJ""")
    private val HEX_LITERAL = Regex("""<([0-9A-Fa-f\s]+)>\s*Tj""")

    fun pageCount(bytes: ByteArray): Int {
        val text = String(bytes, Charsets.ISO_8859_1)
        val count = Regex("""/Type\s*/Page(?![sA-Za-z])""").findAll(text).count()
        return count.coerceAtLeast(if (text.startsWith("%PDF")) 1 else 0)
    }

    fun extractText(bytes: ByteArray): String {
        val header = String(bytes.copyOfRange(0, minOf(8, bytes.size)), Charsets.ISO_8859_1)
        if (bytes.size > BinarySupport.MAX_BYTES || !header.startsWith("%PDF")) {
            return ""
        }
        val source = String(bytes, Charsets.ISO_8859_1)
        val chunks = STREAM.findAll(source).mapNotNull { match ->
            val prefix = source.substring(maxOf(0, match.range.first - 300), match.range.first)
            var data = match.groupValues[1].toByteArray(Charsets.ISO_8859_1)
            if (prefix.contains("/FlateDecode", true)) {
                data = runCatching { inflate(data) }.getOrElse { return@mapNotNull null }
            }
            String(data, Charsets.ISO_8859_1)
        }.toList()
        val parts = mutableListOf<String>()
        chunks.forEach { chunk ->
            STRING_LITERAL.findAll(chunk).forEach { parts += unescape(it.groupValues[1]) }
            HEX_LITERAL.findAll(chunk).forEach { parts += hexText(it.groupValues[1]) }
            ARRAY_LITERAL.findAll(chunk).forEach { match ->
                val content = match.groupValues[1]
                Regex("""\(((?:\\.|[^\\)])*)\)""").findAll(content).forEach {
                    parts += unescape(it.groupValues[1])
                }
                Regex("""<([0-9A-Fa-f\s]+)>""").findAll(content).forEach {
                    parts += hexText(it.groupValues[1])
                }
            }
        }
        return parts.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200_000)
    }

    private fun inflate(data: ByteArray): ByteArray {
        InflaterInputStream(ByteArrayInputStream(data)).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > BinarySupport.MAX_BYTES) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun hexText(hex: String): String {
        val clean = hex.replace(Regex("\\s"), "")
        if (clean.length % 2 != 0) return ""
        return runCatching {
            ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                .toString(Charsets.ISO_8859_1)
        }.getOrDefault("")
    }

    private fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c != '\\' || i >= text.length) { out.append(c); continue }
            when (text[i++]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                '(', ')', '\\' -> out.append(text[i - 1])
                in '0'..'7' -> {
                    val start = i - 1
                    var digits = 1
                    while (digits < 3 && i < text.length && text[i] in '0'..'7') { i++; digits++ }
                    out.append(text.substring(start, start + digits).toIntOrNull(8)?.toChar() ?: '?')
                }
                else -> out.append(text[i - 1])
            }
        }
        return out.toString()
    }
}
