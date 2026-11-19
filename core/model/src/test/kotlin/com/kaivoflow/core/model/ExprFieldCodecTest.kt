package com.kaivoflow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExprFieldCodecTest {
    @Test fun legacy_plain_strings_stay_fixed_even_with_braces() {
        assertEquals(ParamValue.Fixed("hello"), ExprFieldCodec.decode("hello"))
        assertEquals(ParamValue.Fixed("Hi {{ \$vars.name }}"), ExprFieldCodec.decode("Hi {{ \$vars.name }}"))
        assertEquals(ParamValue.Fixed(""), ExprFieldCodec.decode(null))
    }

    @Test fun expression_round_trips() {
        val v = ParamValue.Expr("Hi {{ \$vars.name }} \"quoted\"\nline")
        val stored = ExprFieldCodec.encode(v)
        assertTrue(stored.startsWith("{\"kx\""))
        assertEquals(v, ExprFieldCodec.decode(stored))
        assertTrue(ExprFieldCodec.isExpression(stored))
    }

    @Test fun fixed_text_that_looks_like_the_marker_reads_back_verbatim() {
        val v = ParamValue.Fixed("{\"kx\":\"boom\"}")
        val stored = ExprFieldCodec.encode(v)
        assertEquals(v, ExprFieldCodec.decode(stored))
        assertFalse(ExprFieldCodec.isExpression(stored))
    }

    @Test fun broken_marker_text_is_fixed() {
        assertEquals(ParamValue.Fixed("{\"kx\": oops"), ExprFieldCodec.decode("{\"kx\": oops"))
    }

    @Test fun maps_round_trip() {
        val m = mapOf("a" to ParamValue.Expr("{{ 1 }}"), "b" to ParamValue.Fixed("x"))
        assertEquals(m, ExprFieldCodec.decodeAll(ExprFieldCodec.encodeAll(m)))
    }

    @Test fun display_text_shows_the_text_of_either_mode() {
        assertEquals("a {{ 1 }}", ExprFieldCodec.displayText(ExprFieldCodec.encode(ParamValue.Expr("a {{ 1 }}"))))
        assertEquals("plain", ExprFieldCodec.displayText("plain"))
    }

    @Test fun modes_keep_text() {
        assertEquals(ParamValue.Expr("abc"), ExprInputModes.toExpression(ParamValue.Fixed("abc")))
        assertEquals(ParamValue.Fixed("abc"), ExprInputModes.toFixed(ParamValue.Expr("abc")))
        assertEquals(ParamValue.Fixed("done"), ExprInputModes.toFixed(ParamValue.Expr("{{ 1 }}"), "done"))
        assertEquals(ParamValue.Expr("n"), ExprInputModes.withText(ParamValue.Expr("o"), "n"))
        assertEquals(ParamValue.Fixed("n"), ExprInputModes.withText(null, "n"))
    }

    @Test fun surfaces_policy_never_allows_secrets_chats_names_or_search() {
        for (id in listOf("credential.secret", "oauth.secret", "assistant.chat", "assistant.modelSearch", "agent.name", "agent.testPrompt", "search.any", "variable.key", "flow.name")) {
            assertFalse(id, ExprSurfaces.allows(id))
            assertEquals(id, ExprDecision.Never, ExprSurfaces.find(id)!!.decision)
        }
        assertFalse(ExprSurfaces.allows("does.not.exist"))
        assertTrue(ExprSurfaces.allows("agent.instructions"))
        assertEquals(all().size, ExprSurfaces.all.map { it.id }.toSet().size)
        // Expression-capable inputs never see item data and always resolve at a defined moment.
        ExprSurfaces.all.filter { it.decision == ExprDecision.Expression }.forEach {
            assertEquals(it.id, ExprScopeKind.Global, it.scope)
            assertTrue(it.id, it.resolved != ExprWhen.Never)
        }
    }

    private fun all() = ExprSurfaces.all

    @Test fun every_builtin_secret_credential_field_refuses_expressions() {
        CredentialTypeInfo.ALL.flatMap { it.fields }.forEach { spec ->
            assertEquals(spec.key, !spec.isSecret, ExprSurfaces.credentialFieldAllows(spec))
        }
    }
}
