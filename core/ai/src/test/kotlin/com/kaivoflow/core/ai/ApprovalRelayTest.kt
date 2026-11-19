package com.kaivoflow.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalRelayTest {
    private fun request(tool: String = "send_sms") = ApprovalRequest(tool, ToolRisk.ExternalEffect, "d", "+91", emptyMap(), false)

    @Test fun `with no screen attached a request is denied at once and nothing waits`() = runBlocking {
        val relay = ApprovalRelay()
        val d = withTimeout(1_000) { relay.approve(request()) }
        assertTrue(d is ApprovalDecision.Denied)
        assertNull(relay.pending.value)
    }

    @Test fun `an attached screen sees the question and its answer is returned`() = runBlocking {
        val relay = ApprovalRelay()
        relay.attach(inline = true)
        val asked = async(Dispatchers.Default) { relay.approve(request("a")) }
        val shown = withTimeout(2_000) { relay.pending.first { it != null } }
        assertEquals("a", shown!!.toolName)
        relay.respond(true)
        assertEquals(ApprovalDecision.Approved, withTimeout(2_000) { asked.await() })
        assertNull(relay.pending.value)
    }

    @Test fun `deny returns the reason and the next question waits its turn`() = runBlocking {
        val relay = ApprovalRelay()
        relay.attach()
        val first = async(Dispatchers.Default) { relay.approve(request("one")) }
        withTimeout(2_000) { relay.pending.first { it?.toolName == "one" } }
        val second = async(Dispatchers.Default) { relay.approve(request("two")) }
        delay(50)
        assertEquals("one", relay.pending.value!!.toolName)
        relay.respond(false, "no thanks")
        assertEquals(ApprovalDecision.Denied("no thanks"), withTimeout(2_000) { first.await() })
        withTimeout(2_000) { relay.pending.first { it?.toolName == "two" } }
        relay.respond(true)
        assertEquals(ApprovalDecision.Approved, withTimeout(2_000) { second.await() })
    }

    @Test fun `cancelling the asker clears the card`() = runBlocking {
        val relay = ApprovalRelay()
        relay.attach()
        val job = launch(Dispatchers.Default) { relay.approve(request()) }
        withTimeout(2_000) { relay.pending.first { it != null } }
        job.cancel(); job.join()
        withTimeout(2_000) { relay.pending.first { it == null } }
        Unit
    }

    @Test fun `the last screen leaving denies the open question`() = runBlocking {
        val relay = ApprovalRelay()
        val detach = relay.attach()
        val asked = async(Dispatchers.Default) { relay.approve(request()) }
        withTimeout(2_000) { relay.pending.first { it != null } }
        detach()
        assertTrue(withTimeout(2_000) { asked.await() } is ApprovalDecision.Denied)
        assertEquals(0, relay.presenters.value)
    }

    @Test fun `the policy asks the relay for side effects and never for reads`() = runBlocking {
        val relay = ApprovalRelay(); relay.attach()
        val policy = ApprovalPolicy(ApprovalHost.Interactive, relay)
        val read = FakeTool("read", ToolRisk.Read)
        val sms = FakeTool("sms", ToolRisk.ExternalEffect)
        assertEquals(ApprovalDecision.Approved, policy.decide(read, emptyMap()))
        val d = async(Dispatchers.Default) { policy.decide(sms, mapOf("q" to "x")) }
        val shown = withTimeout(2_000) { relay.pending.first { it != null } }
        assertEquals("sms", shown!!.toolName)
        relay.respond(false)
        assertTrue(d.await() is ApprovalDecision.Denied)
    }

    @Test fun `a destructive tool asks for typed confirmation and is never automatic`() = runBlocking {
        val relay = ApprovalRelay(); relay.attach()
        val policy = ApprovalPolicy(ApprovalHost.Interactive, relay)
        val d = async(Dispatchers.Default) { policy.decide(FakeTool("wipe", ToolRisk.Destructive), mapOf("q" to "all")) }
        val shown = withTimeout(2_000) { relay.pending.first { it != null } }
        assertTrue(shown!!.requiresTypedConfirmation)
        relay.respond(false); d.await()
        Unit
    }
}
