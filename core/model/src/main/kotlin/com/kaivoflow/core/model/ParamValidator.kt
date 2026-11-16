package com.kaivoflow.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

enum class ValidationPhase {
    /** Editing: expression values are only syntax-checked. */
    DesignTime,

    /** Values are resolved literals. An expression left in the data is skipped. */
    Run,
}

/** Everything the validator needs besides the schema and the values. */
class ValidationContext(
    val phase: ValidationPhase = ValidationPhase.DesignTime,
    val credentialsFilled: Set<String> = emptySet(),
    /** Named validators for [ParamRule.Custom]: value to error message, or null when fine. */
    val custom: Map<String, (Any?) -> String?> = emptyMap(),
    /** Design-time expression syntax check: expression text to error message, or null. */
    val exprSyntax: ((String) -> String?)? = null,
)

/**
 * The only place parameter issues come from. Walks the schema once: hidden params are skipped
 * (so a hidden required param gives no issue), expression-controlled show rules count as visible,
 * and every kind checks its own value shape.
 */
object ParamValidator {

    fun validate(
        schema: ParamSchema,
        values: Map<String, ParamValue?>,
        context: ValidationContext = ValidationContext(),
    ): List<ParamIssue> {
        val out = ArrayList<ParamIssue>()
        val scope = ParamScope(schema.params, values, null, context.credentialsFilled)
        for (slot in schema.credentialSlots) {
            if (!slot.required) continue
            if (!ShowRules.evaluate(slot.show, scope).visible) continue
            if (slot.key !in context.credentialsFilled) {
                out += ParamIssue(ParamPath.of(slot.key), ParamSeverity.Error, "credential.missing", "${slot.label} needs a credential.")
            }
        }
        walk(schema.params, values, scope, ParamPath.Root, context, out)
        return out
    }

    private fun walk(
        defs: List<ParamDef>,
        values: Map<String, ParamValue?>,
        scope: ParamScope,
        at: ParamPath,
        ctx: ValidationContext,
        out: MutableList<ParamIssue>,
    ) {
        for (def in defs) {
            if (def.kind is ParamKind.Notice) continue
            if (!ShowRules.evaluate(def.show, scope).visible) continue
            checkParam(def, values[def.key] ?: def.default, scope, at.child(def.key), ctx, out)
        }
    }

    private fun checkParam(
        def: ParamDef,
        pv: ParamValue?,
        scope: ParamScope,
        path: ParamPath,
        ctx: ValidationContext,
        out: MutableList<ParamIssue>,
    ) {
        fun err(code: String, msg: String) { out += ParamIssue(path, ParamSeverity.Error, code, msg) }
        fun warn(code: String, msg: String) { out += ParamIssue(path, ParamSeverity.Warning, code, msg) }
        val label = def.label.ifBlank { def.key }
        val needsValue = def.required || ParamRule.Required in def.rules

        if (pv is ParamValue.Expr) {
            val neverExpr = def.expr == ExprMode.Never || def.kind.neverExpr()
            if (neverExpr) { err("expr.not_allowed", "$label cannot use an expression."); return }
            if (pv.text.isBlank()) { if (needsValue) err("required.missing", "$label is required."); return }
            if (ctx.phase == ValidationPhase.DesignTime) {
                ctx.exprSyntax?.invoke(pv.text)?.let { err("expr.syntax", "$label: $it") }
            }
            return
        }
        val raw = (pv as? ParamValue.Fixed)?.value
        // A group is always present: its children are checked (and may be required) even when nothing is stored.
        val v = if (def.kind is ParamKind.Group && ShowRules.isEmpty(raw)) emptyMap<String, Any?>() else raw
        if (ShowRules.isEmpty(v) && v !is Boolean && def.kind !is ParamKind.Group) {
            val kind = def.kind
            if (needsValue && kind !is ParamKind.Bool) err("required.missing", "$label is required.")
            else if (kind is ParamKind.Repeater && kind.minItems > 0) err("items.min", "$label needs at least ${kind.minItems} item(s).")
            return
        }

        when (val k = def.kind) {
            is ParamKind.Text -> text(def, v, k.format, path, out)
            is ParamKind.Number -> number(def, k, v, path, out)
            is ParamKind.Bool -> if (!(v is Boolean || (v is String && v.lowercase() in setOf("true", "false")))) {
                err("type.bool", "$label must be true or false.")
            }
            is ParamKind.Choice -> {
                val opts = (k.options as? OptionSource.Static)?.options
                if (opts != null && opts.none { it.value == Values0.str(v) }) {
                    err("choice.unknown", "$label: \"${Values0.str(v)}\" is no longer available.")
                }
            }
            is ParamKind.MultiChoice -> {
                val list = v as? List<*>
                if (list == null) err("type.list", "$label must be a list.") else {
                    val opts = (k.options as? OptionSource.Static)?.options
                    if (opts != null) list.filter { x -> opts.none { it.value == Values0.str(x) } }.forEach {
                        err("choice.unknown", "$label: \"${Values0.str(it)}\" is no longer available.")
                    }
                    if (list.size < k.min) err("items.min", "$label needs at least ${k.min} selection(s).")
                    if (k.max != null && list.size > k.max) err("items.max", "$label allows at most ${k.max} selection(s).")
                }
            }
            is ParamKind.DateTime -> if (!parsesAsDate(Values0.str(v), k.mode)) err("type.datetime", "$label is not a valid date or time.")
            is ParamKind.Json -> json(def, k, v, path, out)
            is ParamKind.Code -> {
                if (v !is String) err("type.text", "$label must be text.")
                else if (k.language == CodeLanguage.Json) ParamJson.checkTemplate(v)?.let {
                    err("json.invalid", "$label: ${it.message} (line ${it.line}, column ${it.column}).")
                }
            }
            is ParamKind.Color -> if (!(v is String && COLOR.matches(v.trim()))) err("type.color", "$label must be a hex color like #1A2B3C.")
            is ParamKind.Collection -> {
                val map = v as? Map<*, *>
                if (map == null) err("type.object", "$label must be an object.") else {
                    val known = k.optional.map { it.key }.toSet()
                    map.keys.map { it.toString() }.filter { it !in known }.forEach {
                        out += ParamIssue(path.child(it), ParamSeverity.Warning, "collection.unknown_key", "$label has an unknown field \"$it\".")
                    }
                    children(k.optional, map, scope, path, ctx, out, onlyPresent = true)
                }
            }
            is ParamKind.Group -> {
                val map = v as? Map<*, *>
                if (map == null) err("type.object", "$label must be an object.")
                else children(k.children, map, scope, path, ctx, out)
            }
            is ParamKind.Repeater -> {
                val list = v as? List<*>
                if (list == null) err("type.list", "$label must be a list.") else {
                    if (list.size < k.minItems) err("items.min", "$label needs at least ${k.minItems} item(s).")
                    if (k.maxItems != null && list.size > k.maxItems) err("items.max", "$label allows at most ${k.maxItems} item(s).")
                    list.forEachIndexed { i, row ->
                        val m = row as? Map<*, *>
                        if (m == null) out += ParamIssue(path.index(i), ParamSeverity.Error, "type.object", "$label row ${i + 1} must be an object.")
                        else children(k.children, m, scope, path.index(i), ctx, out)
                    }
                }
            }
            is ParamKind.KeyValueList -> keyValue(def, v, path, out)
            is ParamKind.Assignments -> assignments(def, k, v, path, out)
            is ParamKind.Conditions -> conditions(def, v, path, out)
            is ParamKind.ResourcePick -> resource(def, k, v, path, out)
            is ParamKind.CredentialPick -> if (v !is String || v.isBlank()) err("credential.missing", "$label needs a credential.")
            is ParamKind.Notice -> Unit
            is ParamKind.Secret -> if (v !is String) err("type.text", "$label must be text.")
            is ParamKind.FileRef -> fileRef(def, v, path, out)
        }

        for (rule in def.rules) rule(def, rule, v, ctx, path, out)
    }

    private fun ParamKind.neverExpr() = this is ParamKind.Secret || this is ParamKind.Code ||
        this is ParamKind.CredentialPick || this is ParamKind.Collection || this is ParamKind.Group ||
        this is ParamKind.Repeater || this is ParamKind.Notice

    private fun children(
        defs: List<ParamDef>,
        map: Map<*, *>,
        scope: ParamScope,
        at: ParamPath,
        ctx: ValidationContext,
        out: MutableList<ParamIssue>,
        onlyPresent: Boolean = false,
    ) {
        val stored: Map<String, ParamValue?> = map.entries.associate { it.key.toString() to ParamValueCodec.decodeLenient(it.value) }
        val sub = scope.child(defs, stored)
        for (child in defs) {
            if (child.kind is ParamKind.Notice) continue
            if (onlyPresent && child.key !in stored) continue
            if (!ShowRules.evaluate(child.show, sub).visible) continue
            checkParam(child, stored[child.key] ?: child.default, sub, at.child(child.key), ctx, out)
        }
    }

    private fun rule(
        def: ParamDef,
        rule: ParamRule,
        v: Any?,
        ctx: ValidationContext,
        path: ParamPath,
        out: MutableList<ParamIssue>,
    ) {
        val label = def.label.ifBlank { def.key }
        fun err(code: String, msg: String) { out += ParamIssue(path, ParamSeverity.Error, code, msg) }
        when (rule) {
            ParamRule.Required -> Unit // handled with the required flag
            is ParamRule.MinLength -> if (Values0.str(v).length < rule.n) err("length.min", "$label needs at least ${rule.n} characters.")
            is ParamRule.MaxLength -> if (Values0.str(v).length > rule.n) err("length.max", "$label allows at most ${rule.n} characters.")
            is ParamRule.Min -> ShowRules.toDouble(v)?.let { if (it < rule.n) err("number.min", "$label must be at least ${fmt(rule.n)}.") }
            is ParamRule.Max -> ShowRules.toDouble(v)?.let { if (it > rule.n) err("number.max", "$label must be at most ${fmt(rule.n)}.") }
            is ParamRule.Pattern -> {
                val re = runCatching { Regex(rule.regex) }.getOrNull()
                if (re == null) err("pattern.bad", "$label has an invalid pattern rule.")
                else if (!re.matches(Values0.str(v))) err("pattern.mismatch", "$label has the wrong format.")
            }
            is ParamRule.OneOf -> if (Values0.str(v) !in rule.values) err("choice.unknown", "$label must be one of: ${rule.values.joinToString(", ")}.")
            ParamRule.JsonValid -> if (v is String) ParamJson.checkTemplate(v)?.let {
                err("json.invalid", "$label: ${it.message} (line ${it.line}, column ${it.column}).")
            }
            ParamRule.UrlValid -> if (!validUrl(Values0.str(v))) err("url.invalid", "$label is not a valid URL.")
            is ParamRule.Custom -> {
                val fn = ctx.custom[rule.id]
                if (fn == null) err("custom.unregistered", "$label uses an unknown check \"${rule.id}\".")
                else fn(v)?.let { err("custom.${rule.id}", it) }
            }
        }
    }

    private fun text(def: ParamDef, v: Any?, format: TextFormat, path: ParamPath, out: MutableList<ParamIssue>) {
        val label = def.label.ifBlank { def.key }
        if (v is Map<*, *> || v is List<*>) {
            out += ParamIssue(path, ParamSeverity.Error, "type.text", "$label must be text."); return
        }
        val s = Values0.str(v)
        val bad = when (format) {
            TextFormat.Url -> if (s.contains("{{") || validUrl(s)) null else "url.invalid" to "$label is not a valid URL."
            TextFormat.Email -> if (s.contains("{{") || EMAIL.matches(s.trim())) null else "email.invalid" to "$label is not a valid email address."
            TextFormat.Phone -> if (s.contains("{{") || PHONE.matches(s.trim())) null else "phone.invalid" to "$label is not a valid phone number."
            else -> null
        }
        bad?.let { out += ParamIssue(path, ParamSeverity.Error, it.first, it.second) }
    }

    private fun number(def: ParamDef, k: ParamKind.Number, v: Any?, path: ParamPath, out: MutableList<ParamIssue>) {
        val label = def.label.ifBlank { def.key }
        fun err(code: String, msg: String) { out += ParamIssue(path, ParamSeverity.Error, code, msg) }
        if (v is Boolean || v is Map<*, *> || v is List<*>) { err("type.number", "$label must be a number."); return }
        val n = ShowRules.toDouble(v)
        if (n == null || n.isNaN() || n.isInfinite()) { err("type.number", "$label must be a number."); return }
        if (k.integer && n != Math.floor(n)) err("number.integer", "$label must be a whole number.")
        if (k.min != null && n < k.min) err("number.min", "$label must be at least ${fmt(k.min)}.")
        if (k.max != null && n > k.max) err("number.max", "$label must be at most ${fmt(k.max)}.")
        if (k.step != null && k.step > 0) {
            val base = k.min ?: 0.0
            val q = (n - base) / k.step
            if (Math.abs(q - Math.rint(q)) > 1e-9) err("number.step", "$label must move in steps of ${fmt(k.step)}.")
        }
    }

    private fun json(def: ParamDef, k: ParamKind.Json, v: Any?, path: ParamPath, out: MutableList<ParamIssue>) {
        val label = def.label.ifBlank { def.key }
        val parsed: Any? = if (v is String) {
            when (val r = ParamJson.parse(neutralise(v))) {
                is ParamJson.Parsed.Failed -> {
                    out += ParamIssue(path, ParamSeverity.Error, "json.invalid", "$label: ${r.error.message} (line ${r.error.line}, column ${r.error.column}).")
                    return
                }
                is ParamJson.Parsed.Ok -> r.value
            }
        } else v
        val shape = when (k.expect) {
            JsonShape.Any -> true
            JsonShape.Object -> parsed is Map<*, *> || (v is String && v.trim().startsWith("{{"))
            JsonShape.List -> parsed is List<*> || (v is String && v.trim().startsWith("{{"))
        }
        if (!shape) out += ParamIssue(path, ParamSeverity.Error, "json.shape", "$label must be a JSON ${if (k.expect == JsonShape.Object) "object" else "list"}.")
    }

    private fun neutralise(text: String) = Regex("""\{\{[\s\S]*?\}\}""").replace(text) { "null".padEnd(it.value.length) }

    private fun keyValue(def: ParamDef, v: Any?, path: ParamPath, out: MutableList<ParamIssue>) {
        val label = def.label.ifBlank { def.key }
        when (v) {
            is Map<*, *> -> v.entries.forEach { (k, x) ->
                if (k.toString().isBlank()) out += ParamIssue(path, ParamSeverity.Error, "keyvalue.name", "$label has a row without a name.")
                if (x is Map<*, *> || x is List<*>) out += ParamIssue(path.child(k.toString()), ParamSeverity.Error, "type.text", "$label values must be text.")
            }
            is List<*> -> v.forEachIndexed { i, row ->
                val m = row as? Map<*, *>
                if (m == null || Values0.str(m["name"]).isBlank()) {
                    out += ParamIssue(path.index(i), ParamSeverity.Error, "keyvalue.name", "$label row ${i + 1} needs a name.")
                }
            }
            else -> out += ParamIssue(path, ParamSeverity.Error, "type.object", "$label must be a list of name and value rows.")
        }
    }

    private fun assignments(def: ParamDef, k: ParamKind.Assignments, v: Any?, path: ParamPath, out: MutableList<ParamIssue>) {
        val label = def.label.ifBlank { def.key }
        val rows = v as? List<*>
        if (rows == null) { out += ParamIssue(path, ParamSeverity.Error, "type.list", "$label must be a list of assignments."); return }
        val seen = HashSet<String>()
        rows.forEachIndexed { i, row ->
            val p = path.index(i)
            val m = row as? Map<*, *>
            if (m == null) { out += ParamIssue(p, ParamSeverity.Error, "type.object", "$label row ${i + 1} must be an object."); return@forEachIndexed }
            val name = Values0.str(m["name"]).trim()
            if (name.isEmpty()) out += ParamIssue(p.child("name"), ParamSeverity.Error, "assignment.name_empty", "$label row ${i + 1} needs a name.")
            else if (!seen.add(name)) out += ParamIssue(p.child("name"), ParamSeverity.Error, "assignment.name_duplicate", "$label has the name \"$name\" twice.")
            val type = m["type"]?.let { t -> ValueType.entries.firstOrNull { it.name.equals(t.toString(), true) } }
            if (m["type"] != null && type == null) out += ParamIssue(p.child("type"), ParamSeverity.Error, "assignment.type_unknown", "$label row ${i + 1} has an unknown type.")
            else if (type != null && type !in k.allowedTypes) out += ParamIssue(p.child("type"), ParamSeverity.Error, "assignment.type_unknown", "$label row ${i + 1}: type ${type.name} is not allowed here.")
            val value = m["value"]
            val isExpr = value is Map<*, *> && value.size == 1 && value.containsKey(ParamValueCodec.EXPR_KEY) || value is String && value.contains("{{")
            if (type != null && !isExpr && value != null && !matches(type, value)) {
                out += ParamIssue(p.child("value"), ParamSeverity.Error, "assignment.type_mismatch", "$label row ${i + 1}: the value is not a ${type.name.lowercase()}.")
            }
        }
    }

    private fun matches(t: ValueType, v: Any?) = when (t) {
        ValueType.Text -> v is String
        ValueType.Number -> v is Number || (v is String && v.trim().toDoubleOrNull() != null)
        ValueType.Boolean -> v is Boolean || (v is String && v.lowercase() in setOf("true", "false"))
        ValueType.List -> v is List<*>
        ValueType.Object -> v is Map<*, *>
        ValueType.Any -> true
    }

    /** Operators that take no right-hand value. */
    val unaryOperators: Set<String> = setOf(
        "isEmpty", "isNotEmpty", "isTrue", "isFalse", "exists", "notExists",
    )

    val knownOperators: Set<String> = unaryOperators + setOf(
        "is", "isNot", "contains", "notContains", "startsWith", "endsWith", "matches",
        "equals", "notEquals", "gt", "gte", "lt", "lte", "between", "before", "after",
        "lengthEquals", "lengthGt", "hasKey",
    )

    /** Deepest nesting the Conditions widget allows; the root group is depth 1. */
    const val MAX_CONDITION_DEPTH = 3

    private fun conditions(def: ParamDef, v: Any?, path: ParamPath, out: MutableList<ParamIssue>) {
        val label = def.label.ifBlank { def.key }
        val m = v as? Map<*, *>
        if (m == null) { out += ParamIssue(path, ParamSeverity.Error, "type.object", "$label must be a condition group."); return }
        conditionGroup(label, m, path, 1, def.required || ParamRule.Required in def.rules, out)
    }

    private fun conditionGroup(label: String, m: Map<*, *>, path: ParamPath, depth: Int, requireOne: Boolean, out: MutableList<ParamIssue>) {
        val comb = Values0.str(m["combinator"]).ifBlank { "and" }.lowercase()
        if (comb !in setOf("and", "or")) out += ParamIssue(path.child("combinator"), ParamSeverity.Error, "conditions.combinator", "$label: combinator must be and or or.")
        val list = m["conditions"] as? List<*> ?: emptyList<Any?>()
        if (list.isEmpty() && requireOne) {
            out += ParamIssue(path.child("conditions"), ParamSeverity.Error, "required.missing", "$label needs at least one condition.")
        }
        list.forEachIndexed { i, row ->
            val p = path.child("conditions").index(i)
            val c = row as? Map<*, *>
            if (c == null) { out += ParamIssue(p, ParamSeverity.Error, "type.object", "$label condition ${i + 1} must be an object."); return@forEachIndexed }
            if (c.containsKey("conditions")) {
                // A nested group: the widget writes it as {id, combinator, conditions}.
                if (depth >= MAX_CONDITION_DEPTH) {
                    out += ParamIssue(p, ParamSeverity.Error, "conditions.depth", "$label allows groups nested at most $MAX_CONDITION_DEPTH levels deep.")
                } else conditionGroup(label, c, p, depth + 1, false, out)
                return@forEachIndexed
            }
            val op = Values0.str(c["op"])
            if (op !in knownOperators) {
                out += ParamIssue(p.child("op"), ParamSeverity.Error, "conditions.operator", "$label condition ${i + 1} has an unknown operator.")
                return@forEachIndexed
            }
            if (c["left"] == null || Values0.str(c["left"]).isBlank()) out += ParamIssue(p.child("left"), ParamSeverity.Error, "conditions.left", "$label condition ${i + 1} needs a left value.")
            if (op !in unaryOperators && (c["right"] == null || Values0.str(c["right"]).isEmpty() && c["right"] !is Boolean)) {
                out += ParamIssue(p.child("right"), ParamSeverity.Error, "conditions.right", "$label condition ${i + 1} needs a right value.")
            }
            if (op == "matches") {
                val r = Values0.str(c["right"])
                if (r.isNotEmpty() && !r.contains("{{") && runCatching { Regex(r) }.isFailure) {
                    out += ParamIssue(p.child("right"), ParamSeverity.Error, "pattern.bad", "$label condition ${i + 1}: the pattern does not compile.")
                }
            }
        }
    }

    private fun resource(def: ParamDef, k: ParamKind.ResourcePick, v: Any?, path: ParamPath, out: MutableList<ParamIssue>) {
        val label = def.label.ifBlank { def.key }
        val m = v as? Map<*, *>
        if (m == null) { out += ParamIssue(path, ParamSeverity.Error, "type.object", "$label must have a mode and a value."); return }
        val mode = when (Values0.str(m["mode"]).lowercase()) {
            "fromlist", "list" -> ResourceMode.FromList
            "byurl", "url" -> ResourceMode.ByUrl
            "byid", "id" -> ResourceMode.ById
            else -> null
        }
        if (mode == null || mode !in k.modes) { out += ParamIssue(path.child("mode"), ParamSeverity.Error, "resource.mode", "$label has a mode that is not available."); return }
        val value = Values0.str(m["value"])
        if (value.isBlank()) { out += ParamIssue(path.child("value"), ParamSeverity.Error, "required.missing", "$label needs a value."); return }
        if (mode == ResourceMode.ByUrl && k.urlPattern != null && !value.contains("{{")) {
            val re = runCatching { Regex(k.urlPattern) }.getOrNull()
            if (re != null && !re.containsMatchIn(value)) out += ParamIssue(path.child("value"), ParamSeverity.Error, "resource.url", "$label does not look like a valid address for this service.")
        }
    }

    private fun fileRef(def: ParamDef, v: Any?, path: ParamPath, out: MutableList<ParamIssue>) {
        val label = def.label.ifBlank { def.key }
        val m = v as? Map<*, *>
        if (m == null) { out += ParamIssue(path, ParamSeverity.Error, "type.object", "$label must say where the file comes from."); return }
        when (Values0.str(m["mode"]).lowercase()) {
            "data", "fromdata" -> if (Values0.str(m["property"]).isBlank()) out += ParamIssue(path.child("property"), ParamSeverity.Error, "required.missing", "$label needs a binary property name.")
            "device", "fromdevice" -> {
                val uri = Values0.str(m["uri"])
                if (uri.isBlank()) out += ParamIssue(path.child("uri"), ParamSeverity.Error, "required.missing", "$label needs a file.")
                else if (uri.contains("{{")) out += ParamIssue(path.child("uri"), ParamSeverity.Error, "expr.not_allowed", "$label cannot use an expression for the file location.")
            }
            else -> out += ParamIssue(path.child("mode"), ParamSeverity.Error, "file.mode", "$label has an unknown source.")
        }
    }

    internal fun parsesAsDate(s: String, mode: DateTimeMode): Boolean {
        val t = s.trim()
        if (t.contains("{{")) return true
        return runCatching {
            when (mode) {
                DateTimeMode.Date -> LocalDate.parse(t.take(10).let { if (t.length > 10) it else t })
                DateTimeMode.Time -> LocalTime.parse(t)
                DateTimeMode.DateTime -> isoInstant(t)
            }
        }.isSuccess
    }

    private fun isoInstant(t: String) {
        runCatching { OffsetDateTime.parse(t) }.onSuccess { return }
        runCatching { ZonedDateTime.parse(t) }.onSuccess { return }
        runCatching { Instant.parse(t) }.onSuccess { return }
        runCatching { LocalDateTime.parse(t) }.onSuccess { return }
        LocalDate.parse(t)
    }

    private fun validUrl(s: String): Boolean {
        if (s.contains("{{")) return true
        return URL.matches(s.trim())
    }

    private fun fmt(d: Double) = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

    private val COLOR = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val PHONE = Regex("^\\+?[0-9 ()\\-.]{3,20}$")
    private val URL = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s/?#]+([/?#]\\S*)?$")

    /** Local string coercion: Double without a fraction prints as a whole number. */
    private object Values0 {
        fun str(v: Any?): String = when (v) {
            null -> ""
            is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
            else -> v.toString()
        }
    }
}
