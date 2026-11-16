package com.kaivoflow.core.nodes

/** A tiny JSON parser so parse Steps do not depend on Android or kotlinx. */
internal object JsonLite {
    /** Strict JSON reader with bounds. Null represents either JSON null or a parse failure. */
    fun parse(text: String): Any? {
        if (text.length > 1_000_000) return null
        return runCatching {
            val parser = Parser(text)
            val value = parser.parseValue(0)
            parser.skipWhitespace()
            if (!parser.atEnd()) throw IllegalArgumentException("Trailing JSON content")
            value
        }.getOrNull()
    }

    private class Parser(private val text: String) {
        private var i = 0
        fun atEnd() = i >= text.length
        fun skipWhitespace() { while (!atEnd() && text[i] in " \t\r\n") i++ }
        fun parseValue(depth: Int): Any? {
            require(depth <= 64) { "JSON is nested too deeply" }
            skipWhitespace()
            require(!atEnd()) { "Expected a JSON value" }
            return when (text[i]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }
        private fun expect(word: String) {
            require(text.startsWith(word, i)) { "Invalid literal" }; i += word.length
        }
        private fun parseObject(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[i] == '}') { i++; return out }
            while (true) {
                skipWhitespace()
                require(!atEnd() && text[i] == '"') { "Expected object key" }
                val key = parseString()
                require(!out.containsKey(key)) { "Duplicate object key" }
                skipWhitespace()
                require(!atEnd() && text[i] == ':') { "Expected colon" }; i++
                out[key] = parseValue(depth)
                skipWhitespace()
                require(!atEnd()) { "Unclosed object" }
                when (text[i++]) {
                    '}' -> return out
                    ',' -> Unit
                    else -> throw IllegalArgumentException("Expected comma")
                }
            }
        }
        private fun parseArray(depth: Int): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            skipWhitespace()
            if (!atEnd() && text[i] == ']') { i++; return out }
            while (true) {
                out.add(parseValue(depth))
                skipWhitespace()
                require(!atEnd()) { "Unclosed array" }
                when (text[i++]) {
                    ']' -> return out
                    ',' -> Unit
                    else -> throw IllegalArgumentException("Expected comma")
                }
            }
        }
        private fun parseString(): String {
            require(!atEnd() && text[i] == '"') { "Expected string" }; i++
            val out = StringBuilder()
            while (!atEnd()) {
                val c = text[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        require(!atEnd()) { "Incomplete escape" }
                        when (val escaped = text[i++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                require(i + 4 <= text.length) { "Incomplete unicode escape" }
                                val code = text.substring(i, i + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid unicode escape")
                                out.append(code.toChar()); i += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape")
                        }
                    }
                    c.code < 0x20 -> throw IllegalArgumentException("Control character in string")
                    else -> out.append(c)
                }
            }
            throw IllegalArgumentException("Unclosed string")
        }
        private fun parseNumber(): Double {
            val match = NUMBER.find(text, i)?.takeIf { it.range.first == i }
                ?: throw IllegalArgumentException("Invalid number")
            i = match.range.last + 1
            val value = match.value.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number")
            require(value.isFinite()) { "Number out of range" }
            return value
        }
    }
    private val NUMBER = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")
}
