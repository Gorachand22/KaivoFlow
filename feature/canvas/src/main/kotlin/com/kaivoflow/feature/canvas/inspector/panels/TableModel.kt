package com.kaivoflow.feature.canvas.inspector.panels

import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.SchemaPath
import com.kaivoflow.core.model.SchemaType

enum class CellKind { Text, Number, Bool, Null, EmptyString, Object, Array }

/** One drawn cell. [text] is already truncated to one line; [full] is the untruncated text for copy and the value sheet. */
data class TableCell(
    val kind: CellKind,
    val text: String,
    val full: String,
    val raw: Any?,
    val truncated: Boolean,
) {
    /** Nested values show as a `{3}` or `[5]` summary chip. */
    val isSummary: Boolean get() = kind == CellKind.Object || kind == CellKind.Array
    val rightAligned: Boolean get() = kind == CellKind.Number
}

data class TableColumn(val key: String, val type: SchemaType) {
    val path: SchemaPath get() = SchemaPath(listOf(key))
}

data class TableRow(val itemIndex: Int, val cells: List<TableCell>, val hasError: Boolean)

data class TableModel(
    val columns: List<TableColumn>,
    val rows: List<TableRow>,
    /** Columns left out because of the column cap. */
    val hiddenColumns: Int,
)

/** Selected rows, by original item index. */
data class RowSelection(val selected: Set<Int> = emptySet()) {
    fun toggle(itemIndex: Int): RowSelection =
        RowSelection(if (itemIndex in selected) selected - itemIndex else selected + itemIndex)

    fun isSelected(itemIndex: Int): Boolean = itemIndex in selected
    fun clear(): RowSelection = RowSelection()
    val count: Int get() = selected.size
}

object TableBuilder {
    const val MAX_COLUMNS = 60
    const val DEFAULT_CELL_CHARS = 80
    const val MIN_COLUMN_DP = 96
    const val MAX_COLUMN_DP = 240

    /**
     * Columns are the union of top-level keys in first-seen order. Each column's type is the type
     * of its non-null values, [SchemaType.Mixed] when they differ, [SchemaType.Null] when all are null.
     */
    fun build(
        items: List<Item>,
        originalIndices: List<Int> = items.indices.toList(),
        maxCells: Int = DEFAULT_CELL_CHARS,
        maxColumns: Int = MAX_COLUMNS,
    ): TableModel {
        val keys = LinkedHashMap<String, MutableSet<SchemaType>>()
        for (item in items) {
            for ((k, v) in item.json) {
                val set = keys.getOrPut(k) { LinkedHashSet() }
                typeOf(v)?.let { set += it }
            }
        }
        val shown = keys.entries.take(maxColumns)
        val columns = shown.map { (k, types) ->
            TableColumn(
                key = k,
                type = when (types.size) {
                    0 -> SchemaType.Null
                    1 -> types.first()
                    else -> SchemaType.Mixed
                },
            )
        }
        val rows = items.mapIndexed { i, item ->
            TableRow(
                itemIndex = originalIndices.getOrElse(i) { i },
                cells = columns.map { c -> cell(item.json[c.key], maxCells, present = item.json.containsKey(c.key)) },
                hasError = item.error != null,
            )
        }
        return TableModel(columns, rows, hiddenColumns = (keys.size - shown.size).coerceAtLeast(0))
    }

    /** Type of a value, or null for null. */
    fun typeOf(v: Any?): SchemaType? = when (v) {
        null -> null
        is Boolean -> SchemaType.Bool
        is Number -> SchemaType.Number
        is Map<*, *> -> SchemaType.Object
        is Iterable<*>, is Array<*> -> SchemaType.Array
        else -> SchemaType.String
    }

    /** A missing key is drawn as an empty cell, an explicit null as muted `null`. */
    fun cell(v: Any?, maxChars: Int = DEFAULT_CELL_CHARS, present: Boolean = true): TableCell = when (v) {
        null -> if (present) TableCell(CellKind.Null, "null", "null", null, false) else TableCell(CellKind.Text, "", "", null, false)
        is Boolean -> TableCell(CellKind.Bool, v.toString(), v.toString(), v, false)
        is Number -> numberText(v).let { TableCell(CellKind.Number, it, it, v, false) }
        is Map<*, *> -> "{${v.size}}".let { TableCell(CellKind.Object, it, JsonText.compact(v), v, false) }
        is List<*> -> "[${v.size}]".let { TableCell(CellKind.Array, it, JsonText.compact(v), v, false) }
        is Array<*> -> "[${v.size}]".let { TableCell(CellKind.Array, it, JsonText.compact(v.toList()), v, false) }
        else -> {
            val s = v.toString()
            if (s.isEmpty()) TableCell(CellKind.EmptyString, "\"\"", "", v, false)
            else {
                val one = s.replace('\n', ' ').replace('\r', ' ')
                val cut = one.length > maxChars
                TableCell(CellKind.Text, if (cut) one.take(maxChars - 1) + "…" else one, s, v, cut)
            }
        }
    }

    /** `3` for 3.0, plain text otherwise; NaN and infinities are shown as text. */
    fun numberText(n: Number): String = when (n) {
        is Double -> if (n.isFinite() && n == Math.rint(n) && Math.abs(n) < 1e15) n.toLong().toString() else n.toString()
        is Float -> if (n.isFinite() && n == Math.rint(n.toDouble()).toFloat() && Math.abs(n) < 1e7f) n.toLong().toString() else n.toString()
        else -> n.toString()
    }

    /** Column width in dp from the header and the cells: between 96 and 240. */
    fun columnWidthDp(header: String, cells: List<TableCell>): Int {
        val longest = maxOf(header.length + 3, cells.maxOfOrNull { it.text.length } ?: 0)
        return (longest * 8 + 28).coerceIn(MIN_COLUMN_DP, MAX_COLUMN_DP)
    }

    /** Ranges of [text] matching [query] (case-insensitive, non-overlapping), for highlighting. */
    fun highlightRanges(text: String, query: String): List<IntRange> {
        val q = query.trim()
        if (q.isEmpty() || text.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var from = 0
        while (from <= text.length - q.length) {
            val at = text.indexOf(q, from, ignoreCase = true)
            if (at < 0) break
            out += at until at + q.length
            from = at + q.length
        }
        return out
    }
}
