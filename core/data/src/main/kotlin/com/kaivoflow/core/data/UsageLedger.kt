package com.kaivoflow.core.data

import java.time.Instant
import java.time.ZoneId

/** One model call as the ledger stores it. Cost is an integer number of micro-USD (1 USD = 1_000_000), no float drift. */
data class UsageRecord(
    val agentId: String,
    val provider: String,
    val model: String,
    val tokensIn: Long,
    val tokensOut: Long,
    val costMicros: Long,
    val at: Long,
    val flowId: String? = null,
    val runId: String? = null,
) {
    companion object {
        fun usdToMicros(usd: Double): Long = if (usd.isNaN() || usd <= 0.0) 0L else Math.round(usd * 1_000_000.0)
    }
}

/** What `:core:ai`'s accounting feeds. The loop turns each model round into one [UsageRecord]. */
fun interface AiUsageSink {
    suspend fun record(record: UsageRecord)
}

data class UsageTotals(val calls: Long = 0, val tokensIn: Long = 0, val tokensOut: Long = 0, val costMicros: Long = 0) {
    val tokens: Long get() = tokensIn + tokensOut
}

/** Limits that are checked against the counters. A null limit is not enforced. */
data class BudgetLimits(
    val maxTokensPerDay: Long? = null,
    val maxCostMicrosPerDay: Long? = null,
    val maxCallsPerDay: Long? = null,
    val maxTokensPerMonth: Long? = null,
    val maxCostMicrosPerMonth: Long? = null,
)

sealed interface BudgetVerdict {
    data class Ok(val day: UsageTotals, val month: UsageTotals) : BudgetVerdict
    data class Exceeded(val reason: String, val day: UsageTotals, val month: UsageTotals) : BudgetVerdict
}

object UsageScope {
    const val ALL = "all"
    fun agent(id: String) = "agent:$id"
    fun provider(name: String) = "provider:${name.lowercase()}"
}

object UsagePeriod {
    fun day(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "d:" + Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    fun month(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "m:" + Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString().take(7)
}

interface UsageLedger : AiUsageSink {
    suspend fun totals(scope: String, period: String): UsageTotals
    suspend fun recent(limit: Int = 50): List<UsageRecord>
    suspend fun forRun(runId: String): List<UsageRecord>

    /** Checks [limits] against today's and this month's counters for [scope]. Never throws. */
    suspend fun check(scope: String, limits: BudgetLimits, now: Long = System.currentTimeMillis()): BudgetVerdict
}

/**
 * Room ledger. Each call is stored once and three counter scopes are bumped in the same transaction (`all`,
 * the agent, the provider), for the day and the month, so the aggregates always equal the sum of the calls
 * kept. Old calls are pruned by [prune]; counters are kept longer than calls.
 */
class RoomUsageLedger(
    private val access: UsageAccess,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : UsageLedger {

    override suspend fun record(record: UsageRecord) {
        val z = zone()
        val day = UsagePeriod.day(record.at, z)
        val month = UsagePeriod.month(record.at, z)
        val scopes = listOf(UsageScope.ALL, UsageScope.agent(record.agentId), UsageScope.provider(record.provider))
        val rollups = scopes.flatMap { listOf(it to day, it to month) }
        access.record(
            AiUsageEntity(
                agentId = record.agentId, flowId = record.flowId, runId = record.runId, provider = record.provider,
                model = record.model, tokensIn = record.tokensIn.coerceAtLeast(0), tokensOut = record.tokensOut.coerceAtLeast(0),
                costMicros = record.costMicros.coerceAtLeast(0), at = record.at,
            ),
            rollups,
        )
    }

    override suspend fun totals(scope: String, period: String): UsageTotals =
        access.rollup(scope, period)?.let { UsageTotals(it.calls, it.tokensIn, it.tokensOut, it.costMicros) } ?: UsageTotals()

    override suspend fun recent(limit: Int): List<UsageRecord> = access.recent(limit.coerceIn(1, 500)).map { it.toRecord() }

    override suspend fun forRun(runId: String): List<UsageRecord> = access.forRun(runId).map { it.toRecord() }

    override suspend fun check(scope: String, limits: BudgetLimits, now: Long): BudgetVerdict {
        val z = zone()
        val day = totals(scope, UsagePeriod.day(now, z))
        val month = totals(scope, UsagePeriod.month(now, z))
        fun over(limit: Long?, value: Long) = limit != null && value >= limit
        val reason = when {
            over(limits.maxCallsPerDay, day.calls) -> "the daily call limit is reached"
            over(limits.maxTokensPerDay, day.tokens) -> "the daily token limit is reached"
            over(limits.maxCostMicrosPerDay, day.costMicros) -> "the daily cost limit is reached"
            over(limits.maxTokensPerMonth, month.tokens) -> "the monthly token limit is reached"
            over(limits.maxCostMicrosPerMonth, month.costMicros) -> "the monthly cost limit is reached"
            else -> null
        }
        return if (reason == null) BudgetVerdict.Ok(day, month) else BudgetVerdict.Exceeded(reason, day, month)
    }

    /** Calls older than [callDays] and counters older than [rollupMonths] months are removed. */
    suspend fun prune(now: Long = System.currentTimeMillis(), callDays: Int = 400, rollupMonths: Int = 14) {
        val z = zone()
        access.deleteCallsBefore(now - callDays * 86_400_000L)
        val cutoff = Instant.ofEpochMilli(now).atZone(z).toLocalDate().minusMonths(rollupMonths.toLong())
        access.deleteRollupsBefore("d:$cutoff", "m:" + cutoff.toString().take(7))
    }

    private fun AiUsageEntity.toRecord() =
        UsageRecord(agentId, provider, model, tokensIn, tokensOut, costMicros, at, flowId, runId)
}
