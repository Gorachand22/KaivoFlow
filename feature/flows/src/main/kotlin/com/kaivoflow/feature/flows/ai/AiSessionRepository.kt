package com.kaivoflow.feature.flows.ai

import android.content.Context
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

/**
 * Represents a saved conversation thread with the AI Assistant.
 * Modeled after n8n's SessionManagerService & assistant thread persistence.
 */
data class AiSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val flowId: String? = null,
    val flowName: String? = null,
    val messages: List<AiChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

interface AiSessionRepository {
    fun observeSessions(): Flow<List<AiSession>>
    suspend fun getSession(id: String): AiSession?
    suspend fun saveSession(session: AiSession)
    suspend fun deleteSession(id: String)
    suspend fun clearAll()
}

@Singleton
class FileAiSessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : AiSessionRepository {

    private val io: CoroutineDispatcher = Dispatchers.IO

    private val sessionsDir: File
        get() = context.filesDir.resolve("ai_sessions").apply { mkdirs() }

    private val _sessionsFlow = MutableStateFlow<List<AiSession>>(emptyList())

    init {
        loadFromDisk()
    }

    override fun observeSessions(): Flow<List<AiSession>> = _sessionsFlow.asStateFlow()

    override suspend fun getSession(id: String): AiSession? = withContext(io) {
        _sessionsFlow.value.firstOrNull { it.id == id } ?: loadSessionFile(id)
    }

    override suspend fun saveSession(session: AiSession): Unit = withContext(io) {
        val updated = session.copy(updatedAt = System.currentTimeMillis())
        val file = sessionsDir.resolve("${updated.id}.json")
        file.writeText(serializeSession(updated))

        val current = _sessionsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            current[index] = updated
        } else {
            current.add(0, updated)
        }
        current.sortByDescending { it.updatedAt }
        _sessionsFlow.value = current
    }

    override suspend fun deleteSession(id: String): Unit = withContext(io) {
        sessionsDir.resolve("$id.json").delete()
        _sessionsFlow.value = _sessionsFlow.value.filterNot { it.id == id }
    }

    override suspend fun clearAll(): Unit = withContext(io) {
        sessionsDir.listFiles()?.forEach { it.delete() }
        _sessionsFlow.value = emptyList()
    }

    private fun loadFromDisk() {
        val files = sessionsDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        val list = files.mapNotNull { file ->
            runCatching { deserializeSession(file.readText()) }.getOrNull()
        }.sortedByDescending { it.updatedAt }
        _sessionsFlow.value = list
    }

    private fun loadSessionFile(id: String): AiSession? {
        val file = sessionsDir.resolve("$id.json")
        if (!file.exists()) return null
        return runCatching { deserializeSession(file.readText()) }.getOrNull()
    }

    private fun serializeSession(session: AiSession): String {
        val root = JSONObject()
        root.put("id", session.id)
        root.put("title", session.title)
        root.put("flowId", session.flowId ?: JSONObject.NULL)
        root.put("flowName", session.flowName ?: JSONObject.NULL)
        root.put("createdAt", session.createdAt)
        root.put("updatedAt", session.updatedAt)

        val msgsArray = JSONArray()
        session.messages.forEach { msg ->
            val mObj = JSONObject()
            mObj.put("id", msg.id)
            mObj.put("sender", msg.sender.name)
            mObj.put("text", msg.text)
            mObj.put("timestamp", msg.timestamp)

            if (msg.toolCalls.isNotEmpty()) {
                val toolsArr = JSONArray()
                msg.toolCalls.forEach { tc ->
                    val tObj = JSONObject()
                    tObj.put("toolName", tc.toolName)
                    tObj.put("description", tc.description)
                    tObj.put("isSuccess", tc.isSuccess)
                    toolsArr.put(tObj)
                }
                mObj.put("toolCalls", toolsArr)
            }

            msgsArray.put(mObj)
        }
        root.put("messages", msgsArray)

        return root.toString(2)
    }

    private fun deserializeSession(raw: String): AiSession {
        val root = JSONObject(raw)
        val id = root.optString("id", UUID.randomUUID().toString())
        val title = root.optString("title", "Conversation")
        val flowId = if (root.has("flowId") && !root.isNull("flowId")) root.getString("flowId") else null
        val flowName = if (root.has("flowName") && !root.isNull("flowName")) root.getString("flowName") else null
        val createdAt = root.optLong("createdAt", System.currentTimeMillis())
        val updatedAt = root.optLong("updatedAt", System.currentTimeMillis())

        val msgs = mutableListOf<AiChatMessage>()
        val msgsArray = root.optJSONArray("messages")
        if (msgsArray != null) {
            for (i in 0 until msgsArray.length()) {
                val mObj = msgsArray.optJSONObject(i) ?: continue
                val senderName = mObj.optString("sender", "Assistant")
                val sender = if (senderName == "User") MessageSender.User else MessageSender.Assistant
                val text = mObj.optString("text", "")
                val timestamp = mObj.optLong("timestamp", System.currentTimeMillis())
                val mId = mObj.optString("id", UUID.randomUUID().toString())

                val tools = mutableListOf<ToolExecution>()
                val toolsArr = mObj.optJSONArray("toolCalls")
                if (toolsArr != null) {
                    for (j in 0 until toolsArr.length()) {
                        val tObj = toolsArr.optJSONObject(j) ?: continue
                        tools.add(
                            ToolExecution(
                                toolName = tObj.optString("toolName", "tool"),
                                description = tObj.optString("description", ""),
                                isSuccess = tObj.optBoolean("isSuccess", true),
                            )
                        )
                    }
                }

                msgs.add(
                    AiChatMessage(
                        id = mId,
                        sender = sender,
                        text = text,
                        toolCalls = tools,
                        timestamp = timestamp,
                    )
                )
            }
        }

        return AiSession(
            id = id,
            title = title,
            flowId = flowId,
            flowName = flowName,
            messages = msgs,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
