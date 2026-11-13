package com.kaivoflow.core.engine

/**
 * Value plumbing shared by the engine and node executors.
 *
 * The engine's data model is deliberately "plain Kotlin JSON": `Map<String, Any?>`,
 * `List<Any?>`, `String`, `Double`, `Boolean`, `null`. That keeps it dependency-free,
 * fast to clone and identical to what a JSON payload looks like after parsing.
 */
object Values {

    /** JSON-ish null check: `null` and the string "null" both count as absent. */
    fun isBlank(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isBlank()
        is Collection<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        else -> false
    }

    /**
     * Reads a dotted path: `deepGet(item, "a.b.0.c")`.
     * Supports numeric segments for lists and quoted segments for keys containing dots.
     */
    fun deepGet(root: Any?, path: String): Any? {
        if (path.isBlank()) return root
        var current: Any? = root
        for (raw in splitPath(path)) {
            current = when (current) {
                null -> return null
                is Map<*, *> -> current[raw]
                is List<*> -> raw.toIntOrNull()?.let { current.getOrNull(it) }
                is Array<*> -> raw.toIntOrNull()?.let { current.getOrNull(it) }
                else -> return null
            }
        }
        return current
    }

    /** Returns a copy of [root] with [path] set to [value] (creating maps as needed). */
    fun deepSet(root: Map<String, Any?>, path: String, value: Any?): Map<String, Any?> {
        val segments = splitPath(path)
        if (segments.isEmpty()) return root
        if (segments.size == 1) return root + (segments[0] to value)
        val head = segments[0]
        @Suppress("UNCHECKED_CAST")
        val child = (root[head] as? Map<String, Any?>) ?: emptyMap()
        return root + (head to deepSet(child, segments.drop(1).joinToString("."), value))
    }

    private fun splitPath(path: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < path.length) {
            val c = path[i]
            when (c) {
                '.' -> { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
                '[' -> {
                    if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                    val end = path.indexOf(']', i)
                    if (end > i) {
                        var inner = path.substring(i + 1, end).trim()
                        if (inner.length >= 2 && (inner.startsWith("'") || inner.startsWith("\""))) {
                            inner = inner.substring(1, inner.length - 1)
                        }
                        if (inner.isNotEmpty()) out += inner
                        i = end
                    } else {
                        sb.append(c)
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** Coerces any JSON-ish value into a String, with JSON output for containers. */
    fun asString(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Boolean -> if (value) "true" else "false"
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
        is Number -> value.toString()
        is Collection<*>, is Map<*, *> -> stringify(value)
        else -> value.toString()
    }

    /** asString with a fallback for blank/missing values — the most common call site pattern. */
    fun asString(value: Any?, fallback: String): String {
        val text = asString(value)
        return if (text.isBlank()) fallback else text
    }

    fun asList(value: Any?): List<Any?> = when (value) {
        null -> emptyList()
        is List<*> -> value
        is Array<*> -> value.toList()
        else -> listOf(value)
    }

    fun asMap(value: Any?): Map<String, Any?> = when (value) {
        null -> emptyMap()
        is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
        else -> mapOf("value" to value)
    }

    fun asBoolean(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.equals("true", true) || value == "1" || value.equals("yes", true)
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> false
    }

    fun asDouble(value: Any?): Double? = when (value) {
        null -> null
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        is Boolean -> if (value) 1.0 else 0.0
        else -> null
    }

    /** Minimal, deterministic JSON serialiser (stable key order for golden-file tests). */
    fun stringify(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        writeJson(value, sb, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun writeJson(value: Any?, sb: StringBuilder, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value, sb)
            is Boolean -> sb.append(value)
            is Number -> {
                val d = value.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null")
                else if (d == Math.floor(d) && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
            }
            is Map<*, *> -> {
                sb.append('{')
                val entries = value.entries.toList()
                entries.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    val childIndent = if (indent >= 0) indent + 1 else -1
                    newline(sb, childIndent)
                    writeString(e.key.toString(), sb)
                    sb.append(if (indent >= 0) ": " else ":")
                    writeJson(e.value, sb, childIndent)
                }
                if (entries.isNotEmpty()) newline(sb, indent)
                sb.append('}')
            }
            is Collection<*> -> {
                sb.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    val childIndent = if (indent >= 0) indent + 1 else -1
                    newline(sb, childIndent)
                    writeJson(v, sb, childIndent)
                }
                if (value.isNotEmpty()) newline(sb, indent)
                sb.append(']')
            }
            else -> writeString(value.toString(), sb)
        }
    }

    private fun newline(sb: StringBuilder, indent: Int) {
        if (indent < 0) return
        sb.append('\n')
        repeat(indent) { sb.append("  ") }
    }

    private fun writeString(value: String, sb: StringBuilder) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Masks values that look like secrets so logs and the UI never leak them. */
    fun redact(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) ->
            val key = k.toString()
            val sensitive = SENSITIVE_KEY_HINTS.any { key.contains(it, ignoreCase = true) }
            key to if (sensitive && v != null) "••••••" else redact(v)
        }
        is List<*> -> value.map { redact(it) }
        else -> value
    }

    private val SENSITIVE_KEY_HINTS = listOf(
        "password", "secret", "token", "apikey", "api_key", "authorization",
        "credential", "bearer", "private", "otp", "card", "cvv",
    )

    /**
     * Stable hash of a JSON-ish value. Used by Freeze Output and by the per-Step result cache,
     * so a Run can skip Steps whose inputs and config did not change.
     */
    fun stableHash(value: Any?): String {
        val s = stringify(value)
        var hash = 0xcbf29ce484222325uL
        for (b in s.encodeToByteArray()) {
            hash = hash xor b.toULong()
            hash *= 0x100000001b3uL
        }
        return hash.toString(16)
    }

    /** Deep merge, used by the Merge Step. Objects merge recursively, arrays concatenate. */
    fun deepMerge(base: Any?, override: Any?): Any? = when {
        base is Map<*, *> && override is Map<*, *> -> {
            val out = LinkedHashMap<String, Any?>()
            base.forEach { (k, v) -> out[k.toString()] = v }
            override.forEach { (k, v) ->
                val key = k.toString()
                out[key] = if (out.containsKey(key)) deepMerge(out[key], v) else v
            }
            out
        }
        base is List<*> && override is List<*> -> base + override
        override == null -> base
        else -> override
    }
}
