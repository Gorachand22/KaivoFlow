package com.kaivoflow.core.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamValidationTest {

    private fun fx(v: Any?) = ParamValue.Fixed(v)
    private fun ex(t: String) = ParamValue.Expr(t)
    private fun defs(vararg d: ParamDef) = persistentListOf(*d)
    private fun schema(vararg d: ParamDef) = ParamSchema(1, defs(*d))
    private fun text(key: String, show: ShowWhen = ShowWhen.Always, required: Boolean = false, default: ParamValue? = null) =
        ParamDef(key, key, ParamKind.Text(), required = required, show = show, default = default)
    private val opts = OptionSource.Static(persistentListOf(ParamOption("a", "A"), ParamOption("b", "B")))

    private fun codes(s: ParamSchema, v: Map<String, ParamValue?>, ctx: ValidationContext = ValidationContext()) =
        ParamValidator.validate(s, v, ctx).map { it.path.toString() + ":" + it.code }

    private fun vis(rule: ShowWhen, defs: List<ParamDef>, values: Map<String, ParamValue?>, parent: ParamScope? = null) =
        ShowRules.evaluate(rule, ParamScope(defs, values, parent))

    // ---- show rules ----

    @Test fun `sibling path`() {
        val d = listOf(text("mode"))
        assertTrue(vis(ShowWhen.Eq("mode", "x"), d, mapOf("mode" to fx("x"))).visible)
        assertFalse(vis(ShowWhen.Eq("mode", "x"), d, mapOf("mode" to fx("y"))).visible)
        assertFalse(vis(ShowWhen.Eq("mode", "x"), d, emptyMap()).visible)
    }

    @Test fun `controlling default counts when nothing is stored`() {
        val d = listOf(text("mode", default = fx("x")))
        assertTrue(vis(ShowWhen.Eq("mode", "x"), d, emptyMap()).visible)
    }

    @Test fun `parent and root paths`() {
        val root = ParamScope(listOf(text("op"), text("auth")), mapOf("op" to fx("send"), "auth" to fx("key")))
        val mid = root.child(listOf(text("m")), mapOf("m" to fx(1L)))
        val leaf = mid.child(listOf(text("l")), emptyMap())
        assertTrue(ShowRules.evaluate(ShowWhen.Eq("/op", "send"), leaf).visible)
        assertTrue(ShowRules.evaluate(ShowWhen.Eq("../../auth", "key"), leaf).visible)
        assertTrue(ShowRules.evaluate(ShowWhen.Eq("../m", 1.0), leaf).visible)
        assertFalse(ShowRules.evaluate(ShowWhen.Eq("../../auth", "other"), leaf).visible)
        assertFalse(ShowRules.evaluate(ShowWhen.Eq("../auth", "key"), leaf).visible)
    }

    @Test fun `dotted tail reads into an object`() {
        val d = listOf(ParamDef("body", "b", ParamKind.Json()))
        assertTrue(vis(ShowWhen.Eq("body.kind", "raw"), d, mapOf("body" to fx(mapOf("kind" to "raw")))).visible)
    }

    @Test fun `all operators`() {
        val d = listOf(text("n"), text("s"))
        val v = mapOf("n" to fx(5L), "s" to fx(""))
        assertTrue(vis(ShowWhen.Ne("n", 4L), d, v).visible)
        assertFalse(vis(ShowWhen.Ne("n", 5L), d, v).visible)
        assertTrue(vis(ShowWhen.In("n", listOf(1L, 5L)), d, v).visible)
        assertFalse(vis(ShowWhen.In("n", listOf(1L)), d, v).visible)
        assertTrue(vis(ShowWhen.Cmp("n", CmpOp.Gt, 4.0), d, v).visible)
        assertFalse(vis(ShowWhen.Cmp("n", CmpOp.Lt, 5.0), d, v).visible)
        assertTrue(vis(ShowWhen.Cmp("n", CmpOp.Le, 5.0), d, v).visible)
        assertTrue(vis(ShowWhen.Cmp("n", CmpOp.Ge, 5.0), d, v).visible)
        assertTrue(vis(ShowWhen.Exists("n"), d, v).visible)
        assertFalse(vis(ShowWhen.Exists("zzz"), d, v).visible)
        assertTrue(vis(ShowWhen.Empty("s"), d, v).visible)
        assertFalse(vis(ShowWhen.Empty("n"), d, v).visible)
        assertTrue(vis(ShowWhen.All(listOf(ShowWhen.Exists("n"), ShowWhen.Empty("s"))), d, v).visible)
        assertFalse(vis(ShowWhen.All(listOf(ShowWhen.Exists("n"), ShowWhen.Exists("q"))), d, v).visible)
        assertTrue(vis(ShowWhen.Any(listOf(ShowWhen.Exists("q"), ShowWhen.Exists("n"))), d, v).visible)
        assertFalse(vis(ShowWhen.Not(ShowWhen.Exists("n")), d, v).visible)
        val scope = ParamScope(d, v, null, setOf("main"))
        assertTrue(ShowRules.evaluate(ShowWhen.CredentialSet("main"), scope).visible)
        assertFalse(ShowRules.evaluate(ShowWhen.CredentialSet("other"), scope).visible)
    }

    @Test fun `expression controlled rule renders visible with a flag`() {
        val d = listOf(text("mode"))
        val r = vis(ShowWhen.Eq("mode", "x"), d, mapOf("mode" to ex("{{ \$json.m }}")))
        assertTrue(r.visible); assertTrue(r.dependsOnExpr)
        // Not of unknown stays unknown, so it also stays visible.
        val n = vis(ShowWhen.Not(ShowWhen.Eq("mode", "x")), d, mapOf("mode" to ex("{{ 1 }}")))
        assertTrue(n.visible && n.dependsOnExpr)
        // A known false still hides the field in an All.
        val all = vis(ShowWhen.All(listOf(ShowWhen.Eq("mode", "x"), ShowWhen.Eq("other", "y"))), d + text("other"), mapOf("mode" to ex("{{ 1 }}"), "other" to fx("z")))
        assertFalse(all.visible); assertFalse(all.dependsOnExpr)
        // A literal never sets the flag.
        assertFalse(vis(ShowWhen.Eq("mode", "x"), d, mapOf("mode" to fx("x"))).dependsOnExpr)
    }

    // ---- validator ----

    @Test fun `hidden required params give no issue and visible ones do`() {
        val s = schema(text("mode"), text("url", show = ShowWhen.Eq("mode", "http"), required = true))
        assertEquals(emptyList<String>(), codes(s, mapOf("mode" to fx("file"))))
        assertEquals(listOf("url:required.missing"), codes(s, mapOf("mode" to fx("http"))))
        // dependsOnExpr fields stay visible, so required still applies.
        assertEquals(listOf("url:required.missing"), codes(s, mapOf("mode" to ex("{{ 1 }}"))))
    }

    @Test fun `required treats zero and false as present`() {
        val s = schema(
            ParamDef("n", "N", ParamKind.Number(), required = true),
            ParamDef("b", "B", ParamKind.Bool, required = true),
            text("t", required = true),
        )
        assertEquals(listOf("t:required.missing"), codes(s, mapOf("n" to fx(0L), "b" to fx(false), "t" to fx("  "))))
    }

    @Test fun `text rules`() {
        val s = schema(ParamDef("t", "T", ParamKind.Text(), rules = persistentListOf(
            ParamRule.MinLength(3), ParamRule.MaxLength(5), ParamRule.Pattern("[a-z]+"))))
        assertEquals(listOf("t:length.min", "t:pattern.mismatch"), codes(s, mapOf("t" to fx("A1"))))
        assertEquals(listOf("t:length.max"), codes(s, mapOf("t" to fx("abcdefg"))))
        assertEquals(emptyList<String>(), codes(s, mapOf("t" to fx("abcd"))))
        assertEquals(listOf("t:pattern.bad"), codes(schema(ParamDef("t", "T", ParamKind.Text(), rules = persistentListOf(ParamRule.Pattern("(")))), mapOf("t" to fx("x"))))
    }

    @Test fun `text formats`() {
        fun s(f: TextFormat) = schema(ParamDef("t", "T", ParamKind.Text(format = f)))
        assertEquals(emptyList<String>(), codes(s(TextFormat.Url), mapOf("t" to fx("https://a.b/c?d=1"))))
        assertEquals(listOf("t:url.invalid"), codes(s(TextFormat.Url), mapOf("t" to fx("not a url"))))
        assertEquals(emptyList<String>(), codes(s(TextFormat.Url), mapOf("t" to fx("{{ \$json.u }}/x"))))
        assertEquals(listOf("t:email.invalid"), codes(s(TextFormat.Email), mapOf("t" to fx("a@b"))))
        assertEquals(emptyList<String>(), codes(s(TextFormat.Email), mapOf("t" to fx("a@b.co"))))
        assertEquals(listOf("t:phone.invalid"), codes(s(TextFormat.Phone), mapOf("t" to fx("abc"))))
        assertEquals(listOf("t:url.invalid"), codes(schema(ParamDef("t", "T", ParamKind.Text(), rules = persistentListOf(ParamRule.UrlValid))), mapOf("t" to fx("nope"))))
        assertEquals(listOf("t:type.text"), codes(s(TextFormat.Plain), mapOf("t" to fx(listOf(1L)))))
    }

    @Test fun `number type and bounds`() {
        val s = schema(ParamDef("n", "N", ParamKind.Number(min = 0.0, max = 10.0, step = 2.0, integer = true)))
        assertEquals(emptyList<String>(), codes(s, mapOf("n" to fx(4L))))
        assertEquals(emptyList<String>(), codes(s, mapOf("n" to fx("6"))))
        assertEquals(listOf("n:type.number"), codes(s, mapOf("n" to fx("abc"))))
        assertEquals(listOf("n:type.number"), codes(s, mapOf("n" to fx(true))))
        assertEquals(listOf("n:number.integer", "n:number.step"), codes(s, mapOf("n" to fx(3.5))))
        assertEquals(listOf("n:number.min"), codes(s, mapOf("n" to fx(-2L))))
        assertEquals(listOf("n:number.max"), codes(s, mapOf("n" to fx(12L))))
        assertEquals(listOf("n:number.step"), codes(s, mapOf("n" to fx(5L))))
        val r = schema(ParamDef("n", "N", ParamKind.Number(), rules = persistentListOf(ParamRule.Min(1.0), ParamRule.Max(3.0))))
        assertEquals(listOf("n:number.min"), codes(r, mapOf("n" to fx(0L))))
        assertEquals(listOf("n:number.max"), codes(r, mapOf("n" to fx(4L))))
    }

    @Test fun `boolean`() {
        val s = schema(ParamDef("b", "B", ParamKind.Bool))
        assertEquals(emptyList<String>(), codes(s, mapOf("b" to fx("TRUE"))))
        assertEquals(listOf("b:type.bool"), codes(s, mapOf("b" to fx("yes"))))
        assertEquals(listOf("b:type.bool"), codes(s, mapOf("b" to fx(1L))))
    }

    @Test fun `choice membership and stale values`() {
        val s = schema(ParamDef("c", "C", ParamKind.Choice(opts)))
        assertEquals(emptyList<String>(), codes(s, mapOf("c" to fx("a"))))
        assertEquals(listOf("c:choice.unknown"), codes(s, mapOf("c" to fx("gone"))))
        val dyn = schema(ParamDef("c", "C", ParamKind.Choice(OptionSource.Dynamic("loader"))))
        assertEquals(emptyList<String>(), codes(dyn, mapOf("c" to fx("anything"))))
        val rule = schema(ParamDef("c", "C", ParamKind.Text(), rules = persistentListOf(ParamRule.OneOf(listOf("x", "y")))))
        assertEquals(listOf("c:choice.unknown"), codes(rule, mapOf("c" to fx("z"))))
    }

    @Test fun `multi choice`() {
        val s = schema(ParamDef("m", "M", ParamKind.MultiChoice(opts, min = 1, max = 2)))
        assertEquals(emptyList<String>(), codes(s, mapOf("m" to fx(listOf("a")))))
        assertEquals(listOf("m:choice.unknown"), codes(s, mapOf("m" to fx(listOf("a", "zz")))))
        assertEquals(listOf("m:items.max"), codes(s, mapOf("m" to fx(listOf("a", "b", "a")))))
        assertEquals(listOf("m:type.list"), codes(s, mapOf("m" to fx("a"))))
    }

    @Test fun `date time`() {
        fun s(m: DateTimeMode) = schema(ParamDef("d", "D", ParamKind.DateTime(m)))
        assertEquals(emptyList<String>(), codes(s(DateTimeMode.DateTime), mapOf("d" to fx("2026-11-16T10:00:00+05:30"))))
        assertEquals(emptyList<String>(), codes(s(DateTimeMode.Date), mapOf("d" to fx("2026-11-16"))))
        assertEquals(emptyList<String>(), codes(s(DateTimeMode.Time), mapOf("d" to fx("10:30"))))
        assertEquals(listOf("d:type.datetime"), codes(s(DateTimeMode.Date), mapOf("d" to fx("16/11/2026"))))
        assertEquals(listOf("d:type.datetime"), codes(s(DateTimeMode.DateTime), mapOf("d" to fx("soon"))))
    }

    @Test fun `json text, shape and templates`() {
        val s = schema(ParamDef("j", "J", ParamKind.Json(JsonShape.Object)))
        assertEquals(emptyList<String>(), codes(s, mapOf("j" to fx("""{"a": 1}"""))))
        assertEquals(emptyList<String>(), codes(s, mapOf("j" to fx(mapOf("a" to 1L)))))
        assertEquals(emptyList<String>(), codes(s, mapOf("j" to fx("""{"id": "{{ ${'$'}json.id }}"}"""))))
        assertEquals(listOf("j:json.invalid"), codes(s, mapOf("j" to fx("{\"a\": }"))))
        assertEquals(listOf("j:json.shape"), codes(s, mapOf("j" to fx("[1]"))))
        val msg = ParamValidator.validate(s, mapOf("j" to fx("{\n\"a\": }"))).single().message
        assertTrue(msg, msg.contains("line 2"))
        val r = schema(ParamDef("j", "J", ParamKind.Text(), rules = persistentListOf(ParamRule.JsonValid)))
        assertEquals(listOf("j:json.invalid"), codes(r, mapOf("j" to fx("{"))))
    }

    @Test fun `code size and json language`() {
        val s = schema(ParamDef("c", "C", ParamKind.Code(CodeLanguage.Json), rules = persistentListOf(ParamRule.MaxLength(5))))
        assertEquals(listOf("c:json.invalid", "c:length.max"), codes(s, mapOf("c" to fx("{broken"))))
        assertEquals(listOf("c:type.text"), codes(s, mapOf("c" to fx(1L))))
    }

    @Test fun `color`() {
        val s = schema(ParamDef("c", "C", ParamKind.Color))
        assertEquals(emptyList<String>(), codes(s, mapOf("c" to fx("#C8A24A"))))
        assertEquals(emptyList<String>(), codes(s, mapOf("c" to fx("#fff"))))
        assertEquals(listOf("c:type.color"), codes(s, mapOf("c" to fx("gold"))))
    }

    @Test fun `collection flags unknown keys and checks present children only`() {
        val k = ParamKind.Collection(defs(text("a", required = true), ParamDef("n", "N", ParamKind.Number())))
        val s = schema(ParamDef("c", "C", k))
        assertEquals(emptyList<String>(), codes(s, mapOf("c" to fx(mapOf("n" to 1L)))))
        assertEquals(listOf("c.n:type.number"), codes(s, mapOf("c" to fx(mapOf("n" to "x")))))
        val unknown = ParamValidator.validate(s, mapOf("c" to fx(mapOf("zz" to 1L))))
        assertEquals("c.zz", unknown.single().path.toString())
        assertEquals(ParamSeverity.Warning, unknown.single().severity)
    }

    @Test fun `group and repeater children use their own rules and paths`() {
        val s = schema(
            ParamDef("g", "G", ParamKind.Group(defs(text("a", required = true)))),
            ParamDef("r", "R", ParamKind.Repeater(defs(text("name", required = true)), minItems = 1, maxItems = 2)),
        )
        assertEquals(listOf("g.a:required.missing"), codes(s, mapOf("g" to fx(mapOf<String, Any?>()), "r" to fx(listOf(mapOf("name" to "x"))))))
        assertEquals(listOf("r[1].name:required.missing"), codes(s, mapOf("g" to fx(mapOf("a" to "x")), "r" to fx(listOf(mapOf("name" to "x"), mapOf("name" to ""))))))
        assertEquals(listOf("r:items.max"), codes(s, mapOf("g" to fx(mapOf("a" to "x")), "r" to fx(List(3) { mapOf("name" to "x") }))))
        assertEquals(listOf("r:items.min"), codes(s, mapOf("g" to fx(mapOf("a" to "x")), "r" to fx(emptyList<Any?>()))))
        assertEquals(listOf("r[0]:type.object"), codes(s, mapOf("g" to fx(mapOf("a" to "x")), "r" to fx(listOf("bad")))))
    }

    @Test fun `child show rules resolve against the parent`() {
        val k = ParamKind.Group(defs(text("secret", show = ShowWhen.Eq("../mode", "on"), required = true)))
        val s = schema(text("mode"), ParamDef("g", "G", k))
        assertEquals(emptyList<String>(), codes(s, mapOf("mode" to fx("off"), "g" to fx(mapOf<String, Any?>()))))
        assertEquals(listOf("g.secret:required.missing"), codes(s, mapOf("mode" to fx("on"), "g" to fx(mapOf<String, Any?>()))))
    }

    @Test fun `key value list`() {
        val s = schema(ParamDef("h", "H", ParamKind.KeyValueList))
        assertEquals(emptyList<String>(), codes(s, mapOf("h" to fx(mapOf("A" to "1")))))
        assertEquals(emptyList<String>(), codes(s, mapOf("h" to fx(listOf(mapOf("name" to "A", "value" to "1"))))))
        assertEquals(listOf("h[0]:keyvalue.name"), codes(s, mapOf("h" to fx(listOf(mapOf("name" to "", "value" to "1"))))))
        assertEquals(listOf("h:type.object"), codes(s, mapOf("h" to fx("x"))))
    }

    @Test fun `assignments`() {
        val s = schema(ParamDef("a", "A", ParamKind.Assignments()))
        fun row(n: String, t: String, v: Any?) = mapOf("name" to n, "type" to t, "value" to v)
        assertEquals(emptyList<String>(), codes(s, mapOf("a" to fx(listOf(row("x", "number", 1L), row("a.b", "text", "s"), row("e", "number", "{{ 1 }}"))))))
        assertEquals(listOf("a[1].name:assignment.name_duplicate"), codes(s, mapOf("a" to fx(listOf(row("x", "text", "a"), row("x", "text", "b"))))))
        assertEquals(listOf("a[0].name:assignment.name_empty"), codes(s, mapOf("a" to fx(listOf(row("", "text", "a"))))))
        assertEquals(listOf("a[0].value:assignment.type_mismatch"), codes(s, mapOf("a" to fx(listOf(row("x", "number", "abc"))))))
        assertEquals(listOf("a[0].type:assignment.type_unknown"), codes(s, mapOf("a" to fx(listOf(row("x", "weird", "a"))))))
        assertEquals(listOf("a:type.list"), codes(s, mapOf("a" to fx("no"))))
    }

    @Test fun `conditions`() {
        val s = schema(ParamDef("c", "C", ParamKind.Conditions(), required = true))
        fun cond(op: String, right: Any? = "x", left: Any? = "a") = mapOf("id" to "1", "left" to left, "op" to op, "right" to right)
        fun g(vararg c: Map<String, Any?>) = fx(mapOf("combinator" to "and", "conditions" to c.toList()))
        assertEquals(emptyList<String>(), codes(s, mapOf("c" to g(cond("is"), cond("isEmpty", null)))))
        assertEquals(listOf("c.conditions:required.missing"), codes(s, mapOf("c" to g())))
        assertEquals(listOf("c.conditions[0].right:conditions.right"), codes(s, mapOf("c" to g(cond("is", "")))))
        assertEquals(listOf("c.conditions[0].op:conditions.operator"), codes(s, mapOf("c" to g(cond("weird")))))
        assertEquals(listOf("c.conditions[0].left:conditions.left"), codes(s, mapOf("c" to g(cond("is", "x", "")))))
        assertEquals(listOf("c.conditions[0].right:pattern.bad"), codes(s, mapOf("c" to g(cond("matches", "(")))))
        assertEquals(listOf("c.combinator:conditions.combinator"), codes(s, mapOf("c" to fx(mapOf("combinator" to "xor", "conditions" to listOf(cond("is")))))))
    }

    @Test fun `nested condition groups validate up to the widget depth`() {
        val s = schema(ParamDef("c", "C", ParamKind.Conditions(), required = true))
        fun rule(op: String = "is", left: Any? = "a", right: Any? = "x") = mapOf("id" to "r", "left" to left, "op" to op, "right" to right)
        fun grp(vararg c: Map<String, Any?>) = mapOf("id" to "g", "combinator" to "or", "conditions" to c.toList())
        val d2 = mapOf("combinator" to "and", "conditions" to listOf(rule(), grp(rule(), rule("isEmpty", right = null))))
        assertEquals(emptyList<String>(), codes(s, mapOf("c" to fx(d2))))
        val d3 = mapOf("combinator" to "and", "conditions" to listOf(grp(grp(rule()))))
        assertEquals(emptyList<String>(), codes(s, mapOf("c" to fx(d3))))
        val d4 = mapOf("combinator" to "and", "conditions" to listOf(grp(grp(grp(rule())))))
        assertEquals(listOf("c.conditions[0].conditions[0].conditions[0]:conditions.depth"), codes(s, mapOf("c" to fx(d4))))
        val bad = mapOf("combinator" to "and", "conditions" to listOf(grp(rule("weird"))))
        assertEquals(listOf("c.conditions[0].conditions[0].op:conditions.operator"), codes(s, mapOf("c" to fx(bad))))
        val emptyNested = mapOf("combinator" to "and", "conditions" to listOf(rule(), grp()))
        assertEquals(emptyList<String>(), codes(s, mapOf("c" to fx(emptyNested))))
    }

    @Test fun `resource picker`() {
        val k = ParamKind.ResourcePick(persistentSetOf(ResourceMode.ByUrl, ResourceMode.ById), null, "/d/[A-Za-z0-9]+")
        val s = schema(ParamDef("r", "R", k))
        assertEquals(emptyList<String>(), codes(s, mapOf("r" to fx(mapOf("mode" to "byId", "value" to "abc")))))
        assertEquals(emptyList<String>(), codes(s, mapOf("r" to fx(mapOf("mode" to "byUrl", "value" to "https://x/d/abc1")))))
        assertEquals(listOf("r.value:resource.url"), codes(s, mapOf("r" to fx(mapOf("mode" to "byUrl", "value" to "https://x/y")))))
        assertEquals(listOf("r.value:required.missing"), codes(s, mapOf("r" to fx(mapOf("mode" to "byId", "value" to " ")))))
        assertEquals(listOf("r.mode:resource.mode"), codes(s, mapOf("r" to fx(mapOf("mode" to "fromList", "value" to "x")))))
    }

    @Test fun `credential picker secret and file reference never take expressions`() {
        val s = schema(
            ParamDef("c", "C", ParamKind.CredentialPick(), expr = ExprMode.Never),
            ParamDef("s", "S", ParamKind.Secret, expr = ExprMode.Never),
            ParamDef("k", "K", ParamKind.Code(), expr = ExprMode.Never),
        )
        assertEquals(listOf("c:expr.not_allowed", "s:expr.not_allowed", "k:expr.not_allowed"),
            codes(s, mapOf("c" to ex("{{ 1 }}"), "s" to ex("{{ 1 }}"), "k" to ex("{{ 1 }}"))))
        assertEquals(listOf("c:credential.missing"), codes(schema(ParamDef("c", "C", ParamKind.CredentialPick(), required = true)), mapOf("c" to fx("   "))).take(1).map { it.replace("required.missing", "credential.missing") })
        val f = schema(ParamDef("f", "F", ParamKind.FileRef()))
        assertEquals(emptyList<String>(), codes(f, mapOf("f" to fx(mapOf("mode" to "data", "property" to "photo")))))
        assertEquals(listOf("f.property:required.missing"), codes(f, mapOf("f" to fx(mapOf("mode" to "data", "property" to "")))))
        assertEquals(listOf("f.uri:expr.not_allowed"), codes(f, mapOf("f" to fx(mapOf("mode" to "device", "uri" to "{{ 1 }}")))))
        assertEquals(listOf("f.mode:file.mode"), codes(f, mapOf("f" to fx(mapOf("mode" to "weird")))))
    }

    @Test fun `credential slots follow their show rules`() {
        val s = ParamSchema(1, defs(text("auth")), persistentListOf(
            CredentialSlot("main", "Account", show = ShowWhen.Eq("auth", "key")),
            CredentialSlot("opt", "Extra", required = false),
        ))
        assertEquals(emptyList<String>(), codes(s, mapOf("auth" to fx("none"))))
        assertEquals(listOf("main:credential.missing"), codes(s, mapOf("auth" to fx("key"))))
        assertEquals(emptyList<String>(), codes(s, mapOf("auth" to fx("key")), ValidationContext(credentialsFilled = setOf("main"))))
    }

    @Test fun `expressions are syntax checked at design time and skipped at run`() {
        val s = schema(text("t"))
        val check: (String) -> String? = { if (it.contains("((")) "unbalanced" else null }
        val design = ValidationContext(exprSyntax = check)
        assertEquals(listOf("t:expr.syntax"), codes(s, mapOf("t" to ex("{{ (( }}")), design))
        assertEquals(emptyList<String>(), codes(s, mapOf("t" to ex("{{ 1 }}")), design))
        assertEquals(emptyList<String>(), codes(s, mapOf("t" to ex("{{ (( }}")), ValidationContext(ValidationPhase.Run, exprSyntax = check)))
        assertEquals(listOf("t:required.missing"), codes(schema(text("t", required = true)), mapOf("t" to ex(" "))))
    }

    @Test fun `custom rules are registered by id`() {
        val s = schema(ParamDef("t", "T", ParamKind.Text(), rules = persistentListOf(ParamRule.Custom("even"))))
        val ctx = ValidationContext(custom = mapOf("even" to { v: Any? -> if (v.toString().length % 2 == 0) null else "odd length" }))
        assertEquals(emptyList<String>(), codes(s, mapOf("t" to fx("ab")), ctx))
        assertEquals(listOf("t:custom.even"), codes(s, mapOf("t" to fx("abc")), ctx))
        assertEquals(listOf("t:custom.unregistered"), codes(s, mapOf("t" to fx("abc"))))
    }

    @Test fun `defaults satisfy required and notices are ignored`() {
        val s = schema(text("t", required = true, default = fx("d")), ParamDef("n", "N", ParamKind.Notice(NoticeTone.Info), required = true))
        assertEquals(emptyList<String>(), codes(s, emptyMap()))
    }

    @Test fun `paths render with keys and indexes`() {
        assertEquals("a.b[2].c", ParamPath.of("a", "b").index(2).child("c").toString())
        assertEquals("", ParamPath.Root.toString())
    }

    @Test fun `validator runs on a spec converted from every param type without throwing`() {
        val spec = NodeSpec("x.y", "X", StepCategory.Transform, "s", "i", params = persistentListOf(
            *ParamType.entries.map { ParamSpec(it.name, it.name, it, required = true, options = persistentListOf(ParamOption("a", "A"))) }.toTypedArray()))
        val schema = spec.toSchema()
        val weird = listOf<Any?>(null, "", "x", 1L, 2.5, true, listOf(1L), mapOf("a" to 1L), mapOf("kx" to "y"))
        for (w in weird) ParamValidator.validate(schema, schema.params.associate { it.key to fx(w) })
        ParamValidator.validate(schema, schema.params.associate { it.key to ex("{{ 1 }}") })
    }
}
