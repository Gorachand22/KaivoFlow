package com.kaivoflow.core.expressions

/** Raw template pieces before parsing. */
internal sealed interface RawSegment {
    data class Text(val start: Int, val end: Int) : RawSegment
    /** `{{ body }}`: [start, end) is the whole thing including delimiters. */
    data class Code(val start: Int, val end: Int, val bodyStart: Int, val bodyEnd: Int) : RawSegment
    data class Unterminated(val start: Int) : RawSegment
}

internal object TemplateScanner {
    fun hasCode(text: String): Boolean = text.contains("{{")

    /** Splits [text] into literal and `{{ }}` parts. Quote aware: `}}` inside a string does not close. */
    fun scan(text: String): List<RawSegment> {
        val out = ArrayList<RawSegment>()
        var i = 0
        var textStart = 0
        while (true) {
            val open = text.indexOf("{{", i)
            if (open < 0) break
            // Triple braces `{{{ ... }}}` are accepted as an alias of `{{ ... }}`.
            var openLen = 2
            var close = -1
            if (text.startsWith("{{{", open)) {
                close = findClose(text, open + 3, 3)
                if (close >= 0) openLen = 3
            }
            if (close < 0) close = findClose(text, open + 2, 2).also { openLen = 2 }
            if (open > textStart) out += RawSegment.Text(textStart, open)
            if (close < 0) {
                out += RawSegment.Unterminated(open)
                return out
            }
            val closeLen = openLen
            out += RawSegment.Code(open, close + closeLen, open + openLen, close)
            i = close + closeLen
            textStart = i
        }
        if (textStart < text.length) out += RawSegment.Text(textStart, text.length)
        return out
    }

    /** Index of the closing `}}` (or `}}}`) for a body that starts at [from], or -1. */
    private fun findClose(s: String, from: Int, closeLen: Int): Int {
        var depth = 0
        var j = from
        val closer = "}".repeat(closeLen)
        while (j < s.length) {
            val c = s[j]
            when (c) {
                '\'', '"', '`' -> {
                    val n = Scan.skipString(s, j, s.length)
                    if (n < 0) return -1
                    j = n
                    continue
                }
                '{' -> depth++
                '}' -> {
                    if (depth == 0 && s.startsWith(closer, j)) return j
                    if (depth > 0) depth--
                }
            }
            j++
        }
        return -1
    }
}
