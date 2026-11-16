package com.kaivoflow.core.model

/**
 * Where a show rule is evaluated: the params of one level (the root, or one group or repeater
 * row), their stored values, and the level above. Credential slots that are filled are known
 * to the whole tree through [credentialsFilled].
 */
class ParamScope(
    val defs: List<ParamDef>,
    val values: Map<String, ParamValue?>,
    val parent: ParamScope? = null,
    val credentialsFilled: Set<String> = emptySet(),
) {
    val root: ParamScope get() = parent?.root ?: this

    fun child(defs: List<ParamDef>, values: Map<String, ParamValue?>) = ParamScope(defs, values, this, credentialsFilled)
}

/** Outcome of a show rule. [dependsOnExpr] is set when the answer hinged on an expression-controlled param. */
data class Visibility(val visible: Boolean, val dependsOnExpr: Boolean = false) {
    companion object {
        val Visible = Visibility(true)
    }
}

object ShowRules {
    private enum class Tri { True, False, Unknown }

    fun evaluate(rule: ShowWhen, scope: ParamScope): Visibility {
        if (rule is ShowWhen.Always) return Visibility.Visible
        return when (eval(rule, scope)) {
            Tri.True -> Visibility(true)
            Tri.False -> Visibility(false)
            // An unknown controlling value never hides a field: show it with a badge.
            Tri.Unknown -> Visibility(visible = true, dependsOnExpr = true)
        }
    }

    private fun eval(rule: ShowWhen, scope: ParamScope): Tri = when (rule) {
        ShowWhen.Always -> Tri.True
        is ShowWhen.All -> {
            val rs = rule.of.map { eval(it, scope) }
            when {
                Tri.False in rs -> Tri.False
                Tri.Unknown in rs -> Tri.Unknown
                else -> Tri.True
            }
        }
        is ShowWhen.Any -> {
            val rs = rule.of.map { eval(it, scope) }
            when {
                Tri.True in rs -> Tri.True
                Tri.Unknown in rs -> Tri.Unknown
                else -> if (rs.isEmpty()) Tri.True else Tri.False
            }
        }
        is ShowWhen.Not -> when (eval(rule.of, scope)) {
            Tri.True -> Tri.False
            Tri.False -> Tri.True
            Tri.Unknown -> Tri.Unknown
        }
        is ShowWhen.CredentialSet -> if (rule.slot in scope.credentialsFilled) Tri.True else Tri.False
        is ShowWhen.Eq -> leaf(rule.path, scope) { v -> looseEquals(v, rule.value) }
        is ShowWhen.Ne -> leaf(rule.path, scope) { v -> !looseEquals(v, rule.value) }
        is ShowWhen.In -> leaf(rule.path, scope) { v -> rule.values.any { looseEquals(v, it) } }
        is ShowWhen.Cmp -> leaf(rule.path, scope) { v ->
            val n = toDouble(v)
            n != null && when (rule.op) {
                CmpOp.Gt -> n > rule.value
                CmpOp.Ge -> n >= rule.value
                CmpOp.Lt -> n < rule.value
                CmpOp.Le -> n <= rule.value
            }
        }
        is ShowWhen.Exists -> leaf(rule.path, scope) { v -> v != null }
        is ShowWhen.Empty -> leaf(rule.path, scope) { v -> isEmpty(v) }
    }

    private inline fun leaf(path: String, scope: ParamScope, test: (Any?) -> Boolean): Tri =
        when (val r = lookup(path, scope)) {
            Lookup.Unknown -> Tri.Unknown
            is Lookup.Literal -> if (test(r.value)) Tri.True else Tri.False
        }

    private sealed interface Lookup {
        data object Unknown : Lookup
        data class Literal(val value: Any?) : Lookup
    }

    /** Resolves [path] to the controlling literal: stored value, else the param's default; an expression is unknown. */
    private fun lookup(path: String, from: ParamScope): Lookup {
        var scope = from
        var rest = path
        if (rest.startsWith("/")) {
            scope = from.root
            rest = rest.removePrefix("/")
        } else {
            while (rest.startsWith("../")) {
                scope = scope.parent ?: scope
                rest = rest.removePrefix("../")
            }
        }
        val head = rest.substringBefore('.')
        val tail = if (rest.contains('.')) rest.substringAfter('.') else ""
        val stored = scope.values[head] ?: scope.defs.firstOrNull { it.key == head }?.default
        val v = when (stored) {
            null -> return Lookup.Literal(null)
            is ParamValue.Expr -> return Lookup.Unknown
            is ParamValue.Fixed -> stored.value
        }
        if (tail.isEmpty()) return Lookup.Literal(v)
        var cur: Any? = v
        for (seg in tail.split('.')) {
            cur = when (cur) {
                is Map<*, *> -> cur[seg]
                is List<*> -> seg.toIntOrNull()?.let { cur.getOrNull(it) }
                else -> null
            }
            if (cur is Map<*, *> && cur.size == 1 && cur.containsKey(ParamValueCodec.EXPR_KEY)) return Lookup.Unknown
        }
        return Lookup.Literal(cur)
    }

    internal fun toDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    private fun looseEquals(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return (a?.toString() ?: "") == (b?.toString() ?: "")
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        if (a is Boolean || b is Boolean) return a.toString().equals(b.toString(), ignoreCase = true)
        return a.toString() == b.toString()
    }

    internal fun isEmpty(v: Any?): Boolean = when (v) {
        null -> true
        is String -> v.isBlank()
        is Collection<*> -> v.isEmpty()
        is Map<*, *> -> v.isEmpty()
        else -> false
    }
}
