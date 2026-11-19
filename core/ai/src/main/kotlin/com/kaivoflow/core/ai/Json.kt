package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.HttpResponseSpec
import com.kaivoflow.core.model.ParamJson

/** Thin helpers over the strict reader in :core:model so adapters read provider JSON the same way. */
internal object J {
    fun parse(text: String): Any? = (ParamJson.parse(text) as? ParamJson.Parsed.Ok)?.value

    fun write(value: Any?): String = ParamJson.write(value)

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = (v as? Map<*, *>)?.let { m -> m.entries.associate { it.key.toString() to it.value } }

    fun list(v: Any?): List<Any?> = (v as? List<*>) ?: emptyList()

    fun str(v: Any?): String? = v as? String

    fun long(v: Any?): Long = (v as? Number)?.toLong() ?: 0L

    /** The response as a JSON object, from the already parsed body or from the text. */
    fun body(r: HttpResponseSpec): Map<String, Any?>? = obj(r.body) ?: obj(parse(r.bodyText))

    /** Dotted path read, for example `error.message`. */
    fun path(root: Any?, path: String): Any? = path.split('.').fold(root) { acc, k -> obj(acc)?.get(k) }
}
