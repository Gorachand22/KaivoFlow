package com.kaivoflow.feature.flows.ai.agent

import android.content.Context
import com.kaivoflow.feature.flows.ai.AiProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface AgentRepository {
    fun observeAgents(): Flow<List<CustomAgent>>
    suspend fun agentById(id: String): CustomAgent?
    suspend fun saveAgent(agent: CustomAgent)
    suspend fun deleteAgent(id: String)
    suspend fun duplicateAgent(id: String): CustomAgent?
}

@Singleton
class FileAgentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentRepository {

    private val io: CoroutineDispatcher = Dispatchers.IO

    private val agentsDir: File
        get() = context.filesDir.resolve("agents").apply { mkdirs() }

    private val _agentsFlow = MutableStateFlow<List<CustomAgent>>(emptyList())

    init {
        loadFromDisk()
    }

    override fun observeAgents(): Flow<List<CustomAgent>> = _agentsFlow.asStateFlow()

    override suspend fun agentById(id: String): CustomAgent? = withContext(io) {
        _agentsFlow.value.firstOrNull { it.id == id } ?: loadAgentFile(id)
    }

    override suspend fun saveAgent(agent: CustomAgent): Unit = withContext(io) {
        val updated = agent.copy(updatedAt = System.currentTimeMillis())
        val file = agentsDir.resolve("${updated.id}.json")
        file.writeText(serializeAgent(updated))

        val current = _agentsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            current[index] = updated
        } else {
            current.add(0, updated)
        }
        _agentsFlow.value = current
    }

    override suspend fun deleteAgent(id: String): Unit = withContext(io) {
        agentsDir.resolve("$id.json").delete()
        _agentsFlow.value = _agentsFlow.value.filterNot { it.id == id }
    }

    override suspend fun duplicateAgent(id: String): CustomAgent? = withContext(io) {
        val original = agentById(id) ?: return@withContext null
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = "${original.name} (Copy)",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        saveAgent(copy)
        copy
    }

    private fun loadFromDisk() {
        val files = agentsDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        val list = files.mapNotNull { file ->
            runCatching { deserializeAgent(file.readText()) }.getOrNull()
        }.sortedByDescending { it.updatedAt }
        _agentsFlow.value = list
    }

    private fun loadAgentFile(id: String): CustomAgent? {
        val file = agentsDir.resolve("$id.json")
        if (!file.exists()) return null
        return runCatching { deserializeAgent(file.readText()) }.getOrNull()
    }

    private fun serializeAgent(agent: CustomAgent): String {
        val json = JSONObject()
        json.put("id", agent.id)
        json.put("name", agent.name)
        json.put("iconEmoji", agent.iconEmoji)
        json.put("iconColorHex", agent.iconColorHex)
        json.put("provider", agent.provider.name)
        json.put("model", agent.model)
        json.put("instructions", agent.instructions)
        json.put("isPublished", agent.isPublished)
        json.put("createdAt", agent.createdAt)
        json.put("updatedAt", agent.updatedAt)

        // Triggers
        val triggersArr = JSONArray()
        agent.triggers.forEach { trig ->
            val tObj = JSONObject()
            tObj.put("id", trig.id)
            tObj.put("type", trig.type)
            tObj.put("name", trig.name)
            tObj.put("description", trig.description)
            tObj.put("isEnabled", trig.isEnabled)
            val cfgObj = JSONObject()
            trig.config.forEach { (k, v) -> cfgObj.put(k, v) }
            tObj.put("config", cfgObj)
            triggersArr.put(tObj)
        }
        json.put("triggers", triggersArr)

        // Tools
        val toolsArr = JSONArray()
        agent.tools.forEach { tool ->
            val toolObj = JSONObject()
            toolObj.put("id", tool.id)
            toolObj.put("type", tool.type)
            toolObj.put("name", tool.name)
            toolObj.put("description", tool.description)
            toolObj.put("isEnabled", tool.isEnabled)
            toolsArr.put(toolObj)
        }
        json.put("tools", toolsArr)

        // Knowledge
        val knowArr = JSONArray()
        agent.knowledge.forEach { k ->
            val kObj = JSONObject()
            kObj.put("id", k.id)
            kObj.put("title", k.title)
            kObj.put("content", k.content)
            kObj.put("updatedAt", k.updatedAt)
            knowArr.put(kObj)
        }
        json.put("knowledge", knowArr)

        // Settings
        val setObj = JSONObject()
        setObj.put("maxIterations", agent.settings.maxIterations)
        setObj.put("reasoningEffort", agent.settings.reasoningEffort)
        setObj.put("memoryDepth", agent.settings.memoryDepth)
        setObj.put("toolCallConcurrency", agent.settings.toolCallConcurrency)
        setObj.put("timeoutSeconds", agent.settings.timeoutSeconds)
        setObj.put("description", agent.settings.description)
        json.put("settings", setObj)

        // Sessions
        val sessArr = JSONArray()
        agent.sessions.forEach { s ->
            val sObj = JSONObject()
            sObj.put("id", s.id)
            sObj.put("timestamp", s.timestamp)
            sObj.put("status", s.status)
            sObj.put("prompt", s.prompt)
            sObj.put("response", s.response)
            sObj.put("durationMs", s.durationMs)
            sessArr.put(sObj)
        }
        json.put("sessions", sessArr)

        return json.toString(2)
    }

    private fun deserializeAgent(jsonStr: String): CustomAgent {
        val json = JSONObject(jsonStr)
        val triggers = mutableListOf<AgentTrigger>()
        val trigArr = json.optJSONArray("triggers") ?: JSONArray()
        for (i in 0 until trigArr.length()) {
            val tObj = trigArr.getJSONObject(i)
            val cfgObj = tObj.optJSONObject("config") ?: JSONObject()
            val cfg = mutableMapOf<String, String>()
            cfgObj.keys().forEach { k -> cfg[k] = cfgObj.optString(k) }
            triggers.add(
                AgentTrigger(
                    id = tObj.optString("id", UUID.randomUUID().toString()),
                    type = tObj.optString("type", "manual"),
                    name = tObj.optString("name", "Trigger"),
                    description = tObj.optString("description", ""),
                    config = cfg,
                    isEnabled = tObj.optBoolean("isEnabled", true),
                )
            )
        }

        val tools = mutableListOf<AgentTool>()
        val toolsArr = json.optJSONArray("tools") ?: JSONArray()
        for (i in 0 until toolsArr.length()) {
            val toolObj = toolsArr.getJSONObject(i)
            tools.add(
                AgentTool(
                    id = toolObj.optString("id", UUID.randomUUID().toString()),
                    type = toolObj.optString("type", "web_search"),
                    name = toolObj.optString("name", "Tool"),
                    description = toolObj.optString("description", ""),
                    isEnabled = toolObj.optBoolean("isEnabled", true),
                )
            )
        }

        val knowledge = mutableListOf<AgentKnowledgeItem>()
        val knowArr = json.optJSONArray("knowledge") ?: JSONArray()
        for (i in 0 until knowArr.length()) {
            val kObj = knowArr.getJSONObject(i)
            knowledge.add(
                AgentKnowledgeItem(
                    id = kObj.optString("id", UUID.randomUUID().toString()),
                    title = kObj.optString("title", "Untitled Document"),
                    content = kObj.optString("content", ""),
                    updatedAt = kObj.optLong("updatedAt", System.currentTimeMillis()),
                )
            )
        }

        val setObj = json.optJSONObject("settings") ?: JSONObject()
        val settings = AgentSettings(
            maxIterations = setObj.optInt("maxIterations", 10),
            reasoningEffort = setObj.optString("reasoningEffort", "Medium"),
            memoryDepth = setObj.optInt("memoryDepth", 10),
            toolCallConcurrency = setObj.optInt("toolCallConcurrency", 1),
            timeoutSeconds = setObj.optInt("timeoutSeconds", 60),
            description = setObj.optString("description", ""),
        )

        val sessions = mutableListOf<AgentSession>()
        val sessArr = json.optJSONArray("sessions") ?: JSONArray()
        for (i in 0 until sessArr.length()) {
            val sObj = sessArr.getJSONObject(i)
            sessions.add(
                AgentSession(
                    id = sObj.optString("id", UUID.randomUUID().toString()),
                    timestamp = sObj.optLong("timestamp", System.currentTimeMillis()),
                    status = sObj.optString("status", "Success"),
                    prompt = sObj.optString("prompt", ""),
                    response = sObj.optString("response", ""),
                    durationMs = sObj.optLong("durationMs", 0L),
                )
            )
        }

        return CustomAgent(
            id = json.getString("id"),
            name = json.optString("name", "Custom Agent"),
            iconEmoji = json.optString("iconEmoji", "☀️"),
            iconColorHex = json.optLong("iconColorHex", 0xFFFF9800),
            provider = AiProvider.fromKey(json.optString("provider", "Gemini")),
            model = json.optString("model", "gemini-2.0-flash"),
            instructions = json.optString("instructions", ""),
            triggers = triggers,
            tools = tools,
            knowledge = knowledge,
            settings = settings,
            sessions = sessions,
            isPublished = json.optBoolean("isPublished", false),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
        )
    }
}
