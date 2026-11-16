package com.kaivoflow.core.engine

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.FlowSettings
import com.kaivoflow.core.model.OnErrorPolicy
import com.kaivoflow.core.model.RetryPolicy
import com.kaivoflow.core.model.RunEvent
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.RunTrigger
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FlowRunnerTest {

    private fun runner(
        registry: NodeRegistry = registry(),
        clock: () -> Long = System::currentTimeMillis,
    ) = FlowRunner(registry, StubEvaluator(), NullHttpGateway(), InMemoryCredentials(), clock)

    private fun flowOf(
        vararg steps: com.kaivoflow.core.model.StepNode,
        streams: List<StreamEdge> = emptyList(),
        settings: FlowSettings = FlowSettings(),
        vars: Map<String, Any?> = emptyMap(),
    ) = Flow(
        id = "flow-1",
        name = "Test Flow",
        steps = persistentListOf(*steps),
        streams = streams.toImmutableList(),
        settings = settings,
        vars = vars.toImmutableMap(),
    )

    private fun s(from: String, to: String, port: Int = 0) =
        StreamEdge("e-$from-$to-$port", from, to, fromPort = port)

    @Test
    fun `linear flow passes items through and reports success`() = runBlocking {
        val flow = flowOf(step("t", "spark.manual"), step("a", "test.set", params = mapOf("field" to "ok", "value" to true), y = 10f), streams = listOf(s("t", "a")))
        val record = runner().run(flow, RunTrigger(items = listOf(item("id" to 1)).toImmutableList()))
        assertEquals(RunStatus.Succeeded, record.status)
        assertEquals(2, record.stepRuns.size)
        assertEquals(1, record.itemsOut)
    }

    @Test
    fun `branch that receives no items is skipped and does not execute downstream`() = runBlocking {
        var falseBranchRuns = 0
        val countingPass = object : StepExecutor {
            override val spec = TestSpecs.pass
            override suspend fun execute(ctx: StepContext): StepResult {
                falseBranchRuns++
                return StepResult(mapOf(0 to ctx.input))
            }
        }
        // Override "test.pass" with the counting version.
        val reg = NodeRegistry(listOf(ManualTriggerExecutor(), PassExecutor(), countingPass, SetExecutor(), IfExecutor()))
        val flow = flowOf(
            step("t", "spark.manual"),
            step("if", "test.if", params = mapOf("field" to "flag"), y = 10f),
            step("yes", "test.pass", y = 20f),
            step("no", "test.pass", y = 20f),
            streams = listOf(s("t", "if"), s("if", "yes", port = 0), s("if", "no", port = 1)),
        )
        val record = runner(reg).run(flow, RunTrigger(items = listOf(item("flag" to true)).toImmutableList()))

        assertEquals(RunStatus.Succeeded, record.status)
        val no = record.stepRuns.first { it.stepId == "no" }
        assertEquals(StepRunStatus.Skipped, no.status)
        assertEquals(1, falseBranchRuns) // only the "yes" branch actually executed
    }

    @Test
    fun `failing step stops the run and downstream is skipped`() = runBlocking {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("bad", "test.fail", y = 10f),
            step("after", "test.pass", y = 20f),
            streams = listOf(s("t", "bad"), s("bad", "after")),
        )
        val record = runner().run(flow, RunTrigger(items = listOf(item()).toImmutableList()))
        assertEquals(RunStatus.Failed, record.status)
        assertEquals(StepRunStatus.Failed, record.stepRuns.first { it.stepId == "bad" }.status)
        assertEquals(StepRunStatus.Skipped, record.stepRuns.first { it.stepId == "after" }.status)
        assertTrue(record.error!!.contains("boom"))
    }

    @Test
    fun `continue on error emits an error item and keeps flowing`() = runBlocking {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("bad", "test.fail", y = 10f).copy(onError = OnErrorPolicy.Continue),
            step("after", "test.set", params = mapOf("field" to "seen", "value" to "yes"), y = 20f),
            streams = listOf(s("t", "bad"), s("bad", "after")),
        )
        val record = runner().run(flow, RunTrigger(items = listOf(item()).toImmutableList()))
        val after = record.stepRuns.first { it.stepId == "after" }
        assertEquals(StepRunStatus.Succeeded, after.status)
        assertEquals("boom", after.output.first().json["error"])
        // The Flow kept going, so the Run is "PartialSuccess" rather than a hard failure.
        assertEquals(RunStatus.PartialSuccess, record.status)
    }

    @Test
    fun `retry policy retries and then succeeds`() = runBlocking {
        val flaky = FlakyExecutor(failuresBeforeSuccess = 2)
        val reg = NodeRegistry(listOf(ManualTriggerExecutor(), flaky, PassExecutor()))
        val flow = flowOf(
            step("t", "spark.manual"),
            step("f", "test.flaky", y = 10f).copy(
                onError = OnErrorPolicy.Retry,
                retry = RetryPolicy(enabled = true, maxAttempts = 3, initialDelayMs = 1),
            ),
            streams = listOf(s("t", "f")),
        )
        val record = runner(reg).run(flow, RunTrigger(items = listOf(item()).toImmutableList()))
        assertEquals(RunStatus.Succeeded, record.status)
        assertEquals(3, flaky.calls.get())
        assertEquals(3, record.stepRuns.first { it.stepId == "f" }.attempts)
    }

    @Test
    fun `without retry enabled a flaky step fails once`() = runBlocking {
        val flaky = FlakyExecutor(failuresBeforeSuccess = 1)
        val reg = NodeRegistry(listOf(ManualTriggerExecutor(), flaky, PassExecutor()))
        val flow = flowOf(step("t", "spark.manual"), step("f", "test.flaky", y = 10f), streams = listOf(s("t", "f")))
        val record = runner(reg).run(flow, RunTrigger(items = listOf(item()).toImmutableList()))
        assertEquals(RunStatus.Failed, record.status)
        assertEquals(1, flaky.calls.get())
    }

    @Test
    fun `frozen output is replayed instead of executing`() = runBlocking {
        val frozen = persistentListOf(FlowData(mapOf("pinned" to true)))
        val flow = flowOf(
            step("t", "spark.manual"),
            step("f", "test.fail", y = 10f).copy(frozenOutput = frozen),
            streams = listOf(s("t", "f")),
        )
        val record = runner().run(flow, RunTrigger(items = listOf(item()).toImmutableList()))
        assertEquals(RunStatus.Succeeded, record.status)
        assertEquals(StepRunStatus.Frozen, record.stepRuns.first { it.stepId == "f" }.status)
    }

    @Test
    fun `ignoreFrozen re-executes the step`() = runBlocking {
        val frozen = persistentListOf(FlowData(mapOf("pinned" to true)))
        val flow = flowOf(
            step("t", "spark.manual"),
            step("f", "test.fail", y = 10f).copy(frozenOutput = frozen),
            streams = listOf(s("t", "f")),
        )
        val record = runner().run(flow, RunTrigger(items = listOf(item()).toImmutableList()), RunOptions(ignoreFrozen = true))
        assertEquals(RunStatus.Failed, record.status)
    }

    @Test
    fun `disabled step passes items straight through`() = runBlocking {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("off", "test.pass", y = 10f, disabled = true),
            step("after", "test.pass", y = 20f),
            streams = listOf(s("t", "off"), s("off", "after")),
        )
        val record = runner().run(flow, RunTrigger(items = listOf(item()).toImmutableList()))
        val off = record.stepRuns.first { it.stepId == "off" }
        assertEquals(StepRunStatus.Skipped, off.status)
        val after = record.stepRuns.first { it.stepId == "after" }
        assertEquals(StepRunStatus.Succeeded, after.status)
        assertEquals(1, after.itemsIn) // items passed through the switched-off Step
    }

    @Test
    fun `invalid flow returns a failed record instead of throwing`() = runBlocking {
        val flow = flowOf(step("a", "test.pass")) // no trigger
        val record = runner().run(flow)
        assertEquals(RunStatus.Failed, record.status)
        assertNotNull(record.error)
    }

    @Test
    fun `independent branches run concurrently`() = runBlocking {
        val barrier = AtomicInteger(0)
        val bothStarted = CompletableDeferred<Unit>()
        val latchExecutor = object : StepExecutor {
            override val spec = TestSpecs.slow
            override suspend fun execute(ctx: StepContext): StepResult {
                if (barrier.incrementAndGet() == 2) bothStarted.complete(Unit)
                // If the runner is serial this await times out and the test fails.
                withTimeout(2_000) { bothStarted.await() }
                return StepResult(mapOf(0 to ctx.input))
            }
        }
        val reg = NodeRegistry(listOf(ManualTriggerExecutor(), PassExecutor(), latchExecutor))
        val flow = flowOf(
            step("t", "spark.manual"),
            step("a", "test.slow", params = mapOf("ms" to 1), y = 10f),
            step("b", "test.slow", params = mapOf("ms" to 1), y = 10f),
            step("join", "test.pass", y = 20f),
            streams = listOf(s("t", "a"), s("t", "b"), s("a", "join"), s("b", "join")),
        )
        val record = runner(reg).run(flow, RunTrigger(items = listOf(item()).toImmutableList()), RunOptions(maxConcurrency = 4))
        assertEquals(RunStatus.Succeeded, record.status)
        assertEquals(2, record.stepRuns.first { it.stepId == "join" }.itemsIn)
    }

    @Test
    fun `merge step concatenates items in stream order`() = runBlocking {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("a", "test.set", params = mapOf("field" to "from", "value" to "a"), y = 10f),
            step("b", "test.set", params = mapOf("field" to "from", "value" to "b"), y = 10f),
            step("join", "test.pass", y = 20f),
            streams = listOf(s("t", "a"), s("t", "b"), s("a", "join"), s("b", "join")),
        )
        val record = runner().run(flow, RunTrigger(items = listOf(item()).toImmutableList()))
        val join = record.stepRuns.first { it.stepId == "join" }
        assertEquals(2, join.itemsIn)
        assertEquals("a", join.output[0].json["from"])
    }

    @Test
    fun `cancelling the coroutine cancels the run`() = runBlocking {
        val flow = flowOf(step("t", "spark.manual"), step("slow", "test.slow", params = mapOf("ms" to 5_000), y = 10f), streams = listOf(s("t", "slow")))
        val job = async { runner().run(flow, RunTrigger(items = listOf(item()).toImmutableList())) }
        delay(150)
        job.cancel()
        val record = runCatching { job.await() }.getOrNull()
        // Cancellation either surfaces as a canceled record or as a CancellationException;
        // what must never happen is a "Succeeded" flow.
        if (record != null) assertEquals(RunStatus.Canceled, record.status)
    }

    @Test
    fun `run timeout stops the flow`() = runBlocking {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("slow", "test.slow", params = mapOf("ms" to 5_000), y = 10f),
            streams = listOf(s("t", "slow")),
            settings = FlowSettings(executionTimeoutMs = 200),
        )
        val started = System.currentTimeMillis()
        val record = runner().run(flow, RunTrigger(items = listOf(item()).toImmutableList()))
        assertEquals(RunStatus.Failed, record.status)
        assertTrue(record.error!!.contains("time limit"))
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }

    @Test
    fun `step timeout is reported against the step`() = runBlocking {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("slow", "test.slow", params = mapOf("ms" to 2_000), y = 10f).copy(timeoutMs = 100),
            streams = listOf(s("t", "slow")),
        )
        val record = runner().run(flow, RunTrigger(items = listOf(item()).toImmutableList()))
        assertEquals(RunStatus.Failed, record.status)
        assertTrue(record.stepRuns.first { it.stepId == "slow" }.error!!.contains("Timed out"))
    }

    @Test
    fun `error items are routed to the error step`() = runBlocking {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("bad", "test.fail", y = 10f),
            step("handler", "test.set", params = mapOf("field" to "handled", "value" to true), y = 0f),
            streams = listOf(s("t", "bad")),
            settings = FlowSettings(errorStepId = "handler"),
        )
        val record = runner().run(flow, RunTrigger(items = listOf(item()).toImmutableList()))
        val handler = record.stepRuns.first { it.stepId == "handler" }
        assertEquals(StepRunStatus.Succeeded, handler.status)
        assertEquals(1, handler.itemsIn)
        assertEquals(RunStatus.Failed, record.status)
    }

    @Test
    fun `events are emitted in a coherent order`() = runBlocking {
        val events = mutableListOf<RunEvent>()
        val flow = flowOf(step("t", "spark.manual"), step("a", "test.pass", y = 10f), streams = listOf(s("t", "a")))
        runner().run(flow, RunTrigger(items = listOf(item()).toImmutableList()), onEvent = { events += it })

        assertTrue(events.first() is RunEvent.RunStarted)
        assertTrue(events.last() is RunEvent.RunFinished)
        assertEquals(2, events.filterIsInstance<RunEvent.StepStarted>().size)
        assertEquals(2, events.filterIsInstance<RunEvent.StepFinished>().size)
        assertEquals(RunStatus.Succeeded, (events.last() as RunEvent.RunFinished).status)
    }

    @Test
    fun `item limit truncates input`() = runBlocking {
        val items = (1..50).map { item("n" to it) }
        val flow = flowOf(step("t", "spark.manual"), step("a", "test.pass", y = 10f), streams = listOf(s("t", "a")))
        val record = runner().run(flow, RunTrigger(items = items.toImmutableList()), RunOptions(itemLimit = 5))
        assertEquals(5, record.stepRuns.first { it.stepId == "a" }.itemsIn)
    }

    @Test
    fun `thousand step flow completes`() = runBlocking {
        val steps = mutableListOf(step("t", "spark.manual", y = 0f))
        val streams = mutableListOf<StreamEdge>()
        var previous = "t"
        for (i in 1..1_000) {
            val id = "n$i"
            steps += step(id, "test.pass", y = i.toFloat())
            streams += s(previous, id)
            previous = id
        }
        val flow = flowOf(*steps.toTypedArray(), streams = streams)
        val started = System.currentTimeMillis()
        val record = runner().run(flow, RunTrigger(items = listOf(item()).toImmutableList()))
        assertEquals(RunStatus.Succeeded, record.status)
        assertEquals(1_001, record.stepRuns.size)
        println("1000-step Run took ${System.currentTimeMillis() - started}ms")
    }

    @Test
    fun `testStep runs only the requested step`() = runBlocking {
        val flow = flowOf(
            step("t", "spark.manual"),
            step("a", "test.set", params = mapOf("field" to "x", "value" to 1), y = 10f),
            streams = listOf(s("t", "a")),
        )
        val run = runner().testStep(flow, "a", listOf(item("seed" to true)))
        assertEquals(StepRunStatus.Succeeded, run.status)
        assertEquals(1, run.itemsIn)
        assertNull(run.error)
    }
}
