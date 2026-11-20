package com.kaivoflow.feature.flows.ai.agent

import com.kaivoflow.feature.flows.ai.AiProvider
import java.util.UUID

/**
 * Trigger source specifying where and when the agent executes.
 */
data class AgentTrigger(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // "manual", "schedule", "webhook", "phone_event"
    val name: String,
    val description: String = "",
    val config: Map<String, String> = emptyMap(),
    val isEnabled: Boolean = true,
)

/**
 * Capability or Tool attached to the agent (e.g. Web search, KaivoFlow workflow, code execution).
 */
data class AgentTool(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // "web_search", "workflow", "code", "device", "sub_agent"
    val name: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    /**
     * Author's consent for this tool to act when nobody is there to approve it (a published, always-on run). Default
     * false. A tool that is not listed here is not offered to the model in an unattended run (see AgentNode).
     */
    val allowUnattended: Boolean = false,
)

/**
 * Knowledge source document or context note referenced by the agent.
 */
data class AgentKnowledgeItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class AgentSkill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val isEnabled: Boolean = true,
)

/**
 * Advanced runtime and memory tuning for the agent.
 */
data class AgentSettings(
    val maxIterations: Int = 10,
    val reasoningEffort: String = "Medium", // "Low", "Medium", "High"
    val memoryDepth: Int = 10,
    val toolCallConcurrency: Int = 1,
    val timeoutSeconds: Int = 60,
    val description: String = "",
    /** Hard budget of one run, in model tokens (prompt plus answer). Publish needs it inside [TOKEN_BUDGET_RANGE]. */
    val maxTotalTokens: Int = 50_000,
    /** Hard limit of tool calls in one run. */
    val maxToolCalls: Int = 25,
    /** How often one tool may run in one run when nobody is present (the AgentNode rate limit). */
    val rateLimitPerTool: Int = 5,
    /** What a published run does with the answer: "notification" (device.notify) or "none" (kept in the Run record only). */
    val deliverResult: String = "notification",
) {
    companion object {
        val TOKEN_BUDGET_RANGE = 1_000..1_000_000
        const val DELIVER_NOTIFICATION = "notification"
        const val DELIVER_NONE = "none"
    }
}

/**
 * Execution history and test session traces.
 */
data class AgentSession(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "Success", // "Success", "Error"
    val prompt: String,
    val response: String,
    val durationMs: Long = 0L,
)

/**
 * Sovereign Custom Agent built manually by the user.
 * Serverless, pure Kotlin model with full persistence.
 */
data class CustomAgent(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Agent",
    val iconEmoji: String = "☀️",
    val iconColorHex: Long = 0xFFFF9800,
    val provider: AiProvider = AiProvider.OpenAI,
    val model: String = AiProvider.OpenAI.defaultModel,
    val credentialId: String? = null,
    val instructions: String = "",
    val skills: List<AgentSkill> = emptyList(),
    val triggers: List<AgentTrigger> = emptyList(),
    val tools: List<AgentTool> = emptyList(),
    val knowledge: List<AgentKnowledgeItem> = emptyList(),
    val webSearchEnabled: Boolean = false,
    val crossSessionMemoryEnabled: Boolean = false,
    val settings: AgentSettings = AgentSettings(),
    val sessions: List<AgentSession> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
