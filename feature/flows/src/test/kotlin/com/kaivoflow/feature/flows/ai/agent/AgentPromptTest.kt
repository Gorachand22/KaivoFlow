package com.kaivoflow.feature.flows.ai.agent

import com.kaivoflow.core.engine.GlobalScope
import com.kaivoflow.core.engine.UniversalResolver
import com.kaivoflow.core.expressions.KaivoExpressionEvaluator
import com.kaivoflow.core.model.ExprFieldCodec
import com.kaivoflow.core.model.ParamValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptTest {
    private val resolver = UniversalResolver(KaivoExpressionEvaluator())
    private val scope = GlobalScope.build(mapOf("TONE" to "formal"), flowId = "a1", flowName = "News", clock = { 1_700_000_000_000L })

    private fun agent(instructions: String, vararg knowledge: Pair<String, String>) = CustomAgent(
        instructions = instructions,
        knowledge = knowledge.map { AgentKnowledgeItem(title = it.first, content = it.second) },
        tools = listOf(AgentTool(type = "web_search", name = "Web", description = "Search")),
    )

    @Test fun legacy_plain_agent_is_sent_unchanged_even_with_braces() {
        val r = AgentPrompt.build(agent("Say {{ literally }}"), "hi", resolver, scope) as AgentPrompt.Built.Ok
        assertTrue(r.text.contains("AGENT INSTRUCTIONS:\nSay {{ literally }}"))
        assertTrue(r.text.endsWith("USER PROMPT / TASK:\nhi"))
        assertTrue(r.text.contains("- Web (web_search): Search"))
    }

    @Test fun expressions_in_instructions_and_knowledge_are_resolved_per_call() {
        val a = agent(
            ExprFieldCodec.encode(ParamValue.Expr("Be {{ \$vars.TONE }} for {{ \$run.flowName }}")),
            "Style" to ExprFieldCodec.encode(ParamValue.Expr("Written on {{ \$today }}")),
        )
        val r = AgentPrompt.build(a, "go", resolver, scope) as AgentPrompt.Built.Ok
        assertTrue(r.text, r.text.contains("Be formal for News"))
        assertTrue(r.text, r.text.contains("- Style: Written on 2023-11-14"))
    }

    @Test fun a_bad_expression_fails_with_the_field_name_and_calls_nothing() {
        val a = agent(ExprFieldCodec.encode(ParamValue.Expr("{{ 1 + }}")), "Doc" to ExprFieldCodec.encode(ParamValue.Expr("{{ ( }}")))
        val r = AgentPrompt.build(a, "go", resolver, scope) as AgentPrompt.Built.Failed
        assertTrue(r.message, r.message.startsWith("Instructions:"))
        assertTrue(r.message, r.message.contains("Knowledge \"Doc\":"))
    }

    @Test fun item_data_is_empty_in_agent_scope() {
        val r = AgentPrompt.build(agent(ExprFieldCodec.encode(ParamValue.Expr("[{{ \$json.x }}]"))), "go", resolver, scope) as AgentPrompt.Built.Ok
        assertEquals(true, r.text.contains("AGENT INSTRUCTIONS:\n[]"))
    }
}
