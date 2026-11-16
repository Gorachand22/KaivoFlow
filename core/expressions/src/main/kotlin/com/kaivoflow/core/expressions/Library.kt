package com.kaivoflow.core.expressions

import com.kaivoflow.core.engine.Values
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

internal class Fn(val min: Int, val max: Int, val impl: (CallCtx, Any?, List<Any?>) -> Any?)

/** Helper handed to every library function: argument coercion, errors and budget accounting. */
internal class CallCtx(val ip: Interp, val span: Span, val name: String) {
    val st: EvalState get() = ip.st

    fun fail(msg: String, kind: ErrorKind = ErrorKind.Type): Nothing = throw ip.err(span, kind, "$name(): $msg")

    fun tick(n: Int = 1) = ip.tick(span, n)

    fun str(s: String): String = ip.checkString(span, s)

    fun checkSize(n: Int) = ip.checkSize(span, n)

    fun text(a: List<Any?>, i: Int): String = when (val v = a.getOrNull(i)) {
        is String -> v
        is Number, is Boolean -> Rt.str(v)
        null -> fail("argument ${i + 1} must be text but got null")
        else -> fail("argument ${i + 1} must be text but got ${Rt.typeName(v)}")
    }

    fun num(a: List<Any?>, i: Int): Double = when (val v = a.getOrNull(i)) {
        null -> fail("argument ${i + 1} must be a number but got null")
        else -> Rt.toNumberOrNull(v) ?: fail("argument ${i + 1} must be a number but got ${Rt.typeName(v)}")
    }

    fun int(a: List<Any?>, i: Int): Int = num(a, i).let {
        if (it.isNaN()) 0 else it.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
    }

    fun fn(a: List<Any?>, i: Int): Lambda = a.getOrNull(i) as? Lambda
        ?: fail("argument ${i + 1} must be a function like x => x.id")

    fun call(f: Lambda, vararg args: Any?): Any? = ip.callLambda(f, args.asList(), span)

    fun list(v: Any?): List<Any?> = when (v) {
        is List<*> -> v
        is StepStream -> v.items
        else -> fail("expected an array but got ${Rt.typeName(v)}")
    }

    fun map(v: Any?): Map<String, Any?> = when (v) {
        is Map<*, *> -> @Suppress("UNCHECKED_CAST") (v as Map<String, Any?>)
        else -> fail("expected an object but got ${Rt.typeName(v)}")
    }

    /** Null in safe mode gives null; strict mode raises. */
    fun nullIn(): Any? {
        if (st.strict) fail("received null")
        return null
    }

    fun <T> special(v: T): T { st.sawSpecial = true; return v }
}

internal object Library {

    // ---- helpers ----

    private fun roundTo(x: Double, digits: Int): Double =
        if (x.isNaN() || x.isInfinite()) x else BigDecimal.valueOf(x).setScale(digits.coerceIn(-15, 15), RoundingMode.HALF_UP).toDouble()

    private fun idx(i: Int, size: Int): Int = if (i < 0) maxOf(0, size + i) else minOf(i, size)

    private fun lengthOf(v: Any?): Double = when (v) {
        null -> 0.0
        is String -> v.length.toDouble()
        is Collection<*> -> v.size.toDouble()
        is Map<*, *> -> v.size.toDouble()
        is StepStream -> v.items.size.toDouble()
        else -> 1.0
    }

    private fun isEmptyValue(v: Any?): Boolean = when (v) {
        null -> true
        is String -> v.isEmpty()
        is Collection<*> -> v.isEmpty()
        is Map<*, *> -> v.isEmpty()
        is StepStream -> v.items.isEmpty()
        else -> false
    }

    private fun toNumberValue(v: Any?): Double? = when (v) {
        is Boolean -> if (v) 1.0 else 0.0
        else -> Rt.toNumberOrNull(v)
    }

    private fun mathUnary(f: (Double) -> Double): Fn = Fn(1, 1) { c, _, a ->
        if (a[0] == null) c.nullIn() else f(c.num(a, 0)).also { if (it.isNaN() || it.isInfinite()) c.fail("result is not a finite number", ErrorKind.Runtime) }
    }

    private fun minMax(isMax: Boolean): Fn = Fn(1, Int.MAX_VALUE) { c, _, a ->
        val src = if (a.size == 1 && (a[0] is List<*> || a[0] is StepStream)) c.list(a[0]) else a
        c.tick(src.size)
        var best: Any? = null
        var first = true
        for (v in src) {
            if (v == null) continue
            if (first) { best = v; first = false; continue }
            val cmp = Rt.compareForSort(v, best)
            if (if (isMax) cmp > 0 else cmp < 0) best = v
        }
        best
    }

    private fun replaceAllChecked(c: CallCtx, s: String, from: String, to: String): String {
        if (from.isEmpty()) return s
        var count = 0
        var i = s.indexOf(from)
        while (i >= 0) { count++; i = s.indexOf(from, i + from.length) }
        val total = s.length.toLong() + count.toLong() * (to.length - from.length)
        if (total > c.st.limits.maxStringLength) c.fail("result longer than ${c.st.limits.maxStringLength} characters", ErrorKind.Limit)
        return s.replace(from, to)
    }

    private fun sortList(c: CallCtx, src: List<Any?>, a: List<Any?>): List<Any?> {
        var f: Lambda? = null
        var desc = false
        for (x in a) {
            when {
                x is Lambda -> f = x
                x is String && x.equals("desc", true) -> desc = true
                x is String && x.equals("asc", true) -> desc = false
                else -> c.fail("arguments must be an optional function and \"asc\" or \"desc\"")
            }
        }
        c.tick(src.size * 2)
        c.checkSize(src.size)
        val cmp: Comparator<Any?> = when {
            f == null -> Comparator { x, y -> Rt.compareForSort(x, y) }
            f.params.size >= 2 -> Comparator { x, y ->
                c.tick()
                val r = c.call(f, x, y)
                (Rt.toNumberOrNull(r) ?: c.fail("comparator must return a number")).let { if (it < 0) -1 else if (it > 0) 1 else 0 }
            }
            else -> {
                val keys = HashMap<Int, Any?>()
                val keyed = src.map { c.call(f, it) }
                // pair values with keys by index to keep sorting stable and call the key function once per element
                val order = src.indices.sortedWith { i, j -> Rt.compareForSort(keyed[i], keyed[j]) }
                val out = order.map { src[it] }
                return if (desc) out.reversed() else out
            }
        }
        return try {
            val out = src.sortedWith(cmp)
            if (desc) out.reversed() else out
        } catch (e: IllegalArgumentException) {
            c.fail("the comparison function is inconsistent", ErrorKind.Runtime)
        }
    }

    private fun parseJsonValue(c: CallCtx, text: String): Any? = try {
        JsonText.parse(text)
    } catch (e: JsonText.JsonError) {
        c.fail("invalid JSON: ${e.message}", ErrorKind.Runtime)
    }

    private fun summed(c: CallCtx, l: List<Any?>): Double {
        c.tick(l.size)
        var total = 0.0
        for (v in l) {
            if (v == null) continue
            total += Rt.toNumberOrNull(v) ?: c.fail("cannot sum ${Rt.typeName(v)} '${Rt.str(v).take(30)}'")
        }
        return total
    }

    // ---- methods by receiver ----

    val stringMethods: Map<String, Fn> = mapOf(
        "length" to Fn(0, 0) { _, r, _ -> (r as String).length.toDouble() },
        "trim" to Fn(0, 0) { _, r, _ -> (r as String).trim() },
        "trimStart" to Fn(0, 0) { _, r, _ -> (r as String).trimStart() },
        "trimEnd" to Fn(0, 0) { _, r, _ -> (r as String).trimEnd() },
        "upper" to Fn(0, 0) { _, r, _ -> (r as String).uppercase(Locale.ROOT) },
        "lower" to Fn(0, 0) { _, r, _ -> (r as String).lowercase(Locale.ROOT) },
        "toUpperCase" to Fn(0, 0) { _, r, _ -> (r as String).uppercase(Locale.ROOT) },
        "toLowerCase" to Fn(0, 0) { _, r, _ -> (r as String).lowercase(Locale.ROOT) },
        "capitalize" to Fn(0, 0) { _, r, _ -> (r as String).replaceFirstChar { it.titlecase(Locale.ROOT) } },
        "replace" to Fn(2, 2) { c, r, a -> replaceAllChecked(c, r as String, c.text(a, 0), c.text(a, 1)) },
        "replaceAll" to Fn(2, 2) { c, r, a -> replaceAllChecked(c, r as String, c.text(a, 0), c.text(a, 1)) },
        "replaceFirst" to Fn(2, 2) { c, r, a ->
            val s = r as String
            val from = c.text(a, 0)
            val to = c.text(a, 1)
            if (s.length.toLong() + to.length > c.st.limits.maxStringLength) c.fail("result is too long", ErrorKind.Limit)
            s.replaceFirst(from, to)
        },
        "split" to Fn(1, 2) { c, r, a ->
            val s = r as String
            val sep = c.text(a, 0)
            val limit = if (a.size > 1) c.int(a, 1) else Int.MAX_VALUE
            c.tick(s.length / 16 + 1)
            val parts: List<String> = if (sep.isEmpty()) s.map { it.toString() } else s.split(sep)
            c.checkSize(parts.size)
            if (limit >= 0 && parts.size > limit) parts.subList(0, limit) else parts
        },
        "includes" to Fn(1, 1) { c, r, a -> (r as String).contains(c.text(a, 0)) },
        "startsWith" to Fn(1, 1) { c, r, a -> (r as String).startsWith(c.text(a, 0)) },
        "endsWith" to Fn(1, 1) { c, r, a -> (r as String).endsWith(c.text(a, 0)) },
        "indexOf" to Fn(1, 2) { c, r, a -> (r as String).indexOf(c.text(a, 0), if (a.size > 1) c.int(a, 1).coerceAtLeast(0) else 0).toDouble() },
        "lastIndexOf" to Fn(1, 1) { c, r, a -> (r as String).lastIndexOf(c.text(a, 0)).toDouble() },
        "slice" to Fn(1, 2) { c, r, a ->
            val s = r as String
            val from = idx(c.int(a, 0), s.length)
            val to = if (a.size > 1 && a[1] != null) idx(c.int(a, 1), s.length) else s.length
            if (from >= to) "" else s.substring(from, to)
        },
        "substring" to Fn(1, 2) { c, r, a ->
            val s = r as String
            val from = c.int(a, 0).coerceIn(0, s.length)
            val to = if (a.size > 1 && a[1] != null) c.int(a, 1).coerceIn(0, s.length) else s.length
            s.substring(minOf(from, to), maxOf(from, to))
        },
        "padStart" to Fn(1, 2) { c, r, a ->
            val s = r as String
            val len = c.int(a, 0)
            val pad = if (a.size > 1) c.text(a, 1) else " "
            if (len > c.st.limits.maxStringLength) c.fail("length exceeds ${c.st.limits.maxStringLength}", ErrorKind.Limit)
            if (pad.isEmpty() || len <= s.length) s else buildString { val n = len - s.length; while (length < n) append(pad); setLength(n); append(s) }
        },
        "padEnd" to Fn(1, 2) { c, r, a ->
            val s = r as String
            val len = c.int(a, 0)
            val pad = if (a.size > 1) c.text(a, 1) else " "
            if (len > c.st.limits.maxStringLength) c.fail("length exceeds ${c.st.limits.maxStringLength}", ErrorKind.Limit)
            if (pad.isEmpty() || len <= s.length) s else buildString { append(s); while (length < len) append(pad); setLength(len) }
        },
        "repeat" to Fn(1, 1) { c, r, a ->
            val s = r as String
            val n = c.int(a, 0)
            if (n < 0) c.fail("count must not be negative")
            if (s.length.toLong() * n > c.st.limits.maxStringLength) c.fail("result longer than ${c.st.limits.maxStringLength} characters", ErrorKind.Limit)
            s.repeat(n)
        },
        "charAt" to Fn(1, 1) { c, r, a -> (r as String).getOrNull(c.int(a, 0))?.toString() ?: "" },
        "at" to Fn(1, 1) { c, r, a ->
            val s = r as String
            val i = c.int(a, 0).let { if (it < 0) s.length + it else it }
            s.getOrNull(i)?.toString()
        },
        "concat" to Fn(0, Int.MAX_VALUE) { c, r, a -> c.str((r as String) + a.joinToString("") { Rt.str(it) }) },
        "reverse" to Fn(0, 0) { _, r, _ -> (r as String).reversed() },
        "isEmpty" to Fn(0, 0) { _, r, _ -> (r as String).isEmpty() },
        "toNumber" to Fn(0, 0) { _, r, _ -> Rt.toNumberOrNull(r) },
        "toInt" to Fn(0, 0) { _, r, _ -> Rt.toNumberOrNull(r)?.let { if (it < 0) Math.ceil(it) else Math.floor(it) } },
        "toBoolean" to Fn(0, 0) { _, r, _ -> Values.asBoolean(r) },
    )

    val numberMethods: Map<String, Fn> = mapOf(
        "round" to Fn(0, 1) { c, r, a -> roundTo((r as Number).toDouble(), if (a.isNotEmpty()) c.int(a, 0) else 0) },
        "floor" to Fn(0, 0) { _, r, _ -> Math.floor((r as Number).toDouble()) },
        "ceil" to Fn(0, 0) { _, r, _ -> Math.ceil((r as Number).toDouble()) },
        "abs" to Fn(0, 0) { _, r, _ -> abs((r as Number).toDouble()) },
        "trunc" to Fn(0, 0) { _, r, _ -> (r as Number).toDouble().let { if (it < 0) Math.ceil(it) else Math.floor(it) } },
        "sign" to Fn(0, 0) { _, r, _ -> Math.signum((r as Number).toDouble()) },
        "sqrt" to Fn(0, 0) { c, r, _ -> Math.sqrt((r as Number).toDouble()).also { if (it.isNaN()) c.fail("square root of a negative number", ErrorKind.Runtime) } },
        "pow" to Fn(1, 1) { c, r, a -> Math.pow((r as Number).toDouble(), c.num(a, 0)).also { if (it.isNaN() || it.isInfinite()) c.fail("result is not a finite number", ErrorKind.Runtime) } },
        "clamp" to Fn(2, 2) { c, r, a -> (r as Number).toDouble().coerceIn(minOf(c.num(a, 0), c.num(a, 1)), maxOf(c.num(a, 0), c.num(a, 1))) },
        "toFixed" to Fn(0, 1) { c, r, a ->
            val d = if (a.isNotEmpty()) c.int(a, 0) else 0
            if (d !in 0..20) c.fail("digits must be between 0 and 20")
            val x = (r as Number).toDouble()
            if (x.isNaN() || x.isInfinite()) c.fail("not a finite number", ErrorKind.Runtime)
            BigDecimal.valueOf(x).setScale(d, RoundingMode.HALF_UP).toPlainString()
        },
        "isInteger" to Fn(0, 0) { _, r, _ -> (r as Number).toDouble().let { it == Math.floor(it) && !it.isInfinite() } },
        "toInt" to Fn(0, 0) { _, r, _ -> (r as Number).toDouble().let { if (it < 0) Math.ceil(it) else Math.floor(it) } },
    )

    val booleanMethods: Map<String, Fn> = mapOf(
        "toNumber" to Fn(0, 0) { _, r, _ -> if (r as Boolean) 1.0 else 0.0 },
    )

    val listMethods: Map<String, Fn> = mapOf(
        "length" to Fn(0, 0) { c, r, _ -> c.list(r).size.toDouble() },
        "count" to Fn(0, 1) { c, r, a ->
            val l = c.list(r)
            if (a.isEmpty()) l.size.toDouble() else {
                val f = c.fn(a, 0)
                c.tick(l.size)
                l.indices.count { Rt.truthy(c.call(f, l[it], it.toDouble())) }.toDouble()
            }
        },
        "isEmpty" to Fn(0, 0) { c, r, _ -> c.list(r).isEmpty() },
        "map" to Fn(1, 1) { c, r, a ->
            val l = c.list(r); val f = c.fn(a, 0)
            c.tick(l.size)
            val out = ArrayList<Any?>(l.size)
            for (i in l.indices) out += c.call(f, l[i], i.toDouble())
            out
        },
        "flatMap" to Fn(1, 1) { c, r, a ->
            val l = c.list(r); val f = c.fn(a, 0)
            c.tick(l.size)
            val out = ArrayList<Any?>()
            for (i in l.indices) {
                val v = c.call(f, l[i], i.toDouble())
                if (v is List<*>) out.addAll(v) else out += v
                c.checkSize(out.size)
            }
            out
        },
        "filter" to Fn(1, 1) { c, r, a ->
            val l = c.list(r); val f = c.fn(a, 0)
            c.tick(l.size)
            val out = ArrayList<Any?>()
            for (i in l.indices) if (Rt.truthy(c.call(f, l[i], i.toDouble()))) out += l[i]
            out
        },
        "find" to Fn(1, 1) { c, r, a ->
            val l = c.list(r); val f = c.fn(a, 0)
            var found: Any? = null
            for (i in l.indices) { c.tick(); if (Rt.truthy(c.call(f, l[i], i.toDouble()))) { found = l[i]; break } }
            found
        },
        "findIndex" to Fn(1, 1) { c, r, a ->
            val l = c.list(r); val f = c.fn(a, 0)
            var found = -1
            for (i in l.indices) { c.tick(); if (Rt.truthy(c.call(f, l[i], i.toDouble()))) { found = i; break } }
            found.toDouble()
        },
        "some" to Fn(1, 1) { c, r, a ->
            val l = c.list(r); val f = c.fn(a, 0)
            var res = false
            for (i in l.indices) { c.tick(); if (Rt.truthy(c.call(f, l[i], i.toDouble()))) { res = true; break } }
            res
        },
        "every" to Fn(1, 1) { c, r, a ->
            val l = c.list(r); val f = c.fn(a, 0)
            var res = true
            for (i in l.indices) { c.tick(); if (!Rt.truthy(c.call(f, l[i], i.toDouble()))) { res = false; break } }
            res
        },
        "reduce" to Fn(1, 2) { c, r, a ->
            val l = c.list(r); val f = c.fn(a, 0)
            var acc: Any?
            var start = 0
            if (a.size > 1) acc = a[1] else if (l.isEmpty()) return@Fn null else { acc = l[0]; start = 1 }
            for (i in start until l.size) { c.tick(); acc = c.call(f, acc, l[i], i.toDouble()) }
            acc
        },
        "join" to Fn(0, 1) { c, r, a ->
            val l = c.list(r)
            val sep = if (a.isNotEmpty()) c.text(a, 0) else ","
            c.tick(l.size)
            val sb = StringBuilder()
            for (i in l.indices) {
                if (i > 0) sb.append(sep)
                sb.append(Rt.str(l[i]))
                if (sb.length > c.st.limits.maxStringLength) c.fail("result longer than ${c.st.limits.maxStringLength} characters", ErrorKind.Limit)
            }
            sb.toString()
        },
        "first" to Fn(0, 0) { c, r, _ -> c.list(r).firstOrNull() },
        "last" to Fn(0, 0) { c, r, _ -> c.list(r).lastOrNull() },
        "at" to Fn(1, 1) { c, r, a -> val l = c.list(r); val i = c.int(a, 0).let { if (it < 0) l.size + it else it }; l.getOrNull(i) },
        "unique" to Fn(0, 1) { c, r, a ->
            val l = c.list(r)
            c.tick(l.size)
            val f = if (a.isNotEmpty()) c.fn(a, 0) else null
            val seen = HashSet<Any?>()
            val out = ArrayList<Any?>()
            for (v in l) { val k = Rt.norm(if (f != null) c.call(f, v) else v); if (seen.add(k)) out += v }
            out
        },
        "sort" to Fn(0, 2) { c, r, a -> sortList(c, c.list(r), a) },
        "reverse" to Fn(0, 0) { c, r, _ -> c.list(r).reversed() },
        "slice" to Fn(1, 2) { c, r, a ->
            val l = c.list(r)
            val from = idx(c.int(a, 0), l.size)
            val to = if (a.size > 1 && a[1] != null) idx(c.int(a, 1), l.size) else l.size
            if (from >= to) emptyList() else l.subList(from, to).toList()
        },
        "take" to Fn(1, 1) { c, r, a -> val l = c.list(r); l.take(c.int(a, 0).coerceAtLeast(0)) },
        "flat" to Fn(0, 1) { c, r, a ->
            val depth = if (a.isNotEmpty()) c.int(a, 0) else 1
            fun go(l: List<Any?>, d: Int, out: MutableList<Any?>) {
                c.tick(l.size)
                for (v in l) {
                    val inner = if (v is StepStream) v.items else v
                    if (inner is List<*> && d > 0) go(inner, d - 1, out) else out += v
                    c.checkSize(out.size)
                }
            }
            ArrayList<Any?>().also { go(c.list(r), depth, it) }
        },
        "sum" to Fn(0, 0) { c, r, _ -> summed(c, c.list(r)) },
        "avg" to Fn(0, 0) { c, r, _ -> c.list(r).let { if (it.isEmpty()) null else summed(c, it) / it.size } },
        "min" to Fn(0, 0) { c, r, _ -> minMax(false).impl(c, null, listOf(c.list(r))) },
        "max" to Fn(0, 0) { c, r, _ -> minMax(true).impl(c, null, listOf(c.list(r))) },
        "includes" to Fn(1, 1) { c, r, a -> val l = c.list(r); c.tick(l.size); l.any { Rt.eq(it, a[0]) } },
        "indexOf" to Fn(1, 1) { c, r, a -> val l = c.list(r); c.tick(l.size); l.indexOfFirst { Rt.eq(it, a[0]) }.toDouble() },
        "concat" to Fn(0, Int.MAX_VALUE) { c, r, a ->
            val out = ArrayList<Any?>(c.list(r))
            for (x in a) { if (x is List<*>) out.addAll(x) else out += x; c.checkSize(out.size) }
            out
        },
        "append" to Fn(1, Int.MAX_VALUE) { c, r, a -> ArrayList<Any?>(c.list(r)).also { it.addAll(a); c.checkSize(it.size) } },
        "compact" to Fn(0, 0) { c, r, _ -> c.list(r).filter { it != null && it != "" } },
        "pluck" to Fn(1, 1) { c, r, a -> val l = c.list(r); val k = c.text(a, 0); c.tick(l.size); l.map { Values.deepGet(it, k) } },
    )

    val mapMethods: Map<String, Fn> = mapOf(
        "keys" to Fn(0, 0) { c, r, _ -> c.map(r).keys.toList() },
        "values" to Fn(0, 0) { c, r, _ -> c.map(r).values.toList() },
        "entries" to Fn(0, 0) { c, r, _ -> c.map(r).entries.map { listOf(it.key, it.value) } },
        "hasKey" to Fn(1, 1) { c, r, a -> c.map(r).containsKey(Rt.str(a[0])) },
        "merge" to Fn(0, Int.MAX_VALUE) { c, r, a ->
            val out = LinkedHashMap(c.map(r))
            for (x in a) { if (x != null) out.putAll(c.map(x)); c.checkSize(out.size) }
            out
        },
        "pick" to Fn(1, Int.MAX_VALUE) { c, r, a ->
            val m = c.map(r)
            val keys = if (a.size == 1 && a[0] is List<*>) (a[0] as List<*>).map { Rt.str(it) } else a.map { Rt.str(it) }
            val out = LinkedHashMap<String, Any?>()
            for (k in keys) if (m.containsKey(k)) out[k] = m[k]
            out
        },
        "omit" to Fn(1, Int.MAX_VALUE) { c, r, a ->
            val m = c.map(r)
            val keys = (if (a.size == 1 && a[0] is List<*>) (a[0] as List<*>).map { Rt.str(it) } else a.map { Rt.str(it) }).toSet()
            m.filterKeys { it !in keys }
        },
        "get" to Fn(1, 2) { c, r, a -> Values.deepGet(r, c.text(a, 0)) ?: a.getOrNull(1) },
        "isEmpty" to Fn(0, 0) { c, r, _ -> c.map(r).isEmpty() },
        "length" to Fn(0, 0) { c, r, _ -> c.map(r).size.toDouble() },
    )

    private val commonMethods: Map<String, Fn> = mapOf(
        "toString" to Fn(0, 0) { c, r, _ -> c.str(Rt.str(r)) },
        "toJson" to Fn(0, 1) { c, r, a -> c.str(Values.stringify(Rt.plain(r), a.isNotEmpty() && Rt.truthy(a[0]))) },
    )

    private fun tableFor(v: Any?): Map<String, Fn>? = when (v) {
        is String -> stringMethods
        is Number -> numberMethods
        is Boolean -> booleanMethods
        is List<*> -> listMethods
        is Map<*, *> -> mapMethods
        is KDate -> DateOps.methods
        else -> null
    }

    private fun kindName(v: Any?) = when (v) {
        is String -> "text"; is Number -> "number"; is Boolean -> "boolean"; is List<*> -> "array"
        is Map<*, *> -> "object"; is KDate -> "date"; else -> Rt.typeName(v)
    }

    fun callMethod(ip: Interp, recv: Any?, name: String, args: List<Any?>, call: Call, m: Member): Any? =
        dispatch(ip, call.span, m.nameSpan, recv, name, args)

    fun dispatch(ip: Interp, span: Span, nameSpan: Span, recvIn: Any?, name: String, args: List<Any?>): Any? {
        var recv = recvIn
        if (recv is StepStream) recv = recv.items
        var table = tableFor(recv)
        var fn = table?.get(name)
        if (fn == null && recv is String && DateOps.methods.containsKey(name)) {
            val c = CallCtx(ip, span, name)
            recv = DateOps.coerce(c, recv)
            ip.st.sawSpecial = true
            fn = DateOps.methods[name]
            table = DateOps.methods
        }
        if (fn == null) fn = commonMethods[name]
        if (fn == null) {
            val names = (table?.keys.orEmpty()) + commonMethods.keys +
                (if (recv is String) DateOps.methods.keys else emptySet())
            throw ip.err(nameSpan, ErrorKind.Unknown, "Unknown method '$name' on ${kindName(recv)}", suggest(name, names))
        }
        return ip.invoke(span, fn, name, recv, args)
    }

    // ---- namespaces and globals ----

    private fun forward(name: String): Fn = Fn(1, Int.MAX_VALUE) { c, _, a ->
        if (a[0] == null) c.nullIn() else dispatch(c.ip, c.span, c.span, a[0], name, a.subList(1, a.size))
    }

    private fun dateFn(name: String): Fn = Fn(1, Int.MAX_VALUE) { c, _, a ->
        val d = DateOps.coerce(c, a[0])
        c.special(Unit)
        val fn = DateOps.methods[name]!!
        val rest = a.subList(1, a.size)
        if (rest.size < fn.min || rest.size > fn.max) c.fail("takes ${fn.min} to ${fn.max} arguments after the date")
        fn.impl(c, d, rest)
    }

    val namespaces: Map<String, Map<String, Fn>> = mapOf(
        "Math" to mapOf(
            "round" to Fn(1, 2) { c, _, a -> if (a[0] == null) c.nullIn() else roundTo(c.num(a, 0), if (a.size > 1) c.int(a, 1) else 0) },
            "floor" to mathUnary { Math.floor(it) },
            "ceil" to mathUnary { Math.ceil(it) },
            "abs" to mathUnary { abs(it) },
            "trunc" to mathUnary { if (it < 0) Math.ceil(it) else Math.floor(it) },
            "sign" to mathUnary { Math.signum(it) },
            "sqrt" to Fn(1, 1) { c, _, a -> if (a[0] == null) c.nullIn() else c.num(a, 0).let { if (it < 0) c.fail("square root of a negative number", ErrorKind.Runtime) else Math.sqrt(it) } },
            "pow" to Fn(2, 2) { c, _, a -> Math.pow(c.num(a, 0), c.num(a, 1)).also { if (it.isNaN() || it.isInfinite()) c.fail("result is not a finite number", ErrorKind.Runtime) } },
            "min" to minMax(false),
            "max" to minMax(true),
            "clamp" to Fn(3, 3) { c, _, a -> c.num(a, 0).coerceIn(minOf(c.num(a, 1), c.num(a, 2)), maxOf(c.num(a, 1), c.num(a, 2))) },
        ),
        "Number" to mapOf(
            "parse" to Fn(1, 1) { _, _, a -> toNumberValue(a[0]) },
            "isNumber" to Fn(1, 1) { _, _, a -> a[0] is Number },
            "isInteger" to Fn(1, 1) { _, _, a -> (a[0] as? Number)?.toDouble()?.let { it == Math.floor(it) && !it.isInfinite() } ?: false },
            "toFixed" to Fn(1, 2) { c, _, a -> numberMethods["toFixed"]!!.impl(c, c.num(a, 0), a.drop(1)) },
        ),
        "Date" to mapOf(
            "now" to Fn(0, 0) { c, _, _ -> c.special(DateOps.now(c.st)) },
            "today" to Fn(0, 0) { c, _, _ -> c.special(DateOps.now(c.st).let { KDate(it.zdt.truncatedTo(ChronoUnit.DAYS).toInstant(), it.zone) }) },
            "parse" to Fn(1, 2) { c, _, a ->
                val zone = if (a.size > 1) DateOps.zoneOf(c, c.text(a, 1)) else c.st.zone
                when (val v = a[0]) {
                    is KDate -> v
                    is String -> DateOps.parse(v, zone)?.let { c.special(it) }
                    is Number -> c.special(KDate(Instant.ofEpochMilli(v.toLong()), zone))
                    else -> null
                }
            },
            "fromMillis" to Fn(1, 1) { c, _, a -> c.special(KDate(Instant.ofEpochMilli(c.num(a, 0).toLong()), c.st.zone)) },
            "format" to dateFn("format"), "plus" to dateFn("plus"), "minus" to dateFn("minus"),
            "diff" to dateFn("diff"), "startOf" to dateFn("startOf"), "endOf" to dateFn("endOf"),
            "toIso" to dateFn("toIso"), "toMillis" to dateFn("toMillis"), "inZone" to dateFn("inZone"),
        ),
        "JSON" to mapOf(
            "parse" to Fn(1, 1) { c, _, a -> if (a[0] == null) c.nullIn() else parseJsonValue(c, c.text(a, 0)).also { c.checkSize(if (it is Collection<*>) it.size else 0) } },
            "stringify" to Fn(1, 2) { c, _, a -> c.str(Values.stringify(Rt.plain(a[0]), a.size > 1 && Rt.truthy(a[1]))) },
        ),
        "Object" to mapOf(
            "keys" to Fn(1, 1) { c, _, a -> if (a[0] == null) emptyList<Any?>() else mapMethods["keys"]!!.impl(c, a[0], emptyList()) },
            "values" to Fn(1, 1) { c, _, a -> if (a[0] == null) emptyList<Any?>() else mapMethods["values"]!!.impl(c, a[0], emptyList()) },
            "entries" to Fn(1, 1) { c, _, a -> if (a[0] == null) emptyList<Any?>() else mapMethods["entries"]!!.impl(c, a[0], emptyList()) },
            "hasKey" to Fn(2, 2) { c, _, a -> if (a[0] == null) false else mapMethods["hasKey"]!!.impl(c, a[0], a.drop(1)) },
            "merge" to Fn(1, Int.MAX_VALUE) { c, _, a -> mapMethods["merge"]!!.impl(c, a[0] ?: emptyMap<String, Any?>(), a.drop(1)) },
            "pick" to forward("pick"), "omit" to forward("omit"),
        ),
        "Array" to mapOf(
            "isArray" to Fn(1, 1) { _, _, a -> a[0] is List<*> || a[0] is StepStream },
            "range" to Fn(1, 3) { c, _, a -> range(c, a) },
            "from" to Fn(1, 1) { c, _, a -> when (val v = a[0]) { null -> emptyList<Any?>(); is List<*> -> v; is StepStream -> v.items; is String -> v.map { it.toString() }; is Map<*, *> -> v.values.toList(); else -> listOf(v) } },
        ),
    )

    private fun range(c: CallCtx, a: List<Any?>): List<Any?> {
        val (start, end) = if (a.size == 1) 0.0 to c.num(a, 0) else c.num(a, 0) to c.num(a, 1)
        val step = if (a.size > 2) c.num(a, 2) else 1.0
        if (step == 0.0) c.fail("step must not be zero")
        val count = Math.ceil((end - start) / step)
        if (count <= 0) return emptyList()
        if (count > c.st.limits.maxCollectionSize) c.fail("range longer than ${c.st.limits.maxCollectionSize} elements", ErrorKind.Limit)
        c.tick(count.toInt())
        return List(count.toInt()) { start + it * step }
    }

    fun constant(ns: String, name: String): Any? = when (ns to name) {
        "Math" to "PI" -> Math.PI
        "Math" to "E" -> Math.E
        else -> null
    }

    /** `Number(x)`, `String(x)`, `Boolean(x)`. */
    val callableNamespaces: Map<String, Fn> = mapOf(
        "Number" to Fn(1, 1) { _, _, a -> toNumberValue(a[0]) },
        "String" to Fn(1, 1) { c, _, a -> c.str(Rt.str(a[0])) },
        "Boolean" to Fn(1, 1) { _, _, a -> Rt.truthy(a[0]) },
    )

    private fun stepItems(c: CallCtx, name: String): List<Any?>? {
        val outputs = c.st.scope["step"] as? Map<*, *> ?: return null
        val raw = outputs[name] ?: return null
        return Values.asList(raw)
    }

    private fun itemsOf(c: CallCtx, a: List<Any?>): List<Any?> =
        if (a.isNotEmpty() && a[0] != null) Values.asList(if (a[0] is StepStream) (a[0] as StepStream).items else a[0]) else Values.asList(c.st.scope["items"])

    private val explicitGlobals: Map<String, Fn> = mapOf(
        "now" to Fn(0, 0) { c, _, _ -> c.special(DateOps.now(c.st)) },
        "today" to Fn(0, 0) { c, _, _ -> c.special(DateOps.now(c.st).let { KDate(it.zdt.truncatedTo(ChronoUnit.DAYS).toInstant(), it.zone) }) },
        "\$now" to Fn(0, 0) { c, _, _ -> c.st.scope["now"] ?: DateOps.now(c.st).iso() },
        "\$today" to Fn(0, 0) { c, _, _ -> c.st.scope["today"] ?: DateOps.now(c.st).zdt.toLocalDate().toString() },
        "\$step" to Fn(1, 2) { c, _, a ->
            val name = Rt.str(a[0])
            val items = stepItems(c, name)
            when {
                items == null -> null
                a.size > 1 -> { val field = Rt.str(a[1]); items.map { Values.deepGet(it, field) } }
                else -> c.special(StepStream(items))
            }
        },
        "\$count" to Fn(0, 1) { c, _, a -> itemsOf(c, a).size.toDouble() },
        "\$length" to Fn(0, 1) { c, _, a -> if (a.isEmpty()) lengthOf(Values.asList(c.st.scope["items"])) else lengthOf(a[0]) },
        "\$first" to Fn(0, 1) { c, _, a -> itemsOf(c, a).firstOrNull() },
        "\$last" to Fn(0, 1) { c, _, a -> itemsOf(c, a).lastOrNull() },
        "\$all" to Fn(0, 0) { c, _, _ -> Values.asList(c.st.scope["items"]) },
        "length" to Fn(1, 1) { _, _, a -> lengthOf(a[0]) },
        "isEmpty" to Fn(1, 1) { _, _, a -> isEmptyValue(a[0]) },
        "isNull" to Fn(1, 1) { _, _, a -> a[0] == null },
        "typeOf" to Fn(1, 1) { _, _, a -> Rt.typeName(a[0]) },
        "coalesce" to Fn(1, Int.MAX_VALUE) { _, _, a -> a.firstOrNull { it != null } },
        "toString" to Fn(1, 1) { c, _, a -> c.str(Rt.str(a[0])) },
        "toNumber" to Fn(1, 1) { _, _, a -> toNumberValue(a[0]) },
        "toInt" to Fn(1, 1) { _, _, a -> toNumberValue(a[0])?.let { if (it < 0) Math.ceil(it) else Math.floor(it) } },
        "toBoolean" to Fn(1, 1) { _, _, a -> Values.asBoolean(a[0]) },
        "min" to minMax(false),
        "max" to minMax(true),
        "merge" to Fn(1, Int.MAX_VALUE) { c, _, a -> mapMethods["merge"]!!.impl(c, a[0] ?: emptyMap<String, Any?>(), a.drop(1)) },
        "range" to Fn(1, 3) { c, _, a -> range(c, a) },
        "keys" to Fn(1, 1) { c, _, a -> if (a[0] == null) emptyList<Any?>() else mapMethods["keys"]!!.impl(c, a[0], emptyList()) },
        "values" to Fn(1, 1) { c, _, a -> if (a[0] == null) emptyList<Any?>() else mapMethods["values"]!!.impl(c, a[0], emptyList()) },
        "entries" to Fn(1, 1) { c, _, a -> if (a[0] == null) emptyList<Any?>() else mapMethods["entries"]!!.impl(c, a[0], emptyList()) },
        "hasKey" to Fn(2, 2) { c, _, a -> if (a[0] == null) false else mapMethods["hasKey"]!!.impl(c, a[0], a.drop(1)) },
    )

    /** Global functions: explicit helpers plus every method callable with the receiver as first argument. */
    val global: Map<String, Fn> by lazy {
        val out = LinkedHashMap<String, Fn>()
        for (table in listOf(stringMethods, numberMethods, listMethods, mapMethods, DateOps.methods, booleanMethods)) {
            for (k in table.keys) out.putIfAbsent(k, forward(k))
        }
        out.putAll(explicitGlobals)
        out
    }

    /** Public description of the library for autocomplete. */
    fun catalog(): List<FunctionInfo> {
        val out = ArrayList<FunctionInfo>()
        fun add(owner: String, kind: String, table: Map<String, Fn>) {
            for ((k, f) in table) out += FunctionInfo(k, owner, kind, f.min, if (f.max == Int.MAX_VALUE) -1 else f.max)
        }
        add("text", "method", stringMethods); add("number", "method", numberMethods)
        add("array", "method", listMethods); add("object", "method", mapMethods); add("date", "method", DateOps.methods)
        for ((ns, t) in namespaces) add(ns, "namespace", t)
        add("", "global", explicitGlobals)
        return out
    }
}

/** One library entry. [receiver] is `text`, `number`, `array`, `object`, `date`, a namespace such as `Math`, or empty for globals. */
data class FunctionInfo(val name: String, val receiver: String, val kind: String, val minArgs: Int, val maxArgs: Int)
