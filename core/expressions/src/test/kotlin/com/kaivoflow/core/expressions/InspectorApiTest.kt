package com.kaivoflow.core.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorApiTest {
    @Test fun parseSplitsTemplateIntoSegmentsWithSpans() {
        val text = "Hi {{ \$json.name }}, total {{ \$json.qty * 2 }}!"
        val r = parse(text)
        assertTrue(r.ok)
        assertEquals(5, r.segments.size)
        val t0 = r.segments[0] as Segment.Text
        assertEquals("Hi ", t0.text)
        assertEquals(Span(0, 3), t0.span)
        val c1 = r.segments[1] as Segment.Code
        assertEquals("{{ \$json.name }}", text.substring(c1.span.start, c1.span.end))
        assertEquals(" \$json.name ", text.substring(c1.bodySpan.start, c1.bodySpan.end))
        assertNotNull(c1.ast)
        assertEquals("!", (r.segments[4] as Segment.Text).text)
        assertNull(r.singleAst)
    }

    @Test fun parseTokensCarryKindAndAbsoluteSpans() {
        val text = "x {{ \$json.a + 12 }}"
        val tokens = parse(text).tokens
        assertEquals(listOf(TokenKind.Variable, TokenKind.Punct, TokenKind.Identifier, TokenKind.Operator, TokenKind.Number), tokens.map { it.kind })
        val v = tokens[0]
        assertEquals("\$json", text.substring(v.span.start, v.span.end))
        assertEquals("12", text.substring(tokens[4].span.start, tokens[4].span.end))
        assertEquals(12.0, tokens[4].literal)
    }

    @Test fun parseReportsErrorsPerSegmentWithoutThrowing() {
        val r = parse("a {{ 1 + }} b {{ \$ok }} c {{ \"s\" }}")
        assertEquals(1, r.diagnostics.size)
        assertTrue(!r.ok)
        val codes = r.codeSegments
        assertNotNull(codes[0].error)
        assertNull(codes[0].ast)
        assertNull(codes[1].error)
        assertNotNull(codes[1].ast)
        assertEquals(3, codes.size)
    }

    @Test fun parseKeepsPartialTokensOnLexError() {
        val r = parse("{{ \$json.a + \"open }}x")
        val code = r.codeSegments.single()
        assertNotNull(code.error)
        assertTrue(code.tokens.size >= 3)
    }

    @Test fun parseUnterminatedBraceDoesNotCrash() {
        val r = parse("pre {{ \$json.a")
        assertTrue(!r.ok)
        assertEquals(2, r.segments.size)
        assertEquals(4, r.diagnostics.single().position!!.offset)
        assertTrue(r.diagnostics.single().message!!.contains("Unterminated"))
    }

    @Test fun parseNeverThrowsOnGarbage() {
        for (text in listOf("{{", "{{{", "}}", "{{ }}", "{{ `", "{{ \"", "{{ ((( }}", "{{ \u0000 }}", "{{ 1 }} {{", "\n\n{{\n", "{{ x => }}", "{{ [ }}")) {
            parse(text)
            parse(text, ParseMode.Expression)
        }
    }

    @Test fun parseExpressionModeAndSingleAst() {
        val r = parse("\$json.a + 1", ParseMode.Expression)
        assertTrue(r.ok)
        assertTrue(r.singleAst is Binary)
        val t = parse("  {{ 1 }}  ")
        assertTrue(t.singleAst is Literal)
    }

    @Test fun astNodesHaveSpans() {
        val ast = parse("1 + \$json.x", ParseMode.Expression).singleAst as Binary
        assertEquals(Span(0, 11), ast.span)
        assertEquals(Span(4, 11), ast.right.span)
        assertEquals(Span(0, 1), ast.left.span)
    }

    @Test fun referencedVariables() {
        fun vars(code: String) = referencedVariables(parse(code, ParseMode.Expression).singleAst!!)
        assertEquals(setOf("\$json"), vars("\$json.a.b + 1"))
        assertEquals(setOf("\$json", "\$vars", "\$items"), vars("\$json.a ? \$vars.x : \$items.length"))
        assertEquals(setOf("\$step"), vars("\$step(\"A\").x"))
        assertEquals(setOf("\$json"), vars("\$json.rows.map(r => r.v + \$json.k)"))
        assertEquals(emptySet<String>(), vars("[1, 2].map(\$x => \$x + 1)"))
        assertEquals(setOf("\$json", "\$now"), vars("`\${\$json.a} \${\$now}`"))
        assertEquals(setOf("\$json", "\$vars"), referencedVariables(parse("{{ \$json.a }} and {{ \$vars.b }}")))
    }

    @Test fun analyzeFindsStepsPathsAndFunctions() {
        val ast = parse("\$step(\"Fetch\").body.id + \$json.a.b + \$items[0].id + \$step(\"Other\", \"x\").length + upper(\$json['k'])", ParseMode.Expression).singleAst!!
        val a = analyze(ast)
        assertEquals(setOf("Fetch", "Other"), a.steps)
        assertTrue(a.paths.contains("\$step(\"Fetch\").body.id"))
        assertTrue(a.paths.contains("\$json.a.b"))
        assertTrue(a.paths.contains("\$items[0].id"))
        assertTrue(a.paths.contains("\$json[\"k\"]"))
        assertTrue(a.functions.containsAll(listOf("\$step", "upper")))
    }

    @Test fun previewReturnsValueWithType() {
        val v = evaluatePreview("{{ \$json.a.b.c * 2 }}", SCOPE) as ExpressionPreview.Value
        assertEquals(84.0, v.value)
        assertEquals("number", v.type)
        assertEquals("string", (evaluatePreview("Hi {{ \$json.merchant }}", SCOPE) as ExpressionPreview.Value).type)
        assertEquals("array", (evaluatePreview("{{ \$json.tags }}", SCOPE) as ExpressionPreview.Value).type)
        assertEquals("null", (evaluatePreview("{{ \$json.missing }}", SCOPE) as ExpressionPreview.Value).type)
        assertEquals("plain text", (evaluatePreview("plain text", SCOPE) as ExpressionPreview.Value).value)
        assertEquals(3.0, (evaluatePreview("1 + 2", SCOPE, ParseMode.Expression) as ExpressionPreview.Value).value)
    }

    @Test fun previewReturnsFailureInsteadOfThrowing() {
        val f = evaluatePreview("{{ \$jsno.a }}", SCOPE) as ExpressionPreview.Failure
        assertEquals(ErrorKind.Unknown, f.error.kind)
        assertEquals("\$json", f.error.suggestion)
        assertTrue(evaluatePreview("{{ 1 + }}", SCOPE) is ExpressionPreview.Failure)
        assertTrue(evaluatePreview("{{ ", SCOPE) is ExpressionPreview.Failure)
        assertTrue(evaluatePreview("{{ x => x }}", SCOPE) is ExpressionPreview.Failure)
        assertTrue(evaluatePreview("{{ 1 / 0 }}", SCOPE) is ExpressionPreview.Failure)
        val strict = evaluatePreview("{{ \$json.nothing }}", SCOPE, strict = true)
        assertTrue(strict is ExpressionPreview.Failure)
    }

    @Test fun previewMatchesRunResultForACorpus() {
        val corpus = listOf(
            "{{ \$json.a.b.c }}", "Total {{ \$json.qty * \$json.price }}", "{{ \$json.rows.map(r => r.n).join(\"-\") }}", "{{ upper(\$json.merchant) }}",
            "{{ \$json.tags.unique().length }}", "{{ now().plus(1, \"day\").format(\"date\") }}", "{{ \$step(\"Two\").count }}", "{{ \$json.missing ?? \"d\" }}",
        )
        val engine = evaluator()
        for (text in corpus) {
            val preview = (evaluatePreview(text, SCOPE, limits = ExpressionLimits.Default) as ExpressionPreview.Value).value
            assertTrue(text, same(engine.interpolate(text, SCOPE), preview))
        }
    }
}
