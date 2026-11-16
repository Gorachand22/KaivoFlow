package com.kaivoflow.core.engine

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.FlowSettings
import com.kaivoflow.core.model.ItemRef
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.OnErrorPolicy
import com.kaivoflow.core.model.RunEvent
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.RunTrigger
import com.kaivoflow.core.model.StepCategory
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

/** Executors that exist only to observe or exercise the runner. */
private fun probeSpec(id: String, outputs: Int = 1) = NodeSpec(id, id, StepCategory.Transform, id, "arrow", outputs = outputs)

/** Every input item becomes three output items. */
private class FanOutExecutor : StepExecutor {
    override val spec = probeSpec("test.fan")
    override suspend fun execute(ctx: StepContext) = ctx.output(
        ctx.input.flatMap { item -> (0 until 3).map { k -> item.copy(json = item.json + ("k" to k), sources = emptyList()) } }
    )
}

/** All input items collapse into one. */
private class CollapseExecutor : StepExecutor {
    override val spec = probeSpec("test.collapse")
    override suspend fun execute(ctx: StepContext) = ctx.outputOf("count" to ctx.input.size)
}

/** Records what the runner handed it and passes the items on. */
private class CaptureExecutor : StepExecutor {
    override val spec = probeSpec("test.capture")
    var inputs: List<List<FlowData>> = emptyList()
    var flat: List<FlowData> = emptyList()
    var refs: List<ItemRef> = emptyList()
    var scopes: List<Map<String, Any?>> = emptyList()
    var stepOutputKeys: Set<String> = emptySet()
    override suspend fun execute(ctx: StepContext): StepResult {
        inputs = ctx.inputs
        flat = ctx.input
        refs = ctx.inputRefs
        scopes = ctx.input.indices.map { ctx.scope(it) }
        stepOutputKeys = ctx.stepOutputs.keys
        return ctx.output(ctx.input)
    }
}

/** Fails for the item whose `n` equals [failOn]; everything else is processed. */
private class PerItemExecutor(private val failOn: Set<Int>) : StepExecutor {
    override val spec = probeSpec("test.peritem")
    override suspend fun execute(ctx: StepContext) = ctx.perItem { _, item ->
        val n = (item.json["n"] as Number).toInt()
        if (n in failOn) throw KaivoException("cannot handle $n")
        listOf(item.copy(json = item.json + ("done" to true)))
    }
}

private class StopExecutor : StepExecutor {
    override val spec = probeSpec("test.stop")
    override suspend fun execute(ctx: StepContext) =
        StepResult(mapOf(0 to ctx.input), stop = StopSignal("enough for today"))
}

class ItemLineageTest {
    private val capture = CaptureExecutor()

    private fun runner(vararg extra: StepExecutor, capture: StepExecutor = this.capture) = FlowRunner(
        registry(FanOutExecutor(), CollapseExecutor(), capture, *extra),
        StubEvaluator(), NullHttpGateway(), InMemoryCredentials(),
    )

    private fun flowOf(
        vararg steps: StepNode,
        streams: List<StreamEdge>,
        settings: FlowSettings = FlowSettings(),
    ) = Flow("f", "Flow", persistentListOf(*steps), streams.toImmutableList(), settings = settings)

    private fun e(from: String, to: String, fromPort: Int = 0, toPort: Int = 0) =
        StreamEdge("e-$from-$to-$fromPort-$toPort", from, to, fromPort = fromPort, toPort = toPort)

    private fun items(vararg n: Int) = n.map { item("n" to it) }

    private fun RunRecord.outputOf(id: String): List<FlowData> = stepRuns.first { it.stepId == id }.output

    private fun run(flow: Flow, trigger: List<FlowData>, r: FlowRunner = runner(), onEvent: (RunEvent) -> Unit = {}) =
        runBlocking { r.run(flow, RunTrigger(items = trigger.toImmutableList()), onEvent = onEvent) }

    // ------------------------------------------------------------------ lineage cases

    @Test
    fun `one to one Step links each output to the input at the same position`() {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("set", "test.set", params = mapOf("field" to "x", "value" to 1)),
            streams = listOf(e("t", "set")),
        )
        val record = run(flow, items(10, 20, 30))
        val out = record.outputOf("set")
        assertEquals(3, out.size)
        out.forEachIndexed { i, item -> assertEquals(listOf(ItemRef("t", 0, i, 0)), item.sources) }
    }

    @Test
    fun `one to many Step links every output to the single input`() {
        val flow = flowOf(
            step("t", "spark.manual"), step("fan", "test.fan"),
            streams = listOf(e("t", "fan")),
        )
        val out = run(flow, items(7)).outputOf("fan")
        assertEquals(3, out.size)
        out.forEach { assertEquals(listOf(ItemRef("t", 0, 0, 0)), it.sources) }
    }

    @Test
    fun `one to many with several inputs only claims what it can prove`() {
        // 2 inputs, 6 outputs, none of them an input: the runner does not guess.
        val flow = flowOf(step("t", "spark.manual"), step("fan", "test.fan"), streams = listOf(e("t", "fan")))
        val out = run(flow, items(1, 2)).outputOf("fan")
        assertEquals(6, out.size)
        assertTrue(out.all { it.sources.isEmpty() })
    }

    @Test
    fun `many to one Step links the output to every input`() {
        val flow = flowOf(step("t", "spark.manual"), step("sum", "test.collapse"), streams = listOf(e("t", "sum")))
        val out = run(flow, items(1, 2, 3)).outputOf("sum")
        assertEquals(1, out.size)
        assertEquals((0..2).map { ItemRef("t", 0, it, 0) }, out[0].sources)
    }

    @Test
    fun `sourceless Spark produces items without sources even if the trigger items carried some`() {
        val stale = item("n" to 1).copy(sources = listOf(ItemRef("elsewhere", 0, 4)))
        val flow = flowOf(step("t", "spark.manual"), step("p", "test.pass"), streams = listOf(e("t", "p")))
        val record = run(flow, listOf(stale))
        assertTrue(record.outputOf("t").all { it.sources.isEmpty() })
        assertEquals(listOf(ItemRef("t", 0, 0, 0)), record.outputOf("p")[0].sources)
    }

    @Test
    fun `pass-through branching keeps the original input index on both ports`() {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("if", "test.if", params = mapOf("field" to "ok")),
            step("yes", "test.pass"), step("no", "test.pass"),
            streams = listOf(e("t", "if"), e("if", "yes", 0), e("if", "no", 1)),
        )
        val trigger = listOf(item("ok" to true), item("ok" to false), item("ok" to true), item("ok" to false))
        val record = run(flow, trigger)
        // Branch outputs are sourced to the If's input; the pass Steps then source to the If's ports.
        assertEquals(listOf(ItemRef("if", 0, 0, 0), ItemRef("if", 0, 1, 0)), record.outputOf("yes").map { it.sources.single() })
        assertEquals(listOf(ItemRef("if", 0, 0, 1), ItemRef("if", 0, 1, 1)), record.outputOf("no").map { it.sources.single() })
        val ifPorts = record.stepRuns.first { it.stepId == "if" }
        assertEquals(mapOf(0 to 2, 1 to 2), ifPorts.portCounts)
    }

    @Test
    fun `stale sources copied from the input are replaced`() {
        // test.set uses item.copy(), which would drag the input's own sources along.
        val flow = flowOf(
            step("t", "spark.manual"),
            step("a", "test.set", params = mapOf("field" to "a", "value" to 1)),
            step("b", "test.set", params = mapOf("field" to "b", "value" to 2)),
            streams = listOf(e("t", "a"), e("a", "b")),
        )
        val b = run(flow, items(1, 2)).outputOf("b")
        assertEquals(listOf(ItemRef("a", 0, 0, 0)), b[0].sources)
        assertEquals(listOf(ItemRef("a", 0, 1, 0)), b[1].sources)
    }

    // ------------------------------------------------------------------ ports

    @Test
    fun `multi-input Step receives separate lists per toPort and a flat input`() {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("a", "test.set", params = mapOf("field" to "from", "value" to "a")),
            step("b", "test.set", params = mapOf("field" to "from", "value" to "b")),
            step("join", "test.capture"),
            streams = listOf(e("t", "a"), e("t", "b"), e("a", "join", toPort = 0), e("b", "join", toPort = 1)),
        )
        run(flow, items(1, 2))
        assertEquals(2, capture.inputs.size)
        assertEquals(listOf("a", "a"), capture.inputs[0].map { it.json["from"] })
        assertEquals(listOf("b", "b"), capture.inputs[1].map { it.json["from"] })
        assertEquals(listOf("a", "a", "b", "b"), capture.flat.map { it.json["from"] })
        assertEquals(
            listOf(ItemRef("a", 0, 0), ItemRef("a", 0, 1), ItemRef("b", 0, 0), ItemRef("b", 0, 1)),
            capture.refs,
        )
    }

    @Test
    fun `a connected port with no items is an empty list, not missing`() {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("if", "test.if", params = mapOf("field" to "ok")),
            step("join", "test.capture"),
            streams = listOf(e("t", "if"), e("if", "join", fromPort = 0, toPort = 0), e("if", "join", fromPort = 1, toPort = 1)),
        )
        run(flow, listOf(item("ok" to true)))
        assertEquals(2, capture.inputs.size)
        assertEquals(1, capture.inputs[0].size)
        assertTrue(capture.inputs[1].isEmpty())
    }

    // ------------------------------------------------------------------ expressions: ids, names, pairing

    @Test
    fun `outputs are keyed by Step id and expressions see the current name`() {
        fun flowNamed(name: String) = flowOf(
            step("t", "spark.manual", name = "Start"),
            step("fetch", "test.set", name = name, params = mapOf("field" to "v", "value" to 5)),
            step("probe", "test.capture"),
            streams = listOf(e("t", "fetch"), e("fetch", "probe")),
        )
        run(flowNamed("Alpha"), items(1))
        assertTrue(capture.stepOutputKeys.contains("fetch"))
        val stepScope = capture.scopes[0]["step"] as Map<*, *>
        assertTrue(stepScope.containsKey("Alpha"))

        // Renaming the Step changes the name expressions use; the data is still found by id.
        run(flowNamed("Beta"), items(1))
        val renamed = capture.scopes[0]["step"] as Map<*, *>
        assertTrue(renamed.containsKey("Beta"))
        assertTrue(!renamed.containsKey("Alpha"))
        assertTrue(capture.stepOutputKeys.contains("fetch"))
    }

    @Test
    fun `step lookup returns the item paired through lineage`() {
        val flow = flowOf(
            step("t", "spark.manual", name = "Start"),
            step("a", "test.set", name = "Enrich", params = mapOf("field" to "e", "value" to 1)),
            step("probe", "test.capture"),
            streams = listOf(e("t", "a"), e("a", "probe")),
        )
        run(flow, items(10, 20, 30))
        capture.scopes.forEachIndexed { i, scope ->
            val step = scope["step"] as Map<*, *>
            val start = step["Start"] as List<*>
            assertEquals("input $i should pair with trigger item $i", 1, start.size)
            assertEquals((i + 1) * 10, (start[0] as Map<*, *>)["n"])
            // The full output stays reachable.
            assertEquals(3, ((scope["stepAll"] as Map<*, *>)["Start"] as List<*>).size)
        }
    }

    @Test
    fun `pairing survives a filtering branch`() {
        val flow = flowOf(
            step("t", "spark.manual", name = "Start"),
            step("if", "test.if", params = mapOf("field" to "ok")),
            step("probe", "test.capture"),
            streams = listOf(e("t", "if"), e("if", "probe", fromPort = 0)),
        )
        val trigger = listOf(item("n" to 1, "ok" to false), item("n" to 2, "ok" to true), item("n" to 3, "ok" to false), item("n" to 4, "ok" to true))
        run(flow, trigger)
        assertEquals(2, capture.scopes.size)
        val paired = capture.scopes.map { ((it["step"] as Map<*, *>)["Start"] as List<*>).single() as Map<*, *> }
        assertEquals(listOf(2, 4), paired.map { it["n"] })
        assertEquals("index is the position in the paired Step's output", listOf(1, 3), paired.map { it["index"] })
    }

    @Test
    fun `after a many to one Step the step lookup lists every source item`() {
        val flow = flowOf(
            step("t", "spark.manual", name = "Start"),
            step("sum", "test.collapse"),
            step("probe", "test.capture"),
            streams = listOf(e("t", "sum"), e("sum", "probe")),
        )
        run(flow, items(1, 2, 3))
        val start = (capture.scopes.single()["step"] as Map<*, *>)["Start"] as List<*>
        assertEquals(listOf(1, 2, 3), start.map { (it as Map<*, *>)["n"] })
        val sources = capture.scopes.single()["sources"] as List<*>
        assertTrue(sources.any { (it as Map<*, *>)["step"] == "Start" })
    }

    @Test
    fun `a step with no lineage link keeps its full output in the lookup`() {
        val flow = flowOf(
            step("t", "spark.manual", name = "Start"),
            step("side", "test.set", name = "Side", params = mapOf("field" to "s", "value" to 1)),
            step("probe", "test.capture"),
            streams = listOf(e("t", "side"), e("t", "probe")),
        )
        run(flow, items(1, 2))
        val side = (capture.scopes[0]["step"] as Map<*, *>)["Side"]
        // Whether Side already finished is a race between sibling branches; if it did, it is whole.
        if (side != null) assertEquals(2, (side as List<*>).size)
    }

    // ------------------------------------------------------------------ per-item errors

    private fun partialFlow(policy: OnErrorPolicy, withHandler: Boolean = false): Flow {
        val steps = mutableListOf(
            step("t", "spark.manual"),
            step("work", "test.peritem").copy(onError = policy),
            step("after", "test.capture"),
        )
        val streams = mutableListOf(e("t", "work"), e("work", "after"))
        if (withHandler) steps += step("handler", "test.capture2")
        return flowOf(*steps.toTypedArray(), streams = streams, settings = FlowSettings(errorStepId = if (withHandler) "handler" else null))
    }

    @Test
    fun `continue keeps good items and attaches the failed one as an item error`() {
        val events = mutableListOf<RunEvent>()
        val record = run(partialFlow(OnErrorPolicy.Continue), items(1, 2, 3), runner(PerItemExecutor(setOf(2))), { events += it })
        val work = record.stepRuns.first { it.stepId == "work" }
        assertEquals(StepRunStatus.Succeeded, work.status)
        assertEquals(1, work.itemsFailed)
        assertTrue(work.isPartial)
        assertEquals(3, work.itemsOut)
        assertEquals(RunStatus.PartialSuccess, record.status)
        // Downstream receives both good items and the error item.
        assertEquals(3, capture.flat.size)
        assertEquals(listOf(true, true), capture.flat.filter { !it.isError }.map { it.json["done"] })
        val bad = capture.flat.single { it.isError }
        assertEquals("cannot handle 2", bad.error!!.message)
        assertEquals("work", bad.error!!.stepId)
        assertEquals("item", bad.error!!.kind)
        assertEquals(listOf(ItemRef("t", 0, 1, 0)), bad.sources)
        assertEquals(1, bad.json["itemIndex"])
        val finished = events.filterIsInstance<RunEvent.StepFinished>().first { it.stepId == "work" }
        assertEquals(1, finished.itemsFailed)
        assertEquals(mapOf(0 to 3), finished.portCounts)
    }

    @Test
    fun `continue with every item failing marks the Step failed but keeps the Run going`() {
        val record = run(partialFlow(OnErrorPolicy.Continue), items(2, 2), runner(PerItemExecutor(setOf(2))))
        val work = record.stepRuns.first { it.stepId == "work" }
        assertEquals(StepRunStatus.Failed, work.status)
        assertEquals(2, work.itemsFailed)
        assertEquals(RunStatus.PartialSuccess, record.status)
        assertEquals(2, capture.flat.size)
        assertTrue(capture.flat.all { it.isError })
    }

    @Test
    fun `stop policy fails the Step when any item fails`() {
        val record = run(partialFlow(OnErrorPolicy.Stop), items(1, 2, 3), runner(PerItemExecutor(setOf(2))))
        assertEquals(RunStatus.Failed, record.status)
        assertEquals(StepRunStatus.Failed, record.stepRuns.first { it.stepId == "work" }.status)
        assertEquals(StepRunStatus.Skipped, record.stepRuns.first { it.stepId == "after" }.status)
        assertTrue(record.error!!.contains("item 2 of 3 failed"))
    }

    @Test
    fun `error flow policy sends failed items to the error Step and good ones downstream`() {
        val handler = CaptureExecutor2()
        val record = run(
            partialFlow(OnErrorPolicy.ErrorFlow, withHandler = true), items(1, 2, 3),
            runner(PerItemExecutor(setOf(1, 3)), handler),
        )
        assertEquals(1, capture.flat.size)
        assertEquals(2, capture.flat.single().json["n"])
        assertEquals(2, handler.received.size)
        assertTrue(handler.received.all { it.isError })
        assertEquals(RunStatus.PartialSuccess, record.status)
    }

    // ------------------------------------------------------------------ Stop

    @Test
    fun `Stop ends the Run, skips what never ran and reports the message`() {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("stop", "test.stop"),
            step("after", "test.pass"),
            step("slow", "test.slow", params = mapOf("ms" to 20_000)),
            streams = listOf(e("t", "stop"), e("stop", "after"), e("t", "slow")),
        )
        val started = System.currentTimeMillis()
        val record = runBlocking {
            runner(StopExecutor()).run(flow, RunTrigger(items = items(1).toImmutableList()), RunOptions(maxConcurrency = 5))
        }
        assertTrue("Run must not wait for the slow Step", System.currentTimeMillis() - started < 5_000)
        assertEquals(RunStatus.Canceled, record.status)
        assertEquals("enough for today", record.error)
        assertEquals(StepRunStatus.Succeeded, record.stepRuns.first { it.stepId == "stop" }.status)
        assertEquals(StepRunStatus.Skipped, record.stepRuns.first { it.stepId == "after" }.status)
        assertEquals(StepRunStatus.Skipped, record.stepRuns.first { it.stepId == "slow" }.status)
    }
}

private class CaptureExecutor2 : StepExecutor {
    override val spec = NodeSpec("test.capture2", "capture2", StepCategory.Transform, "", "arrow")
    var received: List<FlowData> = emptyList()
    override suspend fun execute(ctx: StepContext): StepResult {
        received = ctx.input
        return ctx.output(ctx.input)
    }
}
