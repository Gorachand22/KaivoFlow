package com.kaivoflow.core.engine.stepdata

import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.ItemError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepDataPolicyTest {
    private fun items(n: Int, mk: (Int) -> Item = { Item.of("i" to it) }) = List(n) { mk(it) }

    @Test fun smallPortIsComplete() {
        val c = StepDataPolicy().capture(items(5))
        assertEquals(5, c.items.size); assertNull(c.truncation); assertEquals(5, c.itemCount)
        assertEquals(listOf(0, 1, 2, 3, 4), c.originalIndices)
    }

    @Test fun tenThousandItemsStore200AndReportTrueCount() {
        val c = StepDataPolicy().capture(items(10_000))
        assertEquals(200, c.items.size); assertEquals(10_000, c.itemCount)
        val t = c.truncation!!
        assertEquals(TruncationReason.ItemCap, t.reason); assertEquals(200, t.keptItems); assertEquals(10_000, t.originalItems)
        assertEquals((0 until 200).toList(), c.originalIndices)
    }

    @Test fun errorItemsBeyondCapAreKeptUpTo20() {
        val list = items(500) { if (it >= 300 && it % 2 == 0) Item(error = ItemError("boom $it")) else Item.of("i" to it) }
        val c = StepDataPolicy().capture(list)
        assertEquals(220, c.items.size)
        assertEquals(20, c.truncation!!.errorItemsKept)
        assertTrue(c.originalIndices.takeLast(20).all { it >= 300 && it % 2 == 0 })
        assertEquals(500, c.itemCount)
    }

    @Test fun passedTotalCountIsRespected() {
        val c = StepDataPolicy().capture(items(10), totalCount = 5000)
        assertEquals(5000, c.itemCount); assertEquals(TruncationReason.ItemCap, c.truncation!!.reason)
    }

    @Test fun byteCapDropsFromTheEnd() {
        val big = "x".repeat(8000)
        val c = StepDataPolicy(StepDataLimits(maxBytesPerPort = 40_000)).capture(items(100) { Item.of("s" to big) })
        assertTrue(c.bytes <= 40_000); assertTrue(c.items.size in 1..5)
        assertEquals(TruncationReason.ByteCap, c.truncation!!.reason)
        assertEquals((0 until c.items.size).toList(), c.originalIndices)
    }

    @Test fun byteCapPrefersErrorItems() {
        val big = "x".repeat(4000)
        val list = items(30) { if (it == 25) Item(json = mapOf("s" to big), error = ItemError("e")) else Item.of("s" to big) }
        val c = StepDataPolicy(StepDataLimits(maxBytesPerPort = 30_000)).capture(list)
        assertTrue(25 in c.originalIndices)
    }

    @Test fun longValueIsClippedAndCounted() {
        val c = StepDataPolicy().capture(listOf(Item.of("s" to "é".repeat(20_000), "t" to "ok")))
        val s = c.items[0].json["s"] as String
        assertTrue(s.toByteArray().size <= StepDataLimits.DEFAULT_VALUE_BYTES)
        assertTrue(s.contains("clipped 20000 chars"))
        assertEquals("ok", c.items[0].json["t"])
        val t = c.truncation!!
        assertEquals(TruncationReason.ValueCap, t.reason); assertEquals(1, t.clippedValues)
    }

    @Test fun deepValuesBecomeMarkers() {
        var v: Any? = "leaf"
        repeat(30) { v = mapOf("k" to v) }
        val c = StepDataPolicy().capture(listOf(Item.of("d" to v)))
        var cur: Any? = c.items[0].json["d"]
        var depth = 0
        while (cur is Map<*, *>) { cur = cur["k"]; depth++ }
        assertEquals("{…}", cur)
        assertTrue(depth <= StepDataLimits.DEFAULT_DEPTH)
        assertEquals(TruncationReason.ValueCap, c.truncation!!.reason)
    }

    @Test fun limitsNeverExceedHardMaxima() {
        val l = StepDataLimits(maxItemsPerPort = 99_999, maxBytesPerPort = Long.MAX_VALUE, maxValueBytes = Int.MAX_VALUE, maxDepth = 999,
            pinnedBytesPerStep = Long.MAX_VALUE, pinnedBytesPerFlow = Long.MAX_VALUE).coerced()
        assertEquals(1000, l.maxItemsPerPort); assertEquals(2L * 1024 * 1024, l.maxBytesPerPort)
        assertEquals(64 * 1024, l.maxValueBytes); assertEquals(24, l.maxDepth)
        assertEquals(4L * 1024 * 1024, l.pinnedBytesPerStep); assertEquals(8L * 1024 * 1024, l.pinnedBytesPerFlow)
    }

    @Test fun pinVerdict() {
        val p = StepDataPolicy()
        assertEquals(PinVerdict.Ok, p.pinVerdict(500_000))
        assertTrue(p.pinVerdict(2_000_000) is PinVerdict.StepTooLarge)
        assertTrue(p.pinVerdict(900_000, otherPinsInFlow = 3_500_000) is PinVerdict.FlowTooLarge)
    }

    @Test fun secretsAreRedactedBeforeStorage() {
        val red = StepDataRedactor(secretValues = listOf("hunter2-super"), secretKeys = listOf("pin"))
        val c = StepDataPolicy(redactor = red).capture(listOf(Item(
            json = mapOf(
                "password" to "p", "pin" to "1234", "note" to "my hunter2-super here", "h" to "Bearer abcdefghijkl123",
                "jwt" to "eyJhbGciOi.eyJzdWIiOiIx.sig_nature", "u" to "https://me:pw123@x.io/a?token=zzz&x=1",
                "nested" to listOf(mapOf("apiKey" to "k")), "plain" to "hello",
            ),
            error = ItemError("failed with hunter2-super"),
        )))
        val j = c.items[0].json
        val text = j.toString() + c.items[0].error!!.message
        for (leak in listOf("hunter2", "1234", "abcdefghijkl123", "pw123", "zzz", "sig_nature")) assertFalse(leak, text.contains(leak))
        assertEquals("hello", j["plain"])
        assertTrue((j["u"] as String).contains("x=1"))
    }

    @Test fun budgetEvictsOldestFirstAndSkipsProtected() {
        fun e(run: String, at: Long, bytes: Long, pinned: Boolean = false) = StoredExecutionInfo(run, "s", "f", at, bytes, pinned)
        val all = listOf(e("r1", 1, 40, pinned = true), e("r2", 2, 40), e("r3", 3, 40), e("r4", 4, 40), e("r5", 5, 40))
        val plan = StepDataBudget.plan(all, budgetBytes = 70, openRunIds = setOf("r3"))
        assertEquals(listOf("r2"), plan.evictRunIds.take(1)); assertEquals(listOf("r2", "r4", "r5"), plan.evictRunIds)
        assertEquals(80, plan.remainingBytes); assertTrue(plan.stillOverBudget)
        assertTrue("r1" !in plan.evictRunIds && "r3" !in plan.evictRunIds)
        assertTrue(StepDataBudget.plan(all, budgetBytes = 1000).evictRunIds.isEmpty())
    }

    @Test fun retention() {
        fun m(id: String, at: Long, manual: Boolean, failed: Boolean = false) = RunDataMeta(id, "f", at, manual, failed)
        val runs = (1..25).map { m("m$it", it.toLong(), true) } +
            (1..8).map { m("p$it", 100L + it, false) } + listOf(m("pf", 50, false, failed = true))
        val drop = StepDataRetention.runsToDrop(runs, SaveDataMode.Failed, protectedRunIds = setOf("m1"))
        assertEquals(listOf("m2", "m3", "m4", "m5"), drop.filter { it.startsWith("m") })
        assertEquals(setOf("p1", "p2", "p3"), drop.filter { it.startsWith("p") && it != "pf" }.toSet())
        assertFalse("pf" in drop)
        assertTrue(StepDataRetention.runsToDrop(runs, SaveDataMode.None).containsAll((1..8).map { "p$it" } + "pf"))
        assertTrue(StepDataRetention.runsToDrop(runs, SaveDataMode.All).none { it.startsWith("p") })
    }
}
