package com.kaivoflow.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingTest {
    @Test fun `longest prefix wins and dated ids are found`() {
        val t = PriceTable.BUNDLED
        assertEquals(0.15, t.priceOf("gpt-4o-mini-2024-07-18")!!.input, 1e-9)
        assertEquals(2.5, t.priceOf("gpt-4o-2024-08-06")!!.input, 1e-9)
        assertEquals(5.0, t.priceOf("claude-opus-4-5-20251101")!!.input, 1e-9); assertEquals(15.0, t.priceOf("claude-opus-4-1")!!.input, 1e-9)
        assertEquals(0.10, t.priceOf("openai/gpt-4.1-nano")!!.input, 1e-9)
        assertNull(t.priceOf("my-local-llama")); assertNull(t.priceOf(null)); assertTrue(t.size > 20)
    }

    @Test fun `cost counts cached tokens at their own rate and unknown prices are unknown not zero`() {
        val p = ModelPrice(2.0, 10.0, 0.5)
        assertEquals((600 * 2.0 + 400 * 0.5 + 1_000 * 10.0) / 1e6, p.cost(Usage(1_000, 1_000, cachedPromptTokens = 400)), 1e-12)
        val none = PriceTable.BUNDLED.estimate("mystery-model", Usage(10, 10))
        assertNull(none.usd); assertEquals(setOf("mystery-model"), none.unpricedModels)
        assertEquals(0.0, PriceTable.BUNDLED.estimate("llama3", Usage(10, 10), ProviderKind.Ollama).usd!!, 0.0)
    }

    @Test fun `a price list can be replaced from json and bad entries are skipped`() {
        val t = PriceTable.parse("""{"asOf":"2027-02","models":{"acme-1":{"input":1,"output":2},"bad":{"input":-1,"output":2},"worse":{"input":"x"}}}""")!!
        assertEquals("2027-02", t.asOf); assertEquals(1, t.size); assertNotNull(t.priceOf("acme-1-beta"))
        assertNull(PriceTable.parse("not json")); assertNull(PriceTable.parse("""{"models":{}}"""))
    }

    @Test fun `tokens are estimated and marked when a provider reports none`() {
        val req = ChatRequest("sys", listOf(ChatMessage.user("a".repeat(400))))
        val u = TokenEstimator.usageOrEstimate(Usage(), req, ChatResponse("b".repeat(40)))
        assertTrue(u.estimated); assertTrue(u.promptTokens >= 100); assertEquals(10L, u.completionTokens)
        assertEquals(Usage(5, 5), TokenEstimator.usageOrEstimate(Usage(5, 5), req, ChatResponse("x")))
        assertTrue((Usage(1, 1, estimated = true) + Usage(1, 1)).estimated)
    }

    @Test fun `a shared budget stops on tokens or cost and is thread safe to read`() {
        val b = RunBudget(maxTokens = 100, maxUsd = 0.01)
        assertNull(b.check()); b.add(Usage(60, 30), CostEstimate(0.002, false)); assertNull(b.check()); assertTrue(b.wouldExceed(20)); assertFalse(b.wouldExceed(5))
        b.add(Usage(10, 5), CostEstimate(0.02, false)); assertEquals(BudgetStop.Tokens, b.check())
        val c = RunBudget(maxUsd = 0.01, spentUsd = 0.02); assertEquals(BudgetStop.Cost, c.check())
        val u = RunBudget(); u.add(Usage(1, 1), CostEstimate(null, false, setOf("m"))); assertEquals(setOf("m"), u.unpriced)
    }

    @Test fun `the ledger splits usage by model`() {
        val l = UsageLedger()
        l.record("a", Usage(10, 5), CostEstimate(0.001, false)); l.record("b", Usage(1, 1), CostEstimate(null, false, setOf("b"))); l.record("a", Usage(10, 5), CostEstimate(0.001, true))
        assertEquals(Usage(20, 10), l.usageByModel["a"]); assertEquals(Usage(21, 11), l.total)
        assertEquals(0.002, l.costEstimate.usd!!, 1e-12); assertTrue(l.costEstimate.estimated); assertEquals(setOf("b"), l.costEstimate.unpricedModels)
    }
}
