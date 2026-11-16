package com.kaivoflow.core.work.alwayson

import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The Inbox payload envelope: `{"v":1,"items":[...],"meta":{...}}`. No size limit here, large ones spill to a file. */
object InboxPayloads {
    data class Decoded(val items: List<FlowData>, val meta: Map<String, Any?>)

    /** Null when there is nothing to carry (a schedule slot), so the row stays tiny. */
    fun encode(items: List<FlowData>, meta: Map<String, Any?>): String? {
        if (items.isEmpty() && meta.isEmpty()) return null
        return Values.stringify(mapOf("v" to 1, "items" to items.map { it.json }, "meta" to meta))
    }

    fun decode(raw: String?): Decoded {
        if (raw.isNullOrBlank()) return Decoded(emptyList(), emptyMap())
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return Decoded(emptyList(), emptyMap())
        val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonObject)?.let { FlowData(it.mapValues { (_, v) -> fromJson(v) }) }
        }
        val meta = (root["meta"] as? JsonObject)?.mapValues { (_, v) -> fromJson(v) }.orEmpty()
        return Decoded(items, meta)
    }

    private fun fromJson(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { fromJson(it.value) }
        is JsonArray -> e.map { fromJson(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.content == "true" -> true
            e.content == "false" -> false
            else -> e.content.toDoubleOrNull()
        }
    }
}
