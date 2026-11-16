package com.kaivoflow.feature.canvas.inspector.params

import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.expressions.assist.ReferenceBuilder
import com.kaivoflow.core.model.ExprMode
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.ParamValueCodec

// Pure helpers shared by the parameter widgets (spec P2 sections 2 and 3.5). No Compose, no Android.

object ParamModes {
    /** Kinds whose own value can be an expression. Containers and secret-like kinds hold expressions in their children, or never. */
    fun kindAcceptsExpression(kind: ParamKind): Boolean = when (kind) {
        is ParamKind.Code, ParamKind.Secret, is ParamKind.CredentialPick, is ParamKind.Notice,
        is ParamKind.Collection, is ParamKind.Group, is ParamKind.Repeater, is ParamKind.Assignments,
        is ParamKind.Conditions, ParamKind.KeyValueList, is ParamKind.ResourcePick, is ParamKind.FileRef -> false
        else -> true
    }

    /** The trailing Fixed/fx pearl is shown only where the user can switch. `Only` starts as an expression with a small marker. */
    fun showsPearl(def: ParamDef): Boolean = def.expr == ExprMode.Allowed && kindAcceptsExpression(def.kind)

    fun isExprOnly(def: ParamDef): Boolean = def.expr == ExprMode.Only && kindAcceptsExpression(def.kind)

    fun isExpression(def: ParamDef, value: ParamValue?): Boolean =
        isExprOnly(def) || (value ?: def.default) is ParamValue.Expr

    /** Text keeps its literal; every other kind wraps the literal inside `{{ }}`. */
    fun toExpression(def: ParamDef, current: ParamValue?): ParamValue.Expr {
        val literal = (current as? ParamValue.Fixed)?.value
        val text = when {
            literal == null -> ""
            def.kind is ParamKind.Text && literal is String -> literal
            literal is String -> "{{ ${ReferenceBuilder.quote(literal)} }}"
            literal is Number -> "{{ ${Values.asString(literal)} }}"
            literal is Boolean -> "{{ $literal }}"
            else -> "{{ ${Values.stringify(literal)} }}"
        }
        return ParamValue.Expr(text)
    }

    /** Switching back offers the evaluated preview as the literal; null clears. */
    fun toFixed(literal: Any?): ParamValue.Fixed? = literal?.let { ParamValue.Fixed(it) }

    /** The text a plain-text widget shows for a stored fixed value. */
    fun fixedText(value: ParamValue?): String = when (val v = (value as? ParamValue.Fixed)?.value) {
        null -> ""
        is String -> v
        else -> Values.asString(v)
    }

    fun exprText(value: ParamValue?): String = (value as? ParamValue.Expr)?.text ?: ""
}

/** What a stored container value looks like to the widgets. Children use the nested codec (`{"kx": ...}` for expressions). */
object ParamTree {
    fun asMap(v: ParamValue?): Map<String, Any?> =
        ((v as? ParamValue.Fixed)?.value as? Map<*, *>)?.entries?.associateTo(LinkedHashMap()) { it.key.toString() to it.value } ?: emptyMap()

    fun asRows(v: ParamValue?): List<Map<String, Any?>> =
        ((v as? ParamValue.Fixed)?.value as? List<*>)?.map { row ->
            (row as? Map<*, *>)?.entries?.associateTo(LinkedHashMap()) { it.key.toString() to it.value } ?: emptyMap()
        } ?: emptyList()

    fun child(map: Map<String, Any?>, key: String): ParamValue? = ParamValueCodec.decodeLenient(map[key])

    fun withChild(map: Map<String, Any?>, key: String, value: ParamValue?): Map<String, Any?> {
        val out = LinkedHashMap(map)
        if (value == null) out.remove(key) else out[key] = ParamValueCodec.encode(value)
        return out
    }

    fun fixed(map: Map<String, Any?>): ParamValue? = if (map.isEmpty()) null else ParamValue.Fixed(map)
    fun fixedRows(rows: List<Map<String, Any?>>): ParamValue? = if (rows.isEmpty()) null else ParamValue.Fixed(rows)
}

/** The message of the undo bar for an edit, or null when the edit is not worth announcing. */
object UndoText {
    fun describe(before: ParamValue?, after: ParamValue?): String? {
        if (after is ParamValue.Expr && before !is ParamValue.Expr) return "Switched to expression"
        if (before is ParamValue.Expr && after !is ParamValue.Expr) return "Switched to fixed value"
        val b = listSize(before)
        if (b > 0 && listSize(after) < b) return "Removed row"
        return null
    }

    fun describeDrop(): String = "Inserted reference"

    private fun listSize(v: ParamValue?): Int = ((v as? ParamValue.Fixed)?.value as? List<*>)?.size ?: 0
}

/** Number text handling: dot decimal, empty is unset (not zero), clamps and steps. */
object NumberInput {
    sealed interface Parsed {
        data object Empty : Parsed
        data class Ok(val value: Double) : Parsed
        data class Invalid(val reason: String) : Parsed
    }

    fun parse(text: String, integer: Boolean): Parsed {
        val t = text.trim()
        if (t.isEmpty()) return Parsed.Empty
        // Lone sign or dot are intermediate states while typing; they are not an error until the field loses focus.
        if (t == "-" || t == "." || t == "-.") return Parsed.Invalid("Type a number")
        val d = t.toDoubleOrNull() ?: return Parsed.Invalid("Not a number")
        if (d.isNaN() || d.isInfinite()) return Parsed.Invalid("Not a number")
        if (integer && d != Math.floor(d)) return Parsed.Invalid("Whole numbers only")
        return Parsed.Ok(d)
    }

    /** Stored form: whole values as Long so `5` is not written `5.0`. */
    fun toStored(d: Double, integer: Boolean): Any =
        if (integer || (d == Math.floor(d) && Math.abs(d) < 9.0e15)) d.toLong() else d

    fun format(value: Any?): String = when (value) {
        null -> ""
        is Int, is Long -> value.toString()
        is Number -> {
            val d = value.toDouble()
            if (d == Math.floor(d) && Math.abs(d) < 9.0e15) d.toLong().toString() else d.toString()
        }
        else -> value.toString()
    }

    /** One stepper tap. [direction] is +1 or -1; an unset value starts from min (or 0). */
    fun step(current: Double?, direction: Int, step: Double?, min: Double?, max: Double?, integer: Boolean): Double {
        val s = (step ?: 1.0).let { if (it <= 0.0) 1.0 else it }.let { if (integer) Math.max(1.0, Math.rint(it)) else it }
        val base = current ?: (min ?: 0.0)
        var next = if (current == null) base else base + direction * s
        if (min != null && next < min) next = min
        if (max != null && next > max) next = max
        // Remove binary noise: 0.1 + 0.2 should read 0.3.
        next = Math.round(next * 1e9) / 1e9
        return next
    }

    /** The slider is offered when both ends are set and the range holds fewer than 1000 steps. */
    fun sliderEligible(min: Double?, max: Double?, step: Double?, integer: Boolean): Boolean {
        if (min == null || max == null || max <= min) return false
        val s = (step ?: 1.0).let { if (it <= 0.0) 1.0 else it }
        val effective = if (integer) Math.max(1.0, s) else s
        return (max - min) / effective < 1000.0
    }

    /** Long-press auto repeat: interval shrinks from 400 ms to 60 ms. */
    fun repeatDelayMs(repeats: Int): Long = when {
        repeats < 3 -> 400L
        repeats < 8 -> 200L
        repeats < 20 -> 100L
        else -> 60L
    }
}
