package com.kaivoflow.core.engine.stepdata

import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.StepRunStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinServiceTest {
    private class Mem : PinPersistence {
        val map = LinkedHashMap<Pair<String, String>, PinnedData>()
        val state = MutableStateFlow(0)
        override suspend fun load(flowId: String, stepId: String) = map[flowId to stepId]
        override suspend fun save(flowId: String, stepId: String, data: PinnedData) { map[flowId to stepId] = data }
        override suspend fun delete(flowId: String, stepId: String) { map.remove(flowId to stepId) }
        override suspend fun all(flowId: String) = map.filterKeys { it.first == flowId }.mapKeys { it.key.second }
        override fun observe(flowId: String, stepId: String): Flow<PinnedData?> = flowOf(map[flowId to stepId])
    }

    /** Store with one fixed execution: port 0 with [stored] items out of [total]. */
    private class FakeStore(val stored: Int, val total: Int, val truncation: Truncation? = null, val dropped: Boolean = false) : StepDataStore {
        val key = StepExecutionKey("r", "s", 0)
        override suspend fun write(execution: CapturedExecution) {}
        override fun observeExecutions(flowId: String, stepId: String, limit: Int) = flowOf(emptyList<StepExecutionSummary>())
        override suspend fun summary(key: StepExecutionKey) = StepExecutionRecord(
            key, "f", StepRunStatus.Succeeded, 1, 2, 1, emptyList(),
            listOf(PortSnapshot(0, null, total, stored, 0, truncation, null)), null, false, true, dropped,
        )
        override suspend fun latestKey(flowId: String, stepId: String) = key
        override suspend fun page(key: StepExecutionKey, direction: Direction, port: Int, offset: Int, limit: Int, filter: ItemFilter?) =
            ItemPage(List(stored) { Item.of("n" to it) }, List(stored) { it }, 0, stored, stored, total, truncation)
        override suspend fun schema(key: StepExecutionKey, direction: Direction, port: Int) = throw UnsupportedOperationException()
        override suspend fun search(key: StepExecutionKey, direction: Direction, port: Int, query: String, limit: Int) = throw UnsupportedOperationException()
        override suspend fun deleteRun(runId: String) {}
        override suspend fun usage() = emptyList<StoredExecutionInfo>()
        override suspend fun runMetas() = emptyList<RunDataMeta>()
    }

    private fun svc(store: StepDataStore? = null, p: Mem = Mem()) = PinService(p, store, StepDataPolicy(), { 42L }) to p

    @Test fun pinFromJsonAcceptsArrayObjectAndWrappedItems() = runBlocking {
        val (s, p) = svc()
        val r = s.pinFromJson("f", "a", """[{"a":1},{"a":2}]""") as PinResult.Ok
        assertEquals(2, r.data.ports.single().size)
        assertEquals(1L, r.data.ports[0][0].json["a"])
        assertEquals(PinSource.Json, r.data.source); assertEquals(42L, r.data.pinnedAt)
        assertEquals(1, (s.pinFromJson("f", "b", """{"a":1}""") as PinResult.Ok).data.ports[0].size)
        val w = s.pinFromJson("f", "c", """[{"json":{"k":"v"}}]""") as PinResult.Ok
        assertEquals("v", w.data.ports[0][0].json["k"])
        assertEquals(3, p.map.size)
    }

    @Test fun invalidJsonReportsLineAndColumn() = runBlocking {
        val (s, p) = svc()
        val r = s.pinFromJson("f", "a", "[\n  {\"a\": 1},\n  {\"a\": }\n]") as PinResult.Invalid
        assertEquals(3, r.line); assertEquals(9, r.column)
        val r2 = s.pinFromJson("f", "a", "[1, 2]") as PinResult.Invalid
        assertEquals(1, r2.line); assertEquals(2, r2.column)
        assertTrue(r2.message.contains("Item 1"))
        val r3 = s.pinFromJson("f", "a", """{"a": 1} x""") as PinResult.Invalid
        assertEquals(1, r3.line); assertEquals(10, r3.column)
        assertTrue(s.pinFromJson("f", "a", "  ") is PinResult.Invalid)
        assertTrue(s.pinFromJson("f", "a", "[]") is PinResult.Invalid)
        assertTrue(s.pinFromJson("f", "a", "{\"a\": \"unterminated}") is PinResult.Invalid)
        assertTrue(p.map.isEmpty())
    }

    @Test fun stepCapRefusesAndKeepsTheOldPin() = runBlocking {
        val (s, p) = svc()
        assertTrue(s.pinFromJson("f", "a", """{"a":1}""") is PinResult.Ok)
        val big = "[" + (1..40).joinToString(",") { """{"v":"${"x".repeat(40_000)}"}""" } + "]" // ~1.6 MB > 1 MB step cap
        val r = s.pinFromJson("f", "a", big) as PinResult.TooLarge
        assertEquals(StepDataLimits.DEFAULT_PINNED_STEP, r.limit)
        assertEquals(1, p.map.size)
        assertEquals(1L, p.map.values.single().ports[0][0].json["a"])
    }

    @Test fun flowCapSumsOtherSteps() = runBlocking {
        val (s, _) = svc()
        val chunk = "[" + (1..20).joinToString(",") { """{"v":"${"x".repeat(40_000)}"}""" } + "]" // ~0.8 MB
        for (id in listOf("a", "b", "c", "d", "e")) assertTrue(id, s.pinFromJson("f", id, chunk) is PinResult.Ok) // 4.0 MB
        val r = s.pinFromJson("f", "z", chunk)
        assertTrue(r is PinResult.TooLarge)
        assertEquals(StepDataLimits.DEFAULT_PINNED_FLOW, (r as PinResult.TooLarge).limit)
        assertTrue("another flow is unaffected", s.pinFromJson("g", "z", chunk) is PinResult.Ok)
    }

    @Test fun pinFromCompleteExecutionAndRefusalOfTruncatedOnes() = runBlocking {
        val ok = FakeStore(3, 3)
        val (s, p) = svc(ok)
        val r = s.pinFromExecution("f", ok.key, 0) as PinResult.Ok
        assertEquals(PinSource.Execution, r.data.source); assertEquals(3, r.data.ports[0].size)
        assertEquals(3, p.map.getValue("f" to "s").ports[0].size)
        val cut = FakeStore(2, 10, Truncation(TruncationReason.ItemCap, 2, 10, 0))
        assertEquals(PinResult.Truncated, svc(cut).first.pinFromExecution("f", cut.key, 0))
        val drop = FakeStore(0, 5, dropped = true)
        assertEquals(PinResult.Truncated, svc(drop).first.pinFromExecution("f", drop.key, 0))
        assertTrue(svc(ok).first.pinFromExecution("f", ok.key, 3) is PinResult.Invalid)
    }

    @Test fun pinFromHigherPortLeavesEarlierPortsEmpty() = runBlocking {
        val st = object : StepDataStore by FakeStore(1, 1) {
            override suspend fun summary(key: StepExecutionKey) = StepExecutionRecord(
                key, "f", StepRunStatus.Succeeded, 1, 2, 1, emptyList(),
                listOf(PortSnapshot(0, null, 0, 0, 0, null, null), PortSnapshot(1, null, 1, 1, 0, null, null)), null, false, true,
            )
        }
        val r = svc(st).first.pinFromExecution("f", StepExecutionKey("r", "s", 0), 1) as PinResult.Ok
        assertEquals(2, r.data.ports.size); assertTrue(r.data.ports[0].isEmpty()); assertEquals(1, r.data.ports[1].size)
    }

    @Test fun unpinAndObserveAndRestore() = runBlocking {
        val (s, p) = svc()
        s.pinFromJson("f", "a", """{"a":1}""")
        assertEquals(1, s.observe("f", "a").first()!!.ports[0].size)
        assertEquals(1, s.pinnedFor("f", "a")!!.ports[0].size)
        s.unpin("f", "a")
        assertNull(s.pinnedFor("f", "a"))
        val big = PinnedData(listOf(List(1) { Item.of("v" to "x".repeat(2_000_000)) }), 2_000_000, 1, PinSource.Json)
        val ok = PinnedData(listOf(listOf(Item.of("a" to 1))), 5, 1, PinSource.Json)
        assertEquals(listOf("big"), s.restore("f", mapOf("ok" to ok, "big" to big)))
        assertEquals(setOf("f" to "ok"), p.map.keys)
    }
}
