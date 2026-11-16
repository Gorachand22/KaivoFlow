package com.kaivoflow.core.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorsTest {
    @Test fun syntaxErrorsCarryLineColumnAndOffset() {
        val e = failure("1 +\n  * 2")
        assertKind(ErrorKind.Syntax, e)
        val p = e.position!!
        assertEquals(2, p.line)
        assertEquals(3, p.column)
        assertEquals(6, p.offset)
    }

    @Test fun syntaxErrorsCatalogue() {
        val cases = mapOf(
            "1 +" to "end of expression", "(1 + 2" to "')'", "[1, 2" to "']'", "{a: 1" to "'}'", "{a 1}" to "':'", "1 2" to "operator",
            "\"open" to "Unterminated string", "'open" to "Unterminated string", "`open" to "Unterminated template", "a = 1" to "==", "1 & 2" to "&&", "1 | 2" to "||",
            "1; 2" to "semicolons", "\$json." to "property name", "\$json.1" to "property name", "foo(1" to "')'", "x => " to "end of expression", "1 ? 2" to "':'",
            "@" to "Unexpected character", "\$" to "variable name", "1abc" to "Invalid number", "\"\\u12\"" to "unicode", "[1,,2]" to "Unexpected ','", ")" to "Unexpected ')'",
            "{1: 2, }.x +" to "end of expression", "\$json[" to "end of expression", "\$json[1" to "']'", "`\${}`" to "Empty",
        )
        val failures = cases.filter { (code, needle) ->
            val e = try { ev(code); null } catch (e: ExpressionException) { e }
            e == null || e.kind != ErrorKind.Syntax || !e.message!!.contains(needle)
        }
        assertTrue("unexpected messages for $failures", failures.isEmpty())
    }

    @Test fun unknownVariableSuggestsClosestName() {
        val e = failure("\$jsno.a")
        assertKind(ErrorKind.Unknown, e)
        assertEquals("\$json", e.suggestion)
        assertTrue(e.message!!.contains("Did you mean '\$json'"))
        assertEquals("\$vars", failure("\$var.x").suggestion)
        assertEquals("\$items", failure("\$itms.x.y").suggestion)
        assertEquals("\$json", failure("json.a").suggestion)
        assertNull(failure("\$zzzzzz").suggestion)
    }

    @Test fun unknownFunctionSuggestsClosestName() {
        val e = failure("upperr(\"a\")")
        assertKind(ErrorKind.Unknown, e)
        assertEquals("upper", e.suggestion)
        assertEquals("round", failure("rond(1.5)").suggestion)
        assertEquals("\$step", failure("\$stpe(\"a\")").suggestion)
        assertEquals("floor", failure("Math.flor(1)").suggestion)
        assertNull(failure("completelyUnrelatedName()").suggestion)
    }

    @Test fun unknownMethodSuggestsClosestName() {
        val e = failure("\"abc\".trimm()")
        assertKind(ErrorKind.Unknown, e)
        assertEquals("trim", e.suggestion)
        assertTrue(e.message!!.contains("on text"))
        assertEquals("filter", failure("[1].filtr(x => x)").suggestion)
        assertEquals("keys", failure("{a: 1}.kyes()").suggestion)
        assertEquals("format", failure("now().fromat(\"x\")").suggestion)
    }

    @Test fun errorPositionPointsAtTheOffendingPart() {
        val e = failure("1 + upperr(\"a\")")
        assertEquals(5, e.position!!.column)
        val m = failure("\"abc\".trimm()")
        assertEquals(7, m.position!!.column)
        val v = failure("1 + \$jsno")
        assertEquals(5, v.position!!.column)
        assertEquals(5, v.position!!.length)
    }

    @Test fun templateErrorsPointIntoTheTemplate() {
        val e = failure("Hello {{ \$jsno.name }}", template = true)
        assertEquals(10, e.position!!.column)
        assertEquals(9, e.position!!.offset)
    }

    @Test fun arityErrorsExplainExpectedCount() {
        val e = failure("round()")
        assertKind(ErrorKind.Type, e)
        assertTrue(e.message!!.contains("round()") )
        assertTrue(failure("\"a\".padStart()").message!!.contains("1 to 2"))
        assertTrue(failure("Math.pow(1)").message!!.contains("2 argument"))
    }

    @Test fun callingNonFunctionsFails() {
        assertTrue(failure("(1)(2)").message!!.contains("not a function"))
        assertTrue(failure("\$json(1)").message!!.contains("not a function"))
        assertTrue(failure("\$json.merchant.length()(1)").message!!.contains("not a function"))
        assertKind(ErrorKind.Type, failure("\$json(1)"))
        assertTrue(failure("\$vars()").message!!.contains("value, not a function"))
    }

    @Test fun functionsCannotBeResults() {
        val e = failure("x => x")
        assertKind(ErrorKind.Type, e)
        assertTrue(e.message!!.contains("function"))
        assertKind(ErrorKind.Type, failure("[x => x]"))
    }

    @Test fun exceptionHasStructuredFields() {
        val e = failure("\$jsno")
        assertNotNull(e.position)
        assertEquals(ErrorKind.Unknown, e.kind)
        assertEquals("Unknown variable '\$jsno'", e.detail)
        assertTrue((e as Throwable) is IllegalStateException)
    }

    @Test fun missingNamesWithoutDollarAreExplained() {
        val e = failure("foo.bar")
        assertKind(ErrorKind.Unknown, e)
        assertTrue(e.message!!.contains("Unknown variable 'foo'"))
    }

    @Test fun envIsNeverExposed() {
        assertEquals(emptyMap<String, Any?>(), ev("\$env"))
        assertNull(ev("\$env.PATH"))
        assertNull(ev("\$env.HOME"))
        assertNull(ev("\$binary"))
    }

    @Test fun noAccessToHostClasses() {
        for (code in listOf("\$json.class", "\$json.getClass()", "\"x\".constructor", "java.lang.Runtime", "Runtime.getRuntime()", "System.getenv()", "\$json.__proto__", "globalThis", "process.env")) {
            val result = try { ev(code) } catch (e: ExpressionException) { e }
            assertTrue("$code leaked $result", result == null || result is ExpressionException)
        }
        assertKind(ErrorKind.Unknown, failure("\"x\".getClass()"))
        assertKind(ErrorKind.Unknown, failure("System.exit(1)"))
    }
}
