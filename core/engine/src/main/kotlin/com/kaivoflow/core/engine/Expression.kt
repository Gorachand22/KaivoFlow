package com.kaivoflow.core.engine

import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.ItemRef

/**
 * Expression support.
 *
 * Android implementations live in `:core:expressions` (QuickJS). Keeping the interface here
 * means the engine can be fully unit-tested on a plain JVM with a stub evaluator, and any
 * future sandbox (V8, Wasm, remote) can be dropped in without touching the runner.
 */
interface ExpressionEvaluator {

    /**
     * Evaluates a bare expression body (without the surrounding `{{ }}`).
     * The result keeps its JSON type (number stays a number, object stays a map).
     */
    fun evaluate(code: String, scope: Map<String, Any?>): Any?

    /**
     * Resolves a template string.
     *
     * - `"{{ ${'$'}json.total }}"` → typed value (Double)
     * - `"Total: {{ ${'$'}json.total }} INR"` → String with the value interpolated
     * - `"plain text"` → returned unchanged, never evaluated (fast path used by the engine)
     */
    fun interpolate(template: String, scope: Map<String, Any?>): Any? {
        val trimmed = template.trim()
        if (!trimmed.contains("{{")) return template
        val single = SINGLE_EXPRESSION.matchEntire(trimmed)
        if (single != null) return evaluate(single.groupValues[1].trim(), scope)
        return interpolateAll(template, scope)
    }

    fun interpolateAll(template: String, scope: Map<String, Any?>): String {
        val sb = StringBuilder()
        var cursor = 0
        for (m in MULTI_EXPRESSION.findAll(template)) {
            sb.append(template, cursor, m.range.first)
            val value = evaluate(m.groupValues[1].trim(), scope)
            sb.append(Values.asString(value))
            cursor = m.range.last + 1
        }
        sb.append(template, cursor, template.length)
        return sb.toString()
    }

    companion object {
        private val SINGLE_EXPRESSION = Regex("""^\{{2,3}([\s\S]*?)\}{2,3}$""")
        private val MULTI_EXPRESSION = Regex("""\{{2,3}([\s\S]*?)\}{2,3}""")
    }
}

/**
 * Builds the `$…` scope a Step sees. Mirrors the documented variable surface:
 * `${'$'}json`, `${'$'}items`, `${'$'}vars`, `${'$'}now`, `${'$'}today`, `$run`, `${'$'}step()`, `$index`.
 *
 * Lineage: `scope["step"]` maps a Step NAME to the items of that Step. When the current item
 * descends from items of a Step, only those ancestor items are listed, so `${'$'}step("Fetch")`
 * returns the paired item rather than the whole output. Steps the item does not descend from
 * keep their full output. `scope["stepAll"]` always holds the full outputs, and
 * `scope["sources"]` lists the ancestors of the current item.
 *
 * [stepOutputs] and [stepPorts] are keyed by Step id; [stepNames] (id to name) turns ids into
 * the names expressions use. A key with no entry in [stepNames] is used as the name unchanged.
 */
class ScopeBuilder(
    private val flowVars: Map<String, Any?>,
    private val runId: String,
    private val stepOutputs: Map<String, List<FlowData>>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timezone: String = "UTC",
    private val stepNames: Map<String, String> = emptyMap(),
    private val stepPorts: Map<String, Map<Int, List<FlowData>>> = emptyMap(),
    private val inputRefs: List<ItemRef> = emptyList(),
) {
    private val fullByName: Map<String, List<Map<String, Any?>>> by lazy {
        stepOutputs.entries.associate { (key, items) ->
            (stepNames[key] ?: key) to items.mapIndexed { i, d -> d.json + ("index" to i) }
        }
    }

    // build() is called once per item per parameter; the item list must not be rebuilt each time.
    @Volatile
    private var cache: Pair<List<FlowData>, List<Map<String, Any?>>>? = null

    private fun itemsOf(input: List<FlowData>): List<Map<String, Any?>> {
        cache?.let { if (it.first === input) return it.second }
        val items = input.map { it.json }
        cache = input to items
        return items
    }

    private fun itemAt(ref: ItemRef): FlowData? =
        stepPorts[ref.stepId]?.get(ref.port)?.getOrNull(ref.itemIndex)
            ?: if (ref.port == 0) stepOutputs[ref.stepId]?.getOrNull(ref.itemIndex) else null

    /** Walks `sources` links from the current item back through finished Steps. */
    private fun ancestors(start: ItemRef?): List<ItemRef> {
        if (start == null) return emptyList()
        val seen = LinkedHashSet<ItemRef>()
        val queue = ArrayDeque<ItemRef>()
        queue.add(start)
        while (queue.isNotEmpty() && seen.size < MAX_LINEAGE) {
            val ref = queue.removeFirst()
            if (ref.stepId.isBlank() || !seen.add(ref)) continue
            itemAt(ref)?.sources?.forEach { queue.add(it) }
        }
        return seen.toList()
    }

    fun build(input: List<FlowData>, index: Int = 0, stepName: String = ""): Map<String, Any?> {
        val first = input.getOrNull(index)?.json ?: emptyMap()
        val nowMillis = clock()
        val now = java.time.Instant.ofEpochMilli(nowMillis)
        val zone = runCatching { java.time.ZoneId.of(timezone) }.getOrDefault(java.time.ZoneId.of("UTC"))
        val atZone = now.atZone(zone)

        val lineage = ancestors(inputRefs.getOrNull(index))
        val step: Map<String, Any?> = if (lineage.isEmpty()) fullByName else {
            val paired = LinkedHashMap<String, Any?>(fullByName)
            lineage.groupBy { it.stepId }.forEach { (id, refs) ->
                val name = stepNames[id] ?: id
                val picked = refs.sortedWith(compareBy({ it.port }, { it.itemIndex })).mapNotNull { ref ->
                    itemAt(ref)?.let { it.json + ("index" to ref.itemIndex) }
                }
                if (picked.isNotEmpty()) paired[name] = picked
            }
            paired
        }

        return mapOf(
            "json" to first,
            "items" to itemsOf(input),
            "item" to first,
            "index" to index,
            "vars" to flowVars.toMap(),
            "now" to now.toString(),
            "today" to atZone.toLocalDate().toString(),
            "run" to mapOf(
                "id" to runId,
                "timestamp" to nowMillis,
                "timezone" to timezone,
                "step" to stepName,
            ),
            "step" to step,
            "stepAll" to fullByName,
            "sources" to lineage.map {
                mapOf("stepId" to it.stepId, "step" to (stepNames[it.stepId] ?: it.stepId), "port" to it.port, "itemIndex" to it.itemIndex)
            },
        )
    }

    private companion object {
        const val MAX_LINEAGE = 10_000
    }
}
