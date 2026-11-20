package com.kaivoflow.core.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopV2Test {
    private val schema = mapOf("type" to "object", "properties" to mapOf("city" to mapOf("type" to "string"), "temp" to mapOf("type" to "number")), "required" to listOf("city", "temp"), "additionalProperties" to false)
    private fun loop(
        model: ChatModelClient, tools: List<AgentTool> = emptyList(), policy: ApprovalPolicy = ApprovalPolicy(ApprovalHost.Unattended),
        limits: AgentLimits = AgentLimits(), parser: StructuredOutputParser? = null, budget: RunBudget? = null, force: Boolean = false,
        fallbacks: List<ChatModelClient> = emptyList(), costRates: CostRates? = null,
    ) = AgentLoop(model, ToolRegistry(tools), policy, limits, null, parser, costRates = costRates, nonceSource = { "N" }, forceToolFirst = force, budget = budget, fallbacks = fallbacks)

    private fun finalCall(args: Map<String, Any?>) = call(FinalAnswer.TOOL_NAME, args, id = "fin")

    // ---- force tool call first ----

    @Test fun `force tool first applies to the first request only`() = runBlocking {
        val model = ScriptedModel(call("search", mapOf("q" to "x")), say("done"))
        loop(model, listOf(FakeTool("search")), force = true).run(null, "go")
        assertEquals(ToolChoice.Required, model.requests[0].toolChoice); assertEquals(ToolChoice.Auto, model.requests[1].toolChoice)
        val plain = ScriptedModel(say("no tools needed"))
        loop(plain, listOf(FakeTool("search"))).run(null, "go")
        assertEquals(ToolChoice.Auto, plain.requests[0].toolChoice)
        val noTools = ScriptedModel(say("x"))
        loop(noTools, force = true).run(null, "go")
        assertEquals(ToolChoice.Auto, noTools.requests[0].toolChoice)
    }

    // ---- final answer tool ----

    @Test fun `structured output through the final answer tool needs no repair call`() = runBlocking {
        val model = ScriptedModel(finalCall(mapOf("city" to "Pune", "temp" to 31)))
        val r = loop(model, parser = StructuredOutputParser(schema)).run(null, "weather")
        assertTrue(r.isSuccess); assertEquals(mapOf("city" to "Pune", "temp" to 31), r.structured); assertEquals(1, model.requests.size)
        assertEquals(FinalAnswer.TOOL_NAME, model.requests[0].tools.single().name); assertTrue(model.requests[0].system!!.contains(FinalAnswer.TOOL_NAME))
    }

    @Test fun `an invalid final answer goes back to the model as a tool error and a retry succeeds`() = runBlocking {
        val model = ScriptedModel(finalCall(mapOf("city" to "Pune")), finalCall(mapOf("city" to "Pune", "temp" to "31")))
        val r = loop(model, parser = StructuredOutputParser(schema)).run(null, "weather")
        assertTrue(r.isSuccess); assertEquals(31L, (r.structured as Map<*, *>)["temp"])
        val fb = model.requests[1].messages.last()
        assertTrue(fb.isError); assertTrue(fb.text.startsWith("There was an error:")); assertTrue(fb.text.contains("temp"))
    }

    @Test fun `text answers still parse and repair when the model ignores the tool`() = runBlocking {
        val model = ScriptedModel(say("Here: {\"city\":\"Pune\",\"temp\":31}"))
        val r = loop(model, parser = StructuredOutputParser(schema)).run(null, "weather")
        assertTrue(r.isSuccess); assertEquals(mapOf("city" to "Pune", "temp" to 31L), r.structured)
        val repair = ScriptedModel(say("not json"), say("{\"city\":\"Pune\",\"temp\":1}"))
        assertTrue(loop(repair, parser = StructuredOutputParser(schema)).run(null, "w").isSuccess); assertEquals(2, repair.requests.size)
    }

    @Test fun `a non object schema is wrapped for the tool and unwrapped for the caller`() = runBlocking {
        val p = ItemListParser.structured()
        val r = loop(ScriptedModel(finalCall(mapOf("output" to listOf("a", "b")))), parser = p).run(null, "list")
        assertEquals(listOf("a", "b"), r.structured)
        assertEquals(listOf("x", "y"), loop(ScriptedModel(say("- x\n- y")), parser = p).run(null, "list").structured)
    }

    @Test fun `final answer next to other tool calls waits and other tools run first`() = runBlocking {
        val both = ChatResponse("", listOf(ToolCall("s", "search", mapOf("q" to "x")), ToolCall("f", FinalAnswer.TOOL_NAME, mapOf("city" to "P", "temp" to 1))), Usage(1, 1), FinishReason.ToolCalls)
        val model = ScriptedModel(both, finalCall(mapOf("city" to "P", "temp" to 1)))
        val tool = FakeTool("search")
        val r = loop(model, listOf(tool), parser = StructuredOutputParser(schema)).run(null, "go")
        assertTrue(r.isSuccess); assertEquals(1, tool.calls)
        assertTrue(model.requests[1].messages.last { it.toolCallId == "f" }.isError)
    }

    @Test fun `forced first call leaves the final tool out so real tools get used`() = runBlocking {
        val model = ScriptedModel(call("search", mapOf("q" to "x")), finalCall(mapOf("city" to "P", "temp" to 1)))
        loop(model, listOf(FakeTool("search")), parser = StructuredOutputParser(schema), force = true).run(null, "go")
        assertEquals(listOf("search"), model.requests[0].tools.map { it.name }); assertEquals(listOf("search", FinalAnswer.TOOL_NAME), model.requests[1].tools.map { it.name })
    }

    // ---- lenient tools and error contract ----

    @Test fun `a plain text argument reaches a single parameter tool and errors have the stable shape`() = runBlocking {
        val calc = FakeTool("calc", parameters = mapOf("type" to "object", "properties" to mapOf("expression" to mapOf("type" to "string")), "required" to listOf("expression"))) { ToolOutcome("= ${it["expression"]}") }
        val model = ScriptedModel(call("calc", err = "arguments are not a valid JSON object").let { r -> r.copy(toolCalls = r.toolCalls.map { it.copy(rawArguments = "2+2") }) }, say("four"))
        val r = loop(model, listOf(calc)).run(null, "go")
        assertTrue(r.isSuccess); assertTrue(model.requests[1].messages.last().text.contains("= 2+2"))
        val bad = ScriptedModel(call("nope"), say("ok"))
        loop(bad, listOf(calc)).run(null, "go")
        assertTrue(bad.requests[1].messages.last().text.startsWith("There was an error: There is no tool named"))
    }

    @Test fun `a failing http tool reports the status first and a capped redacted body`() = runBlocking {
        val tool = FakeTool("api") { ToolOutcome("Unauthorized", isError = true, httpStatus = 401, errorBody = "k=sk-live-ABCDEFGHIJKLMNOP " + "x".repeat(3_000)) }
        val model = ScriptedModel(call("api", mapOf("q" to "x")), say("ok"))
        AgentLoop(model, ToolRegistry(listOf(tool)), redactor = Redactor(listOf("sk-live-ABCDEFGHIJKLMNOP")), nonceSource = { "N" }).run(null, "go")
        val text = model.requests[1].messages.last().text
        assertTrue(text.contains("HTTP 401 There was an error: Unauthorized")); assertFalse(text.contains("sk-live")); assertTrue(text.length < 2_400)
    }

    // ---- approvals: clock and feedback ----

    private val risky = FakeTool("send_sms", ToolRisk.ExternalEffect)

    @Test fun `waiting for an approval does not use up the working time`() = runBlocking {
        val approver = ApprovalCallback { delay(500); ApprovalDecision.Approved }
        val model = ScriptedModel(call("send_sms", mapOf("q" to "hi")), say("sent"))
        val r = loop(model, listOf(risky), ApprovalPolicy(ApprovalHost.Interactive, approver), AgentLimits(maxWallMs = 250)).run(null, "go")
        assertTrue(r.outcome.name, r.isSuccess); assertEquals(1, risky.calls)
    }

    @Test fun `working time still runs out when the model is slow`() = runBlocking {
        val slow = object : ChatModelClient { override suspend fun complete(request: ChatRequest): ChatResponse { delay(5_000); return say("late") } }
        assertEquals(AgentOutcome.Timeout, loop(slow, limits = AgentLimits(maxWallMs = 150)).run(null, "go").outcome)
    }

    @Test fun `an approval that is never answered becomes a refusal after its own limit`() = runBlocking {
        val never = ApprovalCallback { delay(60_000); ApprovalDecision.Approved }
        val model = ScriptedModel(call("send_sms", mapOf("q" to "hi")), say("could not"))
        val r = loop(model, listOf(risky), ApprovalPolicy(ApprovalHost.Interactive, never), AgentLimits(approvalTimeoutMs = 100)).run(null, "go")
        assertTrue(r.isSuccess); assertEquals(0, risky.calls); assertEquals(ToolStatus.Denied, r.trace.single().status)
        assertTrue(model.requests[1].messages.last().text.startsWith("Not allowed"))
    }

    @Test fun `the persons feedback reaches the model in its own words`() = runBlocking {
        val approver = ApprovalCallback { ApprovalDecision.Denied("the person said no", "send it to Ravi, not Asha") }
        val model = ScriptedModel(call("send_sms", mapOf("q" to "hi")), say("ok, will adjust"))
        loop(model, listOf(risky), ApprovalPolicy(ApprovalHost.Interactive, approver)).run(null, "go")
        val text = model.requests[1].messages.last().text
        assertTrue(text.startsWith("Not allowed")); assertTrue(text.contains("send it to Ravi, not Asha")); assertFalse(text.contains("Do not call send_sms again"))
    }

    @Test fun `the relay carries feedback and events show the approval round trip`() = runBlocking {
        val relay = ApprovalRelay(); relay.attach()
        val model = ScriptedModel(call("send_sms", mapOf("q" to "hi")), say("fine"))
        val agent = loop(model, listOf(risky), ApprovalPolicy(ApprovalHost.Interactive, relay))
        val events = ArrayList<AgentEvent>()
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) { agent.run(null, "go") { events += it } }
        withTimeout(3_000) { relay.pending.first { it != null } }
        relay.respond(false, feedback = "  not now  ")
        job.join()
        val resolved = events.filterIsInstance<AgentEvent.ApprovalResolved>().single()
        assertFalse(resolved.approved); assertEquals("not now", resolved.feedback)
        assertTrue(events.indexOfFirst { it is AgentEvent.ApprovalRequested } < events.indexOfFirst { it is AgentEvent.ApprovalResolved })
    }

    // ---- length handling ----

    @Test fun `a cut answer gets one continue call and the pieces are joined`() = runBlocking {
        val cut = ChatResponse("The first half ", usage = Usage(5, 5), finish = FinishReason.Length)
        val model = ScriptedModel(cut, say("and the second."))
        val r = loop(model).run(null, "write")
        assertEquals("The first half and the second.", r.text); assertFalse(r.truncated)
        val msgs = model.requests[1].messages
        assertEquals(listOf(Role.User, Role.Assistant, Role.User), msgs.map { it.role })
        val again = ScriptedModel(cut, cut.copy(text = "more "))
        val t = loop(again).run(null, "write")
        assertTrue(t.truncated); assertTrue(t.isSuccess); assertEquals("The first half more", t.text)
    }

    @Test fun `an empty answer cut by the limit says why`() = runBlocking {
        val r = loop(ScriptedModel(ChatResponse("", finish = FinishReason.Length, usage = Usage(1, 1)))).run(null, "go")
        assertEquals("model_empty", r.error!!.code); assertTrue(r.error!!.message.contains("output tokens"))
    }

    // ---- events ----

    @Test fun `the event flow tells what really happened and ends with done`() = runBlocking {
        val model = ScriptedModel(call("search", mapOf("q" to "cats"), text = "Looking."), say("Found cats."))
        val events = withTimeout(5_000) { loop(model, listOf(FakeTool("search"))).runStreaming("i", "find").toList() }
        val names = events.map { it::class.simpleName }
        assertEquals("RunStarted", names.first()); assertEquals("Done", names.last())
        assertEquals(2, events.count { it is AgentEvent.StepStarted }); assertEquals(2, events.count { it is AgentEvent.StepFinished })
        val tool = events.filterIsInstance<AgentEvent.ToolFinished>().single(); assertEquals(ToolStatus.Ok, tool.status)
        assertTrue(events.indexOfFirst { it is AgentEvent.ToolRunning } < events.indexOfFirst { it is AgentEvent.ToolFinished })
        assertEquals("Looking.", events.filterIsInstance<AgentEvent.TextDelta>().first().text)
        assertEquals("Found cats.", (events.last() as AgentEvent.Done).result.text)
        assertTrue(events.filterIsInstance<AgentEvent.UsageUpdate>().last().usage.totalTokens > 0)
    }

    @Test fun `a failed run emits an error before done`() = runBlocking {
        val boom = object : ChatModelClient { override suspend fun complete(request: ChatRequest): ChatResponse = throw ModelException(ModelErrorKind.Auth, "bad key", 401) }
        val events = loop(boom).runStreaming(null, "go").toList()
        val err = (events[events.size - 2] as AgentEvent.Error).error
        assertEquals(ModelErrorKind.Auth, err.modelErrorKind); assertEquals(401, err.status); assertFalse(err.retryable); assertNotNull(err.hint)
        assertTrue(events.last() is AgentEvent.Done)
    }

    @Test fun `streaming through a real client forwards deltas as they come`() = runBlocking {
        val gw = FakeStreamGateway { StreamingHttpResponse(200, emptyMap(), sse(
            """{"choices":[{"delta":{"content":"He"}}]}""", """{"choices":[{"delta":{"content":"llo"},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}""", "[DONE]")) }
        val client = ChatModels.create(gw, ModelConfig(ProviderKind.OpenAiCompatible, "gpt-4o-mini", apiKey = ApiKey("sk-test-123456"), retry = RetryPolicy.None))
        val events = AgentLoop(client, nonceSource = { "N" }).runStreaming(null, "hi").toList()
        assertEquals(listOf("He", "llo"), events.filterIsInstance<AgentEvent.TextDelta>().map { it.text })
        val r = (events.last() as AgentEvent.Done).result
        assertEquals("Hello", r.text); assertEquals(Usage(3, 2), r.usage); assertEquals(setOf("gpt-4o-mini"), r.usageByModel.keys)
        assertNotNull(r.cost!!.usd); assertTrue(r.estimatedCost!! > 0.0)
    }

    // ---- usage, cost, budget ----

    @Test fun `unknown prices stay unknown and tokens are estimated when none are reported`() = runBlocking {
        val r = loop(ScriptedModel(ChatResponse("a".repeat(80)))).run(null, "task")
        assertTrue(r.usage.estimated); assertTrue(r.usage.totalTokens > 0); assertNull(r.estimatedCost); assertTrue(r.cost!!.estimated)
    }

    @Test fun `a priced model gets a dollar estimate per model`() = runBlocking {
        val r = loop(ScriptedModel(say("hi", 1_000).copy(model = "gpt-4o-mini"))).run(null, "task")
        assertEquals((1_000 * 0.15 + 1_000 * 0.60) / 1e6, r.estimatedCost!!, 1e-12); assertEquals(Usage(1_000, 1_000), r.usageByModel["gpt-4o-mini"])
    }

    @Test fun `the cost cap stops the run before the next model call`() = runBlocking {
        val many = ScriptedModel(mutableListOf({ _: ChatRequest -> call("search", mapOf("q" to "x"), tokens = 1_000) }, { _: ChatRequest -> call("search", mapOf("q" to "y"), tokens = 1_000) }, { _: ChatRequest -> say("never") }))
        val r = loop(many, listOf(FakeTool("search")), limits = AgentLimits(maxCostUnits = 1.0), costRates = CostRates(1.0, 1.0)).run(null, "go")
        assertEquals(AgentOutcome.CostLimit, r.outcome); assertEquals(1, many.requests.size)
    }

    @Test fun `a shared budget stops a later run before it calls the model`() = runBlocking {
        val budget = RunBudget(maxTokens = 2_000)
        val first = loop(ScriptedModel(say("a", 1_500)), budget = budget).run(null, "one")
        assertTrue(first.isSuccess); assertEquals(3_000L, budget.spentTokens)
        val second = ScriptedModel(say("b"))
        val r = loop(second, budget = budget).run(null, "two")
        assertEquals(AgentOutcome.TokenLimit, r.outcome); assertTrue(second.requests.isEmpty())
        val tiny = RunBudget(maxTokens = 5)
        val pre = loop(ScriptedModel(say("x")), budget = tiny).run(null, "a long enough prompt to pass five tokens easily")
        assertEquals(AgentOutcome.TokenLimit, pre.outcome)
    }

    @Test fun `fallback result carries the flag through the loop with a stream too`() = runBlocking {
        val boom = object : ChatModelClient { override suspend fun complete(request: ChatRequest): ChatResponse = throw ModelException(ModelErrorKind.Server, "down", 500) }
        val events = loop(boom, fallbacks = listOf(ScriptedModel(say("ok")))).runStreaming(null, "go").toList()
        assertTrue(events.filterIsInstance<AgentEvent.Notice>().any { it.kind == NoticeKind.Fallback }); assertTrue((events.last() as AgentEvent.Done).result.fallbackUsed)
    }
}
