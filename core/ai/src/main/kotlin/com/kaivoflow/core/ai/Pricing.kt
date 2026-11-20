package com.kaivoflow.core.ai

import com.kaivoflow.core.model.ParamJson

/** USD per one million tokens. [cachedInput] is the price of a prompt token served from the provider's cache. */
data class ModelPrice(val input: Double, val output: Double, val cachedInput: Double? = null) {
    /** Cost of [u] in USD. Reasoning tokens are already inside the completion count. */
    fun cost(u: Usage): Double {
        val cached = u.cachedPromptTokens.coerceIn(0, u.promptTokens)
        val cachedRate = cachedInput ?: input
        return ((u.promptTokens - cached) * input + cached * cachedRate + u.completionTokens * output) / 1_000_000.0
    }
}

/** A cost with its honesty attached: [usd] is null when the model has no price, [estimated] when tokens were guessed. */
data class CostEstimate(val usd: Double?, val estimated: Boolean, val unpricedModels: Set<String> = emptySet()) {
    companion object { val ZERO = CostEstimate(0.0, false) }
}

/**
 * Prices by model id, longest matching prefix wins (so `gpt-4o-mini-2024-07-18` finds `gpt-4o-mini`, not `gpt-4o`).
 * The bundled list is a starting point written from public price lists; prices change, so an app should call
 * [parse] with a newer JSON when it has one. A model with no entry has no price: cost is reported as unknown,
 * never as zero. Local servers are priced at zero only when named in [localZero].
 *
 * JSON shape for [parse]: `{"asOf":"2026-01","models":{"gpt-4o":{"input":2.5,"output":10,"cachedInput":1.25}}}`.
 */
class PriceTable(val asOf: String, entries: Map<String, ModelPrice>) {
    private val entries: Map<String, ModelPrice> = entries.mapKeys { normalize(it.key) }
    private val keysByLength: List<String> = this.entries.keys.sortedByDescending { it.length }

    fun priceOf(model: String?): ModelPrice? {
        val id = normalize(model ?: return null)
        val key = keysByLength.firstOrNull { id == it || id.startsWith(it) } ?: return null
        return entries[key]
    }

    val size: Int get() = entries.size

    fun estimate(model: String?, usage: Usage, provider: ProviderKind? = null): CostEstimate {
        if (provider == ProviderKind.Ollama) return CostEstimate(0.0, usage.estimated)
        val p = priceOf(model) ?: return CostEstimate(null, usage.estimated, setOfNotNull(model))
        return CostEstimate(p.cost(usage), usage.estimated)
    }

    companion object {
        private fun normalize(id: String): String = id.trim().lowercase().substringAfterLast('/')

        /** Public list prices in USD per million tokens, as last written down by the project. Verify before billing anyone. */
        val BUNDLED = PriceTable(
            "2026-01 (written from public lists, not fetched)",
            mapOf(
                "gpt-4o-mini" to ModelPrice(0.15, 0.60, 0.075),
                "gpt-4o" to ModelPrice(2.50, 10.0, 1.25),
                "gpt-4.1-nano" to ModelPrice(0.10, 0.40, 0.025),
                "gpt-4.1-mini" to ModelPrice(0.40, 1.60, 0.10),
                "gpt-4.1" to ModelPrice(2.0, 8.0, 0.50),
                "o4-mini" to ModelPrice(1.10, 4.40, 0.275),
                "o3-mini" to ModelPrice(1.10, 4.40, 0.55),
                "o3" to ModelPrice(2.0, 8.0, 0.50),
                "o1" to ModelPrice(15.0, 60.0, 7.50),
                "gpt-5-nano" to ModelPrice(0.05, 0.40, 0.005),
                "gpt-5-mini" to ModelPrice(0.25, 2.0, 0.025),
                "gpt-5" to ModelPrice(1.25, 10.0, 0.125),
                "claude-3-5-haiku" to ModelPrice(0.80, 4.0, 0.08),
                "claude-3-haiku" to ModelPrice(0.25, 1.25, 0.03),
                "claude-3-5-sonnet" to ModelPrice(3.0, 15.0, 0.30),
                "claude-3-7-sonnet" to ModelPrice(3.0, 15.0, 0.30),
                "claude-sonnet-4" to ModelPrice(3.0, 15.0, 0.30),
                "claude-haiku-4" to ModelPrice(1.0, 5.0, 0.10),
                "claude-opus-4-5" to ModelPrice(5.0, 25.0, 0.50),
                "claude-opus-4" to ModelPrice(15.0, 75.0, 1.50),
                "gemini-2.5-pro" to ModelPrice(1.25, 10.0, 0.31),
                "gemini-2.5-flash-lite" to ModelPrice(0.10, 0.40, 0.025),
                "gemini-2.5-flash" to ModelPrice(0.30, 2.50, 0.075),
                "gemini-2.0-flash-lite" to ModelPrice(0.075, 0.30),
                "gemini-2.0-flash" to ModelPrice(0.10, 0.40, 0.025),
            ),
        )

        /** Reads a price list. Returns null when the text is not the expected shape; bad entries are skipped. */
        fun parse(json: String): PriceTable? {
            val root = J.obj((ParamJson.parse(json) as? ParamJson.Parsed.Ok)?.value) ?: return null
            val models = J.obj(root["models"]) ?: return null
            val out = LinkedHashMap<String, ModelPrice>()
            for ((k, v) in models) {
                val m = J.obj(v) ?: continue
                val i = (m["input"] as? Number)?.toDouble() ?: continue
                val o = (m["output"] as? Number)?.toDouble() ?: continue
                if (i < 0 || o < 0 || !i.isFinite() || !o.isFinite()) continue
                out[k] = ModelPrice(i, o, (m["cachedInput"] as? Number)?.toDouble()?.takeIf { it >= 0 && it.isFinite() })
            }
            return if (out.isEmpty()) null else PriceTable(J.str(root["asOf"]) ?: "unknown", out)
        }
    }
}

/** Guesses token counts when a provider reports none. About four characters per token; marked as estimated. */
object TokenEstimator {
    fun tokens(text: String): Long = if (text.isEmpty()) 0 else ((text.length + 3) / 4).toLong()

    fun forRequest(request: ChatRequest): Long {
        var n = tokens(request.system.orEmpty())
        for (m in request.messages) { n += tokens(m.text) + 4; m.toolCalls.forEach { n += tokens(it.name) + tokens(J.write(it.arguments)) } }
        request.tools.forEach { n += tokens(it.name) + tokens(it.description) + tokens(J.write(it.parameters)) }
        return n
    }

    fun forResponse(r: ChatResponse): Long =
        tokens(r.text) + tokens(r.thinking) + r.toolCalls.sumOf { tokens(it.name) + tokens(J.write(it.arguments)) }

    /** The provider's numbers when it gave any, else a marked estimate. */
    fun usageOrEstimate(reported: Usage, request: ChatRequest, response: ChatResponse): Usage =
        if (!reported.isEmpty) reported else Usage(forRequest(request), forResponse(response), estimated = true)
}

/** What stopped a run on budget grounds. */
enum class BudgetStop { Tokens, Cost }

/**
 * A spending cap that can be shared by several runs (for example every run of one agent today): each call
 * [add]s what it used and [check] says whether the cap is passed. Thread safe, pure, no storage: a host that wants
 * a daily cap loads yesterday's total into [spentTokens] and [spentUsd] and saves them afterwards.
 * A model with no known price adds tokens but no cost; the cost cap cannot see it, which [unpriced] reports.
 */
class RunBudget(
    val maxTokens: Long? = null,
    val maxUsd: Double? = null,
    spentTokens: Long = 0,
    spentUsd: Double = 0.0,
) {
    init {
        require(maxTokens == null || maxTokens > 0) { "maxTokens must be positive" }
        require(maxUsd == null || maxUsd > 0) { "maxUsd must be positive" }
    }

    private val lock = Any()
    private var tokens = spentTokens
    private var usd = spentUsd
    private val unpricedSet = LinkedHashSet<String>()

    val spentTokens: Long get() = synchronized(lock) { tokens }
    val spentUsd: Double get() = synchronized(lock) { usd }
    val unpriced: Set<String> get() = synchronized(lock) { unpricedSet.toSet() }

    fun add(usage: Usage, cost: CostEstimate) = synchronized(lock) {
        tokens += usage.totalTokens
        cost.usd?.let { usd += it }
        unpricedSet += cost.unpricedModels
    }

    /** Null while within the cap. Checked before a model call (nothing spent beyond the cap) and after it. */
    fun check(): BudgetStop? = synchronized(lock) {
        when {
            maxTokens != null && tokens > maxTokens -> BudgetStop.Tokens
            maxUsd != null && usd > maxUsd -> BudgetStop.Cost
            else -> null
        }
    }

    /** True when one more call of about [nextTokens] would pass the token cap. */
    fun wouldExceed(nextTokens: Long): Boolean = synchronized(lock) { maxTokens != null && tokens + nextTokens > maxTokens }
}

/** Per-model usage of one run, in call order. */
class UsageLedger {
    private val byModel = LinkedHashMap<String, Usage>()
    private var cost = 0.0
    private var anyPriced = false
    private var estimated = false
    private val unpriced = LinkedHashSet<String>()

    fun record(model: String?, usage: Usage, price: CostEstimate) {
        val key = model ?: "unknown"
        byModel[key] = (byModel[key] ?: Usage()) + usage
        price.usd?.let { cost += it; anyPriced = true }
        unpriced += price.unpricedModels
        estimated = estimated || usage.estimated || price.estimated
    }

    val usageByModel: Map<String, Usage> get() = byModel.toMap()
    val total: Usage get() = byModel.values.fold(Usage()) { a, b -> a + b }

    /** USD over the models that have a price; null when none had one. [CostEstimate.unpricedModels] lists the rest. */
    val costEstimate: CostEstimate get() = CostEstimate(if (anyPriced || byModel.isEmpty()) cost else null, estimated, unpriced.toSet())
}
