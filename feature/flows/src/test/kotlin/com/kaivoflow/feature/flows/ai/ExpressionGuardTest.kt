package com.kaivoflow.feature.flows.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionGuardTest {
    @Test fun valid_and_plain_values_pass() {
        val params = mapOf<String, Any?>("a" to "plain", "b" to "Hi {{ \$json.name }}", "c" to mapOf("kx" to "{{ \$vars.X }}"), "n" to 3)
        assertEquals(emptyList<ExpressionProblem>(), ExpressionGuard.check(params, "S"))
    }

    @Test fun syntax_errors_are_reported_in_both_forms_and_nested() {
        val params = mapOf<String, Any?>(
            "a" to "x {{ 1 + }}",
            "b" to mapOf("kx" to "{{ ( }}"),
            "rows" to listOf(mapOf("v" to "{{ foo( }}")),
        )
        val p = ExpressionGuard.check(params, "Send")
        assertEquals(3, p.size)
        assertTrue(p.any { it.where == "\"Send\" / a" })
        assertTrue(p.any { it.where.startsWith("\"Send\" / rows[0].v") })
        assertTrue(ExpressionGuard.summary(p).lines().size == 3)
    }
}
