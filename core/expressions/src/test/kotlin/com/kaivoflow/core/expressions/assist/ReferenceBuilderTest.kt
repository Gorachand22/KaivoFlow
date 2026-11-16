package com.kaivoflow.core.expressions.assist

import com.kaivoflow.core.expressions.KaivoExpressionEvaluator
import com.kaivoflow.core.model.SchemaPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceBuilderTest {
    private fun json(vararg p: String, immediate: Boolean = true, name: String = "Fetch", kind: SourceKind = SourceKind.Json) =
        MapSource("id1", immediate, SchemaPath(p.toList()), kind, name)

    private fun b(s: MapSource) = ReferenceBuilder.build(s, ReferenceTarget.Expression)

    @Test fun identifiersAndNestedPaths() {
        assertEquals("\$json", b(json()))
        assertEquals("\$json.name", b(json("name")))
        assertEquals("\$json.a.b.c", b(json("a", "b", "c")))
        assertEquals("{{ \$json.a }}", ReferenceBuilder.build(json("a")))
    }

    @Test fun oddKeysUseBracketQuoting() {
        assertEquals("\$json[\"first name\"]", b(json("first name")))
        assertEquals("\$json[\"a.b\"].c", b(json("a.b", "c")))
        assertEquals("\$json[\"1st\"]", b(json("1st")))
        assertEquals("\$json[\"null\"]", b(json("null")))
        assertEquals("\$json[\"say \\\"hi\\\"\"]", b(json("say \"hi\"")))
        assertEquals("\$json[\"\"]", b(json("")))
    }

    @Test fun arrayIndices() {
        assertEquals("\$json.rows[0].n", b(json("rows", SchemaPath.ELEMENT, "n")))
        assertEquals("\$json.rows[3].n", b(json("rows", "[3]", "n")))
        assertEquals("\$json[0][0]", b(json(SchemaPath.ELEMENT, SchemaPath.ELEMENT)))
    }

    @Test fun otherStepUsesStepForm() {
        assertEquals("\$step(\"Fetch\")[0].a.b", b(json("a", "b", immediate = false)))
        assertEquals("\$step(\"My \\\"S\\\"\")[0].a", b(json("a", immediate = false, name = "My \"S\"")))
        assertEquals("\$step(\"Fetch\")[2].a", b(json("a", immediate = false).copy(itemIndex = 2)))
    }

    @Test fun binaryRefs() {
        assertEquals("\$binary.photo.fileName", b(json("photo", "fileName", kind = SourceKind.Binary)))
        assertEquals("\$step(\"Fetch\")[0][\"\$binary\"].photo", b(json("photo", immediate = false, kind = SourceKind.Binary)))
    }

    @Test fun itemRunVars() {
        assertEquals("\$item.x", b(json("x", kind = SourceKind.Item)))
        assertEquals("\$run.id", b(json("id", kind = SourceKind.Run)))
        assertEquals("\$vars.token", b(json("token", kind = SourceKind.Vars)))
    }

    @Test fun roundTripIsStable() {
        val sources = listOf(
            json(), json("a"), json("a", "b[]".removeSuffix("[]")), json("first name", "x.y"), json("rows", SchemaPath.ELEMENT, "n"),
            json("a", immediate = false), json("odd key", "k", immediate = false, name = "Weird \\ \"name\""),
            json("photo", "mimeType", kind = SourceKind.Binary), json("photo", immediate = false, kind = SourceKind.Binary),
            json("x", kind = SourceKind.Item), json("id", kind = SourceKind.Run), json("t", kind = SourceKind.Vars),
            json("line\nbreak", "tab\t"), json("üñí", "true"), json("a", "[7]", "b").copy(itemIndex = 4, isImmediateParent = false),
        )
        for (s in sources) {
            val text = ReferenceBuilder.build(s)
            val spans = ReferenceBuilder.parseSpans(text)
            val ref = spans.single() as ExprSpan.Reference
            assertEquals(text, ReferenceBuilder.build(ref.source))
        }
    }

    @Test fun builtReferencesEvaluateToTheValue() {
        val scope = mapOf(
            "json" to mapOf("first name" to "Ada", "a.b" to mapOf("c" to 1.0), "rows" to listOf(mapOf("n" to "x"), mapOf("n" to "y")), "say \"hi\"" to 5.0, "null" to "n"),
            "step" to mapOf("Fetch" to listOf(mapOf("a" to mapOf("b" to "deep")))),
        )
        val ev = KaivoExpressionEvaluator()
        assertEquals("Ada", ev.interpolate(ReferenceBuilder.build(json("first name")), scope))
        assertEquals(1.0, ev.interpolate(ReferenceBuilder.build(json("a.b", "c")), scope))
        assertEquals("x", ev.interpolate(ReferenceBuilder.build(json("rows", SchemaPath.ELEMENT, "n")), scope))
        assertEquals(5.0, ev.interpolate(ReferenceBuilder.build(json("say \"hi\"")), scope))
        assertEquals("n", ev.interpolate(ReferenceBuilder.build(json("null")), scope))
        assertEquals("deep", ev.interpolate(ReferenceBuilder.build(json("a", "b", immediate = false)), scope))
    }

    @Test fun parseSpansMixesLiteralReferenceAndCode() {
        val text = "Hi {{ \$json.name }}, total {{ \$json.qty * 2 }} {{ \$step('A').first.x }}!"
        val spans = ReferenceBuilder.parseSpans(text)
        assertEquals(7, spans.size)
        assertTrue(spans[0] is ExprSpan.Literal)
        val r = spans[1] as ExprSpan.Reference
        assertEquals("\$json.name", r.raw)
        assertEquals("{{ \$json.name }}", text.substring(r.span.start, r.span.end))
        assertTrue(spans[3] is ExprSpan.Code)
        val step = (spans[5] as ExprSpan.Reference).source
        assertEquals("A", step.stepName); assertEquals(false, step.isImmediateParent); assertEquals(listOf("x"), step.path.segments)
    }

    @Test fun unterminatedAndBrokenDoNotCrash() {
        val a = ReferenceBuilder.parseSpans("pre {{ \$json.a")
        assertTrue(a.last() is ExprSpan.Code && (a.last() as ExprSpan.Code).error != null)
        assertTrue(ReferenceBuilder.parseSpans("{{ 1 + }}").single() is ExprSpan.Code)
        assertTrue(ReferenceBuilder.parseSpans("{{ }}").single() is ExprSpan.Code)
        assertTrue(ReferenceBuilder.parseSpans("").isEmpty())
        assertTrue(ReferenceBuilder.parseSpans("{{ \$json?.a }}").single() is ExprSpan.Code)
        assertTrue(ReferenceBuilder.parseSpans("{{ \$json[\$index] }}").single() is ExprSpan.Code)
    }

    @Test fun insertIntoPicksBareOrWrapped() {
        val src = json("a")
        val e1 = ReferenceBuilder.insertInto("hello ", 6, 6, src)
        assertEquals("hello {{ \$json.a }}", e1.text); assertEquals(e1.text.length, e1.caret)
        val e2 = ReferenceBuilder.insertInto("{{ 1 +  }}", 7, 7, src)
        assertEquals("{{ 1 + \$json.a }}", e2.text)
        val e3 = ReferenceBuilder.insertInto("abcdef", 2, 4, src)
        assertEquals("ab{{ \$json.a }}ef", e3.text)
        val e4 = ReferenceBuilder.insertInto("{{ x }}", 7, 7, src)
        assertEquals("{{ x }}{{ \$json.a }}", e4.text)
    }

    @Test fun parseBodyAndReferencesIn() {
        assertEquals(listOf("a"), ReferenceBuilder.parseBody("\$json.a")!!.path.segments)
        assertEquals(null, ReferenceBuilder.parseBody("1 + 2"))
        assertEquals(2, ReferenceBuilder.referencesIn("{{ \$json.a }} {{ \$vars.t }} {{ now() }}").size)
    }
}
