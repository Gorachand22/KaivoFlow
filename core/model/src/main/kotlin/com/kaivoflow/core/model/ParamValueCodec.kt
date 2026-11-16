package com.kaivoflow.core.model

/**
 * Stored form of [ParamValue].
 *
 * Current format: a fixed value is the plain JSON value; an expression is `{"kx": "<text>"}`.
 * A fixed object whose only key is `kx` (or the escape key `kxLiteral`) is written wrapped as
 * `{"kxLiteral": <object>}`, so a literal object inside a Json param is never mistaken for an
 * expression and always reads back as the same fixed value.
 *
 * Legacy format (before ParamValue): plain values where a string containing `{{` is an
 * expression. [decodeLegacy] reads it; maps are never expressions there, even with a `kx` key.
 */
object ParamValueCodec {
    const val EXPR_KEY = "kx"
    const val LITERAL_KEY = "kxLiteral"

    /** Plain JSON-ish value to store. Null stays null. */
    fun encode(value: ParamValue?): Any? = when (value) {
        null -> null
        is ParamValue.Expr -> mapOf(EXPR_KEY to value.text)
        is ParamValue.Fixed -> {
            val v = value.value
            if (v is Map<*, *> && v.size == 1 && (v.containsKey(EXPR_KEY) || v.containsKey(LITERAL_KEY))) {
                mapOf(LITERAL_KEY to v)
            } else v
        }
    }

    /** Reads the current format. Strings are never inferred to be expressions. */
    fun decode(raw: Any?): ParamValue? {
        if (raw is Map<*, *> && raw.size == 1) {
            val only = raw.entries.first()
            if (only.key == EXPR_KEY && only.value is String) return ParamValue.Expr(only.value as String)
            if (only.key == LITERAL_KEY) return ParamValue.Fixed(only.value)
        }
        return if (raw == null) null else ParamValue.Fixed(raw)
    }

    /** Reads the pre-ParamValue format: a string with `{{` is an expression, all else is fixed. */
    fun decodeLegacy(raw: Any?): ParamValue? = when {
        raw == null -> null
        raw is String && raw.contains("{{") -> ParamValue.Expr(raw)
        else -> ParamValue.Fixed(raw)
    }

    /** Reads either format: the `kx` markers and, failing those, legacy strings. Used for nested container values. */
    fun decodeLenient(raw: Any?): ParamValue? {
        val current = decode(raw)
        if (current is ParamValue.Fixed && current.value is String) return decodeLegacy(current.value)
        return current
    }

    fun encodeAll(values: Map<String, ParamValue?>): Map<String, Any?> =
        values.entries.associateTo(LinkedHashMap()) { it.key to encode(it.value) }

    fun decodeAll(raw: Map<String, Any?>): Map<String, ParamValue> =
        raw.entries.mapNotNull { (k, v) -> decode(v)?.let { k to it } }.toMap(LinkedHashMap())

    fun decodeAllLegacy(raw: Map<String, Any?>): Map<String, ParamValue> =
        raw.entries.mapNotNull { (k, v) -> decodeLegacy(v)?.let { k to it } }.toMap(LinkedHashMap())

    fun toJson(value: ParamValue?): String = ParamJson.write(encode(value))

    fun fromJson(text: String): ParamValue? = decode(ParamJson.parseOrThrow(text))
}
