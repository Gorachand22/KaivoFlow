package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.AgentError
import com.kaivoflow.core.ai.AgentEvent
import com.kaivoflow.core.ai.AgentOutcome
import com.kaivoflow.core.ai.ApprovalRequest
import com.kaivoflow.core.ai.CostEstimate
import com.kaivoflow.core.ai.FinishReason
import com.kaivoflow.core.ai.NoticeKind
import com.kaivoflow.core.ai.ToolRisk
import com.kaivoflow.core.ai.ToolStatus
import com.kaivoflow.core.ai.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The event stream of the agent loop folded into the chat's turn model: no scripted step, only what happened. */
class AssistantTurnReducerTest {
    private var t = 1_000L
    private var s = AssistantTurnReducer.start(t)
    private fun at(ms: Long, e: AgentEvent) { t += ms; s = AssistantTurnReducer.reduce(s, e, t) }
    private fun feed(vararg e: AgentEvent) = e.forEach { at(0, it) }
    private fun live() = AssistantTurnReducer.live(s, "m1", t)

    @Test fun `a turn without events shows no invented step and no invented reasoning`() {
        val m = live()
        assertTrue(m.thought!!.steps.isEmpty())
        assertNull(m.thought!!.reasoningText)
        assertEquals("", m.text)
        assertTrue(m.isStreaming && m.thought!!.isActive)
        assertEquals(TurnStatus.Running, m.status)
    }

    @Test fun `text and thinking appear as they arrive and thinking is the providers own text`() {
        feed(AgentEvent.RunStarted, AgentEvent.StepStarted(1), AgentEvent.ThinkingDelta("Let me "), AgentEvent.ThinkingDelta("think."))
        assertEquals("Let me think.", live().thought!!.reasoningText)
        assertEquals("Thinking...", live().thought!!.currentActivity)
        feed(AgentEvent.TextDelta("Hel"), AgentEvent.TextDelta("lo"))
        assertEquals("Hello", live().text)
        assertEquals("Writing the answer...", live().thought!!.currentActivity)
    }

    @Test fun `a tool call is shown with partial json first, then the real arguments, then the real result`() {
        feed(AgentEvent.StepStarted(1), AgentEvent.ToolCallStarted(0, "c1", "add_step"), AgentEvent.ToolCallArgsDelta(0, "{\"type\":\"device."))
        var step = live().thought!!.steps.single()
        assertTrue(step.running); assertEquals("Adding step >", step.label); assertEquals("{\"type\":\"device.", step.inputJson)
        assertNull(step.resultJson)
        feed(AgentEvent.ToolCallArgsDelta(0, "notify\"}"), AgentEvent.ToolCallReady("c1", "add_step", mapOf("type" to "device.notify", "params" to mapOf("title" to "Hi"))))
        step = live().thought!!.steps.single()
        assertEquals("{\n  \"type\": \"device.notify\",\n  \"params\": {\n    \"title\": \"Hi\"\n  }\n}", step.inputJson)
        feed(AgentEvent.ToolRunning("c1", "add_step", ToolRisk.WriteDraft))
        assertEquals("Running add_step...", live().thought!!.currentActivity)
        feed(AgentEvent.ToolFinished("c1", "add_step", ToolStatus.Ok, "ok, 12 chars", 7, "Added Step x"))
        step = live().thought!!.steps.single()
        assertFalse(step.running); assertTrue(step.isSuccess); assertEquals("Added Step x", step.resultJson); assertEquals(7L, step.durationMs)
    }

    @Test fun `a failing tool shows as failed with the error text`() {
        feed(AgentEvent.StepStarted(1), AgentEvent.ToolCallStarted(0, "c1", "update_step_params"), AgentEvent.ToolCallReady("c1", "update_step_params", mapOf("step" to "zz")),
            AgentEvent.ToolRunning("c1", "update_step_params", ToolRisk.WriteDraft),
            AgentEvent.ToolFinished("c1", "update_step_params", ToolStatus.ToolError, "There was an error: no Step zz", 3, "There was an error: no Step zz"))
        val step = live().thought!!.steps.single()
        assertFalse(step.isSuccess); assertEquals("There was an error: no Step zz", step.resultJson)
        assertEquals("update_step_params failed", live().thought!!.currentActivity)
    }

    @Test fun `bad arguments never reach the tool but still show as a real failed call`() {
        feed(AgentEvent.StepStarted(1), AgentEvent.ToolCallStarted(0, "c1", "add_step"), AgentEvent.ToolCallArgsDelta(0, "{\"type\":"),
            AgentEvent.ToolCallReady("c1", "add_step", emptyMap()),
            AgentEvent.ToolFinished("c1", "add_step", ToolStatus.BadArguments, "not valid", 0, "There was an error: bad arguments"))
        val step = live().thought!!.steps.single()
        assertFalse(step.isSuccess); assertFalse(step.running)
    }

    @Test fun `text of a step that ends in tool calls is narration, not the answer`() {
        feed(AgentEvent.StepStarted(1), AgentEvent.TextDelta("I will add a notification."), AgentEvent.ToolCallStarted(0, "c1", "add_step"))
        assertEquals("I will add a notification.", live().text)
        feed(AgentEvent.StepFinished(1, FinishReason.ToolCalls, Usage(10, 5), "gpt-4o"), AgentEvent.StepStarted(2))
        assertEquals("", live().text)
        assertEquals("I will add a notification.", live().thought!!.reasoningText)
        feed(AgentEvent.TextDelta("Done."), AgentEvent.StepFinished(2, FinishReason.Stop, Usage(12, 2), "gpt-4o"))
        assertEquals("Done.", live().text)
    }

    @Test fun `the same call id in two iterations makes two separate steps`() {
        feed(AgentEvent.StepStarted(1), AgentEvent.ToolCallStarted(0, "call_0", "list_steps"), AgentEvent.ToolCallReady("call_0", "list_steps", emptyMap()),
            AgentEvent.ToolFinished("call_0", "list_steps", ToolStatus.Ok, "ok", 1, "a"), AgentEvent.StepFinished(1, FinishReason.ToolCalls, Usage(), null),
            AgentEvent.StepStarted(2), AgentEvent.ToolCallStarted(0, "call_0", "add_step"), AgentEvent.ToolCallReady("call_0", "add_step", emptyMap()),
            AgentEvent.ToolFinished("call_0", "add_step", ToolStatus.Ok, "ok", 1, "b"))
        val steps = live().thought!!.steps
        assertEquals(listOf("list_steps >", "Adding step >"), steps.map { it.label })
        assertEquals(listOf("a", "b"), steps.map { it.resultJson })
        assertEquals(2, steps.map { it.id }.toSet().size)
    }

    @Test fun `a retry notice stays as a real step and the output of the failed attempt is dropped`() {
        feed(AgentEvent.StepStarted(1), AgentEvent.TextDelta("partial answer"), AgentEvent.ThinkingDelta("half a thought"),
            AgentEvent.ToolCallStarted(0, "c1", "add_step"), AgentEvent.ToolCallArgsDelta(0, "{\"ty"))
        feed(AgentEvent.Notice(NoticeKind.Retry, "The model call failed (connection reset); retrying in 1.0s, attempt 2."), AgentEvent.StreamReset)
        val m = live()
        assertEquals("", m.text)
        assertNull(m.thought!!.reasoningText)
        assertEquals(listOf("The model call failed (connection reset); retrying in 1.0s, attempt 2."), m.thought!!.steps.map { it.label })
        assertEquals("Retrying the model call...", m.thought!!.currentActivity)
        feed(AgentEvent.TextDelta("whole answer"))
        assertEquals("whole answer", live().text)
    }

    @Test fun `the clock is real time and the wait for an approval is not counted, open or closed`() {
        feed(AgentEvent.StepStarted(1))
        at(4_000, AgentEvent.ToolRunning("c1", "send_sms", ToolRisk.ExternalEffect))
        assertEquals(4, live().thought!!.durationSec)
        at(0, AgentEvent.ApprovalRequested(ApprovalRequest("send_sms", ToolRisk.ExternalEffect, "Send", "+1", emptyMap(), false)))
        assertEquals("Waiting for your approval: send_sms", live().thought!!.currentActivity)
        t += 30_000
        assertEquals("open card: time stands still", 4L, s.elapsedMs(t) / 1000)
        at(0, AgentEvent.ApprovalResolved("send_sms", true, null, 30_000))
        assertEquals(4L, s.elapsedMs(t) / 1000)
        t += 2_000
        assertEquals(6L, s.elapsedMs(t) / 1000)
    }

    @Test fun `a denied approval says so`() {
        feed(AgentEvent.ApprovalRequested(ApprovalRequest("send_sms", ToolRisk.ExternalEffect, "Send", "+1", emptyMap(), false)), AgentEvent.ApprovalResolved("send_sms", false, "no", 500))
        assertEquals("send_sms was not allowed", live().thought!!.currentActivity)
    }

    @Test fun `an error in the middle of a stream keeps the partial text and the real steps`() {
        feed(AgentEvent.StepStarted(1), AgentEvent.ThinkingDelta("hmm"), AgentEvent.TextDelta("I started to writ"),
            AgentEvent.ToolCallStarted(0, "c1", "add_step"))
        feed(AgentEvent.Error(AgentError(AgentOutcome.ModelFailed, "network", "The provider stream ended before the answer was complete.", status = null)))
        val m = AssistantTurnReducer.endedMessage(s, "m1", TurnStatus.Failed, t + 3_000, "❌ **AI Provider Error**")
        assertEquals("I started to writ\n\n❌ **AI Provider Error**", m.text)
        assertEquals(TurnStatus.Failed, m.status)
        assertFalse(m.thought!!.isActive)
        val step = m.thought!!.steps.single()
        assertFalse(step.running); assertFalse(step.isSuccess); assertTrue(step.resultJson!!.startsWith("Not finished"))
        assertEquals("hmm", m.thought!!.reasoningText)
        assertEquals(3, m.thought!!.durationSec)
    }

    @Test fun `stop keeps the partial text, closes running steps and marks the turn Stopped`() {
        feed(AgentEvent.StepStarted(1), AgentEvent.TextDelta("Working on"), AgentEvent.ToolCallStarted(0, "c1", "add_step"))
        val stopped = AssistantTurnReducer.markCut(live(), TurnStatus.Stopped)
        assertEquals("Working on\n\n*Stopped.*", stopped.text)
        assertEquals(TurnStatus.Stopped, stopped.status)
        assertFalse(stopped.isStreaming); assertFalse(stopped.thought!!.isActive); assertNull(stopped.thought!!.currentActivity)
        assertFalse(stopped.thought!!.steps.single().running)
        assertTrue(stopped.thought!!.steps.single().resultJson!!.contains("stopped by you"))
    }

    @Test fun `stop before any text says only Stopped`() {
        val stopped = AssistantTurnReducer.markCut(live(), TurnStatus.Stopped)
        assertEquals("*Stopped.*", stopped.text)
    }

    @Test fun `the ended state freezes the clock`() {
        feed(AgentEvent.StepStarted(1))
        at(2_000, AgentEvent.TextDelta("x"))
        val e = AssistantTurnReducer.ended(s, TurnStatus.Stopped, t)
        assertEquals(2L, e.elapsedMs(t + 60_000) / 1000)
    }

    @Test fun `a message saved while its turn ran becomes Interrupted at the next start, partial text kept`() {
        val saved = live().copy(text = "Half an ans")
        val m = AssistantTurnReducer.markInterrupted(saved)
        assertEquals(TurnStatus.Interrupted, m.status)
        assertTrue(m.text.startsWith("Half an ans\n\n*Interrupted"))
        assertFalse(m.isStreaming)
        val done = saved.copy(status = TurnStatus.Completed)
        assertEquals(done, AssistantTurnReducer.markInterrupted(done))
    }

    @Test fun `an unfinished flow block is not shown while it streams, a normal code block is`() {
        feed(AgentEvent.StepStarted(1), AgentEvent.TextDelta("Here is the flow.\n\n```kaivoflow\n{\"name\":\"X\",\"ste"))
        assertEquals("Here is the flow.", live().text)
        feed(AgentEvent.TextDelta("ps\":[]}\n```\nDone."))
        assertEquals("Here is the flow.\n\n\nDone.", live().text)
        s = AssistantTurnReducer.start(0); t = 0
        feed(AgentEvent.StepStarted(1), AgentEvent.TextDelta("Try:\n```js\nlet a ="))
        assertEquals("Try:\n```js\nlet a =", live().text)
    }

    @Test fun `a length cut continues the same answer text`() {
        feed(AgentEvent.StepStarted(1), AgentEvent.TextDelta("first part "), AgentEvent.StepFinished(1, FinishReason.Length, Usage(), null),
            AgentEvent.Notice(NoticeKind.Continue, "The answer was cut by the output limit; asking the model to continue."), AgentEvent.StepStarted(2), AgentEvent.TextDelta("second part"))
        assertEquals("first part second part", live().text)
    }

    @Test fun `a second run is a real correction round and shows as one`() {
        feed(AgentEvent.RunStarted, AgentEvent.StepStarted(1), AgentEvent.StepFinished(1, FinishReason.Stop, Usage(), null), AgentEvent.Done(result()))
        assertTrue(live().thought!!.steps.isEmpty())
        feed(AgentEvent.RunStarted)
        assertEquals(1, live().thought!!.steps.size)
        assertTrue(live().thought!!.steps.single().label.startsWith("Checking the draft"))
    }

    @Test fun `usage and cost follow the loop's totals`() {
        feed(AgentEvent.UsageUpdate(Usage(100, 20), CostEstimate(0.001, false)))
        assertEquals(120L, s.usage.totalTokens); assertEquals(0.001, s.cost!!.usd!!, 0.0)
    }

    @Test fun `partial tool arguments are capped`() {
        feed(AgentEvent.StepStarted(1), AgentEvent.ToolCallStarted(0, "c1", "add_step"), AgentEvent.ToolCallArgsDelta(0, "x".repeat(20_000)))
        assertEquals(AssistantTurnReducer.MAX_PAYLOAD_CHARS, live().thought!!.steps.single().inputJson!!.length)
    }

    @Test fun `json text helper escapes and indents`() {
        assertEquals("{\"a\":\"q\\\"\\n\",\"b\":[1,2.5,null,true]}", TurnJson.obj("a" to "q\"\n", "b" to listOf(1, 2.5, null, true)))
        assertEquals("{}", TurnJson.pretty(emptyMap<String, Any>()))
        assertNotNull(TurnJson.pretty(mapOf("k" to listOf(mapOf("z" to 1)))))
    }

    private fun result() = com.kaivoflow.core.ai.AgentResult(AgentOutcome.Completed, "", null, Usage(), 1, 0, emptyList(), null, null)
}
