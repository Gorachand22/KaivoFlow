package com.kaivoflow.core.model

/**
 * Stores a [ParamValue] in a place that can only hold one string: a variable value, a credential
 * field, an agent prompt, a trigger setting.
 *
 * - A fixed text is written as itself, so everything saved before expressions existed reads back
 *   unchanged as [ParamValue.Fixed]. A stored plain string is never guessed to be an expression,
 *   even when it contains `{{`.
 * - An expression is written as the compact JSON `{"kx":"<text>"}` (same marker as [ParamValueCodec]).
 * - A fixed text that itself starts with the marker is wrapped as `{"kxLiteral":"<text>"}` so it reads back verbatim.
 */
object ExprFieldCodec {
    private const val MARKER = "{\"kx"

    fun encode(value: ParamValue?): String = when (value) {
        null -> ""
        is ParamValue.Expr -> ParamJson.write(mapOf(ParamValueCodec.EXPR_KEY to value.text))
        is ParamValue.Fixed -> {
            val s = textOfFixed(value.value)
            if (s.startsWith(MARKER)) ParamJson.write(mapOf(ParamValueCodec.LITERAL_KEY to s)) else s
        }
    }

    /** Reads either format. Null and blank give an empty fixed value. */
    fun decode(stored: String?): ParamValue {
        if (stored == null) return ParamValue.Fixed("")
        if (stored.startsWith(MARKER)) {
            val parsed = (ParamJson.parse(stored) as? ParamJson.Parsed.Ok)?.value
            if (parsed is Map<*, *> && parsed.size == 1) {
                val only = parsed.entries.first()
                if (only.key == ParamValueCodec.EXPR_KEY && only.value is String) return ParamValue.Expr(only.value as String)
                if (only.key == ParamValueCodec.LITERAL_KEY && only.value is String) return ParamValue.Fixed(only.value as String)
            }
        }
        return ParamValue.Fixed(stored)
    }

    fun isExpression(stored: String?): Boolean = decode(stored) is ParamValue.Expr

    /** What a list row or summary shows: the text of either mode. */
    fun displayText(stored: String?): String = ExprInputModes.text(decode(stored))

    fun encodeAll(values: Map<String, ParamValue?>): Map<String, String> =
        values.entries.associateTo(LinkedHashMap()) { it.key to encode(it.value) }

    fun decodeAll(stored: Map<String, String>): Map<String, ParamValue> =
        stored.entries.associateTo(LinkedHashMap()) { it.key to decode(it.value) }

    private fun textOfFixed(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Double -> if (v == Math.floor(v) && Math.abs(v) < 9.0e15) v.toLong().toString() else v.toString()
        else -> v.toString()
    }
}

/** Mode changes of a text input that can be fixed or an expression (the logic behind the fx pearl). */
object ExprInputModes {
    fun isExpression(value: ParamValue?): Boolean = value is ParamValue.Expr

    fun text(value: ParamValue?): String = when (value) {
        null -> ""
        is ParamValue.Expr -> value.text
        is ParamValue.Fixed -> when (val v = value.value) { null -> ""; is String -> v; else -> v.toString() }
    }

    /** The text stays; the user then adds `{{ }}` parts. */
    fun toExpression(current: ParamValue?): ParamValue.Expr = ParamValue.Expr(text(current))

    /** Back to fixed: the evaluated preview when it is plain text, otherwise the expression text is kept as typed. */
    fun toFixed(current: ParamValue?, evaluated: String? = null): ParamValue.Fixed =
        ParamValue.Fixed(evaluated ?: text(current))

    /** New text typed in the field; the mode does not change. */
    fun withText(current: ParamValue?, text: String): ParamValue =
        if (current is ParamValue.Expr) ParamValue.Expr(text) else ParamValue.Fixed(text)
}
