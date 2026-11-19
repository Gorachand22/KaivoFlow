package com.kaivoflow.core.ai.skills

import com.kaivoflow.core.ai.AgentTool
import com.kaivoflow.core.ai.ToolOutcome
import com.kaivoflow.core.ai.ToolRisk

/**
 * Registry managing available skills for agent consumption.
 */
class SkillRegistry(initialSkills: List<AiSkill> = BuiltInSkills.defaultSkills) {
    private val skills = LinkedHashMap<String, AiSkill>()

    init {
        for (skill in initialSkills) {
            skills[skill.id] = skill
        }
    }

    @Synchronized
    fun all(): List<AiSkill> = skills.values.toList()

    @Synchronized
    fun get(id: String): AiSkill? = skills[id]

    @Synchronized
    fun add(skill: AiSkill) {
        skills[skill.id] = skill
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val skill = skills[id] ?: return false
        if (skill.isBuiltIn) return false
        skills.remove(id)
        return true
    }

    @Synchronized
    fun toggleEnabled(id: String, enabled: Boolean) {
        val skill = skills[id] ?: return
        skills[id] = skill.copy(isEnabled = enabled)
    }

    /**
     * Creates agent tools for listing and loading skills dynamically.
     */
    fun createTools(): List<AgentTool> = listOf(
        object : AgentTool {
            override val name = "list_skills"
            override val description = "Lists available skills that provide specialized instructions and guidance for tasks."
            override val parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            override val risk = ToolRisk.Read

            override suspend fun execute(arguments: Map<String, Any?>): ToolOutcome {
                val enabled = all().filter { it.isEnabled }
                val summary = enabled.joinToString("\n") { "- ${it.id}: ${it.name} - ${it.description}" }
                return ToolOutcome("Available Skills:\n$summary")
            }
        },
        object : AgentTool {
            override val name = "load_skill"
            override val description = "Loads a specific skill's instructions into active context by its skillId."
            override val parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "skillId" to mapOf("type" to "string", "description" to "The unique ID of the skill to load (e.g. 'workflow-builder', 'device-automation')."),
                ),
                "required" to listOf("skillId"),
            )
            override val risk = ToolRisk.Read

            override suspend fun execute(arguments: Map<String, Any?>): ToolOutcome {
                val skillId = arguments["skillId"]?.toString()?.trim()
                    ?: return ToolOutcome("Missing skillId parameter.", isError = true)
                val skill = get(skillId)
                    ?: return ToolOutcome("Skill not found: $skillId", isError = true)
                if (!skill.isEnabled) {
                    return ToolOutcome("Skill '$skillId' is currently disabled.", isError = true)
                }
                return ToolOutcome("Loaded skill '${skill.name}':\n${skill.instructions}")
            }
        },
    )
}
