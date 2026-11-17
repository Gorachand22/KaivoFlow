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
)

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
    val provider: AiProvider = AiProvider.Gemini,
    val model: String = AiProvider.Gemini.defaultModel,
    val instructions: String = "",
    val triggers: List<AgentTrigger> = emptyList(),
    val tools: List<AgentTool> = emptyList(),
    val knowledge: List<AgentKnowledgeItem> = emptyList(),
    val settings: AgentSettings = AgentSettings(),
    val sessions: List<AgentSession> = emptyList(),
    val isPublished: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
