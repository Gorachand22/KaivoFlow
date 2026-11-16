package com.kaivoflow.feature.canvas.inspector.panels

/**
 * Which tree nodes are open (Schema and JSON views). Nodes shallower than [openDepth] are open
 * unless the user closed them; deeper nodes are closed unless the user opened them. Immutable, so
 * it can live in `rememberSaveable`-style state of the caller.
 */
data class TreeExpansion(
    val openDepth: Int = 1,
    val opened: Set<String> = emptySet(),
    val closed: Set<String> = emptySet(),
) {
    fun isOpen(id: String, depth: Int): Boolean = when {
        id in closed -> false
        id in opened -> true
        else -> depth < openDepth
    }

    /** Flips [id]; [depth] is the node's depth so the default can be taken into account. */
    fun toggle(id: String, depth: Int): TreeExpansion =
        if (isOpen(id, depth)) copy(opened = opened - id, closed = closed + id)
        else copy(opened = opened + id, closed = closed - id)

    companion object {
        /** Everything open. */
        val All = TreeExpansion(openDepth = Int.MAX_VALUE)

        /** Only the top level shown (rows at depth 0 stay visible, their children are closed). */
        val Collapsed = TreeExpansion(openDepth = 0)
    }
}

/** Joins path segments into a stable row id. */
internal fun idOf(segments: List<String>, prefix: String = ""): String =
    prefix + segments.joinToString("\u001F")
