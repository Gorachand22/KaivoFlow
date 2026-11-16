package com.kaivoflow.core.engine

import com.kaivoflow.core.model.ExprMode
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamJson
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.ParamScope
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.ParamValueCodec
import com.kaivoflow.core.model.ShowRules
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.toSchema

/** A resolved parameter could not be read as the type the Step asked for. */
class ParamCoercionException(
    val key: String,
    val expected: String,
    val actual: String,
) : KaivoException("Parameter \"$key\" must be $expected but is $actual")

/** A required parameter is absent (hidden, unset with no default, or null). */
class ParamMissingException(val key: String) : KaivoException("Parameter \"$key\" is missing")

/**
 * The parameters of one Step for one item, after defaults, show rules and expressions.
 *
 * Only visible params are present; hidden ones are omitted. Accessors coerce explicitly and fail
 * with [ParamCoercionException] naming the key, the expected type and what was found, instead of
 * silently turning a bad value into an empty string or zero.
 */
class ResolvedParams(private val store: Map<String, Any?>) {
    val values: Map<String, Any?> get() = store
    val keys: Set<String> get() = store.keys
    operator fun get(key: String): Any? = store[key]
    operator fun contains(key: String): Boolean = store.containsKey(key)
    fun isSet(key: String): Boolean = store[key] != null
    fun toMap(): Map<String, Any?> = store

    fun stringOrNull(key: String): String? {
        return when (val v = store[key]) {
            null -> null
            is String -> v
            is Boolean -> v.toString()
            is Number -> Values.asString(v)
            else -> throw ParamCoercionException(key, "text", describe(v))
        }
    }
    fun string(key: String): String = stringOrNull(key) ?: throw ParamMissingException(key)
    fun string(key: String, default: String): String = stringOrNull(key) ?: default

    fun doubleOrNull(key: String): Double? {
        return when (val v = store[key]) {
            null -> null
            is Number -> v.toDouble().also { if (it.isNaN() || it.isInfinite()) throw ParamCoercionException(key, "a number", describe(v)) }
            is String -> v.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                ?: throw ParamCoercionException(key, "a number", describe(v))
            else -> throw ParamCoercionException(key, "a number", describe(v))
        }
    }
    fun double(key: String): Double = doubleOrNull(key) ?: throw ParamMissingException(key)
    fun double(key: String, default: Double): Double = doubleOrNull(key) ?: default

    fun longOrNull(key: String): Long? {
        val d = doubleOrNull(key) ?: return null
        val v = store[key]
        if (v is Long) return v
        if (d != Math.floor(d) || Math.abs(d) > 9.0e15) throw ParamCoercionException(key, "a whole number", describe(v))
        return d.toLong()
    }
    fun long(key: String): Long = longOrNull(key) ?: throw ParamMissingException(key)
    fun long(key: String, default: Long): Long = longOrNull(key) ?: default

    fun intOrNull(key: String): Int? {
        val l = longOrNull(key) ?: return null
        if (l > Int.MAX_VALUE || l < Int.MIN_VALUE) throw ParamCoercionException(key, "a whole number in range", describe(store[key]))
        return l.toInt()
    }
    fun int(key: String): Int = intOrNull(key) ?: throw ParamMissingException(key)
    fun int(key: String, default: Int): Int = intOrNull(key) ?: default

    fun boolOrNull(key: String): Boolean? {
        return when (val v = store[key]) {
            null -> null
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw ParamCoercionException(key, "true or false", describe(v))
            }
            else -> throw ParamCoercionException(key, "true or false", describe(v))
        }
    }
    fun bool(key: String): Boolean = boolOrNull(key) ?: throw ParamMissingException(key)
    fun bool(key: String, default: Boolean): Boolean = boolOrNull(key) ?: default

    fun listOrNull(key: String): List<Any?>? {
        return when (val v = store[key]) {
            null -> null
            is List<*> -> v
            is String -> parseJson(key, v, "a list") as? List<Any?> ?: throw ParamCoercionException(key, "a list", describe(v))
            else -> throw ParamCoercionException(key, "a list", describe(v))
        }
    }
    fun list(key: String): List<Any?> = listOrNull(key) ?: throw ParamMissingException(key)

    fun mapOrNull(key: String): Map<String, Any?>? {
        return when (val v = store[key]) {
            null -> null
            is Map<*, *> -> v.entries.associate { it.key.toString() to it.value }
            is String -> (parseJson(key, v, "an object") as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                ?: throw ParamCoercionException(key, "an object", describe(v))
            else -> throw ParamCoercionException(key, "an object", describe(v))
        }
    }
    fun map(key: String): Map<String, Any?> = mapOrNull(key) ?: throw ParamMissingException(key)

    /** A JSON param: text is parsed (error names line and column), already-parsed values pass through. */
    fun json(key: String): Any? = when (val v = store[key]) {
        is String -> parseJson(key, v, "valid JSON")
        else -> v
    }

    private fun parseJson(key: String, text: String, expected: String): Any? = when (val r = ParamJson.parse(text)) {
        is ParamJson.Parsed.Ok -> r.value
        is ParamJson.Parsed.Failed -> throw ParamCoercionException(
            key, expected, "text that is not JSON (${r.error.message}, line ${r.error.line}, column ${r.error.column})",
        )
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "empty"
        is String -> "text \"${v.take(40)}\""
        is Boolean -> "a boolean"
        is Number -> "a number"
        is List<*> -> "a list"
        is Map<*, *> -> "an object"
        else -> v::class.java.simpleName
    }
}

/**
 * Resolves Step parameters into concrete values.
 *
 * Two entry points share the expression rules:
 * - [resolve] and [resolveStored]: schema driven. Defaults are applied here and only here, for
 *   visible params; hidden params are omitted; expressions are evaluated against the scope of one
 *   item; params that never take an expression (Secret, Code, credential ids, `ExprMode.Never`) are
 *   passed through verbatim.
 * - [resolveParams]: the compatibility view used by `StepContext.params()`. It returns every stored
 *   key, applies no defaults and hides nothing, exactly as Steps written before the schema expect.
 */
class ParamResolver(private val evaluator: ExpressionEvaluator) {

    // ---- compatibility view ----

    fun resolveValue(value: Any?, scope: Map<String, Any?>, supportsExpression: Boolean = true): Any? {
        if (!supportsExpression) return value
        return expand(value, scope, markers = false)
    }

    fun resolveParams(step: StepNode, scope: Map<String, Any?>, spec: NodeSpec? = null): Map<String, Any?> {
        val literal = spec?.params?.filter { !it.supportsExpression }?.map { it.key }?.toSet().orEmpty()
        return step.params.entries.associate { (key, raw) ->
            key to resolveValue(raw, scope, supportsExpression = key !in literal)
        }
    }

    fun resolveString(value: Any?, scope: Map<String, Any?>, field: String): String {
        val resolved = resolveValue(value, scope) ?: return ""
        return Values.asString(resolved)
    }

    // ---- schema driven ----

    /** Resolves [stored] values of a current-format Step. */
    fun resolve(
        schema: ParamSchema,
        stored: Map<String, ParamValue?>,
        scope: Map<String, Any?>,
        credentialsFilled: Set<String> = emptySet(),
    ): ResolvedParams = Run(scope, markers = true).top(schema, stored, credentialsFilled)

    /** Resolves a Step that still stores plain values (a string with `{{` is an expression). */
    fun resolveStored(
        schema: ParamSchema,
        raw: Map<String, Any?>,
        scope: Map<String, Any?>,
        credentialsFilled: Set<String> = emptySet(),
    ): ResolvedParams = Run(scope, markers = false).top(schema, ParamValueCodec.decodeAllLegacy(raw), credentialsFilled)

    /** Expands every embedded expression in a plain value. [markers] enables `{"kx": text}` objects. */
    private fun expand(value: Any?, scope: Map<String, Any?>, markers: Boolean): Any? = when (value) {
        is String -> if (value.contains("{{")) evaluator.interpolate(value, scope) else value
        is Map<*, *> -> {
            val only = if (markers && value.size == 1) value.entries.first() else null
            when {
                only != null && only.key == ParamValueCodec.EXPR_KEY && only.value is String ->
                    evaluator.interpolate(only.value as String, scope)
                only != null && only.key == ParamValueCodec.LITERAL_KEY -> only.value
                else -> value.entries.associate { it.key.toString() to expand(it.value, scope, markers) }
            }
        }
        is List<*> -> value.map { expand(it, scope, markers) }
        else -> value
    }

    private inner class Run(val scope: Map<String, Any?>, val markers: Boolean) {

        fun top(schema: ParamSchema, stored: Map<String, ParamValue?>, filled: Set<String>): ResolvedParams {
            val ps = ParamScope(schema.params, stored, null, filled)
            val out = LinkedHashMap<String, Any?>()
            level(schema.params, stored, ps, out, onlyPresent = false)
            return ResolvedParams(out)
        }

        private fun level(
            defs: List<ParamDef>,
            stored: Map<String, ParamValue?>,
            ps: ParamScope,
            out: MutableMap<String, Any?>,
            onlyPresent: Boolean,
        ) {
            for (def in defs) {
                if (def.kind is ParamKind.Notice) continue
                if (!ShowRules.evaluate(def.show, ps).visible) continue
                val own = stored[def.key]?.takeUnless { it is ParamValue.Fixed && it.value == null }
                // The one place defaults are applied.
                val pv = own ?: (if (onlyPresent) null else def.default) ?: continue
                out[def.key] = param(def, pv, ps)
            }
        }

        private fun param(def: ParamDef, pv: ParamValue, ps: ParamScope): Any? {
            val kind = def.kind
            val never = def.expr == ExprMode.Never || kind is ParamKind.Secret || kind is ParamKind.Code ||
                kind is ParamKind.CredentialPick
            if (never) return verbatim(pv)
            when (kind) {
                is ParamKind.Group -> return container(pv, kind.children, ps, onlyPresent = false)
                is ParamKind.Collection -> return container(pv, kind.optional, ps, onlyPresent = true)
                is ParamKind.Repeater -> {
                    val list = (pv as? ParamValue.Fixed)?.value as? List<*> ?: return verbatim(pv)
                    return list.map { row ->
                        if (row is Map<*, *>) rowMap(row, kind.children, ps, onlyPresent = false) else row
                    }
                }
                else -> Unit
            }
            return when (pv) {
                is ParamValue.Expr -> evalText(pv.text, def.expr == ExprMode.Only)
                is ParamValue.Fixed -> {
                    val v = pv.value
                    if (def.expr == ExprMode.Only && v is String && v.isNotBlank()) evalText(v, true)
                    else fixed(kind, v)
                }
            }
        }

        private fun verbatim(pv: ParamValue): Any? = when (pv) {
            is ParamValue.Expr -> pv.text
            is ParamValue.Fixed -> pv.value
        }

        private fun evalText(text: String, only: Boolean): Any? =
            if (only && !text.contains("{{")) evaluator.evaluate(text, scope) else evaluator.interpolate(text, scope)

        private fun container(pv: ParamValue, children: List<ParamDef>, ps: ParamScope, onlyPresent: Boolean): Any? {
            val map = (pv as? ParamValue.Fixed)?.value as? Map<*, *> ?: return verbatim(pv)
            return rowMap(map, children, ps, onlyPresent)
        }

        private fun rowMap(row: Map<*, *>, children: List<ParamDef>, parent: ParamScope, onlyPresent: Boolean): Map<String, Any?> {
            val stored: Map<String, ParamValue?> = row.entries.associate {
                it.key.toString() to if (markers) ParamValueCodec.decodeLenient(it.value) else ParamValueCodec.decodeLegacy(it.value)
            }
            val out = LinkedHashMap<String, Any?>()
            level(children, stored, parent.child(children, stored), out, onlyPresent)
            return out
        }

        /** A fixed value: plain scalars are literal; containers expand the embedded expressions of their value-like parts. */
        private fun fixed(kind: ParamKind, v: Any?): Any? = when (kind) {
            is ParamKind.Json, is ParamKind.KeyValueList -> expand(v, scope, markers)
            is ParamKind.Assignments -> rows(v, verbatimKeys = setOf("type"))
            is ParamKind.Conditions -> conditions(v)
            is ParamKind.ResourcePick -> pick(v, expandKeys = setOf("value"))
            is ParamKind.FileRef -> pick(v, expandKeys = setOf("property", "fileName"))
            else -> v
        }

        private fun rows(v: Any?, verbatimKeys: Set<String>): Any? {
            val list = v as? List<*> ?: return v
            return list.map { row ->
                if (row !is Map<*, *>) row
                else row.entries.associate { (k, x) ->
                    k.toString() to if (k.toString() in verbatimKeys) x else expand(x, scope, markers)
                }
            }
        }

        private fun conditions(v: Any?): Any? {
            val m = v as? Map<*, *> ?: return v
            return m.entries.associate { (k, x) ->
                k.toString() to if (k == "conditions") rows(x, verbatimKeys = setOf("id", "op")) else x
            }
        }

        private fun pick(v: Any?, expandKeys: Set<String>): Any? {
            val m = v as? Map<*, *> ?: return v
            return m.entries.associate { (k, x) -> k.toString() to if (k.toString() in expandKeys) expand(x, scope, markers) else x }
        }
    }
}
