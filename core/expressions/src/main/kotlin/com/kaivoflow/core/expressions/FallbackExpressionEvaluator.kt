package com.kaivoflow.core.expressions

import com.kaivoflow.core.engine.ExpressionEvaluator
import com.kaivoflow.core.engine.Values

/**
 * Pure-Kotlin expression support.
 *
 * Two jobs:
 *  1. A real implementation for the (rare) devices where the QuickJS engine cannot load —
 *     expression support degrades instead of the whole app breaking.
 *  2. The implementation used by JVM unit tests, so expression plumbing is covered without
 *     needing a device or Robolectric.
 *
 * Grammar (deliberately small, parsed by hand rather than by regex so it is easy to reason about):
 *
 *   expression := root ( '.' name | '[' index-or-key ']' )*
 *               | '$' name '(' args ')' ( '.' name )?
 *               | literal
 *   root       := ${'$'}json | ${'$'}vars | ${'$'}items | ${'$'}item | $index | ${'$'}now | ${'$'}today | $run
 *
 * Anything richer (arithmetic, method calls, templates) requires the JS engine and raises an
 * actionable error instead of silently returning nothing.
 */
class FallbackExpressionEvaluator : ExpressionEvaluator {

    override fun evaluate(code: String, scope: Map<String, Any?>): Any? {
        val expression = code.trim()
        if (expression.isEmpty()) return null

        if (expression.startsWith("$")) return evaluateDollar(expression, scope)

        expression.toDoubleOrNull()?.let { return it }
        return when (expression) {
            "true" -> true
            "false" -> false
            "null", "undefined" -> null
            else -> if (expression.length >= 2 &&
                ((expression.first() == '"' && expression.last() == '"') ||
                    (expression.first() == '\'' && expression.last() == '\''))
            ) {
                expression.substring(1, expression.length - 1)
            } else {
                throw ExpressionUnsupportedException(code)
            }
        }
    }

    private fun evaluateDollar(expression: String, scope: Map<String, Any?>): Any? {
        val cursor = Cursor(expression, 1) // skip '$'
        cursor.skipSpaces()
        val name = cursor.readIdentifier()
            ?: throw ExpressionUnsupportedException(expression)

        // Function-style helpers.
        cursor.skipSpaces()
        if (cursor.peek() == '(') {
            cursor.advance() // consume '('
            val args = cursor.readArguments(scope) { code, sc -> evaluate(code, sc) }
            when (name) {
                "step" -> return stepLookup(args, cursor, scope)
                "count" -> return args.firstOrNull()?.let { Values.asList(it).size }
                    ?: Values.asList(scope["items"]).size
                "length" -> return args.firstOrNull().let { lengthOf(it ?: Values.asList(scope["items"])) }
                "first" -> return Values.asList(args.firstOrNull() ?: scope["items"]).firstOrNull()
                "last" -> return Values.asList(args.firstOrNull() ?: scope["items"]).lastOrNull()
                "all" -> return Values.asList(scope["items"])
                "now" -> return scope["now"]
                "today" -> return scope["today"]
                else -> throw ExpressionUnsupportedException(expression)
            }
        }

        val root = scope[name] ?: return null
        cursor.skipSpaces()
        // `${'$'}length` used bare, e.g. {{ ${'$'}length }}
        if (name == "length" && cursor.atEnd()) return lengthOf(Values.asList(scope["items"]))
        return readPath(root, cursor, expression)
    }

    private fun stepLookup(args: List<Any?>, cursor: Cursor, scope: Map<String, Any?>): Any? {
        val stepName = Values.asString(args.firstOrNull())
        val raw = Values.asMap(scope["step"])[stepName] ?: return null
        val items = Values.asList(raw)
        val explicitField = args.getOrNull(1)?.let { Values.asString(it) }
        if (explicitField != null) return items.map { Values.deepGet(it, explicitField) }
        val base: Any? = items.singleOrNull() ?: items
        return readPath(base, cursor, cursor.source)
    }

    private fun readPath(root: Any?, cursor: Cursor, expression: String): Any? {
        var current = root
        while (!cursor.atEnd()) {
            cursor.skipSpaces()
            if (cursor.atEnd()) break
            current = when (cursor.peek()) {
                '.' -> {
                    cursor.advance()
                    cursor.skipSpaces()
                    val key = cursor.readIdentifier() ?: throw ExpressionUnsupportedException(expression)
                    Values.deepGet(current, key)
                }
                '[' -> {
                    cursor.advance()
                    cursor.skipSpaces()
                    val key = cursor.readIndexOrKey()
                    cursor.skipSpaces()
                    cursor.expect(']')
                    Values.deepGet(current, key)
                }
                else -> throw ExpressionUnsupportedException(expression)
            }
            if (current == null) {
                // Consume the rest of the path so the cursor invariant holds, then stop.
                while (!cursor.atEnd()) {
                    cursor.skipSpaces()
                    if (cursor.atEnd()) break
                    if (cursor.peek() == '.') {
                        cursor.advance()
                        cursor.skipSpaces()
                        cursor.readIdentifier()
                    } else if (cursor.peek() == '[') {
                        cursor.advance()
                        cursor.skipSpaces()
                        cursor.readIndexOrKey()
                        cursor.skipSpaces()
                        cursor.expect(']')
                    } else break
                }
                return null
            }
        }
        return current
    }

    private fun lengthOf(value: Any?): Int = when (value) {
        is String -> value.length
        is Collection<*> -> value.size
        is Map<*, *> -> value.size
        null -> 0
        else -> 1
    }

    /** Minimal cursor over the expression text. */
    private class Cursor(val source: String, start: Int = 0) {
        var index = start

        fun atEnd(): Boolean = index >= source.length
        fun peek(): Char? = if (atEnd()) null else source[index]
        fun advance() { index++ }
        fun expect(c: Char) {
            skipSpaces()
            if (peek() != c) throw ExpressionUnsupportedException(source)
            index++
        }

        fun readIdentifier(): String? {
            skipSpaces()
            val start = index
            while (!atEnd() && (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '$')) index++
            return if (index > start) source.substring(start, index) else null
        }

        fun readIndexOrKey(): String {
            skipSpaces()
            val quote = peek()
            if (quote == '\'' || quote == '"') {
                index++
                val start = index
                while (!atEnd() && source[index] != quote) index++
                val value = source.substring(start, index)
                if (!atEnd()) index++ // closing quote
                return value
            }
            val start = index
            while (!atEnd() && (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '-' || source[index] == '.')) index++
            return source.substring(start, index)
        }

        fun readArguments(scope: Map<String, Any?>, resolve: (String, Map<String, Any?>) -> Any?): List<Any?> {
            val args = mutableListOf<Any?>()
            skipSpaces()
            if (peek() == ')') { index++; return args }
            while (!atEnd()) {
                skipSpaces()
                val quote = peek()
                if (quote == '\'' || quote == '"') {
                    index++
                    val start = index
                    while (!atEnd() && source[index] != quote) index++
                    args += source.substring(start, index)
                    if (!atEnd()) index++ // closing quote
                } else {
                    // Bare argument: either a number, a nested $path, or a plain word.
                    val start = index
                    var depth = 0
                    while (!atEnd()) {
                        val c = source[index]
                        if (c == ')' && depth == 0) break
                        if (c == '(') depth++
                        if (c == ')') depth--
                        index++
                    }
                    var token = source.substring(start, index).trim()
                    if (token.startsWith("$")) {
                        args += resolve(token, scope)
                    } else if (token.equals("true", true)) args += true
                    else if (token.equals("false", true)) args += false
                    else args += token.toDoubleOrNull() ?: token
                    token = ""
                }
                skipSpaces()
                if (peek() == ',') { index++; continue }
                if (peek() == ')') { index++; break }
                if (atEnd()) break
                throw ExpressionUnsupportedException(source)
            }
            return args
        }

        fun skipSpaces() { while (!atEnd() && source[index].isWhitespace()) index++ }
    }
}

class ExpressionUnsupportedException(code: String) : IllegalStateException(
    "This expression needs the JavaScript engine, which is unavailable on this device: $code"
)
