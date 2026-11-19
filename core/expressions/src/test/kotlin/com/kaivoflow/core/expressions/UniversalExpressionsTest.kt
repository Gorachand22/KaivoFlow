package com.kaivoflow.core.expressions

import com.kaivoflow.core.engine.CredentialExpr
import com.kaivoflow.core.engine.CredentialProvider
import com.kaivoflow.core.engine.ExprCredentialProvider
import com.kaivoflow.core.engine.GlobalScope
import com.kaivoflow.core.engine.KaivoException
import com.kaivoflow.core.engine.Resolved
import com.kaivoflow.core.engine.UniversalResolver
import com.kaivoflow.core.engine.VariableResolution
import com.kaivoflow.core.model.CredentialRef
import com.kaivoflow.core.model.ExprFieldCodec
import com.kaivoflow.core.model.ParamValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UniversalExpressionsTest {
    private val ev = KaivoExpressionEvaluator()
    private val clock = { 1_700_000_000_000L }
    private fun scope(vars: Map<String, Any?> = mapOf("who" to "Ada", "n" to 2.0)) =
        GlobalScope.build(vars, flowId = "f1", flowName = "Daily", clock = clock)

    @Test fun fixed_is_returned_as_is_and_expression_is_evaluated() {
        val r = UniversalResolver(ev)
        assertEquals(Resolved.Ok("Hi {{ x"), r.resolve(ParamValue.Fixed("Hi {{ x"), scope(), "L"))
        assertEquals(Resolved.Ok("Hi Ada from Daily"), r.resolveText(ParamValue.Expr("Hi {{ \$vars.who }} from {{ \$run.flowName }}"), scope(), "L"))
        assertEquals(Resolved.Ok(2.0), r.resolve(ParamValue.Expr("{{ \$vars.n }}"), scope(), "L"))
    }

    @Test fun global_scope_has_no_item_data_and_no_environment() {
        val s = scope()
        assertEquals(emptyMap<String, Any?>(), s["json"])
        assertEquals(emptyList<Any?>(), s["items"])
        assertTrue(s.keys.none { it.equals("env", true) || it.equals("secrets", true) || it.equals("process", true) })
        assertEquals("2023-11-14", s["today"])
    }

    @Test fun bad_expression_gives_a_clear_failure_and_never_throws() {
        val r = UniversalResolver(ev)
        for (bad in listOf("{{ ", "{{ 1 + }}", "{{ \$vars.who.nope.deeper() }}", "{{ foo( }}")) {
            val out = r.resolve(ParamValue.Expr(bad), scope(), "Instructions")
            if (out is Resolved.Failed) assertTrue(out.message, out.message.startsWith("Instructions:"))
        }
        val out = r.resolve(ParamValue.Expr("{{ 1 + }}"), scope(), "Instructions")
        assertTrue(out is Resolved.Failed)
    }

    @Test fun stored_strings_use_the_codec() {
        val r = UniversalResolver(ev)
        assertEquals(Resolved.Ok("{{ \$vars.who }}"), r.resolveStored("{{ \$vars.who }}", scope(), "L"))
        assertEquals(Resolved.Ok("Ada"), r.resolveStored(ExprFieldCodec.encode(ParamValue.Expr("{{ \$vars.who }}")), scope(), "L"))
    }

    @Test fun variables_expression_sees_fixed_vars_only_and_failure_is_isolated() {
        val raw = mapOf<String, Any?>(
            "BASE" to "https://x.test",
            "URL" to ExprFieldCodec.encode(ParamValue.Expr("{{ \$vars.BASE }}/v1")),
            "CHAIN" to ExprFieldCodec.encode(ParamValue.Expr("{{ \$vars.URL }}")),
            "BAD" to ExprFieldCodec.encode(ParamValue.Expr("{{ 1 + }}")),
            "PLAIN" to "{{ not an expression }}",
        )
        val res = VariableResolution.resolve(raw, ev, flowId = "f", flowName = "F", clock = clock)
        assertEquals("https://x.test/v1", res.vars["URL"])
        assertEquals("{{ not an expression }}", res.vars["PLAIN"])
        assertTrue("BAD" !in res.vars)
        assertTrue(res.errors.getValue("BAD").startsWith("Variable \"BAD\""))
        assertEquals("", res.vars["CHAIN"].toString().ifEmpty { "" }.let { if (it == "null") "" else it })
    }

    @Test fun variables_without_expressions_come_back_untouched() {
        val raw = mapOf<String, Any?>("A" to "1", "B" to 2L)
        assertTrue(VariableResolution.resolve(raw, ev).vars === raw)
    }

    @Test fun flow_level_vars_use_the_value_codec() {
        val raw = mapOf<String, Any?>("A" to mapOf("kx" to "{{ \$now.length }}"), "B" to 5L)
        val res = VariableResolution.resolve(raw, ev, clock = clock)
        assertTrue(res.errors.isEmpty())
        assertEquals(5L, res.vars["B"])
        assertTrue(res.vars["A"] != null)
    }

    @Test fun credential_secret_fields_are_never_evaluated() {
        val fields = mapOf(
            "apiKey" to ExprFieldCodec.encode(ParamValue.Expr("{{ \$vars.who }}")),
            "baseUrl" to ExprFieldCodec.encode(ParamValue.Expr("https://{{ \$vars.who }}.test")),
        )
        val r = CredentialExpr.resolveFields("openAiApi", fields, ev, scope())
        assertEquals("https://Ada.test", r.fields["baseUrl"])
        assertEquals(fields["apiKey"], r.fields["apiKey"])
        assertTrue(r.errors.isEmpty())
    }

    @Test fun unknown_credential_type_fails_closed() {
        val fields = mapOf("baseUrl" to ExprFieldCodec.encode(ParamValue.Expr("{{ \$vars.who }}")))
        assertEquals(fields, CredentialExpr.resolveFields("mystery", fields, ev, scope()).fields)
        assertEquals(fields, CredentialExpr.resolveFields(null, fields, ev, scope()).fields)
    }

    @Test fun credential_expression_error_names_the_field() {
        val fields = mapOf("baseUrl" to ExprFieldCodec.encode(ParamValue.Expr("{{ 1 + }}")))
        val r = CredentialExpr.resolveFields("openAiApi", fields, ev, scope())
        assertTrue(r.errors.getValue("baseUrl").startsWith("Credential field \"Base URL\""))
    }

    private class Vault(val fields: Map<String, String>, val type: String) : CredentialProvider {
        override suspend fun resolve(ref: String) = fields
        override suspend fun list() = listOf(CredentialRef("c1", type, "My key"))
    }

    @Test fun provider_wrapper_evaluates_and_throws_a_kaivo_exception_on_error() = runBlocking {
        val good = Vault(mapOf("apiKey" to "sk", "baseUrl" to ExprFieldCodec.encode(ParamValue.Expr("{{ \$vars.HOST }}"))), "openAiApi")
        val out = ExprCredentialProvider(good, ev, { mapOf("HOST" to "h.test") }, clock).resolve("c1")
        assertEquals(mapOf("apiKey" to "sk", "baseUrl" to "h.test"), out)

        val bad = Vault(mapOf("apiKey" to "sk", "baseUrl" to ExprFieldCodec.encode(ParamValue.Expr("{{ ( }}"))), "openAiApi")
        try {
            ExprCredentialProvider(bad, ev, { emptyMap() }, clock).resolve("c1")
            fail("expected KaivoException")
        } catch (e: KaivoException) {
            assertTrue(e.message!!, e.message!!.contains("Base URL"))
        }
        val plain = Vault(mapOf("apiKey" to "sk"), "openAiApi")
        assertEquals(plain.fields, ExprCredentialProvider(plain, ev, { error("not called") }, clock).resolve("c1"))
    }
}
