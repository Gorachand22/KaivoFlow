package com.kaivoflow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemTest {

    @Test
    fun `FlowData is the same type as Item`() {
        val a: Item = FlowData(mapOf("k" to 1))
        val b: FlowData = Item.of("k" to 1)
        assertEquals(a, b)
        assertTrue(a.sources.isEmpty())
        assertNull(a.error)
    }

    @Test
    fun `old constructor shape still compiles and maps onto lineage and ItemError`() {
        val old = FlowData(json = mapOf("a" to 1), pairedIndex = 4, error = "boom", binary = emptyMap())
        assertEquals(4, old.pairedIndex)
        assertEquals(ItemRef("", 0, 4), old.sources.single())
        assertEquals("boom", old.error!!.message)
        assertTrue(old.isError)
    }

    @Test
    fun `new style construction does not clash with the compat constructor`() {
        val ref = ItemRef("s1", 0, 2, port = 1)
        val item = FlowData(json = mapOf("a" to 1), sources = listOf(ref))
        assertEquals(2, item.pairedIndex)
        assertFalse(item.isError)
        val withBinary = FlowData(json = mapOf(), binary = mapOf("f" to BinaryDataRef("kaivo://files/x", "text/plain", "x.txt", 1)))
        assertTrue(withBinary.hasBinary)
    }

    @Test
    fun `compat reader builds the current shape from stored legacy fields`() {
        val item = ItemCompat.fromLegacy(mapOf("a" to 1), pairedIndex = 2, errorMessage = "late")
        assertEquals(2, item.pairedIndex)
        assertEquals("late", ItemCompat.errorMessage(item))
        val plain = ItemCompat.fromLegacy(mapOf("a" to 1))
        assertTrue(plain.sources.isEmpty())
        assertNull(ItemCompat.errorMessage(plain))
    }

    @Test
    fun `binary helpers and copy keep working`() {
        val ref = BinaryDataRef("kaivo://files/x", "text/plain", "x.txt", 1)
        val item = FlowData.of("a" to 1).withBinary("f", ref)
        assertEquals(ref, item.binaryRef("f"))
        assertTrue(item.withoutBinary().binary.isEmpty())
        assertEquals(listOf(ItemRef("s", 0, 0)), item.withSources(listOf(ItemRef("s", 0, 0))).sources)
    }
}
