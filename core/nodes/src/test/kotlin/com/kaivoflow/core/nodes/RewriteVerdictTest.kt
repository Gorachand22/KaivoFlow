package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StreamInput
import com.kaivoflow.core.model.FlowData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rewritten Steps (taxonomy spec section 3): Merge modes, Loop over items. */
class RewriteVerdictTest {

    private fun merge(params: Map<String, Any?>, a: List<FlowData>, b: List<FlowData>): List<Map<String, Any?>> = runBlocking {
        val exec = NodeCatalog.registry().executor("flow.merge")!!
        val streams = listOf(
            StreamInput("s1", "a", 0, 0, a, emptyList()),
            StreamInput("s2", "b", 0, 1, b, emptyList()),
        )
        exec.execute(ctx(stepOf("flow.merge", params = params), a + b).copy(streamInputs = streams)).outputs.getValue(0).map { it.json }
    }

    private val users = listOf(item("id" to 1, "name" to "Asha"), item("id" to 2, "name" to "Ravi"), item("id" to 3, "name" to "Mina"))
    private val orders = listOf(item("uid" to 2, "total" to 20), item("uid" to 3, "total" to 30), item("uid" to 9, "total" to 90))

    @Test fun `old merge modes still behave the same`() {
        assertEquals(6, merge(mapOf("mode" to "append"), users, orders).size)
        assertEquals(3, merge(mapOf("mode" to "zip"), users, orders).size)
        assertEquals(9, merge(mapOf("mode" to "multiply"), users, orders).size)
        val combined = merge(mapOf("mode" to "combine", "matchField" to "id"), users, listOf(item("id" to 2, "extra" to "x")))
        assertEquals(listOf("Asha", "Ravi", "Mina"), combined.map { it["name"] })
        assertEquals("x", combined[1]["extra"])
    }

    @Test fun `match on a field supports inner left outer and unmatched joins with different field names`() {
        val base = mapOf("mode" to "combine", "matchField" to "id", "matchField2" to "uid")
        assertEquals(listOf("Ravi", "Mina"), merge(base + ("joinKind" to "inner"), users, orders).map { it["name"] })
        assertEquals(listOf("Asha", "Ravi", "Mina"), merge(base + ("joinKind" to "left"), users, orders).map { it["name"] })
        assertEquals(listOf("Asha"), merge(base + ("joinKind" to "unmatched"), users, orders).map { it["name"] })
        val outer = merge(base + ("joinKind" to "outer"), users, orders)
        assertEquals(4, outer.size)
        assertEquals(90, outer.last()["total"])
        assertEquals(20, merge(base + ("joinKind" to "inner"), users, orders)[0]["total"])
    }

    @Test fun `several match fields must all match`() {
        val a = listOf(item("k1" to 1, "k2" to "x", "v" to "a1"), item("k1" to 1, "k2" to "y", "v" to "a2"))
        val b = listOf(item("k1" to 1, "k2" to "y", "w" to "b"))
        val out = merge(mapOf("mode" to "combine", "matchField" to "k1, k2", "joinKind" to "inner"), a, b)
        assertEquals(listOf(mapOf("k1" to 1, "k2" to "y", "v" to "a2", "w" to "b")), out)
    }

    @Test fun `position pairing can keep the unpaired items and choose passes one input`() {
        assertEquals(3, merge(mapOf("mode" to "zip", "unequalLength" to "longest"), users, orders.take(1)).size)
        assertEquals(listOf("Asha", "Ravi", "Mina"), merge(mapOf("mode" to "choose", "inputNumber" to 1), users, orders).map { it["name"] })
        assertEquals(listOf(2, 3, 9), merge(mapOf("mode" to "choose", "inputNumber" to 2), users, orders).map { it["uid"] })
    }

    @Test fun `clash handling picks which input wins`() {
        val a = listOf(item("id" to 1, "v" to "one"))
        val b = listOf(item("id" to 1, "v" to "two"))
        val base = mapOf("mode" to "combine", "matchField" to "id")
        assertEquals("two", merge(base, a, b)[0]["v"])
        assertEquals("one", merge(base + ("clash" to "input1"), a, b)[0]["v"])
        assertEquals("two", merge(base + ("clash" to "input2"), a, b)[0]["v"])
    }

    private fun loop(params: Map<String, Any?>, n: Int) = runBlocking {
        NodeCatalog.registry().executor("flow.loop")!!
            .execute(ctx(stepOf("flow.loop", params = params), (1..n).map { item("n" to it) })).outputs.getValue(0)
    }

    @Test fun `loop batches items into real groups and tags them`() {
        val tagged = loop(mapOf("batchSize" to 2), 5)
        assertEquals(5, tagged.size)
        val tags = tagged.map { it.json["_loop"] as Map<*, *> }
        assertEquals(listOf(0, 0, 1, 1, 2), tags.map { it["batch"] })
        assertEquals(listOf(true, true, true, true, true).size, tags.count { it["batches"] == 3 })
        assertEquals(listOf(false, false, false, false, true), tags.map { it["last"] })
        assertEquals(listOf(0, 1, 0, 1, 0), tags.map { it["index"] })
        assertEquals(1, tags.last()["size"])
        assertEquals(5, tags.first()["total"])
    }

    @Test fun `loop can emit one item per batch holding its items`() {
        val out = loop(mapOf("batchSize" to 2, "emit" to "batches"), 5)
        assertEquals(3, out.size)
        assertEquals(listOf(listOf(mapOf("n" to 1), mapOf("n" to 2)), listOf(mapOf("n" to 3), mapOf("n" to 4)), listOf(mapOf("n" to 5))), out.map { it.json["items"] })
        assertEquals(true, out.last().json["last"])
        assertEquals(3, out[0].json["batches"])
    }

    @Test fun `loop safety limit counts batches not items`() {
        assertEquals(10, loop(mapOf("batchSize" to 2, "maxIterations" to 10), 20).size / 2)
        val failure = runCatching { loop(mapOf("batchSize" to 1, "maxIterations" to 5), 20) }.exceptionOrNull()
        assertTrue(failure is StepExecutionException && failure.message!!.contains("safety limit"))
    }
}
