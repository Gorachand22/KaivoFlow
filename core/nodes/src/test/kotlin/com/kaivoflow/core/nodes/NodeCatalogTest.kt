package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.FlowValidator
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog invariants. These catch the class of bug where a Step is visible in the palette but
 * cannot actually run, or where two Steps collide on an id.
 */
class NodeCatalogTest {

    private val registry = NodeCatalog.registry()

    @Test
    fun `catalog has no duplicate step ids`() {
        val ids = NodeCatalog.executors().map { it.spec.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `every trigger is marked canStartFlow and every action is not`() {
        NodeCatalog.executors().forEach { executor ->
            if (executor.spec.kind == NodeKind.Trigger) {
                assertTrue("${executor.spec.id} should be able to start a Flow", executor.spec.canStartFlow)
                assertTrue("${executor.spec.id} must use the trigger. prefix", executor.spec.id.startsWith("trigger."))
            } else {
                assertTrue("${executor.spec.id} is an action and must not claim to start Flows", !executor.spec.canStartFlow)
                assertTrue("${executor.spec.id} is an action but uses the trigger. prefix", !executor.spec.id.startsWith("trigger."))
            }
        }
    }

    @Test
    fun `every spec has the metadata the UI needs`() {
        registry.specs.forEach { spec ->
            assertTrue("${spec.id} needs a title", spec.title.isNotBlank())
            assertTrue("${spec.id} needs a subtitle", spec.subtitle.isNotBlank())
            assertTrue("${spec.id} needs an icon", spec.iconKey.isNotBlank())
            if (spec.outputs > 1) {
                assertEquals(
                    "${spec.id} must label every output port",
                    spec.outputs,
                    spec.outputLabels.size,
                )
            }
            spec.params.forEach { param ->
                assertTrue("${spec.id}.${param.key} needs a label", param.label.isNotBlank())
                if (param.type == ParamType.Select) {
                    assertTrue("${spec.id}.${param.key} is a Select with no options", param.options.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun `required params have no silent empty default`() {
        registry.specs.forEach { spec ->
            spec.params.filter { it.required && it.type == ParamType.Text }.forEach { param ->
                assertTrue(
                    "${spec.id}.${param.key} is required but defaults to an empty string",
                    (param.default as? String)?.isNotBlank() != false,
                )
            }
        }
    }

    @Test
    fun `search finds steps by legacy and plain names`() {
        assertTrue(registry.search("http").any { it.id == "saas.http" })
        assertTrue(registry.search("n8n http request").any { it.id == "saas.http" })
        assertTrue(registry.search("telegram").any { it.id == "saas.telegram" })
        assertTrue(registry.search("bank sms").any { it.id == "trigger.notification" })
        assertTrue(registry.search("if").any { it.id == "logic.if" })
        assertEquals(0, registry.search("zzzzz").size)
    }

    @Test
    fun `category filtering returns only that category`() {
        StepCategory.entries.forEach { category ->
            val specs = registry.byCategory(category)
            assertTrue("$category should not be empty", specs.isNotEmpty())
            specs.forEach { assertEquals(category, it.category) }
        }
    }

    @Test
    fun `sensitive permission steps declare what they need`() {
        val sensitive = registry.specs.filter { it.requiresSensitivePermission != null }
        assertTrue(sensitive.any { it.id == "trigger.notification" })
        assertTrue(sensitive.any { it.id == "trigger.sms" })
        assertTrue(sensitive.any { it.id == "device.sms" })
    }

    @Test
    fun `a flow built only from catalog steps validates`() {
        val flow = Flow(
            id = "f",
            name = "Valid flow",
            steps = kotlinx.collections.immutable.persistentListOf(
                com.kaivoflow.core.model.StepNode(
                    id = "t", type = "trigger.manual", name = "Start", y = 0f,
                ),
                com.kaivoflow.core.model.StepNode(
                    id = "w", type = "saas.weather", name = "Weather", y = 10f,
                    params = kotlinx.collections.immutable.persistentMapOf("city" to "Bhubaneswar"),
                ),
                com.kaivoflow.core.model.StepNode(
                    id = "n", type = "device.notify", name = "Tell me", y = 20f,
                    params = kotlinx.collections.immutable.persistentMapOf("title" to "Weather", "body" to "{{ \$json.condition }}"),
                ),
            ),
            streams = kotlinx.collections.immutable.persistentListOf(
                com.kaivoflow.core.model.StreamEdge("s1", "t", "w"),
                com.kaivoflow.core.model.StreamEdge("s2", "w", "n"),
            ),
        )
        val report = FlowValidator(registry).validate(flow)
        assertTrue("unexpected blocking issues: ${report.blocking}", report.isValid)
    }

    @Test
    fun `category counts are reported for the palette`() {
        val counts = NodeCatalog.countByCategory()
        assertTrue(counts.values.sum() >= 30)
        assertTrue(counts.getOrDefault(StepCategory.Device, 0) >= 5)
    }
}
