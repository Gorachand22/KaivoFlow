package com.kaivoflow.core.expressions

import com.kaivoflow.core.engine.Values
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor

/**
 * Date value used inside an expression. It never leaves the evaluator: results are converted to
 * ISO-8601 text (see [iso]) so the engine's plain JSON data model stays plain.
 */
internal class KDate(val instant: Instant, val zone: ZoneId) {
    val zdt: ZonedDateTime get() = instant.atZone(zone)
    fun iso(): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt)
    override fun equals(other: Any?) = other is KDate && other.instant == instant
    override fun hashCode() = instant.hashCode()
    override fun toString() = iso()
}

/**
 * A view of a Step's output items returned by `$step("Name")` and `$input`.
 * Members: `items`, `first`, `last`, `count`, `length`. For compatibility with older Flows a
 * Stream holding exactly one item also exposes that item's own fields, and the item's fields win
 * over the member names. As a final result a one-item stream collapses to the item.
 */
internal class StepStream(val items: List<Any?>) {
    fun collapsed(): Any? = if (items.size == 1) items[0] else items
}

internal class Lambda(val params: List<String>, val body: Expr, val env: Env?, val src: SourceText)

internal class Namespace(val name: String)

internal class Env(val names: List<String>, val values: Array<Any?>, val parent: Env?) {
    fun lookup(name: String): Pair<Boolean, Any?> {
        var e: Env? = this
        while (e != null) {
            val i = e.names.indexOf(name)
            if (i >= 0) return true to e.values[i]
            e = e.parent
        }
        return false to null
    }

    fun allNames(): List<String> {
        val out = ArrayList<String>()
        var e: Env? = this
        while (e != null) { out += e.names; e = e.parent }
        return out
    }
}

/** Value semantics shared by the interpreter and the library. */
internal object Rt {
    fun typeName(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> "boolean"
        is Number -> "number"
        is String -> "string"
        is KDate -> "date"
        is StepStream -> "array"
        is Lambda -> "function"
        is Map<*, *> -> "object"
        is Collection<*>, is Array<*> -> "array"
        else -> "object"
    }

    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
        is String -> v.isNotEmpty()
        else -> true
    }

    fun fmtNum(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d == floor(d) && abs(d) < 1e15) return d.toLong().toString()
        val s = d.toString()
        return if (s.contains('E') || s.contains('e')) BigDecimal.valueOf(d).stripTrailingZeros().toPlainString() else s
    }

    fun str(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Boolean -> if (v) "true" else "false"
        is Double -> fmtNum(v)
        is Float -> fmtNum(v.toDouble())
        is BigDecimal -> v.toPlainString()
        is Number -> v.toString()
        is KDate -> v.iso()
        is StepStream -> str(v.collapsed())
        is Lambda -> "[function]"
        is Collection<*>, is Map<*, *>, is Array<*> -> Values.stringify(plain(v))
        else -> v.toString()
    }

    fun isNumber(v: Any?) = v is Number

    /** Number from a number or numeric string; null otherwise. */
    private val NUMERIC = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")

    /** Number from a number or numeric string; null otherwise. */
    fun toNumberOrNull(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().let { t -> if (NUMERIC.matches(t)) t.toDoubleOrNull()?.takeIf { it.isFinite() } else null }
        else -> null
    }

    fun eq(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        if (a is StepStream) return eq(a.collapsed(), b)
        if (b is StepStream) return eq(a, b.collapsed())
        if (a is KDate && b is KDate) return a.instant == b.instant
        if (a is Map<*, *> && b is Map<*, *>) {
            if (a.size != b.size) return false
            for ((k, v) in a) {
                if (!b.containsKey(k)) return false
                if (!eq(v, b[k])) return false
            }
            return true
        }
        if (a is List<*> && b is List<*>) {
            if (a.size != b.size) return false
            for (i in a.indices) if (!eq(a[i], b[i])) return false
            return true
        }
        return a == b
    }

    private fun rank(v: Any?): Int = when (v) {
        null -> 9
        is Boolean -> 1
        is Number -> 2
        is String -> 3
        is KDate -> 4
        else -> 5
    }

    /** Total order used by sort: numbers by value, text lexicographically, nulls last. */
    fun compareForSort(a: Any?, b: Any?): Int {
        if (a is Number && b is Number) return a.toDouble().compareTo(b.toDouble())
        if (a is String && b is String) return a.compareTo(b)
        if (a is Boolean && b is Boolean) return a.compareTo(b)
        if (a is KDate && b is KDate) return a.instant.compareTo(b.instant)
        val ra = rank(a)
        val rb = rank(b)
        if (ra != rb) return ra.compareTo(rb)
        return str(a).compareTo(str(b))
    }

    /** Hash key that treats 1 and 1.0 alike (for unique). */
    fun norm(v: Any?): Any? = when (v) {
        is Number -> v.toDouble()
        is KDate -> v.instant
        else -> v
    }

    /** Converts evaluator-internal values (dates, streams) to plain data. Fails on functions. */
    fun plain(v: Any?, onLambda: () -> Nothing = { throw ExpressionException("A function cannot be used as a value", ErrorKind.Type) }): Any? = when (v) {
        null, is String, is Boolean, is Double -> v
        is Number -> v
        is KDate -> v.iso()
        is StepStream -> plain(v.collapsed(), onLambda)
        is Lambda -> onLambda()
        is Namespace -> "[${v.name}]"
        is Map<*, *> -> {
            var changed = false
            val out = LinkedHashMap<String, Any?>(v.size * 2)
            for ((k, x) in v) {
                val p = plain(x, onLambda)
                if (p !== x) changed = true
                out[k.toString()] = p
            }
            if (changed) out else @Suppress("UNCHECKED_CAST") (v as Map<String, Any?>)
        }
        is List<*> -> {
            var changed = false
            val out = ArrayList<Any?>(v.size)
            for (x in v) {
                val p = plain(x, onLambda)
                if (p !== x) changed = true
                out += p
            }
            if (changed) out else v
        }
        is Array<*> -> v.map { plain(it, onLambda) }
        else -> v
    }
}
