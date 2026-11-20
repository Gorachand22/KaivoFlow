package com.kaivoflow.core.ai

/** A model's tool arguments after the lenient reader: the object, why it is not one, and the text it came from. */
data class ParsedArgs(val arguments: Map<String, Any?>, val error: String?, val raw: String?)

/**
 * Lenient reading of what a model sends as tool arguments. Providers differ and small models are sloppy, so the
 * reader accepts: an object, a JSON string holding an object, JSON with single quotes, unquoted keys, trailing
 * commas, comments, Python literals or a code fence. A cut-off object is refused, not completed. When the
 * text is not an object at all and the tool takes exactly one parameter, the text becomes that parameter.
 * Everything else is reported with the reason, never thrown.
 */
object ToolArguments {
    const val RAW_CAP = 8_000

    /** Reads the `arguments` field of a provider tool call. */
    fun fromProvider(raw: Any?): ParsedArgs = when (raw) {
        null -> ParsedArgs(emptyMap(), null, null)
        is Map<*, *> -> ParsedArgs(J.obj(raw) ?: emptyMap(), null, null)
        is String -> if (raw.isBlank()) ParsedArgs(emptyMap(), null, null) else {
            val v = relaxed(raw)
            val m = J.obj(v)
            if (m != null) ParsedArgs(m, null, null)
            else ParsedArgs(emptyMap(), "arguments are not a valid JSON object", raw.take(RAW_CAP))
        }
        else -> ParsedArgs(emptyMap(), "arguments are not a valid JSON object", null)
    }

    /** Strict parse first, then the repaired text. Null when neither is JSON. */
    fun relaxed(text: String): Any? {
        val t = unfence(text.trim())
        J.parse(t)?.let { return it }
        if (t == "null") return null
        return J.parse(JsonRepair.repair(t))
    }

    private fun unfence(t: String): String {
        val m = Regex("^```[a-zA-Z]*\\s*([\\s\\S]*?)\\s*```$").find(t) ?: return t
        return m.groupValues[1]
    }

    /**
     * Makes a call usable against [schema]: recovers a call whose arguments were not an object, then coerces
     * primitive type mismatches the model made (the string "5" for an integer, and so on). Returns the call
     * with [ToolCall.argumentsError] cleared when it was recovered.
     */
    fun adapt(schema: Map<String, Any?>, call: ToolCall): ToolCall {
        var args = call.arguments
        var error = call.argumentsError
        val props = J.obj(schema["properties"]).orEmpty()
        if (error != null) {
            val raw = call.rawArguments
            if (raw != null && props.size == 1) {
                val key = props.keys.single()
                val v = runCatching { relaxed(raw) }.getOrNull()
                val wrapped: Any? = when {
                    v is Map<*, *> -> null
                    v is String -> v
                    v is Number || v is Boolean -> v
                    else -> raw.trim().removeSurrounding("\"").removeSurrounding("'")
                }
                if (wrapped != null) { args = mapOf(key to wrapped); error = null }
            }
        }
        if (error == null && props.isNotEmpty()) args = coerce(props, args)
        return if (args === call.arguments && error == call.argumentsError) call else call.copy(arguments = args, argumentsError = error)
    }

    private fun coerce(props: Map<String, Any?>, args: Map<String, Any?>): Map<String, Any?> {
        var changed = false
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in args) {
            val sub = J.obj(props[k])
            val nv = if (sub == null) v else coerceValue(sub, v)
            if (nv !== v) changed = true
            out[k] = nv
        }
        return if (changed) out else args
    }

    private fun coerceValue(schema: Map<String, Any?>, v: Any?): Any? {
        val types = when (val t = schema["type"]) { is String -> listOf(t); is List<*> -> t.filterIsInstance<String>(); else -> return v }
        if (types.isEmpty()) return v
        fun ok(t: String) = when (t) {
            "string" -> v is String; "number" -> v is Number; "integer" -> v is Number && v.toDouble() % 1.0 == 0.0
            "boolean" -> v is Boolean; "object" -> v is Map<*, *>; "array" -> v is List<*>; "null" -> v == null; else -> true
        }
        if (types.any { ok(it) }) return v
        for (t in types) {
            when (t) {
                "integer", "number" -> if (v is String) {
                    val d = v.trim().toDoubleOrNull()
                    if (d != null && d.isFinite()) {
                        if (d % 1.0 == 0.0) return d.toLong()
                        if (t == "number") return d
                    }
                }
                "boolean" -> if (v is String) when (v.trim().lowercase()) { "true" -> return true; "false" -> return false }
                "string" -> if (v is Number || v is Boolean) return v.toString()
                "object" -> if (v is String) J.obj(relaxed(v))?.let { return it }
                "array" -> if (v is String && v.trim().startsWith("[")) (relaxed(v) as? List<*>)?.let { return it } else if (v != null && v !is Map<*, *>) return listOf(v)
            }
        }
        return v
    }
}

/** Repairs the usual damage in model-written JSON. Output is only a candidate: the caller still parses it strictly. */
internal object JsonRepair {
    fun repair(src: String): String {
        val out = StringBuilder()
        val stack = ArrayList<Char>()
        var i = 0
        val n = src.length
        fun nextNonSpace(from: Int): Char? { var j = from; while (j < n && src[j].isWhitespace()) j++; return if (j < n) src[j] else null }
        while (i < n) {
            val c = src[i]
            when {
                c == '"' || c == '\'' -> {
                    out.append('"'); i++
                    while (i < n && src[i] != c) {
                        val d = src[i]
                        if (d == '\\' && i + 1 < n) {
                            val e = src[i + 1]
                            if (e == '\'') out.append('\'') else out.append(d).append(e)
                            i += 2; continue
                        }
                        if (d == '"') out.append("\\\"") else if (d == '\n') out.append("\\n") else if (d == '\r') Unit else out.append(d)
                        i++
                    }
                    out.append('"'); i++
                }
                c == '/' && i + 1 < n && src[i + 1] == '/' -> { while (i < n && src[i] != '\n') i++ }
                c == '/' && i + 1 < n && src[i + 1] == '*' -> { val e = src.indexOf("*/", i + 2); i = if (e < 0) n else e + 2 }
                c == '{' || c == '[' -> { stack.add(if (c == '{') '}' else ']'); out.append(c); i++ }
                c == '}' || c == ']' -> { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); out.append(c); i++ }
                c == ',' -> { val nx = nextNonSpace(i + 1); if (nx == null || nx == '}' || nx == ']') Unit else out.append(c); i++ }
                c.isLetter() || c == '_' || c == '$' -> {
                    var j = i
                    while (j < n && (src[j].isLetterOrDigit() || src[j] == '_' || src[j] == '$')) j++
                    val w = src.substring(i, j)
                    when (w) {
                        "true", "false", "null" -> out.append(w)
                        "True" -> out.append("true"); "False" -> out.append("false"); "None" -> out.append("null")
                        else -> out.append('"').append(w).append('"')
                    }
                    i = j
                }
                else -> { out.append(c); i++ }
            }
        }
        // A cut-off object is NOT closed: guessing the missing end would invent values. It stays invalid.
        return out.toString()
    }
}

/**
 * The tool error contract. Every failed tool call reaches the model as text with a stable shape, so the model
 * (and logs) can tell failures from data: `There was an error: <message>`, or `HTTP <status> There was an error:
 * <message>` with the status first when the failure came from an HTTP answer. A response body, when given, is
 * redacted and capped at [BODY_CAP] characters.
 */
object ToolErrors {
    const val PREFIX = "There was an error:"
    const val BODY_CAP = 2_000
    private val HTTP_PREFIX = Regex("^HTTP [0-9]{3} $PREFIX")

    fun isErrorText(text: String): Boolean = text.startsWith(PREFIX) || HTTP_PREFIX.containsMatchIn(text)

    fun format(message: String, httpStatus: Int? = null, body: String? = null, redactor: Redactor = Redactor()): String {
        val msg = redactor.redact(message).trim().ifEmpty { "the tool failed" }
        if (isErrorText(msg)) return appendBody(msg, body, redactor)
        val head = if (httpStatus != null) "HTTP $httpStatus $PREFIX $msg" else "$PREFIX $msg"
        return appendBody(head, body, redactor)
    }

    private fun appendBody(head: String, body: String?, redactor: Redactor): String {
        val b = body?.trim().orEmpty()
        if (b.isEmpty() || b.any { it == '\u0000' }) return head
        val safe = redactor.redact(b)
        return head + "\nResponse body: " + (if (safe.length > BODY_CAP) safe.take(BODY_CAP) + "…[truncated]" else safe)
    }

    /** `HTTP 503` inside a message, if any. Used for tools that only have a message to give. */
    fun statusIn(message: String): Int? = Regex("\\bHTTP ([1-5][0-9]{2})\\b").find(message)?.groupValues?.get(1)?.toIntOrNull()
}
