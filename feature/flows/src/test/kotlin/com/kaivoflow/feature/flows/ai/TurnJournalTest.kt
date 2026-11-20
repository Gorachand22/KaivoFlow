package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.AgentError
import com.kaivoflow.core.ai.AgentEvent
import com.kaivoflow.core.ai.AgentOutcome
import com.kaivoflow.core.ai.ToolStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnJournalTest {
    private val rows = ArrayList<Pair<String, String>>()
    private var now = 0L
    private fun journal(chars: Int = 2_000, ms: Long = 1_000, fail: Boolean = false) =
        TurnJournal({ k, p -> if (fail) throw IllegalStateException("db closed") else rows += k to p }, { now }, chars, ms)

    @Test fun `small deltas are written as one entry when the kind changes or the turn ends`() = runBlocking {
        val j = journal()
        j.started("OpenAI", "gpt-4o", "hi")
        j.on(AgentEvent.ThinkingDelta("a")); j.on(AgentEvent.ThinkingDelta("b"))
        j.on(AgentEvent.TextDelta("c")); j.on(AgentEvent.TextDelta("d"))
        j.ended(TurnStatus.Completed)
        assertEquals(listOf("turn_started", "thinking", "text", "turn_ended"), rows.map { it.first })
        assertEquals("{\"text\":\"ab\"}", rows[1].second); assertEquals("{\"text\":\"cd\"}", rows[2].second)
        assertTrue(rows.last().second.contains("\"status\":\"completed\""))
    }

    @Test fun `a long piece is flushed by size and by time`() = runBlocking {
        val j = journal(chars = 10, ms = 500)
        j.on(AgentEvent.TextDelta("0123456789")); assertEquals(1, rows.size)
        j.on(AgentEvent.TextDelta("x")); assertEquals(1, rows.size)
        now = 600; j.on(AgentEvent.TextDelta("y")); assertEquals(2, rows.size)
    }

    @Test fun `tool calls and results carry the real arguments and output`() = runBlocking {
        val j = journal()
        j.on(AgentEvent.ToolCallReady("c1", "add_step", mapOf("type" to "device.notify")))
        j.on(AgentEvent.ToolFinished("c1", "add_step", ToolStatus.Ok, "ok", 9, "Added"))
        j.on(AgentEvent.Error(AgentError(AgentOutcome.ModelFailed, "network", "cut", status = 502, attempts = 2)))
        assertEquals(listOf("tool_call", "tool_result", "error"), rows.map { it.first })
        assertTrue(rows[0].second.contains("\"arguments\":{\"type\":\"device.notify\"}"))
        assertTrue(rows[1].second.contains("\"output\":\"Added\"") && rows[1].second.contains("\"durationMs\":9"))
        assertTrue(rows[2].second.contains("\"status\":502"))
    }

    @Test fun `a stream reset drops buffered text instead of writing it`() = runBlocking {
        val j = journal()
        j.on(AgentEvent.TextDelta("from the failed attempt")); j.on(AgentEvent.StreamReset); j.flush()
        assertEquals(listOf("stream_reset"), rows.map { it.first })
    }

    @Test fun `a failing log never stops the turn`() = runBlocking {
        val j = journal(fail = true)
        j.started("x", "y", "z"); j.on(AgentEvent.TextDelta("a")); j.ended(TurnStatus.Failed)
    }
}
