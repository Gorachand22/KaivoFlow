package com.kaivoflow.core.expressions

import com.kaivoflow.core.engine.CredentialProvider
import com.kaivoflow.core.engine.ExecutionMode
import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.HttpResponseSpec
import com.kaivoflow.core.engine.ParamResolver
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.model.ExprMode
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.StepNode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import org.junit.Assert.assertEquals
import org.junit.Test

/** The resolver driven by the real expression engine, through the StepContext the runner builds. */
class ParamResolverKaivoTest {

    private val spec = NodeSpec("t.x", "X", StepCategory.Transform, "s", "i", params = persistentListOf(
        ParamSpec("label", "Label", ParamType.Text),
        ParamSpec("total", "Total", ParamType.Number),
        ParamSpec("code", "Code", ParamType.Code),
        ParamSpec("tok", "Token", ParamType.Secret),
        ParamSpec("mode", "Mode", ParamType.Select, default = "a"),
        ParamSpec("extra", "Extra", ParamType.Text, showWhen = mapOf("mode" to listOf("b"))),
    ))

    private fun ctx(params: Map<String, Any?>, vararg items: FlowData) = StepContext(
        step = StepNode("s1", "t.x", "Step", params.toImmutableMap()),
        flow = Flow(id = "f", name = "F"),
        input = items.toList(), runId = "r", mode = ExecutionMode.Manual, attempt = 1,
        evaluator = KaivoExpressionEvaluator(),
        http = object : HttpGateway { override suspend fun request(request: HttpRequestSpec) = HttpResponseSpec(200) },
        credentials = object : CredentialProvider { override suspend fun resolve(ref: String) = emptyMap<String, String>() },
        clock = { 0L }, timezone = "UTC", stepOutputs = emptyMap(), logger = { _, _ -> }, spec = spec,
    )

    private val body = "{{ \$json.price * 2 }}"

    @Test fun `each item gets its own typed values`() {
        val c = ctx(
            mapOf("label" to "Order {{ \$json.id }}", "total" to body, "code" to body, "tok" to body, "extra" to "x"),
            FlowData(mapOf("id" to "A1", "price" to 5L)), FlowData(mapOf("id" to "B2", "price" to 7L)),
        )
        val first = c.resolvedFor(0)
        val second = c.resolvedFor(1)
        assertEquals("Order A1", first.string("label")); assertEquals("Order B2", second.string("label"))
        assertEquals(10L, first.long("total")); assertEquals(14L, second.long("total"))
        // Code and Secret keep their text untouched, and the default and show rule apply.
        assertEquals(body, first.string("code")); assertEquals(body, second.string("tok"))
        assertEquals("a", first.string("mode"))
        assertEquals(false, "extra" in first)
    }

    @Test fun `only mode evaluates bare text and a bad expression fails the same way as a run`() {
        val schema = ParamSchema(1, persistentListOf(ParamDef("e", "E", ParamKind.Number(), expr = ExprMode.Only)))
        val r = ParamResolver(KaivoExpressionEvaluator())
        val scope = mapOf("json" to mapOf("n" to 4L))
        assertEquals(8L, r.resolve(schema, mapOf("e" to ParamValue.Fixed("\$json.n * 2")), scope).long("e"))
        assertEquals(8L, r.resolve(schema, mapOf("e" to ParamValue.Expr("{{ \$json.n * 2 }}")), scope).long("e"))
    }
}
