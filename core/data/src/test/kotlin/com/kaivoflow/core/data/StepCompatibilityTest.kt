package com.kaivoflow.core.data

import com.kaivoflow.core.model.ConvertedFrom
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StreamEdge
import com.kaivoflow.core.nodes.CatalogAliases
import com.kaivoflow.core.nodes.NodeCatalog
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved-Flow compatibility at the loader (taxonomy spec 6.2 and 6.5). */
class StepCompatibilityTest {

    private val registry = NodeCatalog.registry()

    private fun flow() = Flow(
        id = "f",
        name = "Compat",
        steps = persistentListOf(
            StepNode("a", "trigger.manual", "Start"),
            StepNode("b", "future.shiny_thing", "From the future", params = persistentMapOf("k" to "v", "n" to 3.0), x = 5f, y = 6f),
            StepNode("c", "saas.tcp", "Old socket", params = persistentMapOf("host" to "h")),
        ),
        streams = persistentListOf(StreamEdge("e1", "a", "b"), StreamEdge("e2", "b", "c")),
    )

    @Test
    fun `unknown and later removed ids survive a save and load round trip unchanged`() {
        val original = flow()
        val loaded = JsonCodec.decodeFlow(JsonCodec.encodeFlow(original))!!
        assertEquals(original.steps.map { it.type }, loaded.steps.map { it.type })
        assertEquals(original.steps[1].params, loaded.steps[1].params)
        assertEquals(original.streams, loaded.streams)
        // and a second round trip is byte-stable
        assertEquals(JsonCodec.encodeFlow(loaded), JsonCodec.encodeFlow(JsonCodec.decodeFlow(JsonCodec.encodeFlow(loaded))!!))
    }

    @Test
    fun `placeholders are reported for unknown ids but the Flow is not mutated`() {
        val loaded = JsonCodec.decodeFlow(JsonCodec.encodeFlow(flow()))!!
        val unavailable = CatalogAliases.normalizer.unavailable(loaded) { registry.spec(it) != null }
        assertEquals(listOf("future.shiny_thing"), unavailable.map { it.step.type })
    }

    @Test
    fun `conversion provenance and legacy params round trip, plain steps add no new keys`() {
        val converted = StepNode(
            "x", "data.set", "Set",
            legacyParams = persistentMapOf("old" to "value"),
            convertedFrom = ConvertedFrom("transform.text", "text_to_set", 2),
        )
        val back = JsonCodec.decodeFlow(JsonCodec.encodeFlow(Flow("f", "F", steps = persistentListOf(converted))))!!.steps.single()
        assertEquals(converted.legacyParams, back.legacyParams)
        assertEquals(converted.convertedFrom, back.convertedFrom)

        val plain = JsonCodec.encodeFlow(Flow("f", "F", steps = persistentListOf(StepNode("p", "trigger.manual", "P"))))
        assertFalse(plain.contains("legacyParams"))
        assertFalse(plain.contains("convertedFrom"))
    }

    @Test
    fun `catalog alias map is healthy against the live registry`() {
        val live = registry.specs.map { it.id }.toSet()
        assertTrue(CatalogAliases.map.problems(live).joinToString(), CatalogAliases.map.problems(live).isEmpty())
        assertNotNull(CatalogAliases.normalizer)
    }
}
