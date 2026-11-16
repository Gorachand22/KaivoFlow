package com.kaivoflow.core.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamSchemaTest {

    private val opts = OptionSource.Static(persistentListOf(ParamOption("a", "A"), ParamOption("b", "B")))
    private val textDef = ParamDef("name", "Name", ParamKind.Text())

    /** One sample stored value per kind, written the way the inspector will write it. */
    private val samples: List<Triple<String, ParamKind, Any?>> = listOf(
        Triple("text", ParamKind.Text(multiline = true, format = TextFormat.Url), "https://example.org"),
        Triple("number", ParamKind.Number(min = 0.0, max = 10.0, step = 0.5, integer = false, unit = "s"), 4.5),
        Triple("bool", ParamKind.Bool, true),
        Triple("choice", ParamKind.Choice(opts, searchable = true), "a"),
        Triple("multi", ParamKind.MultiChoice(opts, min = 1, max = 2), listOf("a", "b")),
        Triple("date", ParamKind.DateTime(DateTimeMode.Date), "2026-11-16"),
        Triple("json", ParamKind.Json(JsonShape.Object), mapOf("a" to listOf(1L, 2L), "b" to mapOf("c" to null))),
        Triple("code", ParamKind.Code(CodeLanguage.JavaScript), "return items;"),
        Triple("color", ParamKind.Color, "#C8A24A"),
        Triple("collection", ParamKind.Collection(persistentListOf(textDef)), mapOf("name" to "x")),
        Triple("group", ParamKind.Group(persistentListOf(textDef)), mapOf("name" to "x")),
        Triple("repeater", ParamKind.Repeater(persistentListOf(textDef), minItems = 1, maxItems = 3, itemLabelKey = "name"),
            listOf(mapOf("name" to "x"), mapOf("name" to "y"))),
        Triple("assignments", ParamKind.Assignments(), listOf(mapOf("name" to "total", "type" to "number", "value" to 3L))),
        Triple("conditions", ParamKind.Conditions(), mapOf("combinator" to "and", "conditions" to listOf(mapOf("id" to "1", "left" to "a", "op" to "is", "right" to "b")))),
        Triple("resource", ParamKind.ResourcePick(persistentSetOf(ResourceMode.ById), "sheets", "/d/"), mapOf("mode" to "byId", "value" to "123")),
        Triple("credential", ParamKind.CredentialPick(persistentSetOf(CredentialKind.ApiKey)), "cred-1"),
        Triple("notice", ParamKind.Notice(NoticeTone.Warning, NoticeAction("Open", "open")), null),
        Triple("secret", ParamKind.Secret, "tok-123"),
        Triple("file", ParamKind.FileRef(persistentSetOf("image/*"), output = true), mapOf("mode" to "data", "property" to "photo")),
        Triple("keyvalue", ParamKind.KeyValueList, mapOf("Accept" to "application/json")),
    )

    @Test fun `every kind is covered`() {
        assertEquals(20, samples.map { it.second::class }.toSet().size)
    }

    @Test fun `a schema holding every kind builds and keeps its params in order`() {
        val schema = ParamSchema(1, samples.map { (k, kind, _) ->
            ParamDef(k, k, kind, required = true, default = null, hint = "h", placeholder = "p", expr = ExprMode.Allowed,
                rules = persistentListOf(ParamRule.Required), show = ShowWhen.Always, advanced = true, group = "g", docs = "d")
        }.let { persistentListOf(*it.toTypedArray()) })
        assertEquals(samples.map { it.first }, schema.params.map { it.key })
        assertEquals(textDef, ParamSchema(1, persistentListOf(textDef)).find("name"))
        assertNull(schema.find("nope"))
    }

    @Test fun `fixed value round trips through json text for every kind`() {
        for ((name, _, value) in samples) {
            if (value == null) continue
            val text = ParamValueCodec.toJson(ParamValue.Fixed(value))
            val back = ParamValueCodec.fromJson(text)
            assertEquals("kind $name", ParamValue.Fixed(value), back)
            assertEquals("kind $name stable", text, ParamValueCodec.toJson(back))
        }
    }

    @Test fun `expression round trips as a kx object`() {
        val e = ParamValue.Expr("{{ \$json.id }}-x")
        val text = ParamValueCodec.toJson(e)
        assertEquals("""{"kx":"{{ ${'$'}json.id }}-x"}""", text)
        assertEquals(e, ParamValueCodec.fromJson(text))
    }

    @Test fun `legacy strings with braces become expressions and others stay fixed`() {
        assertEquals(ParamValue.Expr("Hi {{ \$json.n }}"), ParamValueCodec.decodeLegacy("Hi {{ \$json.n }}"))
        assertEquals(ParamValue.Fixed("plain"), ParamValueCodec.decodeLegacy("plain"))
        assertEquals(ParamValue.Fixed(""), ParamValueCodec.decodeLegacy(""))
        assertEquals(ParamValue.Fixed(5.0), ParamValueCodec.decodeLegacy(5.0))
        assertEquals(ParamValue.Fixed(false), ParamValueCodec.decodeLegacy(false))
        assertNull(ParamValueCodec.decodeLegacy(null))
        val all = ParamValueCodec.decodeAllLegacy(mapOf("a" to "x", "b" to "{{ 1 }}", "c" to null))
        assertEquals(mapOf("a" to ParamValue.Fixed("x"), "b" to ParamValue.Expr("{{ 1 }}")), all)
    }

    @Test fun `current reader never infers an expression from text`() {
        assertEquals(ParamValue.Fixed("{{ literal }}"), ParamValueCodec.decode("{{ literal }}"))
    }

    @Test fun `a literal object with a kx key inside a json param is not misread`() {
        // Only key: written escaped, read back as the same fixed object.
        val only = ParamValue.Fixed(mapOf("kx" to "not an expression"))
        val text = ParamValueCodec.toJson(only)
        assertEquals("""{"kxLiteral":{"kx":"not an expression"}}""", text)
        assertEquals(only, ParamValueCodec.fromJson(text))
        // The escape key itself is escaped too.
        val esc = ParamValue.Fixed(mapOf("kxLiteral" to 1L))
        assertEquals(esc, ParamValueCodec.fromJson(ParamValueCodec.toJson(esc)))
        // With other keys it is not ambiguous and is written as is.
        val more = ParamValue.Fixed(mapOf("kx" to "a", "y" to 1L))
        assertEquals("""{"kx":"a","y":1}""", ParamValueCodec.toJson(more))
        assertEquals(more, ParamValueCodec.fromJson(ParamValueCodec.toJson(more)))
        // Legacy data never contains markers: a plain map with kx stays fixed.
        assertEquals(ParamValue.Fixed(mapOf("kx" to "a")), ParamValueCodec.decodeLegacy(mapOf("kx" to "a")))
    }

    @Test fun `every bundled template re-saves byte stable`() {
        for (t in FlowTemplates.all) {
            for (step in t.build().steps) {
                val first = ParamValueCodec.encodeAll(ParamValueCodec.decodeAllLegacy(step.params))
                val text1 = ParamJson.write(first)
                val reread = ParamValueCodec.decodeAll(ParamJson.parseOrThrow(text1) as Map<String, Any?>)
                val text2 = ParamJson.write(ParamValueCodec.encodeAll(reread))
                assertEquals("${t.id}/${step.name}", text1, text2)
                // And the expression/fixed split is the legacy rule.
                step.params.forEach { (k, v) ->
                    val expected = if (v is String && v.contains("{{")) ParamValue.Expr(v) else ParamValue.Fixed(v)
                    if (expected is ParamValue.Fixed && expected.value is Number) {
                        assertEquals(expected.value.toDouble(), ((reread[k] as ParamValue.Fixed).value as Number).toDouble(), 0.0)
                    } else assertEquals(expected, reread[k])
                }
            }
        }
    }

    @Test fun `json reader reports line and column`() {
        val r = ParamJson.parse("{\n  \"a\": 1,\n  \"b\": }") as ParamJson.Parsed.Failed
        assertEquals(3, r.error.line)
        assertTrue(r.error.column > 1)
        assertThrows(IllegalArgumentException::class.java) { ParamJson.parseOrThrow("[1,") }
        assertNull(ParamJson.checkTemplate("""{"id": "{{ ${'$'}json.id }}", "n": {{ 1 + 2 }}}"""))
        assertEquals(ParamJson.Parsed.Ok(listOf(1L, 2.5, "x\ny", null, true)), ParamJson.parse("""[1, 2.5, "x\ny", null, true]"""))
    }

    // ---- ParamSpec.toDef() ----

    @Test fun `ParamSpec maps every type to a kind`() {
        val expect = mapOf(
            ParamType.Text to ParamKind.Text(),
            ParamType.Multiline to ParamKind.Text(multiline = true),
            ParamType.Number to ParamKind.Number(),
            ParamType.Boolean to ParamKind.Bool,
            ParamType.Json to ParamKind.Json(),
            ParamType.Url to ParamKind.Text(format = TextFormat.Url),
            ParamType.Secret to ParamKind.Secret,
            ParamType.KeyValue to ParamKind.KeyValueList,
        )
        for ((type, kind) in expect) assertEquals(type.name, kind, ParamSpec("k", "K", type).toDef().kind)
        assertTrue(ParamSpec("k", "K", ParamType.Code).toDef().kind is ParamKind.Code)
        val sel = ParamSpec("k", "K", ParamType.Select, options = persistentListOf(ParamOption("x", "X"))).toDef().kind as ParamKind.Choice
        assertEquals("x", (sel.options as OptionSource.Static).options.single().value)
        assertEquals(ExprMode.Only, ParamSpec("k", "K", ParamType.Expression).toDef().expr)
    }

    @Test fun `secret and code are never expression capable and plain fields are`() {
        assertEquals(ExprMode.Never, ParamSpec("k", "K", ParamType.Secret).toDef().expr)
        assertEquals(ExprMode.Never, ParamSpec("k", "K", ParamType.Code).toDef().expr)
        assertEquals(ExprMode.Allowed, ParamSpec("k", "K", ParamType.Text).toDef().expr)
        assertEquals(ExprMode.Never, ParamSpec("k", "K", ParamType.Text, supportsExpression = false).toDef().expr)
    }

    @Test fun `ParamSpec fields carry over`() {
        val d = ParamSpec("k", "Key", ParamType.Text, required = true, default = "x", placeholder = "ph", hint = "hn", advanced = true).toDef()
        assertEquals("Key", d.label); assertTrue(d.required); assertTrue(d.advanced)
        assertEquals(ParamValue.Fixed("x"), d.default)
        assertEquals("ph", d.placeholder); assertEquals("hn", d.hint)
        assertNull(ParamSpec("k", "Key", ParamType.Text).toDef().hint)
        assertEquals(ParamValue.Expr("{{ 1 }}"), ParamSpec("k", "K", ParamType.Text, default = "{{ 1 }}").toDef().default)
    }

    @Test fun `showWhen maps to In rules with the any-match semantics of today`() {
        assertEquals(ShowWhen.Always, ParamSpec("k", "K", ParamType.Text).toDef().show)
        assertEquals(ShowWhen.In("m", listOf("A")), ParamSpec("k", "K", ParamType.Text, showWhen = mapOf("m" to listOf("A"))).toDef().show)
        val two = ParamSpec("k", "K", ParamType.Text, showWhen = mapOf("m" to listOf("A"), "n" to listOf("B"))).toDef().show
        assertTrue(two is ShowWhen.Any)
    }

    @Test fun `whole catalog spec converts and secret defaults stay verbatim`() {
        val spec = NodeSpec("x.y", "X", StepCategory.Transform, "s", "i",
            params = persistentListOf(ParamSpec("a", "A", ParamType.Text), ParamSpec("b", "B", ParamType.Secret)))
        val schema = spec.toSchema()
        assertEquals(listOf("a", "b"), schema.params.map { it.key })
        assertFalse(schema.params.any { it.kind is ParamKind.Notice })
    }
}
