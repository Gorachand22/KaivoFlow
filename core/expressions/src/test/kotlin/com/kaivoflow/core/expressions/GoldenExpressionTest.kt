package com.kaivoflow.core.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour of the previous stub evaluator that existing Flows rely on. */
class GoldenExpressionTest {
    private val evaluator = KaivoExpressionEvaluator()

    private val scope: Map<String, Any?> = mapOf(
        "json" to mapOf("amount" to 1250.5, "merchant" to "Cafe"),
        "items" to listOf(mapOf("id" to 1), mapOf("id" to 2)),
        "vars" to mapOf("sheetId" to "abc"),
        "now" to "2026-11-13T04:30:00Z",
        "today" to "2026-11-13",
        "step" to mapOf("Parse SMS" to listOf(mapOf("text" to "debited"))),
    )

    @Test fun readsNestedJsonPaths() {
        assertEquals(1250.5, evaluator.evaluate("\$json.amount", scope))
        assertEquals("Cafe", evaluator.evaluate("\$json.merchant", scope))
        assertEquals("abc", evaluator.evaluate("\$vars.sheetId", scope))
        assertNull(evaluator.evaluate("\$json.missing.deep", scope))
    }

    @Test fun indexesIntoArrays() {
        assertEquals(2, evaluator.evaluate("\$items[1].id", scope))
    }

    @Test fun stepReferencesWorkByName() {
        assertEquals("debited", evaluator.evaluate("\$step(\"Parse SMS\").text", scope))
        assertEquals(listOf("debited"), evaluator.evaluate("\$step(\"Parse SMS\", \"text\")", scope))
        assertEquals("debited", evaluator.evaluate("\$step('Parse SMS').text", scope))
    }

    @Test fun countAndLengthHelpers() {
        assertEquals(2, (evaluator.evaluate("\$count()", scope) as Number).toInt())
        assertEquals(4, (evaluator.evaluate("\$length(\$json.merchant)", scope) as Number).toInt())
        assertEquals(2, (evaluator.evaluate("\$length(\$items)", scope) as Number).toInt())
        assertEquals(2, (evaluator.evaluate("\$length()", scope) as Number).toInt())
        assertEquals(mapOf("id" to 1), evaluator.evaluate("\$first()", scope))
        assertEquals(mapOf("id" to 2), evaluator.evaluate("\$last()", scope))
        assertEquals(scope["items"], evaluator.evaluate("\$all()", scope))
        assertEquals("2026-11-13T04:30:00Z", evaluator.evaluate("\$now()", scope))
        assertEquals("2026-11-13", evaluator.evaluate("\$today()", scope))
        assertEquals("2026-11-13", evaluator.evaluate("\$today", scope))
    }

    @Test fun literalsAreTyped() {
        assertEquals(42.0, evaluator.evaluate("42", scope))
        assertEquals(true, evaluator.evaluate("true", scope))
        assertEquals("hello", evaluator.evaluate("\"hello\"", scope))
        assertNull(evaluator.evaluate("null", scope))
        assertNull(evaluator.evaluate("", scope))
        assertNull(evaluator.evaluate("   ", scope))
    }

    @Test fun formerlyJsOnlyExpressionsNowWork() {
        assertEquals(2501.0, evaluator.evaluate("\$json.amount * 2", scope))
        assertEquals("plain", evaluator.interpolate("plain", scope))
    }

    @Test fun varsInTemplates() {
        val s = mapOf("vars" to mapOf("CLIENT_ID" to "13033585986-h3vcrcf6", "CLIENT_SECRET" to "GOCSPX-j6VMrhs73ljcT"), "json" to mapOf("status" to "ok"))
        assertEquals("Bearer 13033585986-h3vcrcf6", evaluator.interpolate("Bearer {{ \$vars.CLIENT_ID }}", s))
        assertEquals("GOCSPX-j6VMrhs73ljcT", evaluator.interpolate("{{ \$vars.CLIENT_SECRET }}", s))
    }

    @Test fun whitespaceTolerance() {
        val s = mapOf("json" to mapOf("amount" to 500, "status" to "done"), "vars" to mapOf("token" to "xyz123"))
        assertEquals(mapOf("amount" to 500, "status" to "done"), evaluator.interpolate("{{$ json}}", s))
        assertEquals(mapOf("amount" to 500, "status" to "done"), evaluator.interpolate("{{ \$json }}", s))
        assertEquals(500, evaluator.interpolate("{{ \$json . amount }}", s))
        assertEquals("xyz123", evaluator.interpolate("{{ \$vars . token }}", s))
    }

    @Test fun jsonDotAccessStyleExpressions() {
        val s = mapOf("json" to mapOf("a" to mapOf("b" to mapOf("c" to "deep"))))
        assertEquals("deep", evaluator.interpolate("{{ \$json.a.b.c }}", s))
        assertEquals("deep", evaluator.interpolate("{{ \$json['a']['b'][\"c\"] }}", s))
        assertEquals("x deep y", evaluator.interpolate("x {{ \$json.a.b.c }} y", s))
        assertNull(evaluator.interpolate("{{ \$json.a.nope.c }}", s))
        assertEquals("", evaluator.interpolateAll("{{ \$json.a.nope.c }}", s))
    }

    @Test fun missingRootsAreNullForKnownOptionalNames() {
        assertNull(evaluator.evaluate("\$binary", scope))
        assertNull(evaluator.evaluate("\$error.message", scope))
    }

    @Test fun scopeVariablesFromScopeBuilder() {
        val real = com.kaivoflow.core.engine.ScopeBuilder(
            flowVars = mapOf("k" to "v"), runId = "run-9", stepOutputs = emptyMap(), clock = { 1_794_544_200_000L }, timezone = "Asia/Kolkata",
        ).build(listOf(com.kaivoflow.core.model.FlowData(json = mapOf("a" to 1.0, "n" to "x"))), 0, "My step")
        assertEquals(1.0, evaluator.evaluate("\$json.a", real))
        assertEquals("v", evaluator.evaluate("\$vars.k", real))
        assertEquals("run-9", evaluator.evaluate("\$run.id", real))
        assertEquals("run-9", evaluator.evaluate("\$runId", real))
        assertEquals("My step", evaluator.evaluate("\$run.step", real))
        assertEquals(0, (evaluator.evaluate("\$index", real) as Number).toInt())
        assertEquals(1, (evaluator.evaluate("\$input.count", real) as Number).toInt())
        assertEquals("x", evaluator.evaluate("\$item.n", real))
        assertEquals("10:00", evaluator.evaluate("now().format(\"HH:mm\")", real))
        assertEquals("2026-11-13", evaluator.evaluate("\$today", real))
        assertTrue(evaluator.evaluate("\$now", real).toString().startsWith("2026-11-13T04:30"))
        assertEquals(emptyMap<String, Any?>(), evaluator.evaluate("\$env", real))
    }
}
