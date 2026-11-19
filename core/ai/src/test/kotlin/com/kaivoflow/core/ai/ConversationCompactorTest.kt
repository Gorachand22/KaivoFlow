package com.kaivoflow.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompactorTest {

    @Test
    fun `short history does not compact`() {
        val messages = listOf(
            ChatMessage.user("Create a flow"),
            ChatMessage.assistant("Here is the flow proposal.")
        )
        val result = ConversationCompactor.compact(messages, keepRecent = 6)
        assertEquals(2, result.size)
        assertEquals("Create a flow", result[0].text)
    }

    @Test
    fun `long history summarizes older turns and retains recent turns`() {
        val messages = (1..10).flatMap { i ->
            listOf(
                ChatMessage.user("User request $i: build step $i"),
                ChatMessage.assistant("Assistant response $i: built step $i")
            )
        } // 20 messages

        val result = ConversationCompactor.compact(messages, keepRecent = 6)

        // Result should have 1 context summary message + 6 recent messages = 7 messages
        assertEquals(7, result.size)
        assertTrue(result.first().text.contains("[Conversation Summary of Earlier Turns]"))
        assertTrue(result.first().text.contains("User request 1"))
        assertEquals("User request 8: build step 8", result[1].text)
        assertEquals("Assistant response 10: built step 10", result.last().text)
    }
}
