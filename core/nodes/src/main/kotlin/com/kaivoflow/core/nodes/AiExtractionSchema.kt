package com.kaivoflow.core.nodes

/** Shallow typed field contract for AI extraction; not full JSON Schema. */
internal object AiExtractionSchema {
    private val types = setOf("string", "number", "integer", "boolean", "object", "array")

    fun parse(value: Any?): Map<String, String> {
        val parsed = if (value is String) JsonLite.parse(value) else value
        val map = parsed as? Map<*, *> ?: throw IllegalArgumentException("Fields must be a JSON object of field names and types.")
        require(map.isNotEmpty() && map.size <= 100) { "Add between 1 and 100 fields." }
        val result = linkedMapOf<String, String>()
        for ((key, rawType) in map) {
            val name = (key as? String)?.takeIf { it.isNotBlank() && it.length <= 120 }
                ?: throw IllegalArgumentException("A field name is invalid.")
            val type = (rawType as? String)?.trim()?.lowercase()
                ?: throw IllegalArgumentException("A field type is invalid.")
            require(type in types) { "Use string, number, integer, boolean, object, or array as field types." }
            result[name] = type
        }
        return result
    }

    /** Null is allowed for an unavailable value, but keys must exist and types must match. */
    fun validate(schema: Map<String, String>, result: Map<*, *>): String? {
        if (result.keys.any { it !is String || it !in schema }) return "The model returned unexpected fields."
        for ((field, type) in schema) {
            if (!result.containsKey(field)) return "The model omitted a required field: $field."
            val value = result[field] ?: continue
            val valid = when (type) {
                "string" -> value is String
                "number" -> value is Number && value.toDouble().isFinite()
                "integer" -> value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0
                "boolean" -> value is Boolean
                "object" -> value is Map<*, *>
                "array" -> value is List<*>
                else -> false
            }
            if (!valid) return "The model returned the wrong type for $field (expected $type)."
        }
        return null
    }
}
