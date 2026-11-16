package com.kaivoflow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryTreeTest {

    @Test
    fun `ten top levels with the spec keys`() {
        assertEquals(
            listOf("spark", "flow", "data", "files", "net", "app", "ai", "code", "security", "device"),
            CategoryTree.top.map { it.key },
        )
    }

    @Test
    fun `device has the nine subcategories`() {
        assertEquals(
            listOf("sensors", "connectivity", "telephony", "notifications", "files_media", "system", "location", "contacts_calendar", "io"),
            CategoryTree.top.first { it.key == "device" }.subs.map { it.key },
        )
    }

    @Test
    fun `keys are unique, lowercase and never misc`() {
        assertEquals(CategoryTree.top.size, CategoryTree.top.map { it.key }.distinct().size)
        CategoryTree.top.forEach { t ->
            assertTrue(t.key, Regex("[a-z]+").matches(t.key))
            assertEquals(t.subs.size, t.subs.map { it.key }.distinct().size)
            assertTrue(t.label.lowercase() !in CategoryTree.forbiddenNames)
            t.subs.forEach {
                assertTrue(Regex("[a-z_]+").matches(it.key))
                assertTrue(it.label.lowercase() !in CategoryTree.forbiddenNames)
            }
        }
    }

    @Test
    fun `paths resolve and bad paths do not`() {
        assertTrue(CategoryTree.exists("device/sensors"))
        assertFalse(CategoryTree.exists("device"))
        assertFalse(CategoryTree.exists("device/nope"))
        assertFalse(CategoryTree.exists("misc/other"))
        assertEquals("Device > Sensors", CategoryTree.breadcrumb("device/sensors"))
        assertEquals(CategoryTree.leaves().size, CategoryTree.leaves().distinct().size)
        CategoryTree.leaves().forEach { assertTrue(CategoryTree.exists(it)) }
    }

    @Test
    fun `legacy category is derived from the hue family`() {
        assertEquals(StepCategory.Trigger, CategoryTree.legacyCategoryOf("spark/start"))
        assertEquals(StepCategory.Logic, CategoryTree.legacyCategoryOf("flow/branching"))
        assertEquals(StepCategory.Transform, CategoryTree.legacyCategoryOf("files/convert"))
        assertEquals(StepCategory.Saas, CategoryTree.legacyCategoryOf("net/web"))
        assertEquals(StepCategory.Device, CategoryTree.legacyCategoryOf("security/crypto"))
        assertEquals(StepCategory.Ai, CategoryTree.legacyCategoryOf("ai/chains"))
    }

    @Test
    fun `placement rule has ten ordered questions pointing at real top levels`() {
        assertEquals((1..10).toList(), CategoryTree.placementRules.map { it.order })
        val keys = CategoryTree.top.map { it.key }.toSet()
        CategoryTree.placementRules.forEach { assertTrue(it.target in keys) }
    }

    @Test
    fun `new NodeSpec fields default so old specs compile unchanged`() {
        val spec = NodeSpec(id = "x.y", title = "T", category = StepCategory.Logic, subtitle = "s", iconKey = "i")
        assertEquals("", spec.categoryPath)
        assertEquals(Lifecycle.Stable, spec.lifecycle)
        assertEquals(NodeRole.Main, spec.role)
        assertTrue(spec.alsoIn.isEmpty() && spec.replaces.isEmpty())
    }
}
