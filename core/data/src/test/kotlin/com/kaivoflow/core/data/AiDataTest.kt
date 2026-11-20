package com.kaivoflow.core.data

import com.kaivoflow.core.ai.ChatMessage
import com.kaivoflow.core.ai.Role
import com.kaivoflow.core.ai.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private class FakeMemory : MemoryAccess {
    val rows = LinkedHashMap<String, AgentMemoryEntity>()
    override suspend fun get(rowKey: String) = rows[rowKey]
    override suspend fun put(row: AgentMemoryEntity) { rows[row.rowKey] = row }
    override suspend fun touch(rowKey: String, at: Long) { rows[rowKey]?.let { rows[rowKey] = it.copy(lastAccessAt = at) } }
    override suspend fun delete(rowKey: String) { rows.remove(rowKey) }
    override suspend fun deleteScope(scope: String) { rows.entries.removeAll { it.value.scope == scope } }
    override suspend fun deleteIdleBefore(before: Long): Int { val n = rows.size; rows.entries.removeAll { it.value.lastAccessAt < before }; return n - rows.size }
    private fun info(e: AgentMemoryEntity) = MemoryRowInfo(e.rowKey, e.scope, e.bytes, e.lastAccessAt)
    override suspend fun scopeRows(scope: String) = rows.values.filter { it.scope == scope }.sortedBy { it.lastAccessAt }.map(::info)
    override suspend fun totalBytes() = rows.values.sumOf { it.bytes.toLong() }
    override suspend fun allRows() = rows.values.sortedBy { it.lastAccessAt }.map(::info)
}

private class FakeUsage : UsageAccess {
    val calls = ArrayList<AiUsageEntity>()
    val rollups = HashMap<Pair<String, String>, AiUsageRollupEntity>()
    override suspend fun record(row: AiUsageEntity, rollups: List<Pair<String, String>>) {
        calls += row.copy(id = calls.size + 1L)
        for ((s, p) in rollups) {
            val o = this.rollups[s to p] ?: AiUsageRollupEntity(s, p, 0, 0, 0, 0)
            this.rollups[s to p] = o.copy(calls = o.calls + 1, tokensIn = o.tokensIn + row.tokensIn, tokensOut = o.tokensOut + row.tokensOut, costMicros = o.costMicros + row.costMicros)
        }
    }
    override suspend fun rollup(scope: String, period: String) = rollups[scope to period]
    override suspend fun recent(limit: Int) = calls.sortedByDescending { it.at }.take(limit)
    override suspend fun forRun(runId: String) = calls.filter { it.runId == runId }
    override suspend fun deleteCallsBefore(before: Long): Int { val n = calls.size; calls.removeAll { it.at < before }; return n - calls.size }
    override suspend fun deleteRollupsBefore(dayPeriod: String, monthPeriod: String): Int {
        val n = rollups.size
        rollups.keys.removeAll { (_, p) -> (p.startsWith("d:") && p < dayPeriod) || (p.startsWith("m:") && p < monthPeriod) }
        return n - rollups.size
    }
}

private class FakeEvents : EventAccess {
    val rows = ArrayList<AiEventEntity>()
    private var next = 1L
    override suspend fun append(row: AiEventEntity) { if (rows.none { it.sessionId == row.sessionId && it.seq == row.seq }) rows += row.copy(id = next++) }
    override suspend fun maxSeq(sessionId: String) = rows.filter { it.sessionId == sessionId }.maxOfOrNull { it.seq }
    override suspend fun forSession(sessionId: String, afterSeq: Int, limit: Int) = rows.filter { it.sessionId == sessionId && it.seq > afterSeq }.sortedBy { it.seq }.take(limit)
    override suspend fun countForSession(sessionId: String) = rows.count { it.sessionId == sessionId }
    override suspend fun deleteSession(sessionId: String) { rows.removeAll { it.sessionId == sessionId } }
    override suspend fun deleteBefore(before: Long): Int { val n = rows.size; rows.removeAll { it.at < before }; return n - rows.size }
    override suspend fun trimSession(sessionId: String, keepFromSeq: Int) { rows.removeAll { it.sessionId == sessionId && it.seq < keepFromSeq } }
    override suspend fun total() = rows.size
    override suspend fun trimOldest(count: Int) { rows.sortBy { it.id }; repeat(count.coerceAtMost(rows.size)) { rows.removeAt(0) } }
    override suspend fun clear() { rows.clear() }
}

private class Secrets(vararg v: String) : SecretValueSource {
    private val list = v.toList()
    override suspend fun values() = list
}

class AiDataTest {
    private val day = 86_400_000L

    // ---- memory ----

    @Test fun memoryRoundTripsUserAndAssistantTextOnly() = runBlocking {
        val store = RoomMemoryStore("agentA", FakeMemory())
        val call = ToolCall("1", "t", mapOf("secretArg" to "x"))
        store.write("chat1", listOf(ChatMessage.user("hi"), ChatMessage.assistant("calling", listOf(call)), ChatMessage.tool(call, "result"), ChatMessage.assistant("done")))
        val back = store.read("chat1")
        assertEquals(listOf("hi", "calling", "done"), back.map { it.text })
        assertTrue(back.all { it.role != Role.Tool && it.toolCalls.isEmpty() })
    }

    @Test fun memoryKeysAreHashedAndScopedPerAgent() = runBlocking {
        val acc = FakeMemory()
        val a = RoomMemoryStore("A", acc); val b = RoomMemoryStore("B", acc)
        a.write("../../etc/passwd'; DROP TABLE x;--", listOf(ChatMessage.user("a-secret-thread")))
        assertTrue(b.read("../../etc/passwd'; DROP TABLE x;--").isEmpty())
        val key = acc.rows.keys.single()
        assertTrue(key.startsWith("A:")); assertEquals(2 + 64, key.length)
        assertFalse(key.contains("passwd"))
        val k2 = RoomMemoryStore("A", acc).also { it.write("other", listOf(ChatMessage.user("x"))) }
        assertEquals(2, acc.rows.size); assertNotEquals(acc.rows.keys.first(), acc.rows.keys.last()); k2.clear("other")
    }

    @Test fun emptyOrHugeKeysAreRefused() {
        val s = RoomMemoryStore("A", FakeMemory())
        assertTrue(runCatching { runBlocking { s.read("  ") } }.isFailure)
        assertTrue(runCatching { runBlocking { s.write("k".repeat(5000), emptyList()) } }.isFailure)
    }

    @Test fun memoryIsRedactedAndBounded() = runBlocking {
        val acc = FakeMemory()
        val s = RoomMemoryStore("A", acc, Secrets("hunter2hunter2"), MemoryLimits(maxMessagesPerKey = 4, maxCharsPerMessage = 50))
        val msgs = (1..10).map { ChatMessage.user("m$it my key is hunter2hunter2 " + "x".repeat(100)) }
        s.write("k", msgs)
        val back = s.read("k")
        assertEquals(4, back.size)
        assertTrue(back.all { it.text.length <= 51 })
        assertFalse(acc.rows.values.any { it.payload.contains("hunter2hunter2") })
    }

    @Test fun byteCapDropsOldestMessagesFirst() = runBlocking {
        val s = RoomMemoryStore("A", FakeMemory(), limits = MemoryLimits(maxBytesPerKey = 600, maxCharsPerMessage = 200))
        s.write("k", (1..20).map { ChatMessage.user("n$it " + "y".repeat(150)) })
        val back = s.read("k")
        assertTrue(back.isNotEmpty() && back.size < 20)
        assertTrue(back.last().text.startsWith("n20"))
    }

    @Test fun idleMemoryExpiresAfterThirtyDaysOnReadAndSweep() = runBlocking {
        var now = 1_000L
        val acc = FakeMemory()
        val s = RoomMemoryStore("A", acc, clock = { now })
        s.write("old", listOf(ChatMessage.user("a"))); s.write("fresh", listOf(ChatMessage.user("b")))
        now += 29 * day
        assertEquals(1, s.read("fresh").size) // touches fresh
        now += 2 * day
        assertTrue(s.read("old").isEmpty())
        assertEquals(1, s.read("fresh").size)
        s.write("idle2", listOf(ChatMessage.user("c")))
        now += 31 * day
        assertEquals(2, RoomMemoryStore.sweep(acc, now))
        assertTrue(acc.rows.isEmpty())
    }

    @Test fun perScopeKeyCapEvictsLeastRecentlyUsed() = runBlocking {
        var now = 1L
        val acc = FakeMemory()
        val s = RoomMemoryStore("A", acc, limits = MemoryLimits(maxKeysPerScope = 3), clock = { now++ })
        for (i in 1..5) s.write("k$i", listOf(ChatMessage.user("v$i")))
        assertEquals(3, acc.rows.size)
        assertTrue(s.read("k1").isEmpty()); assertEquals(1, s.read("k5").size)
    }

    @Test fun globalByteCapEvictsOldestAcrossScopesButKeepsTheNewWrite() = runBlocking {
        var now = 1L
        val acc = FakeMemory()
        val lim = MemoryLimits(maxTotalBytes = 700, maxCharsPerMessage = 300)
        for (i in 1..6) RoomMemoryStore("S$i", acc, limits = lim, clock = { now++ }).write("k", listOf(ChatMessage.user("z".repeat(250))))
        assertTrue(acc.totalBytes() <= 700 + 300)
        assertTrue(acc.rows.keys.any { it.startsWith("S6:") })
    }

    @Test fun clearAllRemovesOnlyThatAgent() = runBlocking {
        val acc = FakeMemory()
        val a = RoomMemoryStore("A", acc); val b = RoomMemoryStore("B", acc)
        a.write("k", listOf(ChatMessage.user("1"))); b.write("k", listOf(ChatMessage.user("2")))
        a.clearAll()
        assertTrue(a.read("k").isEmpty()); assertEquals(1, b.read("k").size)
    }

    // ---- usage ledger ----

    private val utc = ZoneId.of("UTC")
    private fun rec(at: Long, agent: String = "a1", provider: String = "OpenAI", tin: Long = 100, tout: Long = 50, cost: Long = 2_000, run: String? = "r1") =
        UsageRecord(agent, provider, "gpt", tin, tout, cost, at, "f1", run)

    @Test fun ledgerBumpsDayMonthAndScopesTogether() = runBlocking {
        val l = RoomUsageLedger(FakeUsage()) { utc }
        val t = 1_700_000_000_000L // 2023-11-14
        l.record(rec(t)); l.record(rec(t + 1000, agent = "a2", cost = 1_000))
        val d = UsagePeriod.day(t, utc)
        assertEquals(UsageTotals(2, 200, 100, 3_000), l.totals(UsageScope.ALL, d))
        assertEquals(UsageTotals(1, 100, 50, 2_000), l.totals(UsageScope.agent("a1"), d))
        assertEquals(UsageTotals(2, 200, 100, 3_000), l.totals(UsageScope.provider("openai"), UsagePeriod.month(t, utc)))
        assertEquals(2, l.forRun("r1").size)
    }

    @Test fun dayAndMonthBoundariesFollowTheZone() = runBlocking {
        val l = RoomUsageLedger(FakeUsage()) { utc }
        val endOfDay = 1_700_006_399_000L // 2023-11-14T23:59:59Z
        l.record(rec(endOfDay)); l.record(rec(endOfDay + 2000))
        assertEquals(1L, l.totals(UsageScope.ALL, "d:2023-11-14").calls)
        assertEquals(1L, l.totals(UsageScope.ALL, "d:2023-11-15").calls)
        assertEquals(2L, l.totals(UsageScope.ALL, "m:2023-11").calls)
    }

    @Test fun budgetCheckReportsTheFirstLimitReached() = runBlocking {
        val l = RoomUsageLedger(FakeUsage()) { utc }
        val t = 1_700_000_000_000L
        l.record(rec(t, tin = 600, tout = 500, cost = 9_000))
        assertTrue(l.check(UsageScope.agent("a1"), BudgetLimits(maxTokensPerDay = 5_000), t) is BudgetVerdict.Ok)
        val v = l.check(UsageScope.agent("a1"), BudgetLimits(maxTokensPerDay = 1_000), t) as BudgetVerdict.Exceeded
        assertTrue(v.reason.contains("token"))
        assertTrue(l.check(UsageScope.agent("a1"), BudgetLimits(maxCostMicrosPerDay = 9_000), t) is BudgetVerdict.Exceeded)
        assertTrue(l.check(UsageScope.agent("other"), BudgetLimits(maxTokensPerDay = 1), t) is BudgetVerdict.Ok)
        // The next day starts a fresh counter.
        assertTrue(l.check(UsageScope.agent("a1"), BudgetLimits(maxTokensPerDay = 1_000), t + day) is BudgetVerdict.Ok)
    }

    @Test fun negativeValuesAreClampedAndUsdConvertsExactly() = runBlocking {
        val acc = FakeUsage(); val l = RoomUsageLedger(acc) { utc }
        l.record(rec(1_700_000_000_000L, tin = -5, tout = -1, cost = -9))
        assertEquals(UsageTotals(1, 0, 0, 0), l.totals(UsageScope.ALL, "d:2023-11-14"))
        assertEquals(1_234_568L, UsageRecord.usdToMicros(1.2345678)); assertEquals(0L, UsageRecord.usdToMicros(Double.NaN))
    }

    @Test fun pruneDropsOldCallsAndCounters() = runBlocking {
        val acc = FakeUsage(); val l = RoomUsageLedger(acc) { utc }
        val now = 1_700_000_000_000L
        l.record(rec(now - 500 * day)); l.record(rec(now))
        l.prune(now)
        assertEquals(1, acc.calls.size)
        assertEquals(0L, l.totals(UsageScope.ALL, UsagePeriod.month(now - 500 * day, utc)).calls)
        assertEquals(1L, l.totals(UsageScope.ALL, UsagePeriod.month(now, utc)).calls)
    }

    // ---- event log ----

    @Test fun eventsAreOrderedRedactedAndReplayable() = runBlocking {
        val log = RoomAiEventLog(FakeEvents(), Secrets("topsecretvalue"))
        log.append("s1", "user", "my key topsecretvalue and sk-abcdefghijklmnop")
        log.append("s1", "tool call!!", "x"); log.append("s2", "user", "other")
        val ev = log.replay("s1")
        assertEquals(listOf(1, 2), ev.map { it.seq })
        assertFalse(ev[0].payload.contains("topsecretvalue")); assertFalse(ev[0].payload.contains("sk-abcdef"))
        assertEquals("toolcall", ev[1].kind)
        assertEquals(listOf(2), log.replay("s1", afterSeq = 1).map { it.seq })
        log.deleteSession("s1"); assertTrue(log.replay("s1").isEmpty()); assertEquals(1, log.replay("s2").size)
    }

    @Test fun eventLogIsBounded() = runBlocking {
        val acc = FakeEvents()
        val log = RoomAiEventLog(acc, limits = EventLogLimits(maxPayloadChars = 10, maxEventsPerSession = 5, maxTotalEvents = 8))
        repeat(12) { log.append("s", "k", "p".repeat(100) + it) }
        assertEquals(5, acc.countForSession("s"))
        assertEquals(listOf(8, 9, 10, 11, 12), log.replay("s").map { it.seq })
        assertTrue(acc.rows.all { it.payload.length <= 11 })
        repeat(6) { log.append("t$it", "k", "x") }
        assertTrue(acc.total() <= 8)
    }
}
