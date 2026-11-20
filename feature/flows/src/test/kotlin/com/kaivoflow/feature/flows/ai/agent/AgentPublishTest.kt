package com.kaivoflow.feature.flows.ai.agent

import com.kaivoflow.core.engine.ActivationResult
import com.kaivoflow.core.engine.ActivationStatus
import com.kaivoflow.core.engine.FlowValidator
import com.kaivoflow.core.engine.LifecycleSnapshot
import com.kaivoflow.core.engine.LifecycleState
import com.kaivoflow.core.engine.PlatformFacts
import com.kaivoflow.core.engine.PublishContext
import com.kaivoflow.core.engine.PublishPreview
import com.kaivoflow.core.engine.PublishValidator
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.StepRun
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.nodes.NodeCatalog
import com.kaivoflow.feature.flows.ai.AiProvider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow as KFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPublishTest {
    private val registry = NodeCatalog.registry()
    private val cred = AgentCredentialInfo("cred-1", AiProvider.OpenAI, "")

    private fun agent(
        triggers: List<AgentTrigger> = listOf(AgentTrigger(id = "t1", type = "schedule", name = "Daily", config = AgentTriggerConfig.dailyAt("09:00"))),
        tools: List<AgentTool> = emptyList(),
        settings: AgentSettings = AgentSettings(),
        memory: Boolean = false,
        instructions: String = "Be brief.",
    ) = CustomAgent(id = "a1", name = "Brief", credentialId = "cred-1", instructions = instructions, triggers = triggers, tools = tools, settings = settings, crossSessionMemoryEnabled = memory)

    private fun compile(a: CustomAgent, c: AgentCredentialInfo? = cred, p: CredentialProbe = CredentialProbe.NotChecked) = AgentProductionCompiler.compile(a, c, p)
    private fun codes(r: ProductionCompile) = r.issues.map { it.code }

    @Test fun `a valid scheduled agent compiles to a tagged hidden flow that the real validators accept`() {
        val r = compile(agent(tools = listOf(AgentTool(type = "device", name = "Notify", allowUnattended = true))))
        val flow = r.flow!!
        assertEquals("agent:a1", flow.id)
        assertTrue(AgentIds.TAG in flow.tags && AgentIds.isAgentFlow(flow.id) && AgentIds.agentId(flow.id) == "a1")
        assertEquals(listOf("spark.schedule", "ai.agent", "device.notify"), flow.steps.map { it.type })
        assertEquals(2, flow.streams.size)
        assertTrue(r.issues.isEmpty())
        val structure = FlowValidator(registry).validate(flow)
        assertEquals(emptyList<Any>(), structure.issues.filter { it.severity == com.kaivoflow.core.engine.IssueSeverity.Blocking })
        val report = PublishValidator(FlowValidator(registry)).validate(flow, PublishContext(PlatformFacts.AllGood))
        assertEquals(emptyList<Any>(), report.errors)
        val agentStep = flow.step("agent")!!
        assertEquals(emptyList<Any>(), registry.executor("ai.agent")!!.let { it.validate(agentStep, it.spec) })
        val tool = (agentStep.params["tools"] as List<*>).single() as Map<*, *>
        assertEquals(true, tool["allowUnattended"])
    }

    @Test fun `the key never enters the flow, only the credential id`() {
        val p = compile(agent()).flow!!.steps.joinToString { it.params.toString() }
        assertTrue(p.contains("cred-1")); assertFalse(p.contains("apiKey"))
    }

    @Test fun `schedule settings and legacy cron map to real schedule parameters`() {
        fun p(cfg: Map<String, String>) = AgentTriggerConfig.schedule(cfg)
        assertEquals("daily" to "09:00", (p(mapOf("cron" to "0 9 * * *")) as AgentTriggerConfig.Mapped.Ok).params.let { it["frequency"] to it["time"] })
        assertEquals("hourly", (p(mapOf("cron" to "0 * * * *")) as AgentTriggerConfig.Mapped.Ok).params["frequency"])
        assertEquals(15, (p(mapOf("cron" to "*/15 * * * *")) as AgentTriggerConfig.Mapped.Ok).params["interval"])
        val weekly = (p(mapOf("cron" to "30 7 * * 0,1")) as AgentTriggerConfig.Mapped.Ok).params
        assertEquals("1,7", weekly["daysOfWeek"]); assertEquals("07:30", weekly["time"])
        assertEquals(5, (p(mapOf("cron" to "0 6 5 * *")) as AgentTriggerConfig.Mapped.Ok).params["dayOfMonth"])
        assertTrue(p(mapOf("cron" to "0 6 31 * *")) is AgentTriggerConfig.Mapped.Bad)
        assertTrue(p(mapOf("cron" to "0 6 * 3 *")) is AgentTriggerConfig.Mapped.Bad)
        assertTrue(p(mapOf("cron" to "garbage")) is AgentTriggerConfig.Mapped.Bad)
        assertTrue(p(emptyMap()) is AgentTriggerConfig.Mapped.Bad)
        assertTrue(p(mapOf("frequency" to "daily", "time" to "25:00")) is AgentTriggerConfig.Mapped.Bad)
        assertTrue(p(mapOf("frequency" to "yearly")) is AgentTriggerConfig.Mapped.Bad)
    }

    @Test fun `every refusal has a stable code and nothing is compiled`() {
        fun refused(a: CustomAgent, c: AgentCredentialInfo? = cred, p: CredentialProbe = CredentialProbe.NotChecked): List<String> { val r = compile(a, c, p); assertNull(r.flow); return codes(r) }
        assertTrue("credential_missing" in refused(agent().copy(credentialId = null), null))
        assertTrue("credential_not_found" in refused(agent(), null))
        assertTrue("credential_failed" in refused(agent(), cred, CredentialProbe.Failed("401")))
        assertTrue("model_missing" in refused(agent().copy(model = " ")))
        assertTrue("instructions_empty" in refused(agent(instructions = " ")))
        assertTrue("endpoint_refused" in refused(agent(), AgentCredentialInfo("cred-1", AiProvider.OpenAI, "http://169.254.169.254")))
        assertTrue("endpoint_refused" in refused(agent(), AgentCredentialInfo("cred-1", AiProvider.OpenAI, "http://192.168.1.5:11434")))
        assertTrue("budget_tokens" in refused(agent(settings = AgentSettings(maxTotalTokens = 0))))
        assertTrue("budget_tokens" in refused(agent(settings = AgentSettings(maxTotalTokens = 9_000_000))))
        assertTrue("limit_timeout" in refused(agent(settings = AgentSettings(timeoutSeconds = 1))))
        assertTrue("limit_rate" in refused(agent(settings = AgentSettings(rateLimitPerTool = 0))))
        assertTrue("deliver_unknown" in refused(agent(settings = AgentSettings(deliverResult = "sms"))))
        assertTrue("tool_unavailable" in refused(agent(tools = listOf(AgentTool(type = "code", name = "Run")))))
        assertTrue("web_search_unavailable" in refused(agent().copy(webSearchEnabled = true)))
        assertTrue("trigger_unavailable" in refused(agent(triggers = listOf(AgentTrigger(type = "phone_event", name = "P")) + agent().triggers)))
        assertTrue("schedule_invalid" in refused(agent(triggers = listOf(AgentTrigger(type = "schedule", name = "S")))))
        assertTrue("no_live_trigger" in refused(agent(triggers = listOf(AgentTrigger(type = "manual", name = "M")))))
        assertTrue("no_live_trigger" in refused(agent(triggers = emptyList())))
        assertTrue("objective_conflict" in refused(agent(triggers = listOf(
            AgentTrigger(id = "a", type = "schedule", name = "A", config = AgentTriggerConfig.dailyAt("09:00") + ("objective" to "one")),
            AgentTrigger(id = "b", type = "schedule", name = "B", config = AgentTriggerConfig.dailyAt("10:00") + ("objective" to "two")),
        ))))
    }

    @Test fun `a disabled trigger or tool is not compiled and not checked`() {
        val a = agent(
            triggers = agent().triggers + AgentTrigger(id = "off", type = "phone_event", name = "P", isEnabled = false),
            tools = listOf(AgentTool(type = "code", name = "Run", isEnabled = false)),
        )
        assertNotNull(compile(a).flow)
    }

    @Test fun `a tool the author did not allow for unattended runs is flagged and stays locked`() {
        val r = compile(agent(tools = listOf(AgentTool(type = "device", name = "Notify"))))
        assertNotNull(r.flow)
        assertEquals(listOf("tool_not_unattended"), codes(r))
        val tool = (r.flow!!.step("agent")!!.params["tools"] as List<*>).single() as Map<*, *>
        assertEquals(false, tool["allowUnattended"])
    }

    @Test fun `memory gets a key from the trigger rules and webhook memory needs an explicit key`() {
        val withMem = compile(agent(memory = true))
        assertEquals("agent", (withMem.flow!!.step("agent")!!.params["memory"] as Map<*, *>)["key"])
        assertTrue("memory_volatile" in codes(withMem))
        val hook = AgentTrigger(id = "w", type = "webhook", name = "Hook")
        val noKey = compile(agent(triggers = listOf(hook), memory = true))
        assertNull(noKey.flow); assertTrue("memory_key" in codes(noKey))
        val keyed = compile(agent(triggers = listOf(hook.copy(config = mapOf("memoryKey" to "{{ \$json.body.chat }}"))), memory = true))
        assertEquals("{{ \$json.body.chat }}", (keyed.flow!!.step("agent")!!.params["memory"] as Map<*, *>)["key"])
        assertNull(compile(agent()).flow!!.step("agent")!!.params["memory"])
    }

    @Test fun `webhook agent reads the payload as data and uses a default private path`() {
        val r = compile(agent(triggers = listOf(AgentTrigger(id = "w", type = "webhook", name = "Hook"))))
        val flow = r.flow!!
        val spark = flow.steps.first { it.type == "spark.webhook" }
        assertEquals("agent/a1", spark.params["path"])
        assertNotNull(flow.step("agent")!!.params["data"])
        assertEquals(emptyList<Any>(), PublishValidator(FlowValidator(registry)).validate(flow, PublishContext(PlatformFacts.AllGood)).errors)
    }

    @Test fun `instructions with expressions stay expressions for the engine and plain text with braces is neutralised`() {
        val expr = compile(agent(instructions = com.kaivoflow.core.model.ExprFieldCodec.encode(com.kaivoflow.core.model.ParamValue.Expr("Hi {{ \$vars.name }}"))))
        val sys = expr.flow!!.step("agent")!!.params["system"] as String
        assertTrue(sys.contains("Hi {{ \$vars.name }}"))
        val plain = compile(agent(instructions = "Show the literal {{ x }} please"))
        assertFalse((plain.flow!!.step("agent")!!.params["system"] as String).contains("{{"))
    }

    @Test fun `delivery step is optional and the task always carries the current time`() {
        val none = compile(agent(settings = AgentSettings(deliverResult = "none"))).flow!!
        assertEquals(listOf("spark.schedule", "ai.agent"), none.steps.map { it.type })
        val prompt = none.step("agent")!!.params["prompt"] as String
        assertTrue(prompt.contains("{{ \$now }}"))
    }

    @Test fun `the expressions in the production steps evaluate with the real evaluator`() {
        val ev = com.kaivoflow.core.expressions.KaivoExpressionEvaluator()
        val a = agent(
            triggers = listOf(AgentTrigger(id = "w", type = "webhook", name = "Hook")),
            instructions = com.kaivoflow.core.model.ExprFieldCodec.encode(com.kaivoflow.core.model.ParamValue.Expr("Owner {{ \$vars.owner }}.")),
        )
        val flow = compile(a).flow!!
        val scope = com.kaivoflow.core.engine.GlobalScope.build(mapOf("owner" to "sam"), flowId = flow.id, flowName = flow.name, timezone = "UTC") +
            mapOf("json" to mapOf("body" to mapOf("note" to "hi \"there\"")))
        val params = flow.step("agent")!!.params
        val prompt = ev.interpolate(params["prompt"] as String, scope).toString()
        assertTrue(prompt, Regex("Current date and time: 20\\d\\d-\\d\\d-\\d\\dT").containsMatchIn(prompt))
        assertEquals("""{"body":{"note":"hi \"there\""}}""", ev.interpolate(params["data"] as String, scope).toString())
        assertEquals("Owner sam.", ev.interpolate(params["system"] as String, scope).toString())
        val body = flow.step("deliver")!!.params["body"] as String
        assertEquals("done", ev.interpolate(body, scope + mapOf("json" to mapOf("answer" to "done"))).toString())
    }

    @Test fun `republishing an unchanged agent is detected as no change and an edit as a change`() {
        val a = agent()
        val one = compile(a).flow!!
        assertFalse(com.kaivoflow.core.engine.ChangeDetector.differs(compile(a).flow!!, one))
        assertTrue(com.kaivoflow.core.engine.ChangeDetector.differs(compile(a.copy(instructions = "Different")).flow!!, one))
        assertFalse(com.kaivoflow.core.engine.ChangeDetector.differs(compile(a.copy(iconEmoji = "X", sessions = listOf(AgentSession(prompt = "p", response = "r")))).flow!!, one))
    }

    @Test fun `the test compile and the production compile share model, limits and output field`() {
        val a = agent(settings = AgentSettings(maxIterations = 7, maxToolCalls = 9, maxTotalTokens = 12_000, timeoutSeconds = 90))
        val test = AgentFlowCompiler.compile(a, "sys", "task", AiProvider.OpenAI, "gpt-4o-mini", "cred-1", "").flow.steps.single().params
        val prod = compile(a).flow!!.step("agent")!!.params
        for (k in listOf("maxIterations", "maxToolCalls", "maxTotalTokens", "timeoutSeconds", "outputField")) assertEquals(k, test[k].toString().removeSuffix(".0"), prod[k].toString().removeSuffix(".0"))
        assertEquals((test["chatModel"] as Map<*, *>)["model"], (prod["chatModel"] as Map<*, *>)["model"])
    }

    // ---- service ------------------------------------------------------------------------------------------------

    private class Repo(var agent: CustomAgent?) : AgentRepository {
        var deleted: String? = null
        override fun observeAgents(): KFlow<List<CustomAgent>> = flowOf(listOfNotNull(agent))
        override suspend fun agentById(id: String) = agent?.takeIf { it.id == id }
        override suspend fun saveAgent(agent: CustomAgent) { this.agent = agent }
        override suspend fun deleteAgent(id: String) { deleted = id; agent = null }
        override suspend fun duplicateAgent(id: String): CustomAgent? = null
    }

    private class Flows : AgentFlowPort {
        val saved = HashMap<String, Flow>()
        override suspend fun flow(id: String) = saved[id]
        override suspend fun save(flow: Flow) { saved[flow.id] = flow }
        override suspend fun delete(id: String) { saved.remove(id) }
        override fun observeAgentStates(): KFlow<Map<String, AgentLiveState>> = flowOf(emptyMap())
    }

    private class Engine(var snap: LifecycleSnapshot? = null, var next: ActivationResult? = null, var after: LifecycleSnapshot? = null) : AgentActivationPort {
        val calls = ArrayList<String>()
        private fun r(name: String): ActivationResult { calls += name; return next ?: ActivationResult(ActivationStatus.Done, snapshot = after ?: snap) }
        override suspend fun snapshot(flowId: String) = snap
        override suspend fun publish(flowId: String, acknowledgeWarnings: Boolean) = r("publish")
        override suspend fun publishChanges(flowId: String, acknowledgeWarnings: Boolean) = r("publishChanges")
        override suspend fun pause(flowId: String, untilMs: Long?) = r("pause")
        override suspend fun resume(flowId: String) = r("resume")
        override suspend fun unpublish(flowId: String) = r("unpublish")
        override suspend fun retryNow(flowId: String) = r("retry")
        override suspend fun delete(flowId: String) { calls += "delete" }
        override suspend fun preview(flowId: String): PublishPreview? = null
    }

    private class Creds(var probe: CredentialProbe = CredentialProbe.Working(true), var info: AgentCredentialInfo? = AgentCredentialInfo("cred-1", AiProvider.OpenAI, "")) : AgentCredentialPort {
        var probed = 0
        override suspend fun info(credentialId: String) = info
        override suspend fun probe(info: AgentCredentialInfo, model: String): CredentialProbe { probed++; return probe }
    }

    private val live = LifecycleSnapshot(LifecycleState.Published, publishedRevision = 1, since = 5)

    private fun svc(repo: Repo, flows: Flows, engine: Engine, creds: Creds) = AgentPublicationService(repo, flows, engine, creds) { 1000L }

    @Test fun `publish saves the hidden flow and hands it to the engine`() = runBlocking {
        val flows = Flows(); val engine = Engine(after = live)
        val out = svc(Repo(agent()), flows, engine, Creds()).publish("a1")
        assertTrue(out is AgentPublishOutcome.Done)
        assertEquals(AgentLiveState.Live(1, 5), (out as AgentPublishOutcome.Done).state)
        assertEquals(listOf("publish"), engine.calls)
        assertNotNull(flows.saved["agent:a1"])
    }

    @Test fun `a refused agent is never saved, never armed and the credential is not even called`() = runBlocking {
        val flows = Flows(); val engine = Engine(); val creds = Creds()
        val out = svc(Repo(agent(instructions = "")), flows, engine, creds).publish("a1")
        assertTrue(out is AgentPublishOutcome.Refused)
        assertTrue(flows.saved.isEmpty() && engine.calls.isEmpty() && creds.probed == 0)
    }

    @Test fun `a credential that does not work refuses the publish`() = runBlocking {
        val out = svc(Repo(agent()), Flows(), Engine(), Creds(probe = CredentialProbe.Failed("HTTP 401"))).publish("a1")
        assertTrue(out is AgentPublishOutcome.Refused && out.issues.any { it.code == "credential_failed" })
    }

    @Test fun `warnings need an explicit acknowledgement`() = runBlocking {
        val repo = Repo(agent(tools = listOf(AgentTool(type = "device", name = "Notify"))))
        val flows = Flows(); val engine = Engine(after = live)
        val s = svc(repo, flows, engine, Creds())
        assertTrue(s.publish("a1") is AgentPublishOutcome.NeedsAcknowledgement)
        assertTrue(flows.saved.isEmpty() && engine.calls.isEmpty())
        assertTrue(s.publish("a1", acknowledge = true) is AgentPublishOutcome.Done)
    }

    @Test fun `a blocked or failed engine publish leaves no orphan hidden flow and reports the engine text`() = runBlocking {
        val flows = Flows()
        val blocked = Engine(next = ActivationResult(ActivationStatus.Blocked, report = com.kaivoflow.core.engine.PublishReport(listOf(
            com.kaivoflow.core.engine.ValidationIssue("webhook_token", "needs a token", com.kaivoflow.core.engine.IssueSeverity.Blocking)))))
        val out = svc(Repo(agent()), flows, blocked, Creds()).publish("a1")
        assertEquals(AgentPublishOutcome.Blocked(listOf("needs a token")), out)
        assertTrue(flows.saved.isEmpty())
    }

    @Test fun `publishing again while live goes through publish changes, paused resumes first, error is refused`() = runBlocking {
        val flows = Flows().apply { saved["agent:a1"] = compile(agent()).flow!! }
        val liveEngine = Engine(snap = live)
        svc(Repo(agent()), flows, liveEngine, Creds()).publish("a1")
        assertEquals(listOf("publishChanges"), liveEngine.calls)
        val paused = Engine(snap = LifecycleSnapshot(LifecycleState.Paused, publishedRevision = 1))
        svc(Repo(agent()), flows, paused, Creds()).publish("a1")
        assertEquals(listOf("resume", "publishChanges"), paused.calls)
        val err = Engine(snap = LifecycleSnapshot(LifecycleState.Error, publishedRevision = 1, errorCode = "x"))
        assertTrue(svc(Repo(agent()), flows, err, Creds()).publish("a1") is AgentPublishOutcome.Failed)
        assertTrue(err.calls.isEmpty())
    }

    @Test fun `pause resume unpublish and delete go to the engine, delete disarms before it removes the agent`() = runBlocking {
        val repo = Repo(agent()); val engine = Engine(snap = live)
        val s = svc(repo, Flows(), engine, Creds())
        s.pause("a1"); s.resume("a1"); s.unpublish("a1"); s.retry("a1")
        assertEquals(listOf("pause", "resume", "unpublish", "retry"), engine.calls)
        s.delete("a1")
        assertEquals("delete", engine.calls.last())
        assertEquals("a1", repo.deleted)
    }

    @Test fun `live state comes only from the lifecycle`() {
        assertEquals(AgentLiveState.NotPublished, AgentLiveState.from(null))
        assertEquals(AgentLiveState.NotPublished, AgentLiveState.from(LifecycleSnapshot()))
        assertTrue(AgentLiveState.from(live) is AgentLiveState.Live)
        assertTrue(AgentLiveState.from(LifecycleSnapshot(LifecycleState.Paused, pausedUntil = 9)) is AgentLiveState.Paused)
        assertTrue(AgentLiveState.from(LifecycleSnapshot(LifecycleState.Error, errorCode = "perm")) is AgentLiveState.Failing)
    }

    @Test fun `display state marks changes that are not live`() = runBlocking {
        val a = agent()
        val flows = Flows().apply { saved["agent:a1"] = compile(a).flow!! }
        val s = svc(Repo(a), flows, Engine(snap = live), Creds())
        assertEquals(false, (s.displayState(a, AgentLiveState.Live(1, 5)) as AgentLiveState.Live).changesNotLive)
        assertEquals(true, (s.displayState(a.copy(instructions = "new"), AgentLiveState.Live(1, 5)) as AgentLiveState.Live).changesNotLive)
        assertEquals(true, (s.displayState(a.copy(credentialId = null), AgentLiveState.Live(1, 5)) as AgentLiveState.Live).changesNotLive)
        assertEquals(AgentLiveState.NotPublished, s.displayState(a, AgentLiveState.NotPublished))
    }

    @Test fun `a run record becomes a session with the answer or the failure text`() {
        fun run(status: RunStatus, answer: String?, err: String?) = RunRecord(
            id = "r", flowId = "agent:a1", flowName = "Brief", status = status, startedAt = 10, finishedAt = 25, trigger = "Schedule", error = err,
            stepRuns = persistentListOf(StepRun("agent", "Brief", StepRunStatus.Succeeded, 10, 25, output = persistentListOf(FlowData(json = mapOf("answer" to answer))))),
        )
        val ok = AgentRunHistory.toSession(run(RunStatus.Succeeded, "Done!", null))
        assertEquals("Success" to "Done!", ok.status to ok.response)
        assertEquals(15L, ok.durationMs)
        val bad = AgentRunHistory.toSession(run(RunStatus.Failed, null, "the model refused"))
        assertEquals("Error" to "the model refused", bad.status to bad.response)
    }
}
