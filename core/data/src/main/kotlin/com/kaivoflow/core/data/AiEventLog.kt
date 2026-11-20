package com.kaivoflow.core.data

import com.kaivoflow.core.ai.Redactor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AiEvent(val sessionId: String, val seq: Int, val kind: String, val payload: String, val at: Long)

/** Append-only record of what an assistant or agent session did, so a screen can replay it later. */
interface AiEventLog {
    suspend fun append(sessionId: String, kind: String, payload: String)
    suspend fun replay(sessionId: String, afterSeq: Int = 0, limit: Int = 500): List<AiEvent>
    suspend fun deleteSession(sessionId: String)
}

data class EventLogLimits(
    val maxPayloadChars: Int = 16_000,
    val maxEventsPerSession: Int = 2_000,
    val maxTotalEvents: Int = 20_000,
    val keepDays: Int = 90,
)

/**
 * Room event log. Payload text is redacted with the vault's known secrets and key shapes before it is
 * written, then cut to the cap. Per-session and global counts are bounded by dropping the oldest.
 */
class RoomAiEventLog(
    private val access: EventAccess,
    private val secrets: SecretValueSource = SecretValueSource.None,
    private val limits: EventLogLimits = EventLogLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AiEventLog {
    private val lock = Mutex()

    override suspend fun append(sessionId: String, kind: String, payload: String) {
        require(sessionId.isNotBlank()) { "session id is empty" }
        val clean = Redactor(secrets.values()).redact(payload).let {
            if (it.length > limits.maxPayloadChars) it.take(limits.maxPayloadChars) + "…" else it
        }
        val safeKind = kind.filter { it.isLetterOrDigit() || it in "_.-" }.take(48).ifEmpty { "event" }
        lock.withLock {
            val seq = (access.maxSeq(sessionId) ?: 0) + 1
            access.append(AiEventEntity(sessionId = sessionId, seq = seq, kind = safeKind, payload = clean, at = clock()))
            if (seq > limits.maxEventsPerSession) access.trimSession(sessionId, seq - limits.maxEventsPerSession + 1)
            val total = access.total()
            if (total > limits.maxTotalEvents) access.trimOldest(total - limits.maxTotalEvents)
        }
    }

    override suspend fun replay(sessionId: String, afterSeq: Int, limit: Int): List<AiEvent> =
        access.forSession(sessionId, afterSeq, limit.coerceIn(1, 2_000))
            .map { AiEvent(it.sessionId, it.seq, it.kind, it.payload, it.at) }

    override suspend fun deleteSession(sessionId: String) = access.deleteSession(sessionId)

    suspend fun prune(now: Long = clock()) { access.deleteBefore(now - limits.keepDays * 86_400_000L) }
}
