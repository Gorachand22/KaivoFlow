package com.kaivoflow.feature.flows.ai

/** Tiny JSON text helpers for the chat's tool payloads and the event log. Pure Kotlin, no Android types. */
internal object TurnJson {
    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    /** One-line object from key/value pairs; values may be String, Number, Boolean, null, Map or List. */
    fun obj(vararg pairs: Pair<String, Any?>): String = write(linkedMapOf(*pairs), indent = null)

    /** Indented text of a value, as shown in the tool payload cards. */
    fun pretty(value: Any?): String = write(value, indent = 2)

    fun write(value: Any?, indent: Int?, depth: Int = 0): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is Number -> {
            val d = value.toDouble()
            if (d.isNaN() || d.isInfinite()) "null" else if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
        }
        is Map<*, *> -> if (value.isEmpty()) "{}" else {
            val pad = indent?.let { "\n" + " ".repeat(it * (depth + 1)) } ?: ""
            val end = indent?.let { "\n" + " ".repeat(it * depth) } ?: ""
            value.entries.joinToString(if (indent == null) "," else ",", "{", "$end}") { (k, v) ->
                pad + quote(k.toString()) + (if (indent == null) ":" else ": ") + write(v, indent, depth + 1)
            }
        }
        is List<*> -> if (value.isEmpty()) "[]" else {
            val pad = indent?.let { "\n" + " ".repeat(it * (depth + 1)) } ?: ""
            val end = indent?.let { "\n" + " ".repeat(it * depth) } ?: ""
            value.joinToString(",", "[", "$end]") { pad + write(it, indent, depth + 1) }
        }
        else -> quote(value.toString())
    }
}
