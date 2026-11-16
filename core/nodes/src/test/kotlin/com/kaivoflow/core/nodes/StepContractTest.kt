package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.ParamType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Per-Step contract over the whole registry (taxonomy spec 5.5.1 and the empty-input rule of 5.5.2).
 * Every registered executor is checked, so a new Step is covered without writing a test for it.
 */
class StepContractTest {

    private val executors = NodeCatalog.executors()

    @Test
    fun `every executor has a unique id and a non blank title`() {
        val ids = executors.map { it.spec.id }
        assertEquals("duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}", ids.size, ids.distinct().size)
        executors.forEach {
            assertTrue("${it.spec.id} id has odd characters", Regex("[a-zA-Z0-9_.]+").matches(it.spec.id))
            assertTrue("${it.spec.id} needs a title", it.spec.title.isNotBlank())
        }
    }

    @Test
    fun `ports are declared`() {
        executors.forEach {
            val spec = it.spec
            assertTrue("${spec.id} declares ${spec.outputs} outputs", spec.outputs >= 1)
            if (spec.outputs > 1) assertEquals("${spec.id} output labels", spec.outputs, spec.outputLabels.size)
            if (spec.kind == NodeKind.Trigger) assertTrue("${spec.id} trigger must start a Flow", spec.canStartFlow)
        }
    }

    @Test
    fun `param declarations and defaults are valid`() {
        executors.forEach {
            val spec = it.spec
            val keys = spec.params.map { p -> p.key }
            assertEquals("${spec.id} has duplicate param keys", keys.size, keys.distinct().size)
            spec.params.forEach { p ->
                val where = "${spec.id}.${p.key}"
                assertTrue("$where needs a key", p.key.isNotBlank())
                when (p.type) {
                    ParamType.Select -> {
                        assertTrue("$where Select needs options", p.options.isNotEmpty())
                        val values = p.options.map { o -> o.value }
                        assertEquals("$where option values must be unique", values.size, values.distinct().size)
                        val d = p.default
                        if (d != null && d.toString().isNotEmpty()) {
                            assertTrue("$where default \"$d\" is not an option", d.toString() in values)
                        }
                    }
                    ParamType.Number -> p.default?.let { d ->
                        assertTrue("$where default must be numeric", d is Number || d.toString().toDoubleOrNull() != null || d.toString().isEmpty())
                    }
                    ParamType.Boolean -> p.default?.let { d ->
                        assertTrue("$where default must be boolean", d is Boolean || d.toString() in setOf("true", "false"))
                    }
                    else -> Unit
                }
                p.showWhen.keys.forEach { sibling ->
                    assertTrue("$where showWhen refers to unknown param \"$sibling\"", sibling in keys)
                    assertTrue("$where showWhen cannot refer to itself", sibling != p.key)
                }
            }
        }
    }

    @Test
    fun `empty input never crashes an executor`() {
        val failures = mutableListOf<String>()
        executors.forEach { executor ->
            val spec = executor.spec
            val defaults = spec.params.filter { it.default != null }.associate { it.key to it.default }
            listOf("no params" to emptyMap<String, Any?>(), "default params" to defaults).forEach { (label, params) ->
                try {
                    runBlocking {
                        withTimeout(10_000) {
                            val result = executor.execute(ctx(stepOf(spec.id, params = params), emptyList()))
                            result.outputs.keys.forEach { port ->
                                if (port !in 0 until maxOf(spec.outputs, 1)) {
                                    failures += "${spec.id} ($label): wrote to undeclared port $port"
                                }
                            }
                        }
                    }
                } catch (e: StepExecutionException) {
                    // A typed, user-facing error is the contract for missing required params.
                } catch (e: Throwable) {
                    failures += "${spec.id} ($label): ${e::class.simpleName}: ${e.message}"
                }
            }
        }
        if (failures.isNotEmpty()) fail("Executors that crash on empty input:\n" + failures.joinToString("\n"))
    }
}
