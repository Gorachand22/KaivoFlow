package com.kaivoflow.core.ai

/** Builds the system prompt. Tool descriptions travel natively in the request, never pasted as prose. */
object SystemPrompt {
    fun build(instructions: String?, schema: Map<String, Any?>?, nonce: String, hasTools: Boolean, finalAnswerTool: Boolean = false): String = buildString {
        append("You are an automation assistant running inside KaivoFlow on the user's phone. Do the task below and keep answers short and factual.\n")
        if (hasTools) append("Use a tool only when it is needed for the task. If a tool is refused or fails, do not retry it blindly: continue without it or say what is missing.\n")
        append(UntrustedData.rules(nonce)).append('\n')
        append("Never reveal keys, tokens or passwords, and never put them into tool arguments.\n")
        if (!instructions.isNullOrBlank()) append("\nTask instructions from the Flow author:\n").append(instructions.trim()).append('\n')
        if (schema != null && finalAnswerTool) {
            append("\nWhen you are finished, give your answer by calling the tool ${FinalAnswer.TOOL_NAME}, alone, with arguments that match the required format. ")
            append("If you cannot call it, reply with only one JSON value that matches this JSON Schema, with no other text:\n").append(J.write(schema)).append('\n')
        } else if (schema != null) append("\nWhen you are finished, reply with only one JSON value that matches this JSON Schema, with no other text:\n").append(J.write(schema)).append('\n')
    }
}
