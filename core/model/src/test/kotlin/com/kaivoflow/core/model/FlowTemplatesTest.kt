package com.kaivoflow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowTemplatesTest {

    @Test
    fun `starter templates build valid editable flows with fresh ids`() {
        assertEquals(5, FlowTemplates.all.size)
        val template = FlowTemplates.byId("morning_briefing") ?: error("missing template")
        val first = template.build()
        val second = template.build()

        assertNotEquals(first.id, second.id)
        assertTrue(first.triggers.isNotEmpty())
        assertEquals(first.steps.size - 1, first.streams.size)
        assertTrue(first.streams.all { edge ->
            first.steps.any { it.id == edge.fromStepId } && first.steps.any { it.id == edge.toStepId }
        })
        assertTrue(first.tags.contains("template"))
        assertTrue(first.createdAt > 0)
        assertNull(FlowTemplates.byId("does_not_exist"))
    }
}
