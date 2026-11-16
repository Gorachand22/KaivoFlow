package com.kaivoflow.feature.canvas.inspector.panels

import com.kaivoflow.core.model.BinaryDataRef
import com.kaivoflow.core.model.Item

/** One attachment of one item, as the Binary view lists it. */
data class BinaryEntry(val itemIndex: Int, val item: Item, val name: String, val ref: BinaryDataRef) {
    val iconKey: String
        get() = when {
            ref.mimeType.startsWith("image/") -> "image"
            ref.mimeType.startsWith("audio/") -> "audiotrack"
            else -> "attachFile"
        }

    /** True when the platform can draw a preview of this type. */
    val isImage: Boolean get() = ref.mimeType.startsWith("image/")
}

object BinaryEntries {
    fun collect(items: List<Item>, originalIndices: List<Int> = items.indices.toList()): List<BinaryEntry> {
        val out = ArrayList<BinaryEntry>()
        items.forEachIndexed { i, item ->
            val idx = originalIndices.getOrElse(i) { i }
            for ((name, ref) in item.binary) out += BinaryEntry(idx, item, name, ref)
        }
        return out
    }

    /** Entries whose name, file name or MIME type contains [query]; all of them for a blank query. */
    fun filter(entries: List<BinaryEntry>, query: String): List<BinaryEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter {
            it.name.contains(q, true) || it.ref.fileName.contains(q, true) || it.ref.mimeType.contains(q, true)
        }
    }
}
