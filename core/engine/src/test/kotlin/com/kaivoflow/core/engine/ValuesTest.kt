package com.kaivoflow.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValuesTest {

    @Test
    fun `deepGet walks maps and list indexes`() {
        val data = mapOf(
            "a" to mapOf("b" to listOf(mapOf("c" to 42))),
            "weird.key" to "v",
        )
        assertEquals(42, Values.deepGet(data, "a.b.0.c"))
        assertEquals(42, Values.deepGet(data, "a.b[0].c"))
        assertEquals("v", Values.deepGet(data, "['weird.key']"))
        assertNull(Values.deepGet(data, "a.b.5.c"))
        assertNull(Values.deepGet(null, "a"))
    }

    @Test
    fun `deepSet creates intermediate maps and does not mutate`() {
        val base = mapOf<String, Any?>("x" to 1)
        val result = Values.deepSet(base, "a.b", "v")
        assertEquals(mapOf("x" to 1, "a" to mapOf("b" to "v")), result)
        assertEquals(1, base.size)
    }

    @Test
    fun `stringify produces stable json`() {
        val value = mapOf("b" to 1, "a" to listOf(true, null, "s"))
        assertEquals("""{"b":1,"a":[true,null,"s"]}""", Values.stringify(value))
        assertEquals("""{"a":"he said \"hi\""}""", Values.stringify(mapOf("a" to "he said \"hi\"")))
    }

    @Test
    fun `numbers stringify without trailing zero`() {
        assertEquals("42", Values.asString(42.0))
        assertEquals("4.5", Values.asString(4.5))
    }

    @Test
    fun `stableHash is order sensitive but content stable`() {
        val a = mapOf("a" to 1, "b" to 2)
        val b = mapOf("a" to 1, "b" to 2)
        val c = mapOf("a" to 2, "b" to 1)
        assertEquals(Values.stableHash(a), Values.stableHash(b))
        assertFalse(Values.stableHash(a) == Values.stableHash(c))
    }

    @Test
    fun `deepMerge merges objects and concatenates arrays`() {
        val merged = Values.deepMerge(
            mapOf("a" to mapOf("x" to 1), "list" to listOf(1)),
            mapOf("a" to mapOf("y" to 2), "list" to listOf(2)),
        ) as Map<*, *>
        assertEquals(mapOf("x" to 1, "y" to 2), merged["a"])
        assertEquals(listOf(1, 2), merged["list"])
    }

    @Test
    fun `redact masks secrets recursively`() {
        val redacted = Values.redact(
            mapOf("apiKey" to "sk-1", "nested" to mapOf("Authorization" to "Bearer x", "safe" to "ok"))
        ) as Map<*, *>
        assertEquals("••••••", redacted["apiKey"])
        assertEquals("••••••", (redacted["nested"] as Map<*, *>)["Authorization"])
        assertEquals("ok", (redacted["nested"] as Map<*, *>)["safe"])
    }

    @Test
    fun `coercions behave for messy json input`() {
        assertTrue(Values.asBoolean("TRUE"))
        assertTrue(Values.asBoolean(1))
        assertFalse(Values.asBoolean(0))
        assertEquals(3.0, Values.asDouble("3")!!, 0.001)
        assertEquals(emptyList<Any?>(), Values.asList(null))
        assertEquals(listOf("x"), Values.asList("x"))
        assertEquals(mapOf("value" to 7), Values.asMap(7))
    }

    @Test
    fun `param resolver leaves plain strings untouched`() {
        val resolver = ParamResolver(StubEvaluator())
        assertEquals("plain {{-ish text without braces", resolver.resolveValue("plain {{-ish text without braces", emptyMap()))
        assertEquals("kept", resolver.resolveValue("kept", emptyMap()))
    }
}
