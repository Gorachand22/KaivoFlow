package com.kaivoflow.core.ai

/**
 * Multi-turn conversation compaction engine.
 *
 * Prevents context exhaustion while preserving crucial requirements across long sessions.
 * Older turns are extracted into an architectural summary rather than discarded entirely.
 */
object ConversationCompactor {

    /**
     * Compacts [messages] if the total exceeds [keepRecent] + 2.
     * Keeps the most recent [keepRecent] messages intact, and generates a structured
     * summary of the older turns placed as a synthetic assistant context message at index 0.
     */
    fun compact(
        messages: List<ChatMessage>,
        keepRecent: Int = 6,
        maxSummaryChars: Int = 1_500,
    ): List<ChatMessage> {
        val nonTool = messages.filter { it.role != Role.Tool }
        if (nonTool.size <= keepRecent + 2) {
            return nonTool
        }

        val older = nonTool.dropLast(keepRecent)
        val recent = nonTool.takeLast(keepRecent)

        val summaryText = buildString {
            appendLine("[Conversation Summary of Earlier Turns]")
            older.forEach { msg ->
                val speaker = if (msg.role == Role.User) "User" else "Assistant"
                val snippet = msg.text.trim().lines().firstOrNull()?.take(120) ?: ""
                if (snippet.isNotBlank()) {
                    appendLine("• $speaker: $snippet")
                }
            }
        }.trim().take(maxSummaryChars)

        val summaryMessage = ChatMessage.assistant(summaryText)
        return listOf(summaryMessage) + recent
    }
}
