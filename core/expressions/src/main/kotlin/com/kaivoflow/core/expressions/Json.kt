package com.kaivoflow.core.expressions

/** Minimal strict JSON reader for `JSON.parse`. Numbers become Double. */
internal object JsonText {
    class JsonError(message: String) : IllegalArgumentException(message)

    fun parse(text: String, maxDepth: Int = 64): Any? {
        val p = Reader(text, maxDepth)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i < text.length) throw JsonError("unexpected '${text[p.i]}' at character ${p.i}")
        return v
    }

    private class Reader(val s: String, val maxDepth: Int) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(depth: Int): Any? {
            if (depth > maxDepth) throw JsonError("nested deeper than $maxDepth")
            if (i >= s.length) throw JsonError("unexpected end of text")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonError("unexpected '$c' at character $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonError("unexpected token at character $i")
            i += word.length
            return v
        }

        fun num(): Double {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(st, i).toDoubleOrNull() ?: throw JsonError("invalid number at character $st")
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonError("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonError("unterminated string")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            '/', '\\', '"' -> sb.append(e)
                            'u' -> {
                                if (i + 4 > s.length) throw JsonError("bad unicode escape")
                                sb.append((s.substring(i, i + 4).toIntOrNull(16) ?: throw JsonError("bad unicode escape")).toChar())
                                i += 4
                            }
                            else -> throw JsonError("bad escape '\\$e' at character ${i - 1}")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws()
                out += value(depth + 1)
                ws()
                if (i >= s.length) throw JsonError("unterminated array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return out }
                throw JsonError("expected ',' or ']' at character $i")
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw JsonError("expected a string key at character $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw JsonError("expected ':' at character $i")
                i++
                ws()
                out[k] = value(depth + 1)
                ws()
                if (i >= s.length) throw JsonError("unterminated object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return out }
                throw JsonError("expected ',' or '}' at character $i")
            }
        }
    }
}
