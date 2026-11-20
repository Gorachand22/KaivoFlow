package com.kaivoflow.core.data

import com.kaivoflow.core.ai.ChatMessage
import com.kaivoflow.core.ai.MemoryStore
import com.kaivoflow.core.ai.Redactor
import com.kaivoflow.core.ai.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/** Size and age rules for persistent agent memory. Every bound applies to what is stored. */
data class MemoryLimits(
    val maxMessagesPerKey: Int = 200,
    val maxCharsPerMessage: Int = 8_000,
    val maxBytesPerKey: Int = 128 * 1024,
    val maxKeysPerScope: Int = 100,
    val maxTotalBytes: Long = 8L * 1024 * 1024,
    val idleDays: Int = 30,
    val maxKeyChars: Int = 4_096,
)

/**
 * Room-backed [MemoryStore] for one agent [scope] (an agent id). Several agents share the table but never
 * see each other's rows: the stored id is `scope:sha256(scope, key)`, so the session key (often built from
 * item data) is never used as a raw id and one agent cannot ask for another's key.
 *
 * What is stored: user and assistant text only. Tool turns and tool-call arguments are dropped on write
 * (the window memory does not keep them either), and text is redacted with the vault's known secrets and
 * cut to the limits. A row idle for [MemoryLimits.idleDays] is treated as gone on read and removed by [sweep].
 */
class RoomMemoryStore(
    private val scope: String,
    private val access: MemoryAccess,
    private val secrets: SecretValueSource = SecretValueSource.None,
    private val limits: MemoryLimits = MemoryLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) : MemoryStore {
    init { require(scope.isNotBlank() && ':' !in scope) { "memory scope must be a non-empty id without ':'" } }

    override suspend fun read(key: String): List<ChatMessage> {
        val rowKey = rowKey(key)
        val row = access.get(rowKey) ?: return emptyList()
        val now = clock()
        if (now - row.lastAccessAt > limits.idleDays * DAY_MS) {
            access.delete(rowKey)
            return emptyList()
        }
        access.touch(rowKey, now)
        return decode(row.payload)
    }

    override suspend fun write(key: String, messages: List<ChatMessage>) {
        val rowKey = rowKey(key)
        val redactor = Redactor(secrets.values())
        var kept = messages
            .filter { it.role != Role.Tool }
            .takeLast(limits.maxMessagesPerKey)
            .map { m ->
                val clean = redactor.redact(m.text)
                ChatMessage(m.role, if (clean.length > limits.maxCharsPerMessage) clean.take(limits.maxCharsPerMessage) + "…" else clean)
            }
        var payload = encode(kept)
        while (payload.toByteArray(Charsets.UTF_8).size > limits.maxBytesPerKey && kept.size > 1) {
            kept = kept.drop(1)
            payload = encode(kept)
        }
        val bytes = payload.toByteArray(Charsets.UTF_8).size
        if (bytes > limits.maxBytesPerKey) { access.delete(rowKey); return }
        val now = clock()
        val created = access.get(rowKey)?.createdAt ?: now
        access.put(AgentMemoryEntity(rowKey, scope, payload, kept.size, bytes, created, now, now))
        enforceCaps(rowKey)
    }

    override suspend fun clear(key: String) = access.delete(rowKey(key))

    /** Removes everything this agent remembers (used when the agent is deleted). */
    suspend fun clearAll() = access.deleteScope(scope)

    private suspend fun enforceCaps(justWritten: String) {
        val mine = access.scopeRows(scope)
        if (mine.size > limits.maxKeysPerScope) {
            mine.filter { it.rowKey != justWritten }.take(mine.size - limits.maxKeysPerScope).forEach { access.delete(it.rowKey) }
        }
        var total = access.totalBytes()
        if (total > limits.maxTotalBytes) {
            for (r in access.allRows()) {
                if (total <= limits.maxTotalBytes) break
                if (r.rowKey == justWritten) continue
                access.delete(r.rowKey)
                total -= r.bytes
            }
        }
    }

    private fun rowKey(key: String): String {
        require(key.isNotBlank()) { "the memory key is empty; an empty key would share one thread between everything" }
        require(key.length <= limits.maxKeyChars) { "the memory key is too long" }
        val digest = MessageDigest.getInstance("SHA-256").digest("$scope\u0000$key".toByteArray(Charsets.UTF_8))
        return "$scope:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun encode(list: List<ChatMessage>): String = buildJsonArray {
        list.forEach { m ->
            add(buildJsonObject {
                put("r", JsonPrimitive(m.role.name))
                put("t", JsonPrimitive(m.text))
            })
        }
    }.toString()

    private fun decode(payload: String): List<ChatMessage> {
        val arr = runCatching { Json.parseToJsonElement(payload) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val role = runCatching { Role.valueOf((o["r"] as? JsonPrimitive)?.contentOrNull.orEmpty()) }.getOrNull() ?: return@mapNotNull null
            if (role == Role.Tool) return@mapNotNull null
            ChatMessage(role, (o["t"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
    }

    companion object {
        const val DAY_MS = 86_400_000L

        /** Deletes memory idle longer than [idleDays]. Returns the number of rows removed. */
        suspend fun sweep(access: MemoryAccess, now: Long = System.currentTimeMillis(), idleDays: Int = 30): Int =
            access.deleteIdleBefore(now - idleDays * DAY_MS)
    }
}

/**
 * Hands out one [RoomMemoryStore] per agent id. The first call in a process also runs the 30-day idle sweep.
 */
class AgentMemoryFactory(private val access: MemoryAccess, private val secrets: SecretValueSource) {
    private val swept = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun forAgent(agentId: String, limits: MemoryLimits = MemoryLimits()): MemoryStore {
        if (swept.compareAndSet(false, true)) runCatching { RoomMemoryStore.sweep(access, idleDays = limits.idleDays) }
        return RoomMemoryStore(agentId, access, secrets, limits)
    }
}
