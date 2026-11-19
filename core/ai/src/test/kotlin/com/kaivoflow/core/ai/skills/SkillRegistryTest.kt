package com.kaivoflow.core.ai.skills

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRegistryTest {

    @Test
    fun defaultSkillsArePresent() {
        val registry = SkillRegistry()
        val all = registry.all()
        assertTrue(all.size >= 4)
        assertNotNull(registry.get("workflow-builder"))
        assertNotNull(registry.get("device-automation"))
    }

    @Test
    fun addAndRemoveCustomSkill() {
        val registry = SkillRegistry()
        val custom = AiSkill(
            id = "custom-scraper",
            name = "Custom Scraper",
            description = "Scrapes local pages",
            instructions = "Follow rules",
            isBuiltIn = false,
        )
        registry.add(custom)
        assertEquals(custom, registry.get("custom-scraper"))

        // Cannot remove built-in
        assertFalse(registry.remove("workflow-builder"))
        // Can remove custom
        assertTrue(registry.remove("custom-scraper"))
        assertEquals(null, registry.get("custom-scraper"))
    }

    @Test
    fun toggleEnabledStatus() {
        val registry = SkillRegistry()
        registry.toggleEnabled("workflow-builder", false)
        assertFalse(registry.get("workflow-builder")!!.isEnabled)
        registry.toggleEnabled("workflow-builder", true)
        assertTrue(registry.get("workflow-builder")!!.isEnabled)
    }

    @Test
    fun loadSkillToolExecution() = runBlocking {
        val registry = SkillRegistry()
        val tools = registry.createTools()
        val loadTool = tools.first { it.name == "load_skill" }

        val res = loadTool.execute(mapOf("skillId" to "workflow-builder"))
        assertFalse(res.isError)
        assertTrue(res.text.contains("Workflow Builder"))
    }
}
