package com.kaivoflow.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolContractTest {
    private fun obj(vararg props: Pair<String, String>) = mapOf("type" to "object", "properties" to props.associate { it.first to mapOf("type" to it.second) })
    private fun adapt(schema: Map<String, Any?>, raw: Any?) = ToolArguments.fromProvider(raw).let { p -> ToolArguments.adapt(schema, ToolCall("1", "t", p.arguments, p.error, p.raw)) }

    @Test fun `arguments are read leniently`() {
        val one = obj("q" to "string")
        assertEquals(mapOf("q" to "x"), adapt(one, """{"q":"x"}""").arguments)
        assertEquals(mapOf("q" to "x"), adapt(one, "{'q': 'x',}").arguments)
        assertEquals(mapOf("q" to "x"), adapt(one, "{q: \"x\" // note\n}").arguments)
        assertEquals(mapOf("q" to "x"), adapt(one, "```json\n{\"q\": \"x\"}\n```").arguments)
        assertEquals(mapOf("flag" to true, "n" to null), ToolArguments.fromProvider("{flag: True, n: None}").arguments)
        assertEquals(mapOf("q" to "it's"), adapt(one, "{'q': 'it\\'s'}").arguments)
        assertEquals(emptyMap<String, Any?>(), ToolArguments.fromProvider("").arguments); assertNull(ToolArguments.fromProvider(null).error)
    }

    @Test fun `plain text becomes the single parameter, several parameters stay an error`() {
        val one = obj("expression" to "string")
        assertEquals(mapOf("expression" to "2+2"), adapt(one, "2+2").arguments)
        assertEquals(mapOf("expression" to "weather in Pune"), adapt(one, "\"weather in Pune\"").arguments)
        assertNull(adapt(one, "2+2").argumentsError)
        val two = obj("a" to "string", "b" to "string")
        val c = adapt(two, "just text")
        assertTrue(c.argumentsError != null); assertEquals("just text", c.rawArguments)
    }

    @Test fun `a cut off object is refused, never completed with guessed values`() {
        val c = adapt(obj("q" to "string", "n" to "number"), """{"q": "abc", "n": """)
        assertTrue(c.argumentsError != null)
        assertTrue(ToolArguments.fromProvider("[1, 2").error != null)
    }

    @Test fun `primitive type slips are coerced`() {
        val s = obj("n" to "integer", "x" to "number", "on" to "boolean", "s" to "string", "o" to "object", "l" to "array")
        val c = adapt(s, mapOf("n" to "5", "x" to "2.5", "on" to "true", "s" to 7, "o" to "{\"a\":1}", "l" to "x"))
        assertEquals(mapOf("n" to 5L, "x" to 2.5, "on" to true, "s" to "7", "o" to mapOf("a" to 1L), "l" to listOf("x")), c.arguments.mapValues { (k, v) -> if (k == "o") (v as Map<*, *>).mapValues { (it.value as Number).toLong() } else v })
        assertEquals(emptyList<String>(), SchemaCheck.validate(s, c.arguments.mapValues { (k, v) -> v }))
        assertEquals(mapOf("n" to "abc"), adapt(obj("n" to "integer"), mapOf("n" to "abc")).arguments)
    }

    @Test fun `tool errors have one stable shape with the status first`() {
        assertEquals("There was an error: boom", ToolErrors.format("boom"))
        assertTrue(ToolErrors.format("Unauthorized", 401).startsWith("HTTP 401 There was an error:"))
        val big = ToolErrors.format("failed", 500, "x".repeat(5_000))
        assertTrue(big.startsWith("HTTP 500 There was an error: failed\nResponse body: ")); assertTrue(big.length < 2_200); assertTrue(big.endsWith("…[truncated]"))
        val key = "sk-live-ABCDEFGHIJKLMNOP"
        assertFalse(ToolErrors.format("bad", 400, "{\"error\":\"key $key\"}", Redactor(listOf(key))).contains(key))
        assertEquals("There was an error: x", ToolErrors.format("There was an error: x"))
        assertTrue(ToolErrors.isErrorText("HTTP 404 There was an error: nope")); assertFalse(ToolErrors.isErrorText("fine"))
        assertEquals(503, ToolErrors.statusIn("Step \"a\" failed: HTTP 503 Service Unavailable")); assertNull(ToolErrors.statusIn("no status"))
        assertEquals("There was an error: x", ToolErrors.format("x", null, "\u0000\u0001binary"))
    }

    @Test fun `item list parser splits, cleans and counts`() {
        assertEquals(listOf("a", "b", "c"), (ItemListParser.parse("- a\n2. b\n\n  * c  ") as ParseResult.Ok).value)
        assertEquals(listOf("a", "b"), (ItemListParser.parse("a, b ,", ",") as ParseResult.Ok).value)
        assertEquals(listOf("a", "b"), (ItemListParser.parse("a\nb\nc", count = 2) as ParseResult.Ok).value)
        assertTrue(ItemListParser.parse("a", count = 3) is ParseResult.Invalid)
        val p = ItemListParser.structured()
        assertEquals(listOf("x", "y"), (p.parse("[\"x\", \"y\"]") as ParseResult.Ok).value); assertEquals(listOf("x", "y"), (p.parse("x\ny") as ParseResult.Ok).value)
    }
}
