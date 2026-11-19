package com.kaivoflow.feature.canvas.input

import com.kaivoflow.feature.canvas.inspector.state.PreviewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalExpressionScopeTest {
    private val scope = GlobalExpressionScope(mapOf("HOST" to "h.test"), flowId = "f1", flowName = "Daily", clock = { 1_700_000_000_000L })

    @Test fun fixed_text_has_no_preview() = assertNull(scope.preview("plain {x}"))

    @Test fun vars_and_flow_info_preview() {
        val v = scope.preview("https://{{ \$vars.HOST }}/{{ \$run.flowName }}") as PreviewState.Value
        assertEquals("https://h.test/Daily", v.rendered)
    }

    @Test fun item_data_is_not_available() {
        assertTrue(scope.preview("{{ \$json.a }}") is PreviewState.Pending)
    }

    @Test fun bad_expression_is_an_error_line_not_a_crash() {
        assertTrue(scope.preview("{{ 1 + }}") is PreviewState.Error)
    }

    @Test fun mapper_items_list_vars_and_globals() {
        assertTrue(scope.mapperItems.any { it.snippet == "{{ \$vars.HOST }}" })
        assertTrue(scope.mapperItems.any { it.snippet == "{{ \$now }}" })
    }

    @Test fun insert_appends_with_a_space() {
        assertEquals("{{ a }}", ExpressionInsert.append("", "{{ a }}"))
        assertEquals("x {{ a }}", ExpressionInsert.append("x", "{{ a }}"))
        assertEquals("x {{ a }}", ExpressionInsert.append("x ", "{{ a }}"))
    }
}
