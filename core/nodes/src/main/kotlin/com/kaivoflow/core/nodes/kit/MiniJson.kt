package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.Values

/**
 * A deliberately small, strict JSON reader for the few places a Step has to read a JSON string
 * that did not come from the HTTP gateway (saved tables, token payloads, local notes).
 *
 * It accepts exactly the JSON data model KaivoFlow already uses: null, booleans, Doubles,
 * Strings, Lists and Maps. It has a depth and size guard so an imported file cannot exhaust a Run.
 */
internal object MiniJson {
    private const val MAX_CHARS = 2_000_000
    private const val MAX_DEPTH = 32

    fun parse(text: String): Any? = runCatching {
        if (text.length > MAX_CHARS) return null
        Parser(text).value(0)
    }.getOrNull()

    fun stringMap(text: String): Map<String, String> {
        val parsed = parse(text) ?: return emptyMap()
        return Values.asMap(parsed).mapValues { (_, value) -> Values.asString(value) }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("nested too deeply")
            skip()
            if (i >= s.length) throw IllegalArgumentException("unexpected end")
            return when (s[i]) {
                '{' -> obj(depth + 1)
                '[' -> array(depth + 1)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skip()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (i < s.length) {
                skip()
                if (s[i] != '"') throw IllegalArgumentException("expected a key")
                val key = string()
                skip()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("expected ':'")
                i++
                out[key] = value(depth)
                skip()
                if (i >= s.length) throw IllegalArgumentException("unexpected end")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected ',' or '}'")
                }
            }
            throw IllegalArgumentException("unclosed object")
        }

        private fun array(depth: Int): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skip()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (i < s.length) {
                out += value(depth)
                skip()
                if (i >= s.length) throw IllegalArgumentException("unexpected end")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected ',' or ']'")
                }
            }
            throw IllegalArgumentException("unclosed array")
        }

        private fun string(): String {
            if (s[i] != '"') throw IllegalArgumentException("expected a quote")
            i++
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("bad escape")
                        when (s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("bad unicode escape")
                                val hex = s.substring(i, i + 4)
                                i += 4
                                out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            }
                            else -> throw IllegalArgumentException("bad escape")
                        }
                    }
                    c.code < 0x20 -> throw IllegalArgumentException("control character")
                    else -> out.append(c)
                }
            }
            throw IllegalArgumentException("unclosed string")
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("bad literal")
            i += word.length
            return result
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && s[i] in "-+.eE0123456789") i++
            return s.substring(start, i).toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number")
        }

        private fun skip() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }
    }
}
