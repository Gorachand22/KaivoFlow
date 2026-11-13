package com.kaivoflow.core.engine

import com.kaivoflow.core.model.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import com.kaivoflow.core.model.StreamEdge

class FlowValidatorTest {

    private val registry = registry()
    private val validator = FlowValidator(registry)

    private fun flow(vararg steps: com.kaivoflow.core.model.StepNode, streams: List<StreamEdge> = emptyList()) =
        Flow(
            id = "f1",
            name = "Test Flow",
            steps = persistentListOf(*steps),
            streams = streams.toImmutableList(),
        )

    private fun stream(from: String, to: String, port: Int = 0) =
        StreamEdge(id = "s-$from-$to-$port", fromStepId = from, toStepId = to, fromPort = port)

    @Test
    fun `empty flow needs a spark`() {
        val report = validator.validate(Flow(id = "f", name = "n"))
        assertTrue(report.issues.any { it.code == "empty_flow" })
    }

    @Test
    fun `flow without trigger is blocked`() {
        val report = validator.validate(flow(step("a", "test.pass")))
        assertTrue(report.issues.any { it.code == "no_trigger" })
    }

    @Test
    fun `valid linear flow passes`() {
        val report = validator.validate(
            flow(step("t", "trigger.manual", y = 0f), step("a", "test.pass", y = 100f), streams = listOf(stream("t", "a")))
        )
        assertEquals(emptyList<ValidationIssue>(), report.blocking)
    }

    @Test
    fun `unconnected step is flagged`() {
        val report = validator.validate(flow(step("t", "trigger.manual"), step("a", "test.pass", y = 100f)))
        assertTrue(report.issues.any { it.code == "unconnected_step" && it.stepId == "a" })
    }

    @Test
    fun `dangling stream is flagged`() {
        val report = validator.validate(
            flow(step("t", "trigger.manual"), streams = listOf(stream("t", "ghost")))
        )
        assertTrue(report.issues.any { it.code == "dangling_stream" })
    }

    @Test
    fun `cycle is detected and reported once`() {
        val report = validator.validate(
            flow(
                step("t", "trigger.manual"),
                step("a", "test.pass", y = 100f),
                step("b", "test.pass", y = 200f),
                streams = listOf(stream("t", "a"), stream("a", "b"), stream("b", "a")),
            )
        )
        assertTrue(report.issues.any { it.code == "cycle" })
        assertTrue(validator.detectCycle(flow(step("t", "trigger.manual"), streams = emptyList())) == null)
    }

    @Test
    fun `missing required param is flagged`() {
        val report = validator.validate(
            flow(step("t", "trigger.manual"), step("s", "test.set", y = 50f), streams = listOf(stream("t", "s")))
        )
        assertTrue(report.issues.any { it.code == "missing_param" && it.stepId == "s" })
    }

    @Test
    fun `unknown step type is flagged not crashed`() {
        val report = validator.validate(
            flow(step("t", "trigger.manual"), step("x", "does.not.exist", y = 60f), streams = listOf(stream("t", "x")))
        )
        assertTrue(report.issues.any { it.code == "unknown_step_type" })
    }

    @Test
    fun `port out of range is flagged`() {
        val report = validator.validate(
            flow(
                step("t", "trigger.manual"),
                step("p", "test.pass", y = 50f),
                streams = listOf(stream("t", "p"), stream("p", "t", port = 3)),
            )
        )
        assertTrue(report.issues.any { it.code == "bad_port" })
    }

    @Test
    fun `unreachable branch is flagged`() {
        val report = validator.validate(
            flow(
                step("t", "trigger.manual"),
                step("a", "test.pass", y = 100f),
                step("orphan", "test.pass", y = 200f),
                step("orphanChild", "test.pass", y = 300f),
                streams = listOf(stream("t", "a"), stream("orphan", "orphanChild")),
            )
        )
        // "orphan" has no incoming Stream at all (unconnected); its child is unreachable.
        assertTrue(report.issues.any { it.code == "unconnected_step" && it.stepId == "orphan" })
        assertTrue(report.issues.any { it.code == "unreachable_step" && it.stepId == "orphanChild" })
    }

    @Test
    fun `compiler orders steps top to bottom`() {
        val compiler = FlowCompiler(validator)
        val compiled = compiler.compile(
            flow(
                step("t", "trigger.manual", y = 0f),
                step("c", "test.pass", y = 200f),
                step("b", "test.pass", y = 100f),
                streams = listOf(stream("t", "b"), stream("b", "c")),
            )
        )
        assertEquals(listOf("t", "b", "c"), compiled.order.map { it.id })
    }

    @Test
    fun `error step cannot be the trigger`() {
        val f = Flow(
            id = "f",
            name = "n",
            steps = persistentListOf(step("t", "trigger.manual"), step("a", "test.pass", y = 50f)),
            streams = persistentListOf(stream("t", "a")),
            settings = com.kaivoflow.core.model.FlowSettings(errorStepId = "t"),
        )
        val report = validator.validate(f)
        assertTrue(report.issues.any { it.code == "bad_error_step" })
        assertNull(validator.detectCycle(f))
        assertNotNull(report)
    }
}
