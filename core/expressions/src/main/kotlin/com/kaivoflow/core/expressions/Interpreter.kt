package com.kaivoflow.core.expressions

import com.kaivoflow.core.engine.Values
import java.time.ZoneId

/** Mutable budget and options for one evaluation (shared by all segments of a template). */
internal class EvalState(
    val limits: ExpressionLimits,
    val strict: Boolean,
    val scope: Map<String, Any?>,
) {
    private val deadline: Long = System.nanoTime() + limits.timeoutMillis * 1_000_000L
    var steps = 0L
    private var sinceClock = 0
    var callDepth = 0
    var sawSpecial = false

    val zone: ZoneId by lazy {
        val tz = (scope["run"] as? Map<*, *>)?.get("timezone")?.toString()
        runCatching { ZoneId.of(tz ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
    }

    fun tick(n: Int = 1, onFail: (ErrorKind, String) -> Nothing) {
        steps += n
        if (steps > limits.maxSteps) onFail(ErrorKind.Limit, "Evaluation step limit exceeded (${limits.maxSteps})")
        sinceClock += n
        if (sinceClock >= 64) {
            sinceClock = 0
            if (System.nanoTime() > deadline) onFail(ErrorKind.Timeout, "Evaluation timed out after ${limits.timeoutMillis} ms")
        }
    }
}

/** Tree-walking evaluator. Knows only the whitelisted library; no reflection, no JVM class access. */
internal class Interp(val src: SourceText, val st: EvalState) {

    private val scope get() = st.scope

    fun err(span: Span, kind: ErrorKind, msg: String, suggestion: String? = null): ExpressionException =
        src.error(span, kind, msg, suggestion)

    fun tick(span: Span, n: Int = 1) = st.tick(n) { k, m -> throw err(span, k, m) }

    fun checkString(span: Span, s: String): String {
        if (s.length > st.limits.maxStringLength) throw err(span, ErrorKind.Limit, "String longer than ${st.limits.maxStringLength} characters")
        return s
    }

    fun checkSize(span: Span, n: Int) {
        if (n > st.limits.maxCollectionSize) throw err(span, ErrorKind.Limit, "Collection larger than ${st.limits.maxCollectionSize} elements")
    }

    fun eval(e: Expr, env: Env?): Any? {
        tick(e.span)
        return when (e) {
            is Literal -> e.value
            is Identifier -> identifier(e, env)
            is ArrayLiteral -> {
                checkSize(e.span, e.items.size)
                val out = ArrayList<Any?>(e.items.size)
                for (i in e.items) out += eval(i, env)
                out
            }
            is ObjectLiteral -> {
                checkSize(e.span, e.entries.size)
                val out = LinkedHashMap<String, Any?>()
                for (en in e.entries) {
                    val k = en.key ?: Rt.str(eval(en.keyExpr!!, env))
                    out[k] = eval(en.value, env)
                }
                out
            }
            is Member, is Index, is Call -> evalLink(e, env).let { if (it === SHORT) null else it }
            is Unary -> unary(e, env)
            is Binary -> binary(e, env)
            is Conditional -> if (Rt.truthy(eval(e.test, env))) eval(e.whenTrue, env) else eval(e.whenFalse, env)
            is Arrow -> { st.sawSpecial = true; Lambda(e.params, e.body, env, src) }
            is TemplateLiteral -> {
                val sb = StringBuilder()
                for (p in e.parts) {
                    if (p is String) sb.append(p) else sb.append(Rt.str(eval(p as Expr, env)))
                    if (sb.length > st.limits.maxStringLength) throw err(e.span, ErrorKind.Limit, "String longer than ${st.limits.maxStringLength} characters")
                }
                sb.toString()
            }
        }
    }

    /** Marker for an optional chain (`a?.b.c`) that short-circuited; becomes null at the end of the chain. */
    private object SHORT

    private fun evalTarget(target: Expr, env: Env?): Any? =
        if (target is Member || target is Index || target is Call) evalLink(target, env) else eval(target, env)

    private fun evalLink(e: Expr, env: Env?): Any? {
        when (e) {
            is Member -> {
                val target = evalTarget(e.target, env)
                if (target === SHORT) return SHORT
                if (e.optional && target == null) return SHORT
                return member(target, e.name, e.span, e.optional)
            }
            is Index -> {
                val target = evalTarget(e.target, env)
                if (target === SHORT) return SHORT
                if (e.optional && target == null) return SHORT
                return index(target, eval(e.index, env), e.span)
            }
            is Call -> return call(e, env)
            else -> return eval(e, env)
        }
    }

    // ---- variables ----

    private val OPTIONAL_NAMES = setOf("\$binary", "\$error", "\$flow")

    private fun identifier(e: Identifier, env: Env?): Any? {
        val name = e.name
        if (env != null) {
            val (found, v) = env.lookup(name)
            if (found) return v
        }
        if (name.startsWith("$")) {
            val key = name.substring(1)
            if (scope.containsKey(key)) return scope[key]
            when (key) {
                "input" -> { st.sawSpecial = true; return StepStream(Values.asList(scope["items"])) }
                "runId" -> return (scope["run"] as? Map<*, *>)?.get("id")
                "env" -> return emptyMap<String, Any?>() // process environment is never exposed
            }
            if ("$name" in OPTIONAL_NAMES) return null
            val candidates = scope.keys.map { "$$it" } + listOf("\$input", "\$runId", "\$step", "\$count", "\$length", "\$first", "\$last", "\$all", "\$now", "\$today")
            throw err(e.span, ErrorKind.Unknown, "Unknown variable '$name'", suggest(name, candidates))
        }
        if (Library.namespaces.containsKey(name)) return Namespace(name)
        if (scope.containsKey(name)) throw err(e.span, ErrorKind.Unknown, "Unknown variable '$name'", "$$name")
        val candidates = (env?.allNames().orEmpty()) + Library.namespaces.keys + scope.keys.map { "$$it" }
        throw err(e.span, ErrorKind.Unknown, "Unknown variable '$name'", suggest(name, candidates))
    }

    // ---- member and index access ----

    fun member(target: Any?, name: String, span: Span, optional: Boolean): Any? {
        when (target) {
            null -> {
                if (optional || !st.strict) return null
                throw err(span, ErrorKind.Type, "Cannot read '$name' of null")
            }
            is Map<*, *> -> {
                if (target.containsKey(name)) return target[name]
                if (st.strict) throw err(span, ErrorKind.Unknown, "Property '$name' does not exist", suggest(name, target.keys.map { it.toString() }))
                return null
            }
            is StepStream -> return streamMember(target, name, span)
            is List<*> -> return when (name) {
                "length", "count" -> target.size.toDouble()
                "first" -> target.firstOrNull()
                "last" -> target.lastOrNull()
                else -> if (st.strict) throw err(span, ErrorKind.Unknown, "Arrays have no property '$name'", suggest(name, listOf("length", "first", "last"))) else null
            }
            is String -> return when (name) {
                "length" -> target.length.toDouble()
                else -> if (st.strict) throw err(span, ErrorKind.Unknown, "Text has no property '$name'", suggest(name, listOf("length"))) else null
            }
            is KDate -> return DateOps.property(this, target, name, span)
            is Namespace -> return Library.constant(target.name, name)
                ?: throw err(span, ErrorKind.Unknown, "'${target.name}' has no member '$name'", suggest(name, Library.namespaces[target.name]!!.keys))
            else -> {
                if (st.strict) throw err(span, ErrorKind.Type, "Cannot read '$name' of ${Rt.typeName(target)}")
                return null
            }
        }
    }

    private fun streamMember(s: StepStream, name: String, span: Span): Any? {
        if (s.items.size == 1) {
            val only = s.items[0]
            if (only is Map<*, *> && only.containsKey(name)) return only[name]
        }
        return when (name) {
            "items" -> s.items
            "first" -> s.items.firstOrNull()
            "last" -> s.items.lastOrNull()
            "count", "length" -> s.items.size.toDouble()
            else -> {
                if (s.items.size == 1) {
                    val only = s.items[0]
                    if (only is Map<*, *>) {
                        if (st.strict) throw err(span, ErrorKind.Unknown, "Property '$name' does not exist", suggest(name, only.keys.map { it.toString() }))
                        return null
                    }
                }
                if (st.strict) throw err(span, ErrorKind.Unknown, "Step output has no member '$name'", suggest(name, listOf("items", "first", "last", "count")))
                null
            }
        }
    }

    private fun index(target: Any?, idx: Any?, span: Span): Any? {
        if (target == null) {
            if (!st.strict) return null
            throw err(span, ErrorKind.Type, "Cannot index null")
        }
        val list: List<*>? = when (target) { is List<*> -> target; is StepStream -> target.items; else -> null }
        if (list != null) {
            val n = Rt.toNumberOrNull(idx)
            if (n != null && n == Math.floor(n)) {
                val i = n.toInt()
                val v = list.getOrNull(i)
                if (v == null && (i < 0 || i >= list.size) && st.strict) throw err(span, ErrorKind.Unknown, "Index $i is out of range (length ${list.size})")
                return v
            }
            if (st.strict) throw err(span, ErrorKind.Type, "Arrays are indexed by number, not ${Rt.typeName(idx)}")
            return null
        }
        if (target is Map<*, *>) {
            val key = Rt.str(idx)
            if (target.containsKey(key)) return target[key]
            if (st.strict) throw err(span, ErrorKind.Unknown, "Property '$key' does not exist", suggest(key, target.keys.map { it.toString() }))
            return null
        }
        if (target is String) {
            val n = Rt.toNumberOrNull(idx)
            if (n != null && n == Math.floor(n) && n >= 0 && n < target.length) return target[n.toInt()].toString()
            if (st.strict) throw err(span, ErrorKind.Unknown, "Index out of range for text of length ${target.length}")
            return null
        }
        if (st.strict) throw err(span, ErrorKind.Type, "Cannot index ${Rt.typeName(target)}")
        return null
    }

    // ---- operators ----

    private fun unary(e: Unary, env: Env?): Any? {
        val v = eval(e.operand, env)
        return when (e.op) {
            "!" -> !Rt.truthy(v)
            else -> {
                if (v == null) {
                    if (st.strict) throw err(e.span, ErrorKind.Type, "Cannot apply '${e.op}' to null")
                    return null
                }
                val n = Rt.toNumberOrNull(v) ?: throw err(e.span, ErrorKind.Type, "Cannot apply '${e.op}' to ${Rt.typeName(v)} '${shorten(v)}'")
                if (e.op == "-") -n else n
            }
        }
    }

    private fun shorten(v: Any?): String = Rt.str(v).let { if (it.length > 30) it.take(30) + "..." else it }

    private fun binary(e: Binary, env: Env?): Any? {
        when (e.op) {
            "&&" -> { val l = eval(e.left, env); return if (!Rt.truthy(l)) l else eval(e.right, env) }
            "||" -> { val l = eval(e.left, env); return if (Rt.truthy(l)) l else eval(e.right, env) }
            "??" -> { val l = eval(e.left, env); return l ?: eval(e.right, env) }
        }
        val l = eval(e.left, env)
        val r = eval(e.right, env)
        return when (e.op) {
            "==", "===" -> Rt.eq(l, r)
            "!=", "!==" -> !Rt.eq(l, r)
            "<", ">", "<=", ">=" -> compare(e, l, r)
            "+" -> plus(e, l, r)
            else -> arithmetic(e, l, r)
        }
    }

    private fun compare(e: Binary, l: Any?, r: Any?): Boolean {
        if (l == null || r == null) {
            if (st.strict) throw err(e.span, ErrorKind.Type, "Cannot compare null with '${e.op}'")
            return false
        }
        val c: Int = when {
            l is String && r is String -> l.compareTo(r)
            l is KDate && r is KDate -> l.instant.compareTo(r.instant)
            else -> {
                val a = Rt.toNumberOrNull(l)
                val b = Rt.toNumberOrNull(r)
                if (a == null || b == null) throw err(e.span, ErrorKind.Type, "Cannot compare ${Rt.typeName(l)} with ${Rt.typeName(r)} using '${e.op}'")
                a.compareTo(b)
            }
        }
        return when (e.op) { "<" -> c < 0; ">" -> c > 0; "<=" -> c <= 0; else -> c >= 0 }
    }

    private fun plus(e: Binary, l: Any?, r: Any?): Any? {
        if (l is String || r is String) {
            if ((l is Map<*, *> || l is List<*>) || (r is Map<*, *> || r is List<*>)) {
                throw err(e.span, ErrorKind.Type, "Cannot join text with ${Rt.typeName(if (l is String) r else l)}; use join() or JSON.stringify()")
            }
            val total = Rt.str(l).length.toLong() + Rt.str(r).length
            if (total > st.limits.maxStringLength) throw err(e.span, ErrorKind.Limit, "String longer than ${st.limits.maxStringLength} characters")
            return Rt.str(l) + Rt.str(r)
        }
        return arithmetic(e, l, r)
    }

    private fun arithmetic(e: Binary, l: Any?, r: Any?): Any? {
        if (l == null || r == null) {
            if (st.strict) throw err(e.span, ErrorKind.Type, "Cannot apply '${e.op}' to null")
            return null
        }
        val a = Rt.toNumberOrNull(l) ?: throw err(e.span, ErrorKind.Type, "Cannot apply '${e.op}' to ${Rt.typeName(l)} '${shorten(l)}'")
        val b = Rt.toNumberOrNull(r) ?: throw err(e.span, ErrorKind.Type, "Cannot apply '${e.op}' to ${Rt.typeName(r)} '${shorten(r)}'")
        val out = when (e.op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> { if (b == 0.0) throw err(e.span, ErrorKind.Runtime, "Division by zero"); a / b }
            "%" -> { if (b == 0.0) throw err(e.span, ErrorKind.Runtime, "Division by zero"); a % b }
            "**" -> Math.pow(a, b)
            else -> throw err(e.span, ErrorKind.Runtime, "Unknown operator '${e.op}'")
        }
        if (out.isNaN() || out.isInfinite()) throw err(e.span, ErrorKind.Runtime, "Result is not a finite number")
        return out
    }

    // ---- calls ----

    fun callLambda(f: Lambda, args: List<Any?>, span: Span): Any? {
        if (st.callDepth >= st.limits.maxCallDepth) throw err(span, ErrorKind.Limit, "Function calls nested deeper than ${st.limits.maxCallDepth}")
        st.callDepth++
        try {
            val values = arrayOfNulls<Any?>(f.params.size)
            for (i in values.indices) values[i] = args.getOrNull(i)
            return eval(f.body, Env(f.params, values, f.env))
        } finally {
            st.callDepth--
        }
    }

    private fun call(e: Call, env: Env?): Any? {
        val callee = e.callee
        if (callee is Member) return callMember(e, callee, env)
        if (callee is Identifier) {
            val name = callee.name
            val local = env?.lookup(name)
            if (local != null && local.first) return callValue(e, local.second, env)
            val fn = Library.global[name]
            if (fn != null) return invoke(e.span, fn, name, null, evalArgs(e, env))
            val coerce = Library.callableNamespaces[name]
            if (coerce != null) return invoke(e.span, coerce, name, null, evalArgs(e, env))
            if (name.startsWith("$") && scope.containsKey(name.substring(1))) {
                throw err(callee.span, ErrorKind.Type, "'$name' is a value, not a function")
            }
            val candidates = (env?.allNames().orEmpty()) + Library.global.keys + Library.callableNamespaces.keys
            throw err(callee.span, ErrorKind.Unknown, "Unknown function '$name'", suggest(name, candidates))
        }
        val v = evalTarget(callee, env)
        if (v === SHORT) return SHORT
        if (e.optional && v == null) return SHORT
        return callValue(e, v, env)
    }

    private fun callValue(e: Call, f: Any?, env: Env?): Any? {
        if (f is Lambda) return callLambda(f, evalArgs(e, env), e.span)
        throw err(e.callee.span, ErrorKind.Type, "${Rt.typeName(f).replaceFirstChar { it.uppercase() }} is not a function")
    }

    private fun evalArgs(e: Call, env: Env?): List<Any?> {
        if (e.args.isEmpty()) return emptyList()
        val out = ArrayList<Any?>(e.args.size)
        for (a in e.args) out += eval(a, env)
        return out
    }

    private fun callMember(e: Call, m: Member, env: Env?): Any? {
        val t = m.target
        // Namespace function: Math.round(...)
        if (t is Identifier && !t.name.startsWith("$") && (env == null || !env.lookup(t.name).first)) {
            val ns = Library.namespaces[t.name]
            if (ns != null) {
                val fn = ns[m.name] ?: throw err(m.nameSpan, ErrorKind.Unknown, "'${t.name}' has no function '${m.name}'", suggest(m.name, ns.keys))
                return invoke(e.span, fn, "${t.name}.${m.name}", null, evalArgs(e, env))
            }
        }
        val recv = evalTarget(t, env)
        if (recv === SHORT) return SHORT
        if (recv == null && (m.optional || e.optional)) return SHORT
        if (recv == null && !st.strict) return null
        if (recv == null) throw err(m.nameSpan, ErrorKind.Type, "Cannot call '${m.name}' on null")
        val args = evalArgs(e, env)
        return Library.callMethod(this, recv, m.name, args, e, m)
    }

    fun invoke(span: Span, fn: Fn, name: String, receiver: Any?, args: List<Any?>): Any? {
        if (args.size < fn.min || args.size > fn.max) {
            val expected = if (fn.min == fn.max) "${fn.min}" else if (fn.max == Int.MAX_VALUE) "at least ${fn.min}" else "${fn.min} to ${fn.max}"
            throw err(span, ErrorKind.Type, "$name() takes $expected argument(s) but got ${args.size}")
        }
        val ctx = CallCtx(this, span, name)
        return fn.impl(ctx, receiver, args)
    }
}
