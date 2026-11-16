package com.kaivoflow.core.engine

import com.kaivoflow.core.model.CodeLanguage
import com.kaivoflow.core.model.ExprMode
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.ShowWhen
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamResolverTest {

    private class Counting : ExpressionEvaluator {
        val inner = StubEvaluator()
        var calls = 0
        override fun evaluate(code: String, scope: Map<String, Any?>): Any? { calls++; return inner.evaluate(code, scope) }
    }

    private fun fx(v: Any?) = ParamValue.Fixed(v)
    private fun ex(t: String) = ParamValue.Expr(t)
    private fun schema(vararg d: ParamDef) = ParamSchema(1, persistentListOf(*d))
    private fun text(key: String, default: ParamValue? = null, show: ShowWhen = ShowWhen.Always, expr: ExprMode = ExprMode.Allowed) =
        ParamDef(key, key, ParamKind.Text(), default = default, show = show, expr = expr)

    private fun scopeFor(name: String) = mapOf("json" to mapOf("name" to name, "n" to 3L))
    private val resolver = ParamResolver(StubEvaluator())

    private fun resolve(s: ParamSchema, v: Map<String, ParamValue?>, name: String = "Asha") =
        resolver.resolve(s, v, scopeFor(name))

    // ---- per item ----

    @Test fun `expressions resolve per item`() {
        val s = schema(text("who"))
        val stored = mapOf("who" to ex("{{ \$json.name }}"))
        assertEquals("Asha", resolve(s, stored, "Asha").string("who"))
        assertEquals("Ravi", resolve(s, stored, "Ravi").string("who"))
    }

    @Test fun `StepContext resolves against the item at the index`() {
        val spec = NodeSpec("t.x", "X", StepCategory.Transform, "s", "i", params = persistentListOf(
            ParamSpec("who", "Who", ParamType.Text), ParamSpec("fixed", "Fixed", ParamType.Text, default = "dflt")))
        val ctx = ctx(spec, mapOf("who" to "{{ \$json.name }}"), listOf(item("name" to "Asha"), item("name" to "Ravi")))
        assertEquals("Asha", ctx.resolvedFor(0).string("who"))
        assertEquals("Ravi", ctx.resolvedFor(1).string("who"))
        assertEquals("dflt", ctx.resolvedFor(1).string("fixed"))
        assertEquals("Asha", ctx.resolved().string("who"))
    }

    // ---- never expanded ----

    @Test fun `secret and code are never expanded, as fixed or as expression`() {
        val s = schema(
            ParamDef("tok", "Tok", ParamKind.Secret, expr = ExprMode.Never),
            ParamDef("src", "Src", ParamKind.Code(CodeLanguage.JavaScript), expr = ExprMode.Never),
            ParamDef("cred", "Cred", ParamKind.CredentialPick(), expr = ExprMode.Never),
            text("never", expr = ExprMode.Never),
        )
        val body = "{{ \$json.name }}"
        val a = resolve(s, mapOf("tok" to fx(body), "src" to fx(body), "cred" to fx(body), "never" to fx(body)))
        assertEquals(listOf(body, body, body, body), listOf("tok", "src", "cred", "never").map { a.string(it) })
        val b = resolve(s, mapOf("tok" to ex(body), "src" to ex(body), "cred" to ex(body), "never" to ex(body)))
        assertEquals(listOf(body, body, body, body), listOf("tok", "src", "cred", "never").map { b.string(it) })
        // The kind decides even when the def claims Allowed.
        val c = resolve(schema(ParamDef("tok", "Tok", ParamKind.Secret, expr = ExprMode.Allowed)), mapOf("tok" to ex(body)))
        assertEquals(body, c.string("tok"))
    }

    @Test fun `a fixed value is literal and never inferred to be an expression`() {
        assertEquals("{{ \$json.name }}", resolve(schema(text("t")), mapOf("t" to fx("{{ \$json.name }}"))).string("t"))
    }

    @Test fun `no evaluator call for never or plain values`() {
        val counting = Counting()
        val s = schema(ParamDef("tok", "Tok", ParamKind.Secret, expr = ExprMode.Never), text("t"))
        ParamResolver(counting).resolve(s, mapOf("tok" to ex("{{ \$json.name }}"), "t" to fx("plain")), scopeFor("A"))
        assertEquals(0, counting.calls)
    }

    // ---- defaults and show rules ----

    @Test fun `defaults are applied once for visible params and hidden ones are omitted`() {
        val s = schema(
            text("mode", default = fx("a")),
            text("shown", default = fx("d1"), show = ShowWhen.Eq("mode", "a")),
            text("hidden", default = fx("d2"), show = ShowWhen.Eq("mode", "b")),
            text("nodefault"),
        )
        val r = resolve(s, emptyMap())
        assertEquals(mapOf("mode" to "a", "shown" to "d1"), r.toMap())
        assertFalse("hidden" in r)
        assertFalse("nodefault" in r)
        // Stored values beat defaults; a stored value for a hidden param is kept in storage, not returned.
        val r2 = resolve(s, mapOf("mode" to fx("b"), "shown" to fx("kept"), "hidden" to fx("x")))
        assertEquals(mapOf("mode" to "b", "hidden" to "x"), r2.toMap())
        // Fixed(null) counts as unset.
        assertEquals("a", resolve(s, mapOf("mode" to fx(null))).string("mode"))
    }

    @Test fun `a default expression resolves per item`() {
        val s = schema(text("who", default = ex("{{ \$json.name }}")))
        assertEquals("Ravi", resolve(s, emptyMap(), "Ravi").string("who"))
    }

    @Test fun `expression controlled show rule keeps the field`() {
        val s = schema(text("mode"), text("x", show = ShowWhen.Eq("mode", "b")))
        val r = resolve(s, mapOf("mode" to ex("{{ \$json.name }}"), "x" to fx("v")))
        assertEquals("v", r.string("x"))
    }

    @Test fun `only mode treats bare text as the expression body`() {
        val s = schema(text("e", expr = ExprMode.Only))
        assertEquals(3L, resolve(s, mapOf("e" to fx("\$json.n"))).long("e"))
        assertEquals(3L, resolve(s, mapOf("e" to ex("{{ \$json.n }}"))).long("e"))
        assertEquals("Asha", resolve(s, mapOf("e" to ex("\$json.name"))).string("e"))
    }

    // ---- containers ----

    @Test fun `group repeater and collection apply child rules and per item expressions`() {
        val kids = persistentListOf(
            text("a", default = fx("da")),
            text("b", show = ShowWhen.Eq("../mode", "on")),
            ParamDef("s", "S", ParamKind.Secret, expr = ExprMode.Never),
        )
        val s = schema(
            text("mode", default = fx("on")),
            ParamDef("g", "G", ParamKind.Group(kids)),
            ParamDef("r", "R", ParamKind.Repeater(kids)),
            ParamDef("c", "C", ParamKind.Collection(kids)),
        )
        val row = mapOf("b" to "{{ \$json.name }}", "s" to "{{ \$json.name }}")
        val r = resolve(s, mapOf("g" to fx(row), "r" to fx(listOf(row, mapOf("a" to "x"))), "c" to fx(mapOf("b" to mapOf("kx" to "{{ \$json.name }}")))), "Ravi")
        assertEquals(mapOf("a" to "da", "b" to "Ravi", "s" to "{{ \$json.name }}"), r.map("g"))
        val rows = r.list("r")
        assertEquals(mapOf("a" to "da", "b" to "Ravi", "s" to "{{ \$json.name }}"), rows[0])
        assertEquals(mapOf("a" to "x"), rows[1])
        // A collection keeps only what was chosen: no defaults for absent optional children.
        assertEquals(mapOf("b" to "Ravi"), r.map("c"))
        val off = resolve(s, mapOf("mode" to fx("off"), "g" to fx(row)), "Ravi")
        assertFalse("b" in off.map("g"))
    }

    @Test fun `key value assignments conditions and pickers expand only value parts`() {
        val s = schema(
            ParamDef("h", "H", ParamKind.KeyValueList),
            ParamDef("a", "A", ParamKind.Assignments()),
            ParamDef("c", "C", ParamKind.Conditions()),
            ParamDef("r", "R", ParamKind.ResourcePick(com.kaivoflow.core.model.ResourceMode.entries.let { kotlinx.collections.immutable.persistentSetOf(*it.toTypedArray()) })),
            ParamDef("j", "J", ParamKind.Json()),
        )
        val e = "{{ \$json.name }}"
        val r = resolve(s, mapOf(
            "h" to fx(mapOf("X-Name" to e)),
            "a" to fx(listOf(mapOf("name" to "who", "type" to e, "value" to e))),
            "c" to fx(mapOf("combinator" to "and", "conditions" to listOf(mapOf("id" to "1", "left" to e, "op" to e, "right" to "x")))),
            "r" to fx(mapOf("mode" to e, "value" to e)),
            "j" to fx(mapOf("id" to e, "lit" to mapOf("kxLiteral" to e), "list" to listOf(e, 1L))),
        ), "Asha")
        assertEquals(mapOf("X-Name" to "Asha"), r.map("h"))
        assertEquals(mapOf("name" to "who", "type" to e, "value" to "Asha"), (r.list("a")[0]))
        val cond = (r.map("c")["conditions"] as List<*>)[0] as Map<*, *>
        assertEquals("Asha", cond["left"]); assertEquals(e, cond["op"]); assertEquals("1", cond["id"])
        assertEquals(mapOf("mode" to e, "value" to "Asha"), r.map("r"))
        assertEquals(mapOf("id" to "Asha", "lit" to e, "list" to listOf("Asha", 1L)), r.map("j"))
    }

    @Test fun `notices never appear in the result`() {
        val s = schema(ParamDef("n", "N", ParamKind.Notice(com.kaivoflow.core.model.NoticeTone.Info), default = fx("x")))
        assertTrue(resolve(s, emptyMap()).keys.isEmpty())
    }

    // ---- typed accessors ----

    private fun rp(vararg p: Pair<String, Any?>) = ResolvedParams(mapOf(*p))

    @Test fun `accessors coerce and report explicit errors`() {
        val r = rp("s" to "abc", "n" to 3.0, "ns" to " 4 ", "f" to 2.5, "b" to "TRUE", "l" to listOf(1L), "m" to mapOf("a" to 1L),
            "jt" to "{\"a\": [1, 2]}", "bad" to "{", "z" to null, "e" to "")
        assertEquals("abc", r.string("s"))
        assertEquals("3", r.string("n"))
        assertEquals(3, r.int("n")); assertEquals(3L, r.long("n")); assertEquals(3.0, r.double("n"), 0.0)
        assertEquals(4, r.int("ns"))
        assertTrue(r.bool("b"))
        assertEquals(listOf(1L), r.list("l"))
        assertEquals(mapOf("a" to 1L), r.map("m"))
        assertEquals(mapOf("a" to listOf(1L, 2L)), r.json("jt"))
        assertEquals(mapOf("a" to listOf(1L, 2L)), r.map("jt"))
        assertNull(r.intOrNull("z")); assertNull(r.stringOrNull("nope"))
        assertEquals("d", r.string("nope", "d")); assertEquals(7, r.int("nope", 7)); assertTrue(r.bool("nope", true))

        fun err(block: () -> Unit): ParamCoercionException = assertThrows(ParamCoercionException::class.java) { block() }
        assertEquals("a whole number", err { r.int("f") }.expected)
        assertEquals("s", err { r.int("s") }.key.let { "s" })
        assertEquals("a number", err { r.double("s") }.expected)
        assertEquals("true or false", err { r.bool("s") }.expected)
        assertEquals("a list", err { r.list("m") }.expected)
        assertEquals("an object", err { r.map("l") }.expected)
        assertEquals("text", err { r.string("l") }.expected)
        assertTrue(err { r.json("bad") }.message!!.contains("line 1"))
        assertTrue(err { r.int("e") }.message!!.contains("number"))
        assertThrows(ParamMissingException::class.java) { r.string("nope") }
        assertThrows(ParamMissingException::class.java) { r.int("z") }
        assertThrows(ParamCoercionException::class.java) { rp("n" to Double.NaN).double("n") }
        assertThrows(ParamCoercionException::class.java) { rp("n" to 3_000_000_000L).int("n") }
        assertEquals("Parameter \"f\" must be a whole number but is a number", err { r.int("f") }.message)
    }

    // ---- compat view ----

    @Test fun `legacy params view keeps every stored key, no defaults, no hiding, secrets verbatim`() {
        val spec = NodeSpec("t.x", "X", StepCategory.Transform, "s", "i", params = persistentListOf(
            ParamSpec("who", "Who", ParamType.Text, default = "dflt"),
            ParamSpec("tok", "Tok", ParamType.Secret),
            ParamSpec("later", "Later", ParamType.Text, showWhen = mapOf("who" to listOf("never"))),
        ))
        val ctx = ctx(spec, mapOf("tok" to "{{ \$json.name }}", "later" to "kept", "extra" to "{{ \$json.name }}"), listOf(item("name" to "Asha")))
        @Suppress("DEPRECATION") val p = ctx.params()
        assertEquals(mapOf("tok" to "{{ \$json.name }}", "later" to "kept", "extra" to "Asha"), p)
        @Suppress("DEPRECATION") assertEquals(p, ctx.paramsFor(0))
        // The typed view hides what the show rules hide and applies the default.
        val typed = ctx.resolved()
        assertEquals("dflt", typed.string("who"))
        assertFalse("later" in typed)
        assertFalse("extra" in typed)
        assertEquals("{{ \$json.name }}", typed.string("tok"))
    }

    @Test fun `without a spec the typed view falls back to the stored values`() {
        val ctx = ctx(null, mapOf("a" to "{{ \$json.name }}"), listOf(item("name" to "Asha")))
        assertEquals("Asha", ctx.resolved().string("a"))
    }

    @Test fun `resolveStored reads legacy plain values`() {
        val s = schema(text("a"), text("b"))
        val r = resolver.resolveStored(s, mapOf("a" to "{{ \$json.name }}", "b" to "plain"), scopeFor("Ravi"))
        assertEquals(mapOf("a" to "Ravi", "b" to "plain"), r.toMap())
    }

    // ---- helpers ----

    private fun ctx(spec: NodeSpec?, params: Map<String, Any?>, input: List<FlowData>): StepContext {
        val st = step("s1", spec?.id ?: "t.x", "S", params)
        return StepContext(
            step = st, flow = Flow(id = "f", name = "F"), input = input, runId = "r", mode = ExecutionMode.Manual, attempt = 1,
            evaluator = StubEvaluator(), http = NullHttpGateway(), credentials = InMemoryCredentials(),
            clock = { 0L }, timezone = "UTC", stepOutputs = emptyMap(), logger = { _, _ -> }, spec = spec,
        )
    }
}
