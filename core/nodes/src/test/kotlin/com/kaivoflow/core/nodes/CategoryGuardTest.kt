package com.kaivoflow.core.nodes

import com.kaivoflow.core.model.CategoryTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ties the executor registry to the category tree (taxonomy spec sections 2, 3 and 5.5.1). */
class CategoryGuardTest {

    private val specs = NodeCatalog.executors().map { it.spec }

    @Test
    fun `every executor has a category path that exists in the tree`() {
        specs.forEach {
            assertTrue("${it.id} has no category path", it.categoryPath.isNotEmpty())
            assertTrue("${it.id} path \"${it.categoryPath}\" is not in the tree", CategoryTree.exists(it.categoryPath))
            it.alsoIn.forEach { extra -> assertTrue("${it.id} alsoIn \"$extra\" is not in the tree", CategoryTree.exists(extra)) }
        }
    }

    @Test
    fun `placement table has no entry for an unregistered id`() {
        val ids = specs.map { it.id }.toSet()
        assertEquals(emptySet<String>(), CategoryDefs.placements.keys - ids)
        assertEquals(emptySet<String>(), ids - CategoryDefs.placements.keys)
    }

    @Test
    fun `every tree leaf is reachable and every live leaf has a Step`() {
        val used = specs.flatMap { listOf(it.categoryPath) + it.alsoIn }.toSet()
        CategoryTree.leaves().forEach { leaf ->
            assertTrue("leaf $leaf is not reachable by path", CategoryTree.exists(leaf))
            val planned = CategoryTree.subOf(leaf)!!.planned
            assertTrue("leaf $leaf is not planned and holds no Step", planned || leaf in used)
        }
    }

    @Test
    fun `sparks live under spark and nothing else does`() {
        specs.forEach {
            val isSparkPath = it.categoryPath.startsWith("spark/")
            assertEquals("${it.id} kind and spark category disagree", it.isTrigger, isSparkPath)
        }
    }

    @Test
    fun `legacy category agrees for device sparks and device steps`() {
        specs.filter { it.categoryPath.startsWith("device/") }.forEach {
            assertEquals(it.id, CategoryTree.legacyCategoryOf(it.categoryPath), it.category)
        }
    }
}
