package com.kaivoflow.feature.flows.ai.agent

import com.kaivoflow.core.engine.UniversalResolver
import com.kaivoflow.core.engine.GlobalScope
import com.kaivoflow.core.expressions.KaivoExpressionEvaluator
import com.kaivoflow.core.model.ExprFieldCodec
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.nodes.NodeCatalog
import com.kaivoflow.feature.flows.ai.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFlowCompilerTest {
    private val registry = NodeCatalog.registry()

    private fun agent(vararg tools: AgentTool) = CustomAgent(id = "a1", name = "Helper", tools = tools.toList())

    private fun compile(a: CustomAgent, provider: AiProvider = AiProvider.OpenAI, base: String = "", task: String = "hi {{ x }}") =
        AgentFlowCompiler.compile(a, "Be brief", task, provider, "m1", "cred-1", base)

    @Test fun `the test flow is one ai agent step with no spark, never saved, and the test message is its own input`() {
        val c = compile(agent())
        assertEquals(1, c.flow.steps.size)
        assertEquals("ai.agent", c.flow.steps.single().type)
        assertTrue(c.flow.triggers.isEmpty() && c.flow.streams.isEmpty() && !c.flow.isActive)
        assertEquals("agent-test:a1", c.flow.id)
        assertEquals(listOf(mapOf("text" to "hi {{ x }}")), c.input.map { it.json })
    }

    @Test fun `the compiled step is valid for the real agent executor`() {
        val step = compile(agent(AgentTool(type = "device", name = "Phone Notification", description = "Tell me"))).flow.steps.single()
        val exec = registry.executor("ai.agent")!!
        assertEquals(emptyList<Any>(), exec.validate(step, exec.spec))
        val tool = (step.params["tools"] as List<*>).single() as Map<*, *>
        assertEquals("device.notify", tool["step"])
        assertEquals("phone_notification", tool["name"])
        assertTrue("tool step must exist in the catalog", registry.spec(tool["step"] as String) != null)
        assertEquals(false, tool["allowUnattended"])
    }

    @Test fun `the key is never in the step, only the credential id`() {
        val p = compile(agent()).flow.steps.single().params.toString()
        assertTrue(p.contains("cred-1"))
        assertFalse(p.contains("apiKey"))
    }

    @Test fun `braces in the task and instructions are neutralised so the step cannot read them as a second expression`() {
        val c = compile(agent(), task = "say {{ \$vars.SECRET }}")
        val step = c.flow.steps.single()
        assertFalse((step.params["prompt"] as String).contains("{{"))
        assertFalse((step.params["system"] as String).contains("{{"))
        val withBraces = AgentFlowCompiler.compile(agent(), "use {{ a }}", "t", AiProvider.OpenAI, "m", null, "")
        assertFalse((withBraces.flow.steps.single().params["system"] as String).contains("{{"))
    }

    @Test fun `tools without an implementation are not offered and are reported`() {
        val c = compile(agent(AgentTool(type = "web_search", name = "Web"), AgentTool(type = "device", name = "Notify"), AgentTool(type = "code", name = "Run", isEnabled = false)))
        assertEquals(1, (c.flow.steps.single().params["tools"] as List<*>).size)
        assertEquals(1, c.unavailableTools.size)
        assertTrue(c.unavailableTools.single().contains("Web"))
        assertNull(compile(agent(AgentTool(type = "web_search", name = "Web"))).flow.steps.single().params["tools"])
    }

    @Test fun `two tools with the same name get distinct provider-safe names`() {
        val names = (compile(agent(AgentTool(type = "device", name = "Alert!"), AgentTool(type = "device", name = "Alert?"))).flow.steps.single().params["tools"] as List<*>)
            .map { (it as Map<*, *>)["name"] as String }
        assertEquals(2, names.toSet().size)
        assertTrue(names.all { Regex("[A-Za-z0-9_-]{1,64}").matches(it) })
    }

    @Test fun `the base address is normalised and only ollama may use the local network`() {
        val claude = compile(agent(), AiProvider.Claude, "https://api.anthropic.com/v1").flow.steps.single().params["chatModel"] as Map<*, *>
        assertEquals("https://api.anthropic.com", claude["baseUrl"]); assertEquals("none", claude["localAccess"]); assertEquals("anthropic", claude["provider"])
        val ollama = compile(agent(), AiProvider.Ollama, "http://10.0.2.2:11434").flow.steps.single().params["chatModel"] as Map<*, *>
        assertEquals("http://10.0.2.2:11434/v1", ollama["baseUrl"]); assertEquals("lan", ollama["localAccess"])
        val openai = compile(agent(), AiProvider.OpenAI, "http://192.168.1.2/v1").flow.steps.single().params["chatModel"] as Map<*, *>
        assertEquals("none", openai["localAccess"])
    }

    @Test fun `limits come from the agent settings and are clamped`() {
        val a = agent().copy(settings = AgentSettings(maxIterations = 999, timeoutSeconds = 1))
        val p = compile(a).flow.steps.single().params
        assertEquals(50, p["maxIterations"]); assertEquals(5, p["timeoutSeconds"])
    }

    @Test fun `buildSystem resolves expressions per call and leaves out the tool list and the user prompt`() {
        val resolver = UniversalResolver(KaivoExpressionEvaluator())
        val scope = GlobalScope.build(mapOf("TONE" to "formal"), flowId = "a1", flowName = "News", clock = { 1_700_000_000_000L })
        val a = agent(AgentTool(type = "web_search", name = "Web", description = "Search")).copy(
            instructions = ExprFieldCodec.encode(ParamValue.Expr("Be {{ \$vars.TONE }}")),
            knowledge = listOf(AgentKnowledgeItem(title = "Style", content = "plain")),
        )
        val r = AgentPrompt.buildSystem(a, resolver, scope) as AgentPrompt.Built.Ok
        assertTrue(r.text, r.text.startsWith("Be formal"))
        assertTrue(r.text.contains("- Style: plain"))
        assertFalse(r.text.contains("AVAILABLE TOOLS") || r.text.contains("Web (web_search)"))
        val bad = AgentPrompt.buildSystem(a.copy(instructions = ExprFieldCodec.encode(ParamValue.Expr("{{ 1 + }}"))), resolver, scope)
        assertTrue(bad is AgentPrompt.Built.Failed)
    }
}
