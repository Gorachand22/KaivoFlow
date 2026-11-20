package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.ApprovalRelay
import com.kaivoflow.core.ai.ChatModelClient
import com.kaivoflow.core.ai.ChatRequest
import com.kaivoflow.core.ai.ChatResponse
import com.kaivoflow.core.ai.FinishReason
import com.kaivoflow.core.ai.StreamEvent
import com.kaivoflow.core.ai.ToolCall
import com.kaivoflow.core.ai.Usage
import com.kaivoflow.core.ai.flowedit.AssistantCatalog
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.nodes.CatalogAliases
import com.kaivoflow.core.nodes.NodeCatalog
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow as CFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real agent loop behind the chat, with a scripted streaming model. No network, no storage. */
class AiWorkflowAgentTest {
    private val catalog = AssistantCatalog(NodeCatalog.registry(), CatalogAliases.renames)
    private val config = AiProviderConfig(apiKey = "sk-test-123456")
    private val journal = ArrayList<Pair<String, String>>()
    private val usage = ArrayList<List<Any?>>()

    private fun agent(client: ChatModelClient) = AiWorkflowAgent(
        { client }, catalog, ApprovalRelay(),
        { provider, model, i, o, micros, _, runId, _ -> usage += listOf(provider, model, i, o, micros, runId) },
    ).also { it.tickMs = 60_000 }

    /** One model call per script entry; each entry is a function that emits the call's events. */
    private class Scripted(vararg val calls: suspend kotlinx.coroutines.flow.FlowCollector<StreamEvent>.() -> Unit) : ChatModelClient {
        var n = 0
        override suspend fun complete(request: ChatRequest): ChatResponse = error("the agent must stream")
        override fun stream(request: ChatRequest): CFlow<StreamEvent> { val c = calls[n++]; return flow { c() } }
    }

    private val addCall = ToolCall("c1", "add_step", mapOf("type" to "device.notify", "name" to "Notify", "params" to mapOf("title" to "Hi", "body" to "There")))

    private fun toolRound(gate: CompletableDeferred<Unit>? = null): suspend kotlinx.coroutines.flow.FlowCollector<StreamEvent>.() -> Unit = {
        emit(StreamEvent.ThinkingDelta("I should add a notification."))
        emit(StreamEvent.TextDelta("Adding it."))
        gate?.await()
        emit(StreamEvent.ToolCallStart(0, "c1", "add_step"))
        emit(StreamEvent.ToolCallArgsDelta(0, "{\"type\":\"device.notify\""))
        emit(StreamEvent.ToolCallEnd(0, addCall))
        emit(StreamEvent.Done(ChatResponse("Adding it.", listOf(addCall), Usage(100, 20), FinishReason.ToolCalls, "I should add a notification.", model = "gpt-4o-mini")))
    }

    private val answerRound: suspend kotlinx.coroutines.flow.FlowCollector<StreamEvent>.() -> Unit = {
        emit(StreamEvent.TextDelta("Done, "))
        emit(StreamEvent.TextDelta("it is added."))
        emit(StreamEvent.Done(ChatResponse("Done, it is added.", usage = Usage(130, 6), model = "gpt-4o-mini")))
    }

    private val base = Flow(
        id = "f1", name = "Mine",
        steps = persistentListOf(StepNode("a", "spark.manual", "Start", persistentMapOf(), 0f, 0f)),
        streams = persistentListOf(), vars = persistentMapOf(), createdAt = 1, updatedAt = 100,
    )

    @Test fun `the answer arrives in pieces while the model is still talking`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val a = agent(Scripted(toolRound(gate), answerRound))
        val seen = ArrayList<AgentStreamEvent>()
        val first = CompletableDeferred<AiChatMessage>()
        val job = async(Dispatchers.Default) {
            a.executeTurnStream("add a notification", base, config, emptyList(), "m1") { k, p -> synchronized(journal) { journal += k to p } }.collect {
                seen += it
                if (it is AgentStreamEvent.Progress && it.message.text == "Adding it.") first.complete(it.message)
            }
        }
        val early = withTimeout(5_000) { first.await() }
        // The model has not finished its first call (the gate is closed), yet text and thinking are on screen.
        assertEquals("Adding it.", early.text)
        assertEquals("I should add a notification.", early.thought!!.reasoningText)
        assertTrue(early.isStreaming && early.thought!!.isActive)
        assertTrue(early.thought!!.steps.isEmpty())
        gate.complete(Unit)
        withTimeout(5_000) { job.await() }
        val fin = seen.last() as AgentStreamEvent.Finished
        assertEquals("Done, it is added.", fin.finalMessage.text)
        assertEquals(TurnStatus.Completed, fin.finalMessage.status)
        assertFalse(fin.finalMessage.isStreaming); assertFalse(fin.finalMessage.thought!!.isActive)
    }

    @Test fun `a tool call shows its real arguments and result and no invented step exists`() = runBlocking {
        val a = agent(Scripted(toolRound(), answerRound))
        val events = a.executeTurnStream("add a notification", base, config, emptyList(), "m1").toList()
        val fin = (events.last() as AgentStreamEvent.Finished).finalMessage
        val steps = fin.thought!!.steps
        assertEquals(listOf("Adding step >"), steps.map { it.label })
        assertTrue(steps.single().inputJson!!.contains("\"type\": \"device.notify\""))
        assertTrue(steps.single().inputJson!!.contains("\"title\": \"Hi\""))
        assertTrue(steps.single().isSuccess && !steps.single().running)
        assertTrue(steps.single().resultJson!!.isNotBlank())
        assertTrue(steps.single().durationMs != null)
        val banned = listOf("Read instance context", "Opening skill", "Resolving LLM", "299f72cdc63f", "device-automation", "agent-builder")
        events.filterIsInstance<AgentStreamEvent.Progress>().map { it.message }.plus(fin).forEach { m ->
            val all = (m.thought?.steps.orEmpty().flatMap { listOf(it.label, it.inputJson, it.resultJson) } + m.text + m.thought?.reasoningText + m.thought?.currentActivity)
            banned.forEach { b -> assertTrue("found '$b'", all.none { it?.contains(b) == true }) }
        }
        assertEquals("I should add a notification.\n\nAdding it.", fin.thought!!.reasoningText)
        assertEquals(1, fin.toolCalls.count { it.toolName == "add_step" })
        assertTrue(fin.flowProposal != null && fin.flowProposal!!.steps.size == 2)
    }

    @Test fun `the assistant edits a private draft only, the open flow is untouched until Apply`() = runBlocking {
        val before = base
        val a = agent(Scripted(toolRound(), answerRound))
        val fin = (a.executeTurnStream("add", before, config, emptyList(), "m1").toList().last() as AgentStreamEvent.Finished).finalMessage
        assertSame(before, base)
        assertEquals(1, base.steps.size)
        assertEquals(2, fin.flowProposal!!.steps.size)
        assertNull(fin.baseUpdatedAt) // the screen stamps it; the agent has no repository
    }

    @Test fun `every model call goes to the usage ledger and the log gets the real events in order`() = runBlocking {
        val a = agent(Scripted(toolRound(), answerRound))
        a.executeTurnStream("add", base, config, emptyList(), "m1") { k, p -> journal += k to p }.toList()
        assertEquals(2, usage.size)
        assertEquals(listOf("openai", "gpt-4o-mini", 100L, 20L), usage[0].take(4))
        assertEquals("m1", usage[0][5])
        assertEquals(listOf(130L, 6L), usage[1].subList(2, 4))
        val kinds = journal.map { it.first }
        assertEquals("turn_started", kinds.first()); assertEquals("turn_ended", kinds.last())
        assertTrue(kinds.containsAll(listOf("thinking", "text", "tool_call", "tool_result", "step")))
        assertTrue(kinds.indexOf("tool_call") < kinds.indexOf("tool_result"))
        assertTrue(journal.last().second.contains("completed"))
        assertFalse(journal.any { it.second.contains("sk-test-123456") })
    }

    @Test fun `stop cancels the model stream, and the partial text is what the screen already has`() = runBlocking {
        val opened = CompletableDeferred<Unit>(); val closed = CompletableDeferred<Unit>()
        val a = agent(Scripted({
            emit(StreamEvent.TextDelta("Started to ans")); opened.complete(Unit); awaitCancellation()
        }).let { s -> object : ChatModelClient by s { override fun stream(request: ChatRequest) = s.stream(request).onCompletion { closed.complete(Unit) } } })
        var last: AiChatMessage? = null
        val job = launch(Dispatchers.Default) {
            a.executeTurnStream("hi there", base, config, emptyList(), "m1").collect { if (it is AgentStreamEvent.Progress) last = it.message }
        }
        withTimeout(5_000) { opened.await() }
        while (last?.text != "Started to ans") kotlinx.coroutines.delay(5)
        job.cancelAndJoin()
        withTimeout(2_000) { closed.await() }
        val stopped = AssistantTurnReducer.markCut(last!!, TurnStatus.Stopped)
        assertEquals("Started to ans\n\n*Stopped.*", stopped.text)
        assertEquals(TurnStatus.Stopped, stopped.status)
    }

    @Test fun `an error in the middle of the stream ends as a failed turn that keeps what was shown`() = runBlocking {
        val a = agent(Scripted({
            emit(StreamEvent.ThinkingDelta("thinking so far"))
            emit(StreamEvent.TextDelta("Half an ans"))
            throw com.kaivoflow.core.ai.ModelException(com.kaivoflow.core.ai.ModelErrorKind.Network, "The provider stream ended before the answer was complete.")
        }))
        val events = a.executeTurnStream("hi there", base, config, emptyList(), "m1") { k, p -> journal += k to p }.toList()
        val err = events.last() as AgentStreamEvent.Error
        assertEquals(TurnStatus.Failed, err.errorMessage.status)
        assertTrue(err.errorMessage.text.startsWith("Half an ans"))
        assertTrue(err.errorMessage.text.contains("AI Provider Error"))
        assertEquals("thinking so far", err.errorMessage.thought!!.reasoningText)
        assertTrue(journal.last().second.contains("failed"))
    }

    @Test fun `an unconfigured provider says so and never calls the model`() = runBlocking {
        val s = Scripted()
        val ev = agent(s).executeTurnStream("hi", base, AiProviderConfig(apiKey = ""), emptyList(), "m1").toList()
        assertEquals(1, ev.size)
        assertTrue((ev.single() as AgentStreamEvent.Error).errorMessage.text.contains("Not Configured"))
        assertEquals(0, s.n)
    }

    @Test fun `the canned greeting makes no model call and shows no thought block`() = runBlocking {
        val s = Scripted()
        val ev = agent(s).executeTurnStream("hello", null, config, emptyList(), "m1").toList()
        val fin = (ev.last() as AgentStreamEvent.Finished).finalMessage
        assertNull(fin.thought); assertEquals(0, s.n)
        assertTrue(usage.isEmpty())
    }

    @Test fun `history keeps finished turns and drops stopped or failed ones`() = runBlocking {
        val seen = ArrayList<ChatRequest>()
        val client = object : ChatModelClient {
            override suspend fun complete(request: ChatRequest): ChatResponse = error("stream")
            override fun stream(request: ChatRequest): CFlow<StreamEvent> = flow { seen += request; emit(StreamEvent.TextDelta("ok")); emit(StreamEvent.Done(ChatResponse("ok", usage = Usage(1, 1)))) }
        }
        val history = listOf(
            AiChatMessage(sender = MessageSender.User, text = "first"),
            AiChatMessage(sender = MessageSender.Assistant, text = "partial *Stopped.*", status = TurnStatus.Stopped),
            AiChatMessage(sender = MessageSender.User, text = "second"),
            AiChatMessage(sender = MessageSender.Assistant, text = "full answer"),
            AiChatMessage(sender = MessageSender.User, text = "now"),
        )
        agent(client).executeTurnStream("now", base, config, history, "m1").toList()
        val texts = seen.first().messages.map { it.text }
        assertTrue(texts.contains("full answer")); assertFalse(texts.any { it.contains("Stopped") })
    }
}
