package com.kaivoflow.core.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitsTest {
    private val big = SCOPE + ("big" to List(1_000_000) { it.toDouble() })

    @Test fun expressionLengthLimit() {
        val limits = ExpressionLimits(maxExpressionLength = 50)
        assertEquals(1.0, ev("1", limits = limits))
        val e = failure("1" + " + 1".repeat(30), limits = limits)
        assertKind(ErrorKind.Limit, e)
        assertTrue(e.message!!.contains("50"))
    }

    @Test fun expressionLengthLimitAppliesInsideTemplatesPerExpression() {
        val limits = ExpressionLimits(maxExpressionLength = 20)
        assertEquals("ok " + "1", evaluator(limits = limits).interpolate("ok {{ 1 }}", SCOPE))
        assertKind(ErrorKind.Limit, failure("{{ " + "1+".repeat(30) + "1 }}", limits = limits, template = true))
    }

    @Test fun deeplyNestedParenthesesAreRejectedNotCrashed() {
        val deep = "(".repeat(5_000) + "1" + ")".repeat(5_000)
        val e = failure(deep, limits = ExpressionLimits(maxExpressionLength = 100_000))
        assertKind(ErrorKind.Limit, e)
        assertTrue(e.message!!.contains("nested too deeply"))
    }

    @Test fun deeplyNestedArraysUnaryAndTernariesAreRejected() {
        val big = ExpressionLimits(maxExpressionLength = 100_000)
        assertKind(ErrorKind.Limit, failure("[".repeat(3_000) + "]".repeat(3_000), limits = big))
        assertKind(ErrorKind.Limit, failure("!".repeat(3_000) + "1", limits = big))
        assertKind(ErrorKind.Limit, failure("-".repeat(3_000) + "1", limits = big))
        assertKind(ErrorKind.Limit, failure("true ? ".repeat(3_000) + "1" + " : 2".repeat(3_000), limits = big))
        assertKind(ErrorKind.Limit, failure("x => ".repeat(2_000) + "1", limits = big))
        assertKind(ErrorKind.Limit, failure("`\${".repeat(500) + "1" + "}`".repeat(500), limits = big))
    }

    @Test fun longOperatorChainsBeyondDepthAreRejected() {
        val e = failure((1..400).joinToString(" + "), limits = ExpressionLimits(maxExpressionLength = 100_000))
        assertKind(ErrorKind.Limit, e)
    }

    @Test fun reasonableChainsStillWork() {
        assertEquals(100.0, ev((1..100).joinToString(" + ") { "1" }))
    }

    @Test fun astNodeLimit() {
        val e = failure("[" + "1,".repeat(300) + "1]", limits = ExpressionLimits(maxAstNodes = 100))
        assertKind(ErrorKind.Limit, e)
        assertTrue(e.message!!.contains("too large"))
    }

    @Test fun infiniteRecursionIsStopped() {
        val e = failure("(f => f(f))(f => f(f))")
        assertKind(ErrorKind.Limit, e)
        assertTrue(e.message!!.contains("nested deeper"))
    }

    @Test fun recursionLimitIsConfigurable() {
        val code = "(f => n => n == 0 ? 0 : 1 + f(f)(n - 1))(f => n => n == 0 ? 0 : 1 + f(f)(n - 1))(10)"
        assertEquals(10.0, ev(code, limits = ExpressionLimits(maxCallDepth = 100)))
        assertKind(ErrorKind.Limit, failure(code, limits = ExpressionLimits(maxCallDepth = 5)))
    }

    @Test fun stepLimitStopsLongComputations() {
        val e = failure("\$json.big.map(x => x * 2).filter(x => x > 5).length", SCOPE + ("json" to mapOf("big" to List(50_000) { it })), limits = ExpressionLimits(maxSteps = 10_000))
        assertKind(ErrorKind.Limit, e)
        assertTrue(e.message!!.contains("step limit"))
    }

    @Test fun stepLimitDefaultAllowsNormalWorkloads() {
        val items = List(2_000) { mapOf("v" to it.toDouble()) }
        val scope = SCOPE + ("json" to mapOf("rows" to items))
        assertEquals(1_999_000.0, ev("\$json.rows.map(r => r.v).sum()", scope))
    }

    @Test fun hugeRepeatIsRefused() {
        val e = failure("\"x\".repeat(1000000000)")
        assertKind(ErrorKind.Limit, e)
        assertTrue(e.message!!.contains("repeat"))
        assertKind(ErrorKind.Limit, failure("\"abcdefghij\".repeat(200000)"))
    }

    @Test fun repeatWithinLimitWorks() {
        assertEquals(500_000, (ev("\"xy\".repeat(250000)") as String).length)
    }

    @Test fun hugePaddingIsRefused() {
        assertKind(ErrorKind.Limit, failure("\"x\".padStart(2000000000, \"ab\")"))
        assertKind(ErrorKind.Limit, failure("\"x\".padEnd(2000000000)"))
    }

    @Test fun stringDoublingIsRefused() {
        val e = failure("range(60).reduce((acc, i) => acc + acc, \"xxxxxxxx\")")
        assertKind(ErrorKind.Limit, e)
        assertTrue(e.message!!.contains("String longer"))
    }

    @Test fun stringLimitIsConfigurable() {
        val limits = ExpressionLimits(maxStringLength = 10)
        assertEquals("1234567890", ev("\"12345\" + \"67890\"", limits = limits))
        assertKind(ErrorKind.Limit, failure("\"12345\" + \"678901\"", limits = limits))
        assertKind(ErrorKind.Limit, failure("[\"aaaaaa\", \"bbbbbb\"].join(\"\")", limits = limits))
        assertKind(ErrorKind.Limit, failure("\"aaaaaaaaaaaa\".replace(\"a\", \"bb\")", limits = limits))
        assertKind(ErrorKind.Limit, failure("`\${\"aaaaaa\"}\${\"bbbbbb\"}`", limits = limits))
        assertKind(ErrorKind.Limit, failure("a {{ \"aaaaaa\" }} {{ \"bbbbbb\" }}", limits = limits, template = true))
        assertKind(ErrorKind.Limit, failure("JSON.stringify([1,2,3,4,5,6,7,8,9,10,11,12])", limits = limits))
    }

    @Test fun collectionLimits() {
        val limits = ExpressionLimits(maxCollectionSize = 100)
        assertEquals(100, (ev("range(100)", limits = limits) as List<*>).size)
        assertKind(ErrorKind.Limit, failure("range(101)", limits = limits))
        assertKind(ErrorKind.Limit, failure("range(5000000)"))
        assertKind(ErrorKind.Limit, failure("range(60).reduce((acc, i) => acc.concat(acc), [1])", limits = limits))
        assertKind(ErrorKind.Limit, failure("\"x\".repeat(5).split(\"\")", limits = ExpressionLimits(maxCollectionSize = 3)))
    }

    @Test fun wallClockTimeoutStopsRunawayWork() {
        val limits = ExpressionLimits(maxSteps = Long.MAX_VALUE, timeoutMillis = 20)
        val started = System.nanoTime()
        val e = failure("\$json.big.map(x => x * 2).map(x => x + 1).map(x => x * 3).map(x => x - 2).length", mapOf("json" to mapOf("big" to big["big"])), limits = limits)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertKind(ErrorKind.Timeout, e)
        assertTrue("took $ms ms", ms < 1_500)
    }

    @Test fun timeoutIsConfigurable() {
        val e = failure("\$json.big.map(x => x * 2).map(x => x + 1).map(x => x * 3).length", mapOf("json" to mapOf("big" to big["big"])),
            limits = ExpressionLimits(maxSteps = Long.MAX_VALUE, timeoutMillis = 1))
        assertKind(ErrorKind.Timeout, e)
        assertEquals(1_000_000.0, ev("\$json.big.map(x => x).length", mapOf("json" to mapOf("big" to big["big"])), limits = ExpressionLimits(maxSteps = Long.MAX_VALUE, timeoutMillis = 30_000)))
    }

    @Test fun jsonParseDepthIsLimited() {
        assertKind(ErrorKind.Runtime, failure("JSON.parse(\"[\".repeat(200) + \"]\".repeat(200))"))
    }

    @Test fun sortWithBadComparatorCannotHang() {
        assertEquals(3, (ev("[3, 1, 2].sort((a, b) => 1)") as List<*>).size)
    }

    @Test fun previewBudgetStopsRunawayExpressionQuickly() {
        val started = System.nanoTime()
        val r = evaluatePreview("{{ \$json.big.map(x => x * 2).map(x => x + 1).length }}", mapOf("json" to mapOf("big" to big["big"])))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(r is ExpressionPreview.Failure)
        assertTrue("took $ms ms", ms < 500)
    }
}
