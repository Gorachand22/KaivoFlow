package com.kaivoflow.feature.canvas.inspector.panels

import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.core.model.SchemaOrigin
import com.kaivoflow.core.model.SchemaPath
import com.kaivoflow.core.model.SchemaType

/** One visible line of the Schema view. */
data class SchemaRow(
    val id: String,
    val depth: Int,
    val label: String,
    val path: SchemaPath,
    val type: SchemaType,
    val sample: String?,
    val optional: Boolean,
    val seenIn: Int,
    val of: Int,
    val nullable: Boolean,
    val expandable: Boolean,
    val expanded: Boolean,
    /** True for a shape that was declared (not observed): drawn dimmed with a "sample shape" tag. */
    val declared: Boolean,
    val truncated: Boolean,
    /** True when this row itself matches the search text (ancestors of a match are only shown). */
    val matches: Boolean,
) {
    /** `seen in 7 of 12`, or null when the field is always present. */
    val seenLabel: String? get() = if (optional) "seen in $seenIn of $of" else null
}

/** Turns a [SchemaNode] tree into the flat list of rows the lazy list draws. */
object SchemaTreeFlattener {
    const val ELEMENT_LABEL = "[0]"

    /**
     * [expansion] decides which nodes are open. With a non-blank [query] only rows that match
     * (by key or sample) or lead to a match are kept, and the path to each match is opened.
     * At most [maxRows] rows are produced.
     */
    fun flatten(
        root: SchemaNode,
        expansion: TreeExpansion = TreeExpansion(),
        query: String = "",
        maxRows: Int = 5000,
    ): List<SchemaRow> {
        val out = ArrayList<SchemaRow>()
        val q = query.trim()
        for ((label, child, seg) in children(root)) {
            if (out.size >= maxRows) break
            visit(child, label, SchemaPath(listOf(seg)), 0, expansion, q, out, maxRows)
        }
        return out
    }

    private data class Child(val label: String, val node: SchemaNode, val segment: String)

    private fun children(node: SchemaNode): List<Child> {
        val list = ArrayList<Child>(node.fields.size + 1)
        for ((k, v) in node.fields) list += Child(k, v, k)
        node.element?.let { list += Child(ELEMENT_LABEL, it, SchemaPath.ELEMENT) }
        return list
    }

    private fun selfMatches(label: String, node: SchemaNode, q: String): Boolean =
        q.isNotEmpty() && (label.contains(q, ignoreCase = true) || (node.sample?.contains(q, ignoreCase = true) == true))

    private fun subtreeMatches(label: String, node: SchemaNode, q: String): Boolean {
        if (selfMatches(label, node, q)) return true
        return children(node).any { subtreeMatches(it.label, it.node, q) }
    }

    private fun visit(
        node: SchemaNode,
        label: String,
        path: SchemaPath,
        depth: Int,
        expansion: TreeExpansion,
        q: String,
        out: MutableList<SchemaRow>,
        maxRows: Int,
    ) {
        val self = selfMatches(label, node, q)
        val kids = children(node)
        val descendantMatch = q.isNotEmpty() && kids.any { subtreeMatches(it.label, it.node, q) }
        if (q.isNotEmpty() && !self && !descendantMatch) return
        val id = idOf(path.segments)
        val expandable = kids.isNotEmpty()
        val expanded = expandable && (descendantMatch || expansion.isOpen(id, depth))
        out += SchemaRow(
            id = id,
            depth = depth,
            label = label,
            path = path,
            type = node.type,
            sample = node.sample,
            optional = node.optional,
            seenIn = node.seenIn,
            of = node.of,
            nullable = node.nullable,
            expandable = expandable,
            expanded = expanded,
            declared = node.origin == SchemaOrigin.Declared,
            truncated = node.truncated,
            matches = self,
        )
        if (!expanded) return
        for (c in kids) {
            if (out.size >= maxRows) return
            visit(c.node, c.label, SchemaPath(path.segments + c.segment), depth + 1, expansion, q, out, maxRows)
        }
    }

    /** True when the whole tree has no field to show. */
    fun isEmpty(root: SchemaNode): Boolean = root.fields.isEmpty() && root.element == null
}
