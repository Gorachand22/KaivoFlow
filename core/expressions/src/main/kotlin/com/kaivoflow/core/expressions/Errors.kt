package com.kaivoflow.core.expressions

/** Broad class of an expression failure, so the UI can pick an icon or hint. */
enum class ErrorKind {
    /** The text is not valid expression syntax. */
    Syntax,

    /** A variable, function, method, unit or (strict mode) property does not exist. */
    Unknown,

    /** A value had the wrong type for an operator or function. */
    Type,

    /** A configured size, depth or step limit was exceeded. */
    Limit,

    /** The wall-clock deadline passed. */
    Timeout,

    /** Anything else that went wrong while evaluating (division by zero, bad date, ...). */
    Runtime,
}

/** Half-open character range `[start, end)` in the original text. */
data class Span(val start: Int, val end: Int) {
    val length: Int get() = end - start
    fun cover(other: Span): Span = Span(minOf(start, other.start), maxOf(end, other.end))
}

/** Location of a problem. [line] and [column] are 1-based; [offset] is 0-based. */
data class Position(val line: Int, val column: Int, val offset: Int, val length: Int = 0)

/**
 * The only exception type the expression engine throws on purpose.
 *
 * [detail] is the plain message, [suggestion] a did-you-mean candidate when one exists, and
 * [message] the combined human readable text (detail, line and column, suggestion).
 */
class ExpressionException(
    val detail: String,
    val kind: ErrorKind = ErrorKind.Runtime,
    val position: Position? = null,
    val suggestion: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(compose(detail, position, suggestion), cause) {
    companion object {
        private fun compose(detail: String, position: Position?, suggestion: String?): String {
            val sb = StringBuilder(detail)
            if (position != null) sb.append(" (line ").append(position.line).append(", column ").append(position.column).append(')')
            if (suggestion != null) sb.append(". Did you mean '").append(suggestion).append("'?")
            return sb.toString()
        }
    }
}

/** Maps offsets to line and column. */
internal class SourceText(val text: String) {
    private val lineStarts: IntArray by lazy {
        val starts = ArrayList<Int>()
        starts += 0
        for (i in text.indices) if (text[i] == '\n') starts += i + 1
        starts.toIntArray()
    }

    fun position(span: Span): Position {
        val offset = span.start.coerceIn(0, text.length)
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return Position(lo + 1, offset - lineStarts[lo] + 1, offset, span.length)
    }

    fun error(span: Span, kind: ErrorKind, detail: String, suggestion: String? = null): ExpressionException =
        ExpressionException(detail, kind, position(span), suggestion)
}

/** Best did-you-mean candidate for [name], or null when nothing is close. */
internal fun suggest(name: String, candidates: Collection<String>): String? {
    if (name.isEmpty()) return null
    val n = name.lowercase()
    var best: String? = null
    var bestDistance = Int.MAX_VALUE
    for (candidate in candidates) {
        if (candidate == name) continue
        val c = candidate.lowercase()
        var d = levenshtein(n, c)
        if (n.length >= 3 && c.startsWith(n)) d = minOf(d, 1)
        if (d < bestDistance || (d == bestDistance && best != null && candidate < best)) {
            bestDistance = d
            best = candidate
        }
    }
    val limit = if (n.length <= 3) 1 else maxOf(2, n.length / 3)
    return if (best != null && bestDistance <= limit) best else null
}

/** Edit distance where an adjacent swap counts as one edit ("jsno" vs "json"). */
private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
            d[i][j] = v
        }
    }
    return d[a.length][b.length]
}

/** Configurable safety limits. Every field has a safe default. */
data class ExpressionLimits(
    /** Maximum characters in one expression body (the text inside `{{ }}` or a bare expression). */
    val maxExpressionLength: Int = 10_000,
    /** Maximum nesting depth of the syntax tree. */
    val maxAstDepth: Int = 128,
    /** Maximum number of syntax tree nodes in one expression. */
    val maxAstNodes: Int = 5_000,
    /** Maximum evaluation steps (nodes visited plus elements processed by library functions). */
    val maxSteps: Long = 200_000,
    /** Maximum nested calls of arrow functions. */
    val maxCallDepth: Int = 24,
    /** Maximum characters in any string the expression builds, and in a whole template result. */
    val maxStringLength: Int = 1_000_000,
    /** Maximum elements in any array the expression builds, or keys in an object. */
    val maxCollectionSize: Int = 100_000,
    /** Wall-clock budget for one evaluate call. */
    val timeoutMillis: Long = 1_000,
) {
    companion object {
        val Default = ExpressionLimits()

        /** Tighter budget for live previews while typing. */
        val Preview = ExpressionLimits(
            maxExpressionLength = 4_000,
            maxSteps = 20_000,
            maxStringLength = 100_000,
            maxCollectionSize = 10_000,
            timeoutMillis = 50,
        )
    }
}
