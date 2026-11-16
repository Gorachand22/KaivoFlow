package com.kaivoflow.feature.canvas.inspector.panels

import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.SchemaPath

enum class JsonKind { ItemHeader, Object, Array, Text, Number, Bool, Null }

/** One visible line of the JSON view. */
data class JsonRow(
    val id: String,
    val depth: Int,
    /** Key, `[n]` for array elements, `Item n` for headers. */
    val label: String,
    val kind: JsonKind,
    /** Value text for leaves (clipped), `{3}` or `[5]` for containers. */
    val preview: String,
    /** Route inside the item; array indexes are concrete (`[2]`). Empty for an item header. */
    val path: SchemaPath,
    val itemIndex: Int,
    val value: Any?,
    val expandable: Boolean,
    val expanded: Boolean,
    val matches: Boolean,
    /** Children beyond the per-node cap that are not listed. */
    val hiddenChildren: Int = 0,
)

data class JsonTreeResult(val rows: List<JsonRow>, val matchCount: Int) {
    /** Row positions of matches, for next and previous buttons. */
    val matchPositions: List<Int> by lazy { rows.indices.filter { rows[it].matches } }
}

object JsonTreeFlattener {
    const val MAX_CHILDREN = 200
    const val MAX_PREVIEW = 160

    fun flatten(
        items: List<Item>,
        originalIndices: List<Int> = items.indices.toList(),
        expansion: TreeExpansion = TreeExpansion(),
        query: String = "",
        maxRows: Int = 5000,
    ): JsonTreeResult {
        val out = ArrayList<JsonRow>()
        val q = query.trim()
        var matches = 0
        items.forEachIndexed { i, item ->
            if (out.size >= maxRows) return@forEachIndexed
            val idx = originalIndices.getOrElse(i) { i }
            val id = "i$idx"
            val any = q.isNotEmpty() && item.json.any { (k, v) -> subtreeMatches(k, v, q) }
            val open = any || expansion.isOpen(id, 0)
            out += JsonRow(
                id = id, depth = 0, label = "Item ${idx + 1}", kind = JsonKind.ItemHeader,
                preview = "{${item.json.size}}", path = SchemaPath(emptyList()), itemIndex = idx,
                value = item.json, expandable = item.json.isNotEmpty(), expanded = open && item.json.isNotEmpty(),
                matches = false,
            )
            if (open) {
                var n = 0
                for ((k, v) in item.json) {
                    if (n++ >= MAX_CHILDREN || out.size >= maxRows) break
                    matches += node(k, v, listOf(k), 1, idx, id, expansion, q, out, maxRows)
                }
            }
        }
        return JsonTreeResult(out, matches)
    }

    private fun kindOf(v: Any?): JsonKind = when (v) {
        null -> JsonKind.Null
        is Boolean -> JsonKind.Bool
        is Number -> JsonKind.Number
        is Map<*, *> -> JsonKind.Object
        is List<*>, is Array<*> -> JsonKind.Array
        else -> JsonKind.Text
    }

    private fun childrenOf(v: Any?): List<Pair<String, Any?>> = when (v) {
        is Map<*, *> -> v.entries.map { it.key.toString() to it.value }
        is List<*> -> v.mapIndexed { i, x -> "[$i]" to x }
        is Array<*> -> v.mapIndexed { i, x -> "[$i]" to x }
        else -> emptyList()
    }

    private fun leafText(v: Any?): String = when (v) {
        null -> "null"
        is Number -> TableBuilder.numberText(v)
        else -> v.toString()
    }

    private fun selfMatches(label: String, v: Any?, q: String): Boolean {
        if (q.isEmpty()) return false
        if (label.contains(q, ignoreCase = true)) return true
        return kindOf(v).let { it != JsonKind.Object && it != JsonKind.Array } && leafText(v).contains(q, ignoreCase = true)
    }

    private fun subtreeMatches(label: String, v: Any?, q: String): Boolean =
        selfMatches(label, v, q) || childrenOf(v).any { (k, c) -> subtreeMatches(k, c, q) }

    /** Returns the number of matching rows added. */
    private fun node(
        label: String, v: Any?, segs: List<String>, depth: Int, itemIndex: Int, prefix: String,
        expansion: TreeExpansion, q: String, out: MutableList<JsonRow>, maxRows: Int,
    ): Int {
        val kind = kindOf(v)
        val kids = childrenOf(v)
        val id = idOf(segs, "$prefix\u001E")
        val self = selfMatches(label, v, q)
        val descendant = q.isNotEmpty() && kids.any { (k, c) -> subtreeMatches(k, c, q) }
        val expandable = kids.isNotEmpty()
        val open = expandable && (descendant || expansion.isOpen(id, depth))
        val preview = when (kind) {
            JsonKind.Object -> "{${kids.size}}"
            JsonKind.Array -> "[${kids.size}]"
            else -> leafText(v).replace('\n', ' ').let { if (it.length > MAX_PREVIEW) it.take(MAX_PREVIEW - 1) + "…" else it }
        }
        val hidden = (kids.size - MAX_CHILDREN).coerceAtLeast(0)
        out += JsonRow(id, depth, label, kind, preview, SchemaPath(segs), itemIndex, v, expandable, open, self, hidden)
        var count = if (self) 1 else 0
        if (open) {
            var n = 0
            for ((k, c) in kids) {
                if (n++ >= MAX_CHILDREN || out.size >= maxRows) break
                count += node(k, c, segs + k, depth + 1, itemIndex, prefix, expansion, q, out, maxRows)
            }
        }
        return count
    }
}

/** Minimal JSON writer for copy actions: valid JSON for maps, lists, strings, numbers, booleans and null. */
object JsonText {
    fun compact(v: Any?): String = StringBuilder().also { write(it, v, null, 0) }.toString()

    fun pretty(v: Any?, indent: Int = 2): String = StringBuilder().also { write(it, v, indent, 0) }.toString()

    private fun write(sb: StringBuilder, v: Any?, indent: Int?, level: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v.toString())
            is Number -> sb.append(if ((v is Double && !v.isFinite()) || (v is Float && !v.isFinite())) "null" else TableBuilder.numberText(v))
            is Map<*, *> -> container(sb, '{', '}', v.entries.toList(), indent, level) { e ->
                quote(sb, e.key.toString()); sb.append(if (indent != null) ": " else ":"); write(sb, e.value, indent, level + 1)
            }
            is List<*> -> container(sb, '[', ']', v, indent, level) { e -> write(sb, e, indent, level + 1) }
            is Array<*> -> container(sb, '[', ']', v.toList(), indent, level) { e -> write(sb, e, indent, level + 1) }
            else -> quote(sb, v.toString())
        }
    }

    private fun <T> container(sb: StringBuilder, open: Char, close: Char, entries: List<T>, indent: Int?, level: Int, item: (T) -> Unit) {
        sb.append(open)
        if (entries.isEmpty()) { sb.append(close); return }
        entries.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            if (indent != null) { sb.append('\n'); sb.append(" ".repeat(indent * (level + 1))) }
            item(e)
        }
        if (indent != null) { sb.append('\n'); sb.append(" ".repeat(indent * level)) }
        sb.append(close)
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}
