package com.kaivoflow.feature.flows.ai

import android.content.Context
import com.kaivoflow.core.data.AiEvent
import com.kaivoflow.core.data.AiEventLog
import com.kaivoflow.core.data.AtomicFiles
import com.kaivoflow.core.data.SecretValueSource
import com.kaivoflow.core.data.SessionLimits
import com.kaivoflow.core.data.SessionTextCleaner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents a saved conversation thread with the AI Assistant.
 * Multi-turn sovereign session & assistant thread persistence.
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

    /** Adds one redacted, size-capped entry to the durable event log of [sessionId] (for replay). No-op where no log is wired. */
    suspend fun appendEvent(sessionId: String, kind: String, payload: String) {}

    /** The stored events of [sessionId] after [afterSeq], oldest first. */
    suspend fun replayEvents(sessionId: String, afterSeq: Int = 0, limit: Int = 500): List<AiEvent> = emptyList()
}

@Singleton
class FileAiSessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretValueSource,
    private val eventLog: AiEventLog,
) : AiSessionRepository {

    private val limits = SessionLimits()

    private val io: CoroutineDispatcher = Dispatchers.IO
    private val mutex = Mutex()

    private val sessionsDir: File
        get() = context.filesDir.resolve("ai_sessions").apply { mkdirs() }

    private val _sessionsFlow = MutableStateFlow<List<AiSession>>(emptyList())

    init {
        loadFromDisk()
    }

    override fun observeSessions(): Flow<List<AiSession>> = _sessionsFlow.asStateFlow()

    override suspend fun getSession(id: String): AiSession? = withContext(io) {
        mutex.withLock {
            _sessionsFlow.value.firstOrNull { it.id == id } ?: loadSessionFile(id)
        }
    }

    override suspend fun saveSession(session: AiSession): Unit = withContext(io) {
        mutex.withLock {
            val cleaner = SessionTextCleaner(runCatching { secrets.values() }.getOrDefault(emptyList()), limits)
            var updated = sanitize(session.copy(updatedAt = System.currentTimeMillis()), cleaner)
            // Keep the file under the byte cap by dropping the oldest messages; the newest turns matter most.
            var text = serializeSession(updated)
            while (text.toByteArray(Charsets.UTF_8).size > limits.maxFileBytes && updated.messages.size > 2) {
                updated = updated.copy(messages = updated.messages.drop(maxOf(1, updated.messages.size / 10)))
                text = serializeSession(updated)
            }
            // Atomic: temp file, fsync, rename. A kill mid-save leaves the previous version of the file intact.
            AtomicFiles.write(sessionsDir.resolve("${updated.id}.json"), text)

            val current = _sessionsFlow.value.toMutableList()
            val index = current.indexOfFirst { it.id == updated.id }
            if (index >= 0) current[index] = updated else current.add(0, updated)
            current.sortByDescending { it.updatedAt }
            while (current.size > limits.maxSessions) {
                val oldest = current.removeAt(current.lastIndex)
                sessionsDir.resolve("${oldest.id}.json").delete()
            }
            _sessionsFlow.value = current
        }
    }

    private fun sanitize(s: AiSession, c: SessionTextCleaner): AiSession = s.copy(
        title = c.title(s.title),
        messages = c.window(s.messages).map { m ->
            m.copy(
                text = c.message(m.text),
                toolCalls = c.toolCalls(m.toolCalls).map { t -> t.copy(description = c.message(t.description)) },
            )
        },
    )

    override suspend fun appendEvent(sessionId: String, kind: String, payload: String) {
        runCatching { eventLog.append(sessionId, kind, payload) }
    }

    override suspend fun replayEvents(sessionId: String, afterSeq: Int, limit: Int): List<AiEvent> =
        runCatching { eventLog.replay(sessionId, afterSeq, limit) }.getOrDefault(emptyList())

    override suspend fun deleteSession(id: String): Unit = withContext(io) {
        mutex.withLock {
            sessionsDir.resolve("$id.json").delete()
            sessionsDir.resolve("$id.json.tmp").delete()
            _sessionsFlow.value = _sessionsFlow.value.filterNot { it.id == id }
        }
        runCatching { eventLog.deleteSession(id) }
    }

    override suspend fun clearAll(): Unit = withContext(io) {
        mutex.withLock {
            sessionsDir.listFiles()?.forEach { it.delete() }
            _sessionsFlow.value = emptyList()
        }
    }

    private fun loadFromDisk() {
        val files = sessionsDir.listFiles { _, name -> name.endsWith(".json") && !name.endsWith(".tmp") } ?: emptyArray()
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
        val cleanJson = if (raw.contains("}{")) {
            // Self-heal: extract the second/complete JSON object if multiple concatenated
            val lastOpen = raw.lastIndexOf('{')
            val matchingClose = raw.lastIndexOf('}')
            if (lastOpen in 1..matchingClose) {
                // Find matching boundary of last full object
                raw.substring(raw.indexOf("}{") + 1)
            } else {
                raw.substringBefore("}{") + "}"
            }
        } else {
            raw
        }
        val root = runCatching { JSONObject(cleanJson) }.getOrElse {
            JSONObject(raw.substringBefore("}{") + "}")
        }
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
