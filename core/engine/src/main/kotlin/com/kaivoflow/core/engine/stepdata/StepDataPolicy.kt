package com.kaivoflow.core.engine.stepdata

import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.Item

/**
 * Why stored Step data is not the full data. When several apply the strongest wins, in the order
 * [ByteCap], [ItemCap], [ValueCap]; the counts in [Truncation] always tell the whole story.
 */
enum class TruncationReason {
    /** More items than the per-port item cap; the first ones are kept. */
    ItemCap,

    /** Items were dropped from the end to fit the per-port byte cap. */
    ByteCap,

    /** Every item is kept but some values were clipped or cut by the depth limit. */
    ValueCap,
}

/**
 * Describes stored data that is not complete. [originalItems] is the true item count, [keptItems]
 * how many were stored (including [errorItemsKept] error items kept beyond the item cap),
 * [clippedValues] how many strings were clipped or containers replaced by a marker.
 */
data class Truncation(
    val reason: TruncationReason,
    val keptItems: Int,
    val originalItems: Int,
    val clippedValues: Int,
    val errorItemsKept: Int = 0,
)

/**
 * Capture limits (spec 5.3). Settings may lower a value but never exceed its hard maximum;
 * [coerced] enforces that.
 */
data class StepDataLimits(
    val maxItemsPerPort: Int = DEFAULT_ITEMS,
    val maxBytesPerPort: Long = DEFAULT_BYTES,
    val maxValueBytes: Int = DEFAULT_VALUE_BYTES,
    val maxDepth: Int = DEFAULT_DEPTH,
    /** Error items kept beyond [maxItemsPerPort]. */
    val maxExtraErrorItems: Int = DEFAULT_ERROR_ITEMS,
    val pinnedBytesPerStep: Long = DEFAULT_PINNED_STEP,
    val pinnedBytesPerFlow: Long = DEFAULT_PINNED_FLOW,
) {
    fun coerced(): StepDataLimits = StepDataLimits(
        maxItemsPerPort = maxItemsPerPort.coerceIn(0, HARD_ITEMS),
        maxBytesPerPort = maxBytesPerPort.coerceIn(0L, HARD_BYTES),
        maxValueBytes = maxValueBytes.coerceIn(64, HARD_VALUE_BYTES),
        maxDepth = maxDepth.coerceIn(1, HARD_DEPTH),
        maxExtraErrorItems = maxExtraErrorItems.coerceIn(0, DEFAULT_ERROR_ITEMS),
        pinnedBytesPerStep = pinnedBytesPerStep.coerceIn(0L, HARD_PINNED_STEP),
        pinnedBytesPerFlow = pinnedBytesPerFlow.coerceIn(0L, HARD_PINNED_FLOW),
    )

    companion object {
        const val DEFAULT_ITEMS = 200
        const val DEFAULT_BYTES = 512L * 1024
        const val DEFAULT_VALUE_BYTES = 8 * 1024
        const val DEFAULT_DEPTH = 12
        const val DEFAULT_ERROR_ITEMS = 20
        const val DEFAULT_PINNED_STEP = 1024L * 1024
        const val DEFAULT_PINNED_FLOW = 4L * 1024 * 1024

        const val HARD_ITEMS = 1000
        const val HARD_BYTES = 2L * 1024 * 1024
        const val HARD_VALUE_BYTES = 64 * 1024
        const val HARD_DEPTH = 24
        const val HARD_PINNED_STEP = 4L * 1024 * 1024
        const val HARD_PINNED_FLOW = 8L * 1024 * 1024

        /** Global store budget; oldest Runs are evicted beyond it. */
        const val DEFAULT_GLOBAL_BUDGET_BYTES = 100L * 1024 * 1024

        val Default = StepDataLimits()
    }
}

/**
 * Masks secret-looking values before anything is stored (spec 5.3 rule 3).
 *
 * Three layers: keys that look sensitive (the same rule as `Values.redact`), extra [secretKeys]
 * (exact, case-insensitive), and values: known [secretValues] anywhere inside a string, plus
 * strings that look like tokens (bearer headers, JWTs, common API key shapes, PEM private keys,
 * passwords inside URLs and secret-named query parameters).
 */
class StepDataRedactor(
    secretValues: Collection<String> = emptyList(),
    secretKeys: Collection<String> = emptyList(),
) {
    private val values: List<String> = secretValues.filter { it.length >= MIN_SECRET_LENGTH }.distinct().sortedByDescending { it.length }
    private val keys: Set<String> = secretKeys.map { it.lowercase() }.toSet()

    fun redact(value: Any?): Any? = walk(Values.redact(value))

    fun redactItem(item: Item): Item {
        @Suppress("UNCHECKED_CAST")
        val json = redact(item.json) as Map<String, Any?>
        val error = item.error?.let { it.copy(message = maskString(it.message)) }
        return if (json == item.json && error == item.error) item else item.copy(json = json, error = error)
    }

    fun redactString(s: String): String = maskString(s)

    private fun walk(v: Any?): Any? = when (v) {
        is Map<*, *> -> {
            val out = LinkedHashMap<String, Any?>(v.size)
            for ((k, x) in v) {
                val key = k.toString()
                out[key] = if (key.lowercase() in keys && x != null) MASK else walk(x)
            }
            out
        }
        is List<*> -> v.map { walk(it) }
        is String -> maskString(v)
        else -> v
    }

    private fun maskString(input: String): String {
        if (input.isEmpty()) return input
        var s = input
        for (secret in values) if (s.contains(secret)) s = s.replace(secret, MASK)
        if (s.length >= 8) {
            if (WHOLE_TOKEN.any { it.matches(s.trim()) }) return MASK
            if (s.contains("PRIVATE KEY-----")) return MASK
            s = BEARER.replace(s) { "${it.groupValues[1]}$MASK" }
            s = URL_PASSWORD.replace(s) { "${it.groupValues[1]}$MASK@" }
            s = SECRET_QUERY.replace(s) { "${it.groupValues[1]}$MASK" }
        }
        return s
    }

    companion object {
        const val MASK = "••••••"
        private const val MIN_SECRET_LENGTH = 4
        private val BEARER = Regex("""(?i)(\bbearer\s+)[A-Za-z0-9._~+/=-]{8,}""")
        private val URL_PASSWORD = Regex("""(://[^/\s:@]+:)[^@\s/]+@""")
        private val SECRET_QUERY = Regex("""(?i)([?&](?:access_token|refresh_token|api_key|apikey|token|key|secret|password|signature)=)[^&\s]+""")
        private val WHOLE_TOKEN = listOf(
            Regex("""eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*"""),
            Regex("""(sk|pk|rk)-[A-Za-z0-9_-]{16,}"""),
            Regex("""gh[pousr]_[A-Za-z0-9]{20,}"""),
            Regex("""xox[abprs]-[A-Za-z0-9-]{10,}"""),
            Regex("""AKIA[0-9A-Z]{16}"""),
        )
        val None = StepDataRedactor()
    }
}

/** One port after limits and redaction. [originalIndices] maps each kept item to its position in the full list. */
data class PortCapture(
    val items: List<Item>,
    val originalIndices: List<Int>,
    val itemCount: Int,
    val bytes: Long,
    val truncation: Truncation?,
)

/**
 * Applies the capture rules of spec 5.3 to the items of one port, in this order: redact, replace
 * too-deep containers by a marker, clip long strings, keep the first N items (plus up to 20 error
 * items beyond N), then drop items from the end until the byte cap fits (non-error items first).
 * Never random sampling; the true item count is always reported.
 */
class StepDataPolicy(
    limits: StepDataLimits = StepDataLimits.Default,
    private val redactor: StepDataRedactor = StepDataRedactor.None,
) {
    val limits: StepDataLimits = limits.coerced()

    /** [totalCount] is the true count when [items] is already a prefix (for example an engine item limit). */
    fun capture(items: List<Item>, totalCount: Int = items.size): PortCapture {
        val trueCount = maxOf(totalCount, items.size)
        val cap = limits.maxItemsPerPort
        val selected = ArrayList<Int>()
        for (i in 0 until minOf(cap, items.size)) selected += i
        var extras = 0
        var i = cap
        while (i < items.size && extras < limits.maxExtraErrorItems) {
            if (items[i].error != null) { selected += i; extras++ }
            i++
        }
        val clipped = Counter()
        val kept = ArrayList<Item>(selected.size)
        val sizes = ArrayList<Long>(selected.size)
        for (idx in selected) {
            val item = sanitize(items[idx], clipped)
            kept += item
            sizes += sizeOf(item)
        }
        var total = sizes.sum()
        var byteDropped = 0
        val alive = BooleanArray(kept.size) { true }
        if (total > limits.maxBytesPerPort) {
            val order = ArrayList<Int>(kept.size)
            for (k in kept.indices.reversed()) if (kept[k].error == null) order += k
            for (k in kept.indices.reversed()) if (kept[k].error != null) order += k
            for (k in order) {
                if (total <= limits.maxBytesPerPort) break
                alive[k] = false
                total -= sizes[k]
                byteDropped++
            }
        }
        val outItems = ArrayList<Item>(kept.size)
        val outIdx = ArrayList<Int>(kept.size)
        for (k in kept.indices) if (alive[k]) { outItems += kept[k]; outIdx += selected[k] }
        val errorKept = outIdx.count { it >= cap && items[it].error != null }
        val reason = when {
            byteDropped > 0 -> TruncationReason.ByteCap
            trueCount > outItems.size -> TruncationReason.ItemCap
            clipped.n > 0 -> TruncationReason.ValueCap
            else -> null
        }
        val truncation = reason?.let { Truncation(it, outItems.size, trueCount, clipped.n, errorKept) }
        return PortCapture(outItems, outIdx, trueCount, total, truncation)
    }

    /** Approximate stored size of one item: its JSON in UTF-8 plus attachment and error metadata. */
    fun sizeOf(item: Item): Long {
        var n = Values.stringify(item.json).toByteArray(Charsets.UTF_8).size.toLong()
        for ((k, ref) in item.binary) n += k.length + ref.uri.length + ref.mimeType.length + ref.fileName.length + 24
        item.error?.let { n += it.message.length + it.kind.length + it.stepId.length + 16 }
        return n
    }

    /** Pinned data is explicit user data: refused over the cap, never truncated. */
    fun pinVerdict(bytes: Long, otherPinsInFlow: Long = 0L): PinVerdict = when {
        bytes > limits.pinnedBytesPerStep -> PinVerdict.StepTooLarge(bytes, limits.pinnedBytesPerStep)
        bytes + otherPinsInFlow > limits.pinnedBytesPerFlow -> PinVerdict.FlowTooLarge(bytes + otherPinsInFlow, limits.pinnedBytesPerFlow)
        else -> PinVerdict.Ok
    }

    // ---- internals ----

    private class Counter { var n = 0 }

    private fun sanitize(item: Item, clipped: Counter): Item {
        val redacted = redactor.redactItem(item)
        val walker = Walker(clipped)
        @Suppress("UNCHECKED_CAST")
        val json = walker.value(redacted.json, 1) as Map<String, Any?>
        return if (json === redacted.json) redacted else redacted.copy(json = json)
    }

    /** Copies a value tree applying depth and string limits; stops expanding after an item exceeds the port byte cap. */
    private inner class Walker(val clipped: Counter) {
        var spent = 0L

        fun value(v: Any?, depth: Int): Any? = when (v) {
            null, is Boolean, is Number -> { spent += 8; v }
            is String -> str(v)
            is Map<*, *> -> if (depth > limits.maxDepth) { clipped.n++; "{…}" } else map(v, depth)
            is List<*> -> if (depth > limits.maxDepth) { clipped.n++; "[…]" } else list(v, depth)
            else -> str(v.toString())
        }

        private fun str(s: String): String {
            val limit = limits.maxValueBytes
            if (s.length.toLong() * 3 <= limit || utf8Length(s) <= limit) { spent += s.length; return s }
            clipped.n++
            val marker = "…[clipped ${s.length} chars]"
            val room = (limit - marker.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
            val out = utf8Prefix(s, room) + marker
            spent += out.length
            return out
        }

        private fun map(m: Map<*, *>, depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>(minOf(m.size, 16))
            for ((k, x) in m) {
                if (spent > limits.maxBytesPerPort) { clipped.n++; break }
                val key = k.toString()
                spent += key.length
                out[key] = value(x, depth + 1)
            }
            return out
        }

        private fun list(l: List<*>, depth: Int): List<Any?> {
            val out = ArrayList<Any?>(minOf(l.size, 16))
            for (x in l) {
                if (spent > limits.maxBytesPerPort) { clipped.n++; break }
                out += value(x, depth + 1)
            }
            return out
        }
    }

    private companion object {
        fun utf8Length(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return n
        }

        fun utf8Prefix(s: String, maxBytes: Int): String {
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val width = when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length -> 4
                    else -> 3
                }
                if (n + width > maxBytes) break
                n += width
                i += if (width == 4) 2 else 1
            }
            return s.substring(0, i)
        }
    }
}

sealed interface PinVerdict {
    data object Ok : PinVerdict
    data class StepTooLarge(val bytes: Long, val limit: Long) : PinVerdict
    data class FlowTooLarge(val bytes: Long, val limit: Long) : PinVerdict
}

// ---- global budget and retention ----

/** Size of the stored data of one Step execution, as the store reports it. */
data class StoredExecutionInfo(
    val runId: String,
    val stepId: String,
    val flowId: String,
    val startedAt: Long,
    val bytes: Long,
    /** True when this execution replayed pinned data; its Run is then never evicted. */
    val pinned: Boolean = false,
)

data class EvictionPlan(
    /** Runs to delete, oldest first. Deleting a Run cascades to all its executions. */
    val evictRunIds: List<String>,
    val freedBytes: Long,
    val remainingBytes: Long,
    /** True when the store is still over budget because the rest is protected. */
    val stillOverBudget: Boolean,
)

/**
 * Decides which Runs to delete when stored Step data exceeds the global budget (spec 5.3 rule 5):
 * oldest Run first; a Run holding pinned data or open in the editor is never evicted.
 */
object StepDataBudget {
    fun plan(
        executions: List<StoredExecutionInfo>,
        budgetBytes: Long = StepDataLimits.DEFAULT_GLOBAL_BUDGET_BYTES,
        openRunIds: Set<String> = emptySet(),
    ): EvictionPlan {
        class RunAgg(val runId: String) { var started = Long.MAX_VALUE; var bytes = 0L; var protectedRun = false }
        val runs = LinkedHashMap<String, RunAgg>()
        for (e in executions) {
            val r = runs.getOrPut(e.runId) { RunAgg(e.runId) }
            r.started = minOf(r.started, e.startedAt)
            r.bytes += e.bytes
            if (e.pinned || e.runId in openRunIds) r.protectedRun = true
        }
        var total = runs.values.sumOf { it.bytes }
        val evict = ArrayList<String>()
        var freed = 0L
        if (total > budgetBytes) {
            for (r in runs.values.filter { !it.protectedRun }.sortedWith(compareBy({ it.started }, { it.runId }))) {
                if (total <= budgetBytes) break
                evict += r.runId
                total -= r.bytes
                freed += r.bytes
            }
        }
        return EvictionPlan(evict, freed, total, total > budgetBytes)
    }
}

/** Which Runs keep Step data (setting `Save data`). */
enum class SaveDataMode { Failed, All, None }

data class RunDataMeta(
    val runId: String,
    val flowId: String,
    val startedAt: Long,
    /** A manual or test Run (as opposed to a production Run started by a Spark). */
    val manual: Boolean,
    val failed: Boolean,
)

/** Count-based retention (spec 5.3 rule 5): which Runs lose their Step data. */
object StepDataRetention {
    const val KEEP_MANUAL_RUNS = 20
    /** Test Step executions have their own small bucket so they never push real manual Runs out. */
    const val KEEP_TEST_RUNS = 10
    const val KEEP_SUCCESSFUL_PRODUCTION_RUNS = 5

    fun runsToDrop(
        runs: List<RunDataMeta>,
        mode: SaveDataMode = SaveDataMode.Failed,
        protectedRunIds: Set<String> = emptySet(),
        keepManual: Int = KEEP_MANUAL_RUNS,
        keepSuccessful: Int = KEEP_SUCCESSFUL_PRODUCTION_RUNS,
        keepTest: Int = KEEP_TEST_RUNS,
    ): List<String> {
        val drop = ArrayList<RunDataMeta>()
        for ((_, flowRuns) in runs.groupBy { it.flowId }) {
            val newestFirst = flowRuns.sortedWith(compareByDescending<RunDataMeta> { it.startedAt }.thenByDescending { it.runId })
            val (tests, rest) = newestFirst.partition { it.runId.startsWith(TEST_RUN_PREFIX) }
            val (manual, production) = rest.partition { it.manual }
            drop += tests.drop(keepTest)
            drop += manual.drop(keepManual)
            when (mode) {
                SaveDataMode.All -> {}
                SaveDataMode.None -> drop += production
                SaveDataMode.Failed -> drop += production.filter { !it.failed }.drop(keepSuccessful)
            }
        }
        return drop.filter { it.runId !in protectedRunIds }.sortedBy { it.startedAt }.map { it.runId }
    }
}

