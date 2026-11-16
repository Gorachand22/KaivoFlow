package com.kaivoflow.feature.canvas.inspector.params

import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.expressions.assist.SourceKind
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.ParamValueCodec
import com.kaivoflow.core.model.SchemaPath
import com.kaivoflow.core.model.SchemaType
import com.kaivoflow.core.model.ValueType

/** One row of the Assignments list (spec P2 2.12). Stored as `{ name, type, value }`; the value uses the nested codec. */
data class AssignmentRow(val name: String, val type: ValueType = ValueType.Text, val value: ParamValue? = null)

object AssignmentsModel {

    fun decode(v: ParamValue?): List<AssignmentRow> = ParamTree.asRows(v).map { m ->
        AssignmentRow(
            name = m["name"]?.toString().orEmpty(),
            type = ValueType.entries.firstOrNull { it.name.equals(m["type"]?.toString(), true) } ?: ValueType.Text,
            value = ParamValueCodec.decodeLenient(m["value"]),
        )
    }

    fun encode(rows: List<AssignmentRow>): ParamValue? = ParamTree.fixedRows(rows.map {
        linkedMapOf<String, Any?>("name" to it.name, "type" to it.type.name.lowercase(), "value" to ParamValueCodec.encode(it.value))
    })

    fun add(rows: List<AssignmentRow>, row: AssignmentRow = AssignmentRow("")): List<AssignmentRow> = rows + row

    fun remove(rows: List<AssignmentRow>, index: Int): List<AssignmentRow> =
        if (index in rows.indices) rows.filterIndexed { i, _ -> i != index } else rows

    fun update(rows: List<AssignmentRow>, index: Int, f: (AssignmentRow) -> AssignmentRow): List<AssignmentRow> =
        rows.mapIndexed { i, r -> if (i == index) f(r) else r }

    fun move(rows: List<AssignmentRow>, from: Int, to: Int): List<AssignmentRow> {
        if (from !in rows.indices || to !in rows.indices || from == to) return rows
        val m = rows.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }

    /** Long-press duplicate: the copy goes right after the original with a name that is not taken. */
    fun duplicate(rows: List<AssignmentRow>, index: Int): List<AssignmentRow> {
        val r = rows.getOrNull(index) ?: return rows
        val copy = r.copy(name = copyName(r.name.ifBlank { "field" }, rows.map { it.name }.toSet()))
        return rows.take(index + 1) + copy + rows.drop(index + 1)
    }

    /** Indexes of rows with an empty or repeated name (dotted names are fine). */
    fun invalidNames(rows: List<AssignmentRow>): Set<Int> {
        val seen = HashSet<String>()
        val bad = LinkedHashSet<Int>()
        rows.forEachIndexed { i, r ->
            val n = r.name.trim()
            if (n.isEmpty() || !seen.add(n)) bad += i
        }
        return bad
    }

    fun inferType(t: SchemaType?): ValueType = when (t) {
        SchemaType.String, SchemaType.Date -> ValueType.Text
        SchemaType.Number -> ValueType.Number
        SchemaType.Bool -> ValueType.Boolean
        SchemaType.Array -> ValueType.List
        SchemaType.Object -> ValueType.Object
        else -> ValueType.Any
    }

    /** The name a dropped field gets: its last field name, else the Step name, else the kind. */
    fun nameFor(source: MapSource): String {
        val last = source.path.segments.lastOrNull { it != SchemaPath.ELEMENT && !it.startsWith("[") }
        return last ?: when (source.kind) {
            SourceKind.Json, SourceKind.Item -> source.stepName.ifBlank { "value" }
            SourceKind.Binary -> "file"
            SourceKind.Run -> "run"
            SourceKind.Vars -> "variable"
        }
    }

    /** A drop on the list or its footer: a row named after the key with the reference as value and the type from the schema. */
    fun fromDrop(rows: List<AssignmentRow>, source: MapSource, reference: String, schemaType: SchemaType?, allowed: Set<ValueType> = ValueType.all): List<AssignmentRow> {
        var type = inferType(schemaType)
        if (type !in allowed) type = if (ValueType.Any in allowed) ValueType.Any else allowed.firstOrNull() ?: ValueType.Text
        val name = uniqueName(sanitize(nameFor(source)), rows.map { it.name }.toSet())
        return rows + AssignmentRow(name, type, ParamValue.Expr(reference))
    }

    private fun sanitize(s: String): String = s.trim().ifEmpty { "value" }

    fun uniqueName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var i = 2
        while ("${base}_$i" in taken) i++
        return "${base}_$i"
    }

    fun copyName(base: String, taken: Set<String>): String {
        val c = "${base}_copy"
        return if (c !in taken) c else uniqueName(c, taken)
    }
}

/** Ordered rows for Repeater and key-value lists; every function returns a new list. */
object RowEdits {
    fun <T> add(rows: List<T>, row: T, max: Int? = null): List<T> = if (max != null && rows.size >= max) rows else rows + row
    fun <T> remove(rows: List<T>, index: Int, min: Int = 0): List<T> =
        if (index in rows.indices && rows.size > min) rows.filterIndexed { i, _ -> i != index } else rows
    fun <T> insertAt(rows: List<T>, index: Int, row: T): List<T> = rows.toMutableList().also { it.add(index.coerceIn(0, rows.size), row) }
    fun <T> move(rows: List<T>, from: Int, to: Int): List<T> {
        if (from !in rows.indices || to !in rows.indices || from == to) return rows
        return rows.toMutableList().also { it.add(to, it.removeAt(from)) }
    }
    fun <T> duplicate(rows: List<T>, index: Int, max: Int? = null): List<T> {
        val r = rows.getOrNull(index) ?: return rows
        if (max != null && rows.size >= max) return rows
        return insertAt(rows, index + 1, r)
    }
}

/** A name/value row list: stored as a map or as a list of `{name, value}` rows; both are read, the original shape is kept when possible. */
data class KeyValueRow(val name: String, val value: ParamValue?)

object KeyValueModel {
    fun decode(v: ParamValue?): List<KeyValueRow> {
        val raw = (v as? ParamValue.Fixed)?.value
        return when (raw) {
            is Map<*, *> -> raw.entries.map { KeyValueRow(it.key.toString(), ParamValueCodec.decodeLenient(it.value)) }
            is List<*> -> raw.map { r ->
                val m = r as? Map<*, *>
                KeyValueRow(m?.get("name")?.toString().orEmpty(), ParamValueCodec.decodeLenient(m?.get("value")))
            }
            else -> emptyList()
        }
    }

    fun wasMap(v: ParamValue?): Boolean = (v as? ParamValue.Fixed)?.value is Map<*, *>

    /** Rows with blank or repeated names cannot live in a map, so they force the list form. */
    fun encode(rows: List<KeyValueRow>, asMap: Boolean): ParamValue? {
        if (rows.isEmpty()) return null
        val names = rows.map { it.name }
        val canMap = asMap && names.none { it.isBlank() } && names.toSet().size == names.size
        return if (canMap) ParamValue.Fixed(rows.associateTo(LinkedHashMap<String, Any?>()) { it.name to ParamValueCodec.encode(it.value) })
        else ParamValue.Fixed(rows.map { linkedMapOf<String, Any?>("name" to it.name, "value" to ParamValueCodec.encode(it.value)) })
    }

    fun fromDrop(rows: List<KeyValueRow>, source: MapSource, reference: String): List<KeyValueRow> =
        rows + KeyValueRow(AssignmentsModel.uniqueName(AssignmentsModel.nameFor(source), rows.map { it.name }.toSet()), ParamValue.Expr(reference))
}
