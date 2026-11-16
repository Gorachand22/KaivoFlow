package com.kaivoflow.core.expressions

import org.junit.Assert.assertTrue
import org.junit.Assert.fail

internal fun num(x: Number) = x.toDouble()

internal val SCOPE: Map<String, Any?> = mapOf(
    "json" to mapOf(
        "a" to mapOf("b" to mapOf("c" to 42.0)),
        "name" to "  Ada Lovelace ",
        "price" to 19.99,
        "qty" to 3,
        "tags" to listOf("x", "y", "x"),
        "rows" to listOf(mapOf("n" to "a", "v" to 1.0), mapOf("n" to "b", "v" to 2.0), mapOf("n" to "c", "v" to 3.0)),
        "empty" to "",
        "nil" to null,
        "flag" to true,
        "text" to "hello world",
        "nums" to listOf(3.0, 1.0, 2.0),
        "nested" to listOf(listOf(1.0, 2.0), listOf(3.0, listOf(4.0))),
        "obj" to mapOf("x" to 1.0, "y" to 2.0),
        "jsonText" to "{\"a\":[1,2,{\"b\":null}]}",
        "amount" to 1250.5,
        "merchant" to "Cafe",
        "when" to "2026-11-13T04:30:00Z",
        "mixed" to listOf(2.0, "10", 1.0),
        "dupes" to listOf(1, 1.0, 2, "1"),
        "gaps" to listOf("a", null, "", "b"),
        "numStr" to "42",
        "words" to listOf("pear", "apple", "fig"),
    ),
    "items" to listOf(mapOf("id" to 1), mapOf("id" to 2)),
    "item" to mapOf("id" to 1),
    "index" to 0,
    "vars" to mapOf("sheetId" to "abc", "token" to "xyz123", "n" to 10.0),
    "now" to "2026-11-13T04:30:00Z",
    "today" to "2026-11-13",
    "run" to mapOf("id" to "run-1", "timestamp" to 1.0, "timezone" to "UTC", "step" to "S"),
    "step" to mapOf(
        "Parse SMS" to listOf(mapOf("text" to "debited")),
        "Two" to listOf(mapOf("v" to 1.0, "w" to "p"), mapOf("v" to 2.0, "w" to "q")),
        "Empty" to emptyList<Any?>(),
        "Counted" to listOf(mapOf("count" to 5.0)),
    ),
)

internal fun evaluator(strict: Boolean = false, limits: ExpressionLimits = ExpressionLimits.Default) = KaivoExpressionEvaluator(limits, strict)

internal fun ev(code: String, scope: Map<String, Any?> = SCOPE, strict: Boolean = false, limits: ExpressionLimits = ExpressionLimits.Default): Any? =
    evaluator(strict, limits).evaluate(code, scope)

internal fun tpl(text: String, scope: Map<String, Any?> = SCOPE, strict: Boolean = false): Any? =
    evaluator(strict).interpolate(text, scope)

internal fun same(expected: Any?, actual: Any?): Boolean = when {
    expected is Number && actual is Number -> expected.toDouble() == actual.toDouble()
    expected is List<*> && actual is List<*> -> expected.size == actual.size && expected.indices.all { same(expected[it], actual[it]) }
    expected is Map<*, *> && actual is Map<*, *> -> expected.size == actual.size && expected.all { (k, v) -> actual.containsKey(k) && same(v, actual[k]) }
    else -> expected == actual
}

/** Runs all cases and reports every mismatch at once. */
internal fun checkCases(cases: List<Pair<String, Any?>>, scope: Map<String, Any?> = SCOPE, strict: Boolean = false) {
    val failures = ArrayList<String>()
    for ((code, expected) in cases) {
        try {
            val actual = ev(code, scope, strict)
            if (!same(expected, actual)) failures += "$code => expected <$expected> but was <$actual>"
        } catch (e: Throwable) {
            failures += "$code => threw ${e.javaClass.simpleName}: ${e.message}"
        }
    }
    if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} cases failed:\n" + failures.joinToString("\n"))
}

internal fun failure(code: String, scope: Map<String, Any?> = SCOPE, strict: Boolean = false, limits: ExpressionLimits = ExpressionLimits.Default, template: Boolean = false): ExpressionException {
    try {
        if (template) evaluator(strict, limits).interpolate(code, scope) else ev(code, scope, strict, limits)
    } catch (e: ExpressionException) {
        return e
    }
    fail("expected an ExpressionException for: $code")
    throw IllegalStateException()
}

internal fun assertKind(kind: ErrorKind, e: ExpressionException) = assertTrue("kind was ${e.kind}: ${e.message}", e.kind == kind)
