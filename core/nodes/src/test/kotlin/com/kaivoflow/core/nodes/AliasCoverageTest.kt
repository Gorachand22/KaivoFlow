package com.kaivoflow.core.nodes

import com.kaivoflow.core.model.StepNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every id a pre-2C build could have stored (old-step-ids.txt, the 87 ids of the original catalog)
 * must still resolve: it is a live executor, an alias to a live executor, or a tombstone.
 */
class AliasCoverageTest {
    private val oldIds = AliasCoverageTest::class.java.getResourceAsStream("/old-step-ids.txt")!!
        .bufferedReader().readLines().filter { it.isNotBlank() }
    private val registry = NodeCatalog.registry()
    private val map = CatalogAliases.map

    @Test fun `the fixture lists all 87 original ids`() = assertEquals(87, oldIds.toSet().size)

    @Test fun `every old id resolves to an existing executor or a tombstone`() {
        oldIds.forEach { id ->
            val live = registry.spec(id) != null
            val target = map.alias(id)
            val tomb = map.tombstone(id)
            assertTrue("old id $id is unresolvable", live || target != null || tomb != null)
            if (target != null) assertNotNull("alias $id -> ${target.newId} has no executor", registry.spec(target.newId))
        }
    }

    @Test fun `normalising an old id gives an id the registry can run, or keeps a tombstoned one`() {
        oldIds.forEach { id ->
            val out = CatalogAliases.normalizer.normalize(StepNode("s", id, "S")).type
            val ok = registry.spec(out) != null || map.tombstone(out) != null
            assertTrue("$id normalises to $out which is neither live nor tombstoned", ok)
        }
    }

    @Test fun `renamed ids follow the category dot name rule and the map is healthy`() {
        val live = registry.specs.map { it.id }.toSet()
        assertTrue(map.problems(live).joinToString(), map.problems(live).isEmpty())
        CatalogAliases.renames.values.forEach { id ->
            assertTrue("bad id $id", Regex("^[a-z]+\\.[a-z0-9_]+$").matches(id))
        }
    }
}
