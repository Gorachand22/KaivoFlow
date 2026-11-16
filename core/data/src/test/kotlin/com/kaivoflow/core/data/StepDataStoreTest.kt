package com.kaivoflow.core.data

import com.kaivoflow.core.engine.RunRetention
import com.kaivoflow.core.engine.stepdata.CapturedExecution
import com.kaivoflow.core.engine.stepdata.ChannelStepExecutionRecorder
import com.kaivoflow.core.engine.stepdata.Direction
import com.kaivoflow.core.engine.stepdata.ItemFilter
import com.kaivoflow.core.engine.stepdata.ParentRef
import com.kaivoflow.core.engine.stepdata.RawPort
import com.kaivoflow.core.engine.stepdata.StepDataLimits
import com.kaivoflow.core.engine.stepdata.StepDataPolicy
import com.kaivoflow.core.engine.stepdata.StepDataRedactor
import com.kaivoflow.core.engine.stepdata.StepErrorInfo
import com.kaivoflow.core.engine.stepdata.StepExecutionCapture
import com.kaivoflow.core.engine.stepdata.StepExecutionKey
import com.kaivoflow.core.engine.stepdata.TruncationReason
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.ItemError
import com.kaivoflow.core.model.ItemRef
import com.kaivoflow.core.model.SchemaType
import com.kaivoflow.core.model.StepRunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class StepDataStoreTest {
    private val access = InMemoryStepDataAccess()
    private val store = StepDataStoreImpl(access, Dispatchers.Unconfined)

    private fun items(n: Int) = List(n) { Item(json = mapOf("i" to it.toDouble(), "s" to "row $it"), sources = listOf(ItemRef("up", 0, it, 0))) }

    private fun capture(
        runId: String, stepId: String = "s", flowId: String = "f", n: Int = 3, at: Long = 1_000,
        status: StepRunStatus = StepRunStatus.Succeeded, manual: Boolean = true,
        policy: StepDataPolicy = StepDataPolicy(), outItems: List<Item> = items(n),
    ): CapturedExecution {
        val cap = policy.capture(outItems)
        val raw = StepExecutionCapture(
            key = StepExecutionKey(runId, stepId, 0), flowId = flowId, status = status, startedAt = at, finishedAt = at + 5,
            inputs = listOf(RawPort(0, items = items(2), parents = listOf(ParentRef("up", 1)))),
            outputs = listOf(RawPort(0, "main", outItems)), error = null, manual = manual,
        )
        return runBlocking {
            val got = ArrayList<CapturedExecution>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val rec = ChannelStepExecutionRecorder({ got += it }, scope, policy)
            rec.record(raw); rec.flush(); rec.close(); scope.cancel()
            assertEquals(cap.items.size, got.single().outputs[0].items.size)
            got.single()
        }
    }

    // ---- pure encoder, offset index, pager ----

    @Test fun varintsRoundTrip() {
        val values = intArrayOf(0, 1, 127, 128, 300, 16_383, 16_384, 70_000, Int.MAX_VALUE)
        assertArrayEquals(values, StepDataCodec.readVarints(StepDataCodec.writeVarints(values)))
        assertEquals(0, StepDataCodec.readVarints(ByteArray(0)).size)
    }

    @Test fun encodeKeepsEveryItemAndIsBlockedBy64() {
        val blob = StepDataCodec.encodeItems(items(130))
        assertEquals(130, blob.itemCount)
        assertEquals(3, blob.blockCount)
        val all = blob.range(0, 1000)
        assertEquals((0 until 130).toList(), all.map { it.index })
        assertEquals("row 129", all.last().item.json["s"])
        assertEquals(ItemRef("up", 0, 129, 0), all.last().item.sources.single())
        val empty = StepDataCodec.encodeItems(emptyList())
        assertEquals(0, empty.itemCount); assertTrue(empty.range(0, 10).isEmpty())
    }

    @Test fun pagingTwentyFiveFromAThousandReadsAtMostTwoBlocks() {
        val blob = StepDataCodec.encodeItems(items(1000))
        assertEquals(16, blob.blockCount)
        val a = blob.range(0, 25); assertEquals(1, blob.blocksRead); assertEquals(25, a.size)
        val fresh = StepDataCodec.encodeItems(items(1000))
        val b = fresh.range(500, 25) // 500..524 lies inside block 7 (448..511) and 8 (512..575)
        assertEquals(2, fresh.blocksRead)
        assertEquals((500 until 525).toList(), b.map { it.index })
        assertEquals("row 524", b.last().item.json["s"])
        val mid = StepDataCodec.encodeItems(items(1000)); mid.range(100, 25); assertEquals(1, mid.blocksRead)
        val past = StepDataCodec.encodeItems(items(10)); assertTrue(past.range(10, 5).isEmpty()); assertEquals(0, past.blocksRead)
    }

    @Test fun errorsAndBinaryRefsSurviveTheBlob() {
        val e = Item(json = mapOf("error" to "bad"), error = ItemError("bad", "s", "item", 7))
        val out = StepDataCodec.encodeItems(listOf(e), listOf(42)).range(0, 1).single()
        assertEquals(42, out.original)
        assertEquals(ItemError("bad", "s", "item", 7), out.item.error)
    }

    // ---- store ----

    @Test fun writeThenSummaryPageSchemaSearch() = runBlocking {
        store.write(capture("r1", n = 100))
        val key = StepExecutionKey("r1", "s", 0)
        val sum = store.summary(key)!!
        assertEquals(StepRunStatus.Succeeded, sum.status)
        assertEquals(100, sum.outputs.single().itemCount); assertEquals("main", sum.outputs.single().label)
        assertEquals(listOf(ParentRef("up", 1)), sum.inputs.single().parents)
        assertEquals(2, sum.inputs.single().storedCount)
        assertNotNull(sum.outputs.single().schema)

        val p = store.page(key, Direction.Out, 0, 10, 25)
        assertEquals(25, p.items.size); assertEquals(100, p.totalStored); assertEquals(100, p.totalTrue); assertNull(p.truncation)
        assertEquals("row 10", p.items.first().json["s"])
        assertEquals(2, store.page(key, Direction.In, 0, 0, 25).items.size)
        assertTrue(store.page(key, Direction.Out, 7, 0, 25).items.isEmpty())

        val schema = store.schema(key, Direction.Out, 0)
        assertEquals(SchemaType.Number, schema.fields.getValue("i").type)
        assertEquals(SchemaType.String, schema.fields.getValue("s").type)

        val hit = store.search(key, Direction.Out, 0, "ROW 7", 5)
        assertEquals(11, hit.totalMatches) // row 7, row 70..79
        assertEquals(listOf(7, 70, 71, 72, 73), hit.matchingIndices.toList())
        assertEquals(0, store.search(key, Direction.Out, 0, "nothing", 5).totalMatches)
        val filtered = store.page(key, Direction.Out, 0, 0, 3, ItemFilter(query = "row 9"))
        assertEquals(11, filtered.totalMatching); assertEquals(3, filtered.items.size); assertEquals(100, filtered.totalStored)
    }

    @Test fun latestKeyAndObserveAreOrderedNewestFirst() = runBlocking {
        store.write(capture("r1", at = 10)); store.write(capture("r2", at = 30)); store.write(capture("r3", at = 20, flowId = "other"))
        assertEquals("r2", store.latestKey("f", "s")!!.runId)
        assertEquals(listOf("r2", "r1"), store.observeExecutions("f", "s", 20).first().map { it.key.runId })
        assertEquals(1, store.observeExecutions("f", "s", 1).first().size)
        assertNull(store.latestKey("f", "nope"))
        assertNull(store.summary(StepExecutionKey("zzz", "s", 0)))
    }

    @Test fun truncationAndErrorItemPriorityAreStoredWithOriginalPositions() = runBlocking {
        val policy = StepDataPolicy(StepDataLimits(maxItemsPerPort = 5))
        val all = items(50).mapIndexed { i, it -> if (i == 30) it.copy(error = ItemError("boom", "s", "item", 1)) else it }
        store.write(capture("r1", policy = policy, outItems = all))
        val key = StepExecutionKey("r1", "s", 0)
        val snap = store.summary(key)!!.outputs.single()
        assertEquals(50, snap.itemCount); assertEquals(6, snap.storedCount)
        assertEquals(TruncationReason.ItemCap, snap.truncation!!.reason)
        val page = store.page(key, Direction.Out, 0, 0, 25)
        assertEquals(listOf(0, 1, 2, 3, 4, 30), page.originalIndices)
        assertEquals(50, page.totalTrue); assertEquals(6, page.totalStored)
        assertEquals(1, store.page(key, Direction.Out, 0, 0, 25, ItemFilter(onlyErrors = true)).items.size)
    }

    @Test fun rewritingAnExecutionReplacesItsPorts() = runBlocking {
        store.write(capture("r1", n = 10)); store.write(capture("r1", n = 2))
        assertEquals(1, access.executionCount); assertEquals(2, access.portRowCount)
        assertEquals(2, store.page(StepExecutionKey("r1", "s", 0), Direction.Out, 0, 0, 25).items.size)
    }

    @Test fun deletingARunCascadesToAllItsExecutionsAndPorts() = runBlocking {
        store.write(capture("r1", stepId = "a")); store.write(capture("r1", stepId = "b")); store.write(capture("r2", stepId = "a"))
        assertEquals(6, access.portRowCount)
        store.deleteRun("r1")
        assertEquals(1, access.executionCount); assertEquals(2, access.portRowCount)
        assertNull(store.summary(StepExecutionKey("r1", "a", 0)))
        assertTrue(store.page(StepExecutionKey("r1", "a", 0), Direction.Out, 0, 0, 5).items.isEmpty())
        assertNotNull(store.summary(StepExecutionKey("r2", "a", 0)))
    }

    @Test fun errorInfoAndFlagsRoundTrip() = runBlocking {
        val base = capture("r1", status = StepRunStatus.Failed)
        val err = StepErrorInfo("HTTP 500", itemIndex = 2, kind = "http", details = "d", httpStatus = 500, httpBody = "oops")
        store.write(base.copy(record = base.record.copy(error = err, pinned = true, dataDropped = true, attempts = 3)))
        val s = store.summary(StepExecutionKey("r1", "s", 0))!!
        assertEquals(err, s.error); assertTrue(s.pinned); assertTrue(s.dataDropped); assertEquals(3, s.attempts); assertEquals(StepRunStatus.Failed, s.status)
    }

    // ---- redaction before write ----

    private fun gunzipAll(blobItems: ByteArray, offsets: ByteArray): String {
        val t = StepDataCodec.readVarints(offsets)
        var at = 0
        val sb = StringBuilder()
        for (b in 1 until t.size) {
            sb.append(GZIPInputStream(ByteArrayInputStream(blobItems, at, t[b])).use { String(it.readBytes(), Charsets.UTF_8) }).append('\n')
            at += t[b]
        }
        return sb.toString()
    }

    @Test fun secretValuesNeverReachTheStoredBytes() {
        val secret = "hunter2-very-secret"
        val policy = StepDataPolicy(redactor = StepDataRedactor(secretValues = listOf(secret)))
        val out = listOf(
            Item.of("password" to "p4ssw0rd!", "note" to "token is $secret", "auth" to "Bearer abcdefghijklmnop12345", "ok" to "visible"),
            Item.of("url" to "https://x.test/a?api_key=SECRETKEY123&q=1"),
        )
        runBlocking { store.write(capture("r1", policy = policy, outItems = out)) }
        val row = runBlocking { access.port("r1", "s", 0, "out", 0) }!!
        val text = gunzipAll(row.itemsBlob, row.offsetsBlob)
        for (leak in listOf(secret, "p4ssw0rd!", "abcdefghijklmnop12345", "SECRETKEY123")) assertFalse("leaked $leak", text.contains(leak))
        assertTrue(text.contains("visible"))
        assertFalse(String(access.allBlobBytes(), Charsets.ISO_8859_1).contains(secret))
        val schemaJson = row.schemaJson!!
        assertFalse(schemaJson.contains("p4ssw0rd!")); assertFalse(schemaJson.contains(secret))
    }

    // ---- retention through RunRetention ----

    @Test fun pruningRunsDeletesTheirStepData() = runBlocking {
        for (i in 1..25) store.write(capture("m$i", at = i.toLong()))             // 25 manual Runs: 20 kept
        store.write(capture("fail", at = 100, manual = false, status = StepRunStatus.Failed))
        store.write(capture("ok1", at = 101, manual = false))                       // production, successful
        val dropped = RunRetention.pruneStepData(store)
        assertEquals((1..5).map { "m$it" }.toSet(), dropped.toSet())
        assertNull(store.summary(StepExecutionKey("m1", "s", 0)))
        assertNotNull(store.summary(StepExecutionKey("m25", "s", 0)))
        assertNotNull(store.summary(StepExecutionKey("fail", "s", 0)))
        assertNotNull("one of the last 5 successful production Runs is kept", store.summary(StepExecutionKey("ok1", "s", 0)))
        // Save data: None drops every production Run's data, failed ones included.
        val none = RunRetention.pruneStepData(store, mode = com.kaivoflow.core.engine.stepdata.SaveDataMode.None)
        assertEquals(setOf("fail", "ok1"), none.toSet())
    }

    @Test fun budgetEvictionNeverTouchesPinnedOrOpenRuns() = runBlocking {
        val big = (1..200).map { Item.of("v" to "x".repeat(2000)) }
        store.write(capture("old", at = 1, outItems = big)); store.write(capture("pinnedRun", at = 2, outItems = big).let { it.copy(record = it.record.copy(pinned = true)) })
        store.write(capture("open", at = 3, outItems = big)); store.write(capture("new", at = 4, outItems = big))
        val removed = RunRetention.pruneStepData(store, budgetBytes = 1, protectedRunIds = setOf("open"))
        assertEquals(setOf("old", "new"), removed.toSet())
        assertNotNull(store.summary(StepExecutionKey("pinnedRun", "s", 0))); assertNotNull(store.summary(StepExecutionKey("open", "s", 0)))
    }
}
