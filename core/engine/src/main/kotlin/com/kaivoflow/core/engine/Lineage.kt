package com.kaivoflow.core.engine

import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.ItemRef
import java.util.IdentityHashMap

/**
 * Fills [FlowData.sources] on the items a Step produced. Executors stay lineage-free: the runner
 * infers the link from what it can observe, in this order for every output item:
 *
 *  1. The Step set sources itself and every ref is one of this Step's input items: kept.
 *     Refs that are not input items (typically stale ones copied along with `item.copy(...)`)
 *     are discarded.
 *  2. The output item IS an input item (same instance, then equal json and binary): that input.
 *     This covers If, Switch, Filter, Sort, Limit and other pass-through Steps, many-to-many.
 *  3. Item counts decide the rest: as many outputs as inputs means one-to-one by position;
 *     one input means every output comes from it (one-to-many); one output means it came from
 *     all inputs (many-to-one).
 *  4. Anything else is left without sources rather than guessed.
 *
 * A Step with no input refs (a Spark, or a context built outside the runner) is sourceless: its
 * items get empty sources.
 */
internal object Lineage {

    fun fill(
        outputs: Map<Int, List<FlowData>>,
        input: List<FlowData>,
        refs: List<ItemRef>,
    ): Map<Int, List<FlowData>> {
        if (outputs.isEmpty()) return outputs
        if (refs.isEmpty() || refs.size != input.size) {
            return outputs.mapValues { (_, items) -> items.map { if (it.sources.isEmpty()) it else it.copy(sources = emptyList()) } }
        }
        val valid = refs.toHashSet()
        // Built once, only when a pass-through lookup is actually needed.
        val byIdentity: IdentityHashMap<FlowData, ArrayDeque<Int>> by lazy {
            IdentityHashMap<FlowData, ArrayDeque<Int>>().also { map ->
                input.forEachIndexed { i, item -> map.getOrPut(item) { ArrayDeque() }.add(i) }
            }
        }
        val byValue: Map<Pair<Map<String, Any?>, Map<String, *>>, ArrayDeque<Int>> by lazy {
            HashMap<Pair<Map<String, Any?>, Map<String, *>>, ArrayDeque<Int>>().also { map ->
                input.forEachIndexed { i, item -> map.getOrPut(item.json to item.binary) { ArrayDeque() }.add(i) }
            }
        }
        return outputs.mapValues { (_, items) -> fillPort(items, input, refs, valid, { byIdentity }, { byValue }) }
    }

    private fun fillPort(
        items: List<FlowData>,
        input: List<FlowData>,
        refs: List<ItemRef>,
        valid: Set<ItemRef>,
        identity: () -> IdentityHashMap<FlowData, ArrayDeque<Int>>,
        value: () -> Map<Pair<Map<String, Any?>, Map<String, *>>, ArrayDeque<Int>>,
    ): List<FlowData> {
        if (items.isEmpty()) return items
        val resolved = arrayOfNulls<List<ItemRef>>(items.size)
        items.forEachIndexed { i, item ->
            if (item.sources.isNotEmpty() && item.sources.all { it in valid }) resolved[i] = item.sources
        }
        val used = HashSet<Int>()
        // Pass 2a: same instance. Each input item is claimed at most once per port.
        items.forEachIndexed { i, item ->
            if (resolved[i] != null) return@forEachIndexed
            val candidates = identity()[item] ?: return@forEachIndexed
            val pick = candidates.firstOrNull { it !in used } ?: return@forEachIndexed
            used += pick
            resolved[i] = listOf(refs[pick])
        }
        // Pass 2b: equal content (the Step rebuilt the item without changing it).
        items.forEachIndexed { i, item ->
            if (resolved[i] != null) return@forEachIndexed
            val candidates = value()[item.json to item.binary] ?: return@forEachIndexed
            val pick = candidates.firstOrNull { it !in used } ?: return@forEachIndexed
            used += pick
            resolved[i] = listOf(refs[pick])
        }
        // Pass 3: counts.
        val sameSize = items.size == input.size
        items.forEachIndexed { i, _ ->
            if (resolved[i] != null) return@forEachIndexed
            resolved[i] = when {
                sameSize -> listOf(refs[i])
                input.size == 1 -> listOf(refs[0])
                items.size == 1 -> refs
                else -> emptyList()
            }
        }
        return items.mapIndexed { i, item ->
            val sources = resolved[i].orEmpty()
            if (sources == item.sources) item else item.copy(sources = sources)
        }
    }
}
