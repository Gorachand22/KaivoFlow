package com.kaivoflow.core.model

/**
 * A small strict JSON reader and writer over plain Kotlin values (null, Boolean, Long, Double,
 * String, List, Map). It lives in `:core:model` so the parameter codec and validator stay free of
 * any serialization library. Maps keep insertion order, so a written value reads back byte-stable.
 */
object ParamJson {
    private const val MAX_DEPTH = 64

    /** Where a parse failed; [line] and [column] start at 1. */
    data class Error(val message: String, val line: Int, val column: Int)

    sealed interface Parsed {
        data class Ok(val value: Any?) : Parsed
        data class Failed(val error: Error) : Parsed
    }

    fun parse(text: String): Parsed = try {
        val p = Reader(text)
        val v = p.value(0)
        p.skip()
        if (!p.atEnd()) p.fail("unexpected text after the value")
        Parsed.Ok(v)
    } catch (e: Failure) {
        Parsed.Failed(e.error)
    }

    /** Parses or throws [IllegalArgumentException] with line and column. */
    fun parseOrThrow(text: String): Any? = when (val r = parse(text)) {
        is Parsed.Ok -> r.value
        is Parsed.Failed -> throw IllegalArgumentException("${r.error.message} (line ${r.error.line}, column ${r.error.column})")
    }

    /** Syntax only. `{{ ... }}` regions are swapped for `null` first, so a template can be checked at design time. */
    fun checkTemplate(text: String): Error? {
        val neutral = EXPR.replace(text) { "null".padEnd(it.value.length) }
        return (parse(neutral) as? Parsed.Failed)?.error
    }

    fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float, is Double -> {
                val d = (v as Number).toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(d.toString())
            }
            is Number -> sb.append(v.toString())
            is String -> quote(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) { if (!first) sb.append(','); first = false; write(sb, x) }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private val EXPR = Regex("""\{\{[\s\S]*?\}\}""")

    private class Failure(val error: Error) : RuntimeException(null, null, false, false)

    private class Reader(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun fail(msg: String): Nothing {
            var line = 1
            var col = 1
            for (k in 0 until minOf(i, s.length)) { if (s[k] == '\n') { line++; col = 1 } else col++ }
            throw Failure(Error(msg, line, col))
        }

        fun skip() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nested too deeply")
            skip()
            if (i >= s.length) fail("unexpected end of text")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("unexpected character '${s[i]}'")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skip()
                if (i >= s.length) fail("unexpected end of text")
                if (s[i] != '"') fail("expected a quoted key")
                val k = str()
                skip()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                out[k] = value(depth + 1)
                skip()
                if (i >= s.length) fail("unexpected end of text")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skip()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out += value(depth + 1)
                skip()
                if (i >= s.length) fail("unexpected end of text")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("unterminated string")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad unicode escape")
                                sb.append(code.toChar()); i += 4
                            }
                            else -> { i--; fail("bad escape '\\$e'") }
                        }
                    }
                    c < ' ' -> { i--; fail("control character in string") }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            val digits = i
            while (i < s.length && s[i].isDigit()) i++
            if (i == digits) { i = start; fail("unexpected character '${s.getOrNull(start) ?: ' '}'") }
            if (s[digits] == '0' && i - digits > 1) { i = start; fail("leading zero in number") }
            var integral = true
            if (i < s.length && s[i] == '.') {
                integral = false; i++
                val f = i
                while (i < s.length && s[i].isDigit()) i++
                if (i == f) fail("digits expected after '.'")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integral = false; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val f = i
                while (i < s.length && s[i].isDigit()) i++
                if (i == f) fail("digits expected in exponent")
            }
            val text = s.substring(start, i)
            return if (integral) text.toLongOrNull() ?: text.toDouble() else text.toDouble()
        }
    }
}
