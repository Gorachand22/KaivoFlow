package com.kaivoflow.feature.flows.ai.agent

import com.kaivoflow.core.model.ParamJson
import com.kaivoflow.feature.flows.ai.AiProvider
import java.util.UUID

/**
 * The agent file format. Pure Kotlin over [ParamJson], so it is tested on the JVM and has no Android dependency.
 *
 * Versions: files without `schemaVersion` are version 1 (the Antigravity format). Version 2 adds `allowUnattended` per tool,
 * the run budget fields in `settings`, and drops `isPublished`: that flag was set by a button and
 * read by nothing, so an old `true` never meant the agent ran and is not carried over. Unknown fields are ignored on read; a
 * file with a `schemaVersion` newer than this build is refused (not guessed at) so a downgrade cannot corrupt it.
 */
object AgentJsonCodec {
    const val SCHEMA_VERSION = 2

    sealed interface Decoded {
        data class Ok(val agent: CustomAgent) : Decoded
        data class Bad(val reason: String) : Decoded
    }

    fun encode(a: CustomAgent): String {
        val m = linkedMapOf<String, Any?>(
            "schemaVersion" to SCHEMA_VERSION,
            "id" to a.id,
            "name" to a.name,
            "iconEmoji" to a.iconEmoji,
            "iconColorHex" to a.iconColorHex,
            "provider" to a.provider.name,
            "model" to a.model,
            "credentialId" to (a.credentialId ?: ""),
            "instructions" to a.instructions,
            "webSearchEnabled" to a.webSearchEnabled,
            "crossSessionMemoryEnabled" to a.crossSessionMemoryEnabled,
            "createdAt" to a.createdAt,
            "updatedAt" to a.updatedAt,
            "skills" to a.skills.map { mapOf("id" to it.id, "name" to it.name, "description" to it.description, "isEnabled" to it.isEnabled) },
            "triggers" to a.triggers.map {
                mapOf("id" to it.id, "type" to it.type, "name" to it.name, "description" to it.description, "isEnabled" to it.isEnabled, "config" to it.config)
            },
            "tools" to a.tools.map {
                mapOf("id" to it.id, "type" to it.type, "name" to it.name, "description" to it.description, "isEnabled" to it.isEnabled, "allowUnattended" to it.allowUnattended)
            },
            "knowledge" to a.knowledge.map { mapOf("id" to it.id, "title" to it.title, "content" to it.content, "updatedAt" to it.updatedAt) },
            "settings" to mapOf(
                "maxIterations" to a.settings.maxIterations,
                "reasoningEffort" to a.settings.reasoningEffort,
                "memoryDepth" to a.settings.memoryDepth,
                "toolCallConcurrency" to a.settings.toolCallConcurrency,
                "timeoutSeconds" to a.settings.timeoutSeconds,
                "description" to a.settings.description,
                "maxTotalTokens" to a.settings.maxTotalTokens,
                "maxToolCalls" to a.settings.maxToolCalls,
                "rateLimitPerTool" to a.settings.rateLimitPerTool,
                "deliverResult" to a.settings.deliverResult,
            ),
            "sessions" to a.sessions.map {
                mapOf("id" to it.id, "timestamp" to it.timestamp, "status" to it.status, "prompt" to it.prompt, "response" to it.response, "durationMs" to it.durationMs)
            },
        )
        return ParamJson.write(m)
    }

    fun decode(text: String): Decoded {
        val root = when (val p = ParamJson.parse(text)) {
            is ParamJson.Parsed.Failed -> return Decoded.Bad("not valid JSON (${p.error.message}, line ${p.error.line})")
            is ParamJson.Parsed.Ok -> p.value as? Map<*, *> ?: return Decoded.Bad("the file is not an object")
        }
        val version = (root["schemaVersion"] as? Number)?.toInt() ?: 1
        if (version > SCHEMA_VERSION) return Decoded.Bad("written by a newer version of the app (schema $version)")
        val id = (root["id"] as? String)?.takeIf { it.isNotBlank() && ID_OK.matches(it) } ?: return Decoded.Bad("missing or unsafe id")
        val now = System.currentTimeMillis()
        fun str(m: Map<*, *>, k: String, d: String) = (m[k] as? String) ?: d
        fun bool(m: Map<*, *>, k: String, d: Boolean) = (m[k] as? Boolean) ?: d
        fun long(m: Map<*, *>, k: String, d: Long) = (m[k] as? Number)?.toLong() ?: d
        fun int(m: Map<*, *>, k: String, d: Int) = (m[k] as? Number)?.toInt() ?: d
        fun rows(k: String): List<Map<*, *>> = (root[k] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        fun idOf(m: Map<*, *>) = str(m, "id", "").ifBlank { UUID.randomUUID().toString() }

        val settings = (root["settings"] as? Map<*, *>) ?: emptyMap<String, Any?>()
        val d = AgentSettings()
        val agent = CustomAgent(
            id = id,
            name = str(root, "name", "Custom Agent"),
            iconEmoji = str(root, "iconEmoji", "☀️"),
            iconColorHex = long(root, "iconColorHex", 0xFFFF9800),
            provider = AiProvider.fromKey(str(root, "provider", "OpenAI")),
            model = str(root, "model", "gpt-4o"),
            credentialId = str(root, "credentialId", "").takeIf { it.isNotBlank() },
            instructions = str(root, "instructions", ""),
            skills = rows("skills").map { AgentSkill(idOf(it), str(it, "name", "Skill"), str(it, "description", ""), bool(it, "isEnabled", true)) },
            triggers = rows("triggers").map { t ->
                val cfg = (t["config"] as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k.toString() to (v?.toString() ?: "") }
                AgentTrigger(idOf(t), str(t, "type", "manual"), str(t, "name", "Trigger"), str(t, "description", ""), cfg, bool(t, "isEnabled", true))
            },
            tools = rows("tools").map {
                AgentTool(idOf(it), str(it, "type", "web_search"), str(it, "name", "Tool"), str(it, "description", ""), bool(it, "isEnabled", true), bool(it, "allowUnattended", false))
            },
            knowledge = rows("knowledge").map { AgentKnowledgeItem(idOf(it), str(it, "title", "Untitled Document"), str(it, "content", ""), long(it, "updatedAt", now)) },
            webSearchEnabled = bool(root, "webSearchEnabled", false),
            crossSessionMemoryEnabled = bool(root, "crossSessionMemoryEnabled", false),
            settings = AgentSettings(
                maxIterations = int(settings, "maxIterations", d.maxIterations),
                reasoningEffort = str(settings, "reasoningEffort", d.reasoningEffort),
                memoryDepth = int(settings, "memoryDepth", d.memoryDepth),
                toolCallConcurrency = int(settings, "toolCallConcurrency", d.toolCallConcurrency),
                timeoutSeconds = int(settings, "timeoutSeconds", d.timeoutSeconds),
                description = str(settings, "description", ""),
                maxTotalTokens = int(settings, "maxTotalTokens", d.maxTotalTokens),
                maxToolCalls = int(settings, "maxToolCalls", d.maxToolCalls),
                rateLimitPerTool = int(settings, "rateLimitPerTool", d.rateLimitPerTool),
                deliverResult = str(settings, "deliverResult", d.deliverResult),
            ),
            sessions = rows("sessions").map {
                AgentSession(idOf(it), long(it, "timestamp", now), str(it, "status", "Success"), str(it, "prompt", ""), str(it, "response", ""), long(it, "durationMs", 0L))
            },
            createdAt = long(root, "createdAt", now),
            updatedAt = long(root, "updatedAt", now),
        )
        return Decoded.Ok(agent)
    }

    /** An id becomes a file name and part of a Flow id, so it may only hold these characters. */
    val ID_OK = Regex("^[A-Za-z0-9_-]{1,80}$")
}
