package com.kaivoflow.core.expressions

enum class TokenKind { Number, String, Template, Identifier, Variable, Keyword, Operator, Punct, End }

/** Piece of a backtick template literal: either [text] or an embedded expression at [span]. */
data class TemplatePart(val text: String?, val span: Span?)

/**
 * One lexical token with its span in the original text (absolute offsets, also inside `{{ }}`).
 * [text] is the raw source for operators and punctuation and the normalised name for identifiers
 * and variables; [literal] holds the decoded value of string and number tokens.
 */
class Token(
    val kind: TokenKind,
    val text: String,
    val span: Span,
    val literal: Any? = null,
    val parts: List<TemplatePart>? = null,
) {
    override fun toString(): String = "$kind($text)@${span.start}"
}

internal object Scan {
    /** `s[i]` is a quote character; returns the index after the closing quote, or -1 if unterminated. */
    fun skipString(s: String, i: Int, end: Int): Int {
        val q = s[i]
        var j = i + 1
        while (j < end) {
            val c = s[j]
            if (c == '\\') { j += 2; continue }
            if (c == q) return j + 1
            if (q == '`' && c == '$' && j + 1 < end && s[j + 1] == '{') {
                val close = skipBraces(s, j + 2, end)
                if (close < 0) return -1
                j = close + 1
                continue
            }
            j++
        }
        return -1
    }

    /** [from] is just after an opening brace; returns the index of the matching `}` or -1. */
    fun skipBraces(s: String, from: Int, end: Int): Int {
        var depth = 0
        var j = from
        while (j < end) {
            when (s[j]) {
                '\'', '"', '`' -> {
                    val n = skipString(s, j, end)
                    if (n < 0) return -1
                    j = n
                    continue
                }
                '{' -> depth++
                '}' -> if (depth == 0) return j else depth--
            }
            j++
        }
        return -1
    }
}

internal class Lexer(private val src: SourceText, private val start: Int = 0, private val end: Int = src.text.length) {
    private val s = src.text
    private var i = start
    val tokens = ArrayList<Token>()
    var error: ExpressionException? = null
        private set

    fun run(): Lexer {
        try {
            loop()
        } catch (e: ExpressionException) {
            error = e
        }
        tokens += Token(TokenKind.End, "", Span(end, end))
        return this
    }

    private fun fail(from: Int, to: Int, message: String, suggestion: String? = null): Nothing =
        throw src.error(Span(from, maxOf(to, from + 1)), ErrorKind.Syntax, message, suggestion)

    private fun loop() {
        while (i < end) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() -> number()
                c == '"' || c == '\'' -> string(c)
                c == '`' -> templateLiteral()
                c == '$' -> variable()
                c.isLetter() || c == '_' -> identifier()
                else -> operator(c)
            }
        }
    }

    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun number() {
        val from = i
        while (i < end && s[i].isDigit()) i++
        if (i + 1 < end && s[i] == '.' && s[i + 1].isDigit()) {
            i++
            while (i < end && s[i].isDigit()) i++
        }
        if (i < end && (s[i] == 'e' || s[i] == 'E')) {
            var j = i + 1
            if (j < end && (s[j] == '+' || s[j] == '-')) j++
            if (j < end && s[j].isDigit()) {
                while (j < end && s[j].isDigit()) j++
                i = j
            }
        }
        val text = s.substring(from, i)
        val value = text.toDoubleOrNull() ?: fail(from, i, "Invalid number '$text'")
        if (i < end && (s[i].isLetter() || s[i] == '_')) fail(from, i + 1, "Invalid number: letters directly after '$text'")
        tokens += Token(TokenKind.Number, text, Span(from, i), value)
    }

    private fun readEscape(sb: StringBuilder, quoteStart: Int) {
        // s[i] == '\\'
        if (i + 1 >= end) fail(quoteStart, end, "Unterminated string")
        val e = s[i + 1]
        i += 2
        when (e) {
            'n' -> sb.append('\n')
            't' -> sb.append('\t')
            'r' -> sb.append('\r')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000c')
            '0' -> sb.append('\u0000')
            'u' -> {
                if (i + 4 > end) fail(i - 2, end, "Invalid unicode escape")
                val hex = s.substring(i, i + 4)
                val code = hex.toIntOrNull(16) ?: fail(i - 2, i + 4, "Invalid unicode escape '\\u$hex'")
                sb.append(code.toChar())
                i += 4
            }
            else -> sb.append(e)
        }
    }

    private fun string(q: Char) {
        val from = i
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= end) fail(from, from + 1, "Unterminated string, missing closing $q")
            val c = s[i]
            if (c == '\\') { readEscape(sb, from); continue }
            if (c == q) { i++; break }
            sb.append(c)
            i++
        }
        tokens += Token(TokenKind.String, s.substring(from, i), Span(from, i), sb.toString())
    }

    private fun templateLiteral() {
        val from = i
        i++
        val parts = ArrayList<TemplatePart>()
        val sb = StringBuilder()
        while (true) {
            if (i >= end) fail(from, from + 1, "Unterminated template string, missing closing `")
            val c = s[i]
            when {
                c == '\\' -> readEscape(sb, from)
                c == '`' -> { i++; break }
                c == '$' && i + 1 < end && s[i + 1] == '{' -> {
                    val close = Scan.skipBraces(s, i + 2, end)
                    if (close < 0) fail(i, i + 2, "Unterminated \${ in template string")
                    if (sb.isNotEmpty()) { parts += TemplatePart(sb.toString(), null); sb.clear() }
                    parts += TemplatePart(null, Span(i + 2, close))
                    i = close + 1
                }
                else -> { sb.append(c); i++ }
            }
        }
        if (sb.isNotEmpty()) parts += TemplatePart(sb.toString(), null)
        tokens += Token(TokenKind.Template, s.substring(from, i), Span(from, i), parts = parts)
    }

    private fun variable() {
        val from = i
        i++
        var j = i
        if (j < end && isIdentChar(s[j])) {
            while (j < end && isIdentChar(s[j])) j++
        } else {
            // tolerate "$ json" (whitespace between the dollar sign and the name)
            var k = j
            while (k < end && s[k].isWhitespace()) k++
            if (k > j && k < end && (s[k].isLetter() || s[k] == '_')) {
                j = k
                while (j < end && isIdentChar(s[j])) j++
                val name = "$" + s.substring(k, j)
                i = j
                tokens += Token(TokenKind.Variable, name, Span(from, j))
                return
            }
            fail(from, i, "Unexpected '$': a variable name must follow, like \$json")
        }
        val name = s.substring(from, j)
        i = j
        tokens += Token(TokenKind.Variable, name, Span(from, j))
    }

    private fun identifier() {
        val from = i
        while (i < end && isIdentChar(s[i])) i++
        val text = s.substring(from, i)
        val kind = if (text == "true" || text == "false" || text == "null" || text == "undefined") TokenKind.Keyword else TokenKind.Identifier
        tokens += Token(kind, text, Span(from, i))
    }

    private fun operator(c: Char) {
        val from = i
        fun at(k: Int): Char = if (i + k < end) s[i + k] else '\u0000'
        val three = if (i + 3 <= end) s.substring(i, i + 3) else ""
        val two = if (i + 2 <= end) s.substring(i, i + 2) else ""
        val text = when {
            three == "===" || three == "!==" -> three
            two == "**" || two == "==" || two == "!=" || two == "<=" || two == ">=" ||
                two == "&&" || two == "||" || two == "??" || two == "=>" -> two
            two == "?." && !at(2).isDigit() -> two
            c in "+-*/%<>!?:,.()[]{}" -> c.toString()
            c == '=' -> fail(from, from + 1, "Unexpected '='. Use == to compare values; assignment is not supported")
            c == '&' -> fail(from, from + 1, "Unexpected '&'. Use && for logical and")
            c == '|' -> fail(from, from + 1, "Unexpected '|'. Use || for logical or")
            c == ';' -> fail(from, from + 1, "Unexpected ';'. An expression is a single statement without semicolons")
            else -> fail(from, from + 1, "Unexpected character '$c'")
        }
        i += text.length
        val kind = if (text in "()[]{},.:" ) TokenKind.Punct else TokenKind.Operator
        tokens += Token(kind, text, Span(from, i))
    }
}
