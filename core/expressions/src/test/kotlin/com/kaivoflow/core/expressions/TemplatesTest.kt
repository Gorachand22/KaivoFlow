package com.kaivoflow.core.expressions

import com.kaivoflow.core.engine.ParamResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplatesTest {
    @Test fun plainTextIsReturnedUnchanged() {
        assertEquals("plain", tpl("plain"))
        assertEquals("  keep  spaces ", tpl("  keep  spaces "))
        assertEquals("single { brace } is text", tpl("single { brace } is text"))
    }

    @Test fun singleExpressionKeepsItsType() {
        assertEquals(42.0, tpl("{{ \$json.a.b.c }}"))
        assertEquals(true, tpl("{{ \$json.flag }}"))
        assertEquals(listOf("x", "y", "x"), tpl("{{ \$json.tags }}"))
        assertEquals(mapOf("x" to 1.0, "y" to 2.0), tpl("{{ \$json.obj }}"))
        assertNull(tpl("{{ \$json.missing }}"))
        assertEquals(42.0, tpl("  {{ \$json.a.b.c }}  "))
        assertEquals(3.0, tpl("{{1+2}}"))
    }

    @Test fun mixedTextBecomesString() {
        assertEquals("Hi Cafe!", tpl("Hi {{ \$json.merchant }}!"))
        assertEquals("Total: 1250.5 INR", tpl("Total: {{ \$json.amount }} INR"))
        assertEquals("3 items, 6 total", tpl("{{ \$json.tags.length }} items, {{ \$json.qty * 2 }} total"))
        assertEquals("a  b", tpl("a {{ \$json.missing }} b"))
        assertEquals("n=[1,2]", tpl("n={{ [1, 2] }}"))
        assertEquals("x=[\"x\",\"y\",\"x\"]", tpl("x={{ \$json.tags }}"))
        assertEquals("{{", tpl("{{ \"{{\" }}"))
    }

    @Test fun closingBracesInsideStringsDoNotEndTheExpression() {
        assertEquals("a}}b", tpl("{{ \"a}}b\" }}"))
        assertEquals("x }} y", tpl("x {{ \"}}\" }} y"))
        assertEquals("}", tpl("{{ `\${\"}\"}` }}"))
    }

    @Test fun objectLiteralsInsideTemplates() {
        assertEquals(1.0, tpl("{{ {a: 1}.a }}"))
        assertEquals(2.0, tpl("{{ {a: {b: 2}}.a.b }}"))
        assertEquals(mapOf("k" to listOf(1.0)), tpl("{{ {k: [1]} }}"))
        assertEquals("v=2", tpl("v={{ {a: {b: 2}}.a.b }}"))
    }

    @Test fun tripleBracesAreAnAlias() {
        assertEquals(42.0, tpl("{{{ \$json.a.b.c }}}"))
        assertEquals("v: 42", tpl("v: {{{ \$json.a.b.c }}}"))
    }

    @Test fun extraClosingBraceStaysAsText() {
        assertEquals("42}", tpl("{{ \$json.a.b.c }}}"))
    }

    @Test fun emptyExpressionIsNull() {
        assertNull(tpl("{{ }}"))
        assertEquals("ab", tpl("a{{}}b"))
    }

    @Test fun multilineTemplates() {
        assertEquals("line1\n42\nline3", tpl("line1\n{{ \$json.a.b.c }}\nline3"))
        assertEquals("a\nb", tpl("{{\n  \"a\" +\n  \"\\n\" +\n  \"b\"\n}}"))
    }

    @Test fun interpolateAllAlwaysReturnsString() {
        assertEquals("42", evaluator().interpolateAll("{{ \$json.a.b.c }}", SCOPE))
        assertEquals("", evaluator().interpolateAll("{{ \$json.missing }}", SCOPE))
        assertEquals("plain", evaluator().interpolateAll("plain", SCOPE))
        assertEquals("{\"x\":1}", evaluator().interpolateAll("{{ {x: 1} }}", SCOPE))
    }

    @Test fun paramResolverWalksMapsAndLists() {
        val resolver = ParamResolver(evaluator())
        val resolved = resolver.resolveValue(
            mapOf("url" to "https://x/{{ \$json.merchant }}", "n" to "{{ \$json.qty + 1 }}", "list" to listOf("{{ \$vars.sheetId }}", "lit")),
            SCOPE,
        ) as Map<*, *>
        assertEquals("https://x/Cafe", resolved["url"])
        assertEquals(4.0, resolved["n"])
        assertEquals(listOf("abc", "lit"), resolved["list"])
    }

    @Test fun errorPositionsPointInsideTheWholeTemplate() {
        val e = failure("first line\nsecond {{ \$json.a + }} end", template = true)
        assertKind(ErrorKind.Syntax, e)
        assertEquals(2, e.position!!.line)
        assertEquals(21, e.position!!.column)
    }

    @Test fun unterminatedTemplateIsReported() {
        val e = failure("hello {{ \$json.a", template = true)
        assertKind(ErrorKind.Syntax, e)
        assertTrue(e.message!!.contains("Unterminated"))
        assertEquals(7, e.position!!.column)
    }
}
