package com.kaivoflow.core.ai.flowedit

/**
 * Categorization of user intent for the AI assistant.
 * Following modular agent architecture:
 * - [Greeting]: Conversational openers without tasks.
 * - [Knowledge]: Conceptual, diagnostic, or explanatory questions where no workflow mutation should be drafted.
 * - [WorkflowMutation]: Action requests to create, edit, connect, or repair workflow steps.
 */
enum class PromptIntent {
    Greeting,
    Knowledge,
    WorkflowMutation,
}

object AiTriageClassifier {

    private val GREETING_WORDS = setOf(
        "hi", "hello", "hey", "good morning", "good evening", "good afternoon",
        "sup", "howdy", "greetings", "thanks", "thank you", "who are you"
    )

    private val ACTION_VERBS = listOf(
        "create", "build", "add", "insert", "append", "connect", "wire", "link",
        "disconnect", "unlink", "remove", "delete", "rename", "update", "change",
        "configure", "set", "tune", "fix", "repair", "make a flow", "make a workflow",
        "generate", "draft"
    )

    private val KNOWLEDGE_STARTERS = listOf(
        "how does", "how do", "how can i", "how to", "why did", "why does", "why is",
        "what is", "what are", "what does", "explain", "tell me about", "can you explain",
        "help me understand", "difference between", "which step should i use"
    )

    fun classify(prompt: String): PromptIntent {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase()

        // 1. Simple greetings (e.g. "hi", "hello there!", "good morning", "hey")
        val firstWords = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val cleanGreeting = lower.trimEnd { !it.isLetterOrDigit() }
        val isGreeting = GREETING_WORDS.contains(cleanGreeting) ||
            (firstWords.isNotEmpty() && firstWords.size <= 3 && GREETING_WORDS.contains(firstWords.first()))

        if (isGreeting && !ACTION_VERBS.any { lower.contains(it) }) {
            return PromptIntent.Greeting
        }

        // 2. Strong mutation cues (even inside questions like "can you create a flow")
        val hasExplicitAction = ACTION_VERBS.any { verb ->
            val pattern = Regex("\\b${Regex.escape(verb)}\\b", RegexOption.IGNORE_CASE)
            pattern.containsMatchIn(trimmed)
        }

        val hasKnowledgeStarter = KNOWLEDGE_STARTERS.any { starter ->
            lower.startsWith(starter) || lower.contains(" $starter")
        }

        // If it starts with a question starter and doesn't contain build/create instructions
        if (hasKnowledgeStarter && !hasExplicitAction) {
            return PromptIntent.Knowledge
        }

        // If it asks to create/build/edit
        if (hasExplicitAction) {
            return PromptIntent.WorkflowMutation
        }

        // Fallback to Knowledge if it ends with a question mark
        if (trimmed.endsWith("?")) {
            return PromptIntent.Knowledge
        }

        // Default to workflow mutation if ambiguous and not a simple greeting
        return PromptIntent.WorkflowMutation
    }
}
