package com.kaivoflow.core.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackExpressionEvaluatorTest {

    private val evaluator = FallbackExpressionEvaluator()

    private val scope: Map<String, Any?> = mapOf(
        "json" to mapOf("amount" to 1250.5, "merchant" to "Cafe"),
        "items" to listOf(mapOf("id" to 1), mapOf("id" to 2)),
        "vars" to mapOf("sheetId" to "abc"),
        "now" to "2026-11-13T04:30:00Z",
        "today" to "2026-11-13",
        "step" to mapOf("Parse SMS" to listOf(mapOf("text" to "debited"))),
    )

    @Test
    fun `reads nested json paths`() {
        assertEquals(1250.5, evaluator.evaluate("\$json.amount", scope))
        assertEquals("Cafe", evaluator.evaluate("\$json.merchant", scope))
        assertEquals("abc", evaluator.evaluate("\$vars.sheetId", scope))
        assertNull(evaluator.evaluate("\$json.missing.deep", scope))
    }

    @Test
    fun `indexes into arrays`() {
        assertEquals(2, evaluator.evaluate("\$items[1].id", scope))
    }

    @Test
    fun `step references work by name`() {
        // Single-item Step output collapses to the field value, matching the JS runtime.
        assertEquals("debited", evaluator.evaluate("\$step(\"Parse SMS\").text", scope))
        assertEquals(listOf("debited"), evaluator.evaluate("\$step(\"Parse SMS\", \"text\")", scope))
    }

    @Test
    fun `count and length helpers`() {
        assertEquals(2, evaluator.evaluate("\$count()", scope))
        assertEquals(4, evaluator.evaluate("\$length(\$json.merchant)", scope)) // "Cafe"
        assertEquals(2, evaluator.evaluate("\$length(\$items)", scope))
    }

    @Test
    fun `literals are typed`() {
        assertEquals(42.0, evaluator.evaluate("42", scope))
        assertEquals(true, evaluator.evaluate("true", scope))
        assertEquals("hello", evaluator.evaluate("\"hello\"", scope))
        assertNull(evaluator.evaluate("null", scope))
    }

    @Test
    fun `js-only expressions raise a clear error`() {
        val error = runCatching { evaluator.evaluate("\$json.amount * 2", scope) }.exceptionOrNull()
        assertTrue(error is ExpressionUnsupportedException)
        assertTrue(error!!.message!!.contains("JavaScript engine"))
    }

    @Test
    fun `template interpolation marks the whole string`() {
        // The fallback never partially interpolates; the engine's ParamResolver delegates here
        // only for simple cases, and the JS engine handles the rest.
        assertEquals("plain", evaluator.interpolate("plain", scope))
    }

    @Test
    fun `expression evaluator resolves vars with dollar syntax`() {
        val testScope = mapOf(
            "vars" to mapOf(
                "CLIENT_ID" to "13033585986-h3vcrcf6",
                "CLIENT_SECRET" to "GOCSPX-j6VMrhs73ljcT",
            ),
            "json" to mapOf("status" to "ok"),
        )
        val resolvedId = evaluator.interpolate("Bearer {{ \$vars.CLIENT_ID }}", testScope)
        val resolvedSecret = evaluator.interpolate("{{ \$vars.CLIENT_SECRET }}", testScope)
        assertEquals("Bearer 13033585986-h3vcrcf6", resolvedId)
        assertEquals("GOCSPX-j6VMrhs73ljcT", resolvedSecret)
    }
}
