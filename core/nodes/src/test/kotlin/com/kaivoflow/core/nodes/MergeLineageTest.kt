package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.FlowRunner
import com.kaivoflow.core.model.BinaryDataRef
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.ItemRef
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.RunTrigger
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flow-level checks for the Merge and Stop Steps against the real runner and catalog. */
class MergeLineageTest {

    private fun step(id: String, type: String, params: Map<String, Any?> = emptyMap(), name: String = id) =
        StepNode(id = id, type = type, name = name, params = params.toImmutableMap())

    private fun edge(from: String, to: String, fromPort: Int = 0, toPort: Int = 0) =
        StreamEdge("$from-$fromPort-$to-$toPort", from, to, fromPort, toPort)

    private fun runner() = FlowRunner(NodeCatalog.registry(RecordingDevices()), TestEvaluator(), RecordingHttp(), TestCredentials(), { 1L })

    private fun run(steps: List<StepNode>, edges: List<StreamEdge>, items: List<FlowData>): RunRecord {
        val flow = Flow("f", "Flow", steps.toImmutableList(), edges.toImmutableList())
        return runBlocking { runner().run(flow, RunTrigger(items = items.toImmutableList())) }
    }

    private val bigIf = mapOf(
        "combinator" to "all",
        "conditions" to listOf(mapOf("left" to "{{ \$json.amount }}", "operator" to "Greater", "right" to 500)),
    )

    private fun setField(key: String, value: Any?) = mapOf("fields" to mapOf(key to value))

    @Test
    fun `re-joining both branches of an If keeps the false branch items`() {
        val record = run(
            steps = listOf(
                step("t", "spark.manual"),
                step("if", "flow.if", bigIf),
                step("a", "data.set", setField("branch", "big")),
                step("b", "data.set", setField("branch", "small")),
                step("m", "flow.merge", mapOf("mode" to "append")),
            ),
            edges = listOf(
                edge("t", "if"), edge("if", "a", fromPort = 0), edge("if", "b", fromPort = 1),
                edge("a", "m", toPort = 0), edge("b", "m", toPort = 1),
            ),
            items = listOf(item("amount" to 900), item("amount" to 100), item("amount" to 700)),
        )
        assertEquals(RunStatus.Succeeded, record.status)
        val merged = record.stepRuns.first { it.stepId == "m" }
        assertEquals(3, merged.itemsIn)
        assertEquals(setOf("big", "small"), merged.output.map { it.json["branch"] }.toSet())
        assertEquals(listOf(900, 700, 100), merged.output.map { it.json["amount"] })
    }

    @Test
    fun `If wired straight into both Merge inputs delivers true and false items`() {
        val record = run(
            steps = listOf(
                step("t", "spark.manual"), step("if", "flow.if", bigIf),
                step("m", "flow.merge", mapOf("mode" to "append")),
            ),
            edges = listOf(edge("t", "if"), edge("if", "m", fromPort = 0, toPort = 0), edge("if", "m", fromPort = 1, toPort = 1)),
            items = listOf(item("amount" to 900), item("amount" to 100)),
        )
        assertEquals(listOf(900, 100), record.stepRuns.first { it.stepId == "m" }.output.map { it.json["amount"] })
    }

    @Test
    fun `legacy Flows with both Streams on port 0 still merge by stream`() {
        val record = run(
            steps = listOf(
                step("t", "spark.manual"), step("if", "flow.if", bigIf),
                step("m", "flow.merge", mapOf("mode" to "zip")),
            ),
            edges = listOf(edge("t", "if"), edge("if", "m", fromPort = 0), edge("if", "m", fromPort = 1)),
            items = listOf(item("amount" to 900), item("amount" to 100)),
        )
        // zip pairs the true item with the false item: one item holding the later item's amount.
        val out = record.stepRuns.first { it.stepId == "m" }.output
        assertEquals(1, out.size)
        assertEquals(100, out[0].json["amount"])
    }

    @Test
    fun `Merge keeps binary attachments and records both sources`() {
        val photo = BinaryDataRef("kaivo://files/p1", "image/png", "p1.png", 10)
        val doc = BinaryDataRef("kaivo://files/d1", "application/pdf", "d1.pdf", 20)
        val record = run(
            steps = listOf(
                step("t", "spark.manual"),
                step("a", "flow.noop"),
                step("b", "data.set", setField("extra", 1)),
                step("m", "flow.merge", mapOf("mode" to "zip")),
            ),
            edges = listOf(edge("t", "a"), edge("t", "b"), edge("a", "m", toPort = 0), edge("b", "m", toPort = 1)),
            items = listOf(FlowData(mapOf("n" to 1)).withBinary("photo", photo).withBinary("doc", doc)),
        )
        val out = record.stepRuns.first { it.stepId == "m" }.output.single()
        assertEquals(setOf("photo", "doc"), out.binary.keys)
        assertEquals(photo, out.binary["photo"])
        assertEquals(1, out.json["extra"])
        assertEquals(listOf(ItemRef("a", 0, 0, 0), ItemRef("b", 0, 0, 0)), out.sources)
    }

    @Test
    fun `append Merge sources each item to its own input`() {
        val record = run(
            steps = listOf(
                step("t", "spark.manual"), step("if", "flow.if", bigIf),
                step("m", "flow.merge", mapOf("mode" to "append")),
            ),
            edges = listOf(edge("t", "if"), edge("if", "m", fromPort = 0, toPort = 0), edge("if", "m", fromPort = 1, toPort = 1)),
            items = listOf(item("amount" to 100), item("amount" to 900)),
        )
        val out = record.stepRuns.first { it.stepId == "m" }.output
        assertEquals(listOf(ItemRef("if", 0, 0, 0)), out[0].sources)
        assertEquals(listOf(ItemRef("if", 0, 0, 1)), out[1].sources)
    }

    @Test
    fun `Merge waits for the slow branch`() {
        val record = run(
            steps = listOf(
                step("t", "spark.manual"),
                step("fast", "data.set", setField("who", "fast")),
                step("slow", "flow.wait", mapOf("seconds" to 0.15)),
                step("m", "flow.merge", mapOf("mode" to "append")),
            ),
            edges = listOf(edge("t", "fast"), edge("t", "slow"), edge("fast", "m", toPort = 0), edge("slow", "m", toPort = 1)),
            items = listOf(item("n" to 1)),
        )
        assertEquals(2, record.stepRuns.first { it.stepId == "m" }.itemsIn)
    }

    @Test
    fun `Stop ends the Run and skips the rest`() {
        val record = run(
            steps = listOf(
                step("t", "spark.manual"),
                step("stop", "flow.stop", mapOf("status" to "success", "message" to "nothing to do")),
                step("after", "data.set", setField("x", 1)),
            ),
            edges = listOf(edge("t", "stop"), edge("stop", "after")),
            items = listOf(item("n" to 1)),
        )
        assertEquals(RunStatus.Canceled, record.status)
        assertEquals("nothing to do", record.error)
        assertEquals(StepRunStatus.Succeeded, record.stepRuns.first { it.stepId == "stop" }.status)
        assertEquals(StepRunStatus.Skipped, record.stepRuns.first { it.stepId == "after" }.status)
    }

    @Test
    fun `Stop in error mode still fails the Run`() {
        val record = run(
            steps = listOf(
                step("t", "spark.manual"),
                step("stop", "flow.stop", mapOf("status" to "error", "message" to "bad input")),
            ),
            edges = listOf(edge("t", "stop")),
            items = listOf(item("n" to 1)),
        )
        assertEquals(RunStatus.Failed, record.status)
        assertTrue(record.error!!.contains("bad input"))
    }
}
