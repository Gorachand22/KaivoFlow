package com.kaivoflow.core.ai

import java.util.concurrent.ConcurrentHashMap

/** Persistence seam. A Room-backed implementation arrives later; the loop only sees [ConversationMemory]. */
interface MemoryStore {
    suspend fun read(key: String): List<ChatMessage>
    suspend fun write(key: String, messages: List<ChatMessage>)
    suspend fun clear(key: String)
}

class InMemoryStore : MemoryStore {
    private val map = ConcurrentHashMap<String, List<ChatMessage>>()
    override suspend fun read(key: String): List<ChatMessage> = map[key].orEmpty()
    override suspend fun write(key: String, messages: List<ChatMessage>) { map[key] = messages.toList() }
    override suspend fun clear(key: String) { map.remove(key) }
}

interface ConversationMemory {
    /** Earlier turns for [key], oldest first, starting with a user message. */
    suspend fun load(key: String): List<ChatMessage>

    /** Records one finished turn. Tool calls and tool results are deliberately not remembered. */
    suspend fun remember(key: String, user: String, assistant: String)

    suspend fun clear(key: String)
}

/**
 * Keeps the last [maxMessages] user and assistant messages per key. The bound applies to what is
 * stored, not only to what is loaded, so memory cannot grow without limit. Text is redacted and cut
 * to [maxCharsPerMessage] before it is stored.
 */
class WindowMemory(
    private val store: MemoryStore = InMemoryStore(),
    private val maxMessages: Int = 20,
    private val maxCharsPerMessage: Int = 4_000,
    private val redactor: Redactor = Redactor(),
) : ConversationMemory {
    init { require(maxMessages >= 2) { "a memory window needs room for one turn" } }

    override suspend fun load(key: String): List<ChatMessage> = window(store.read(key))

    override suspend fun remember(key: String, user: String, assistant: String) {
        val next = store.read(key) +
            ChatMessage.user(clean(user)) + ChatMessage.assistant(clean(assistant))
        store.write(key, window(next))
    }

    override suspend fun clear(key: String) = store.clear(key)

    private fun clean(s: String) = redactor.redact(s).let { if (it.length > maxCharsPerMessage) it.take(maxCharsPerMessage) + "…" else it }

    private fun window(all: List<ChatMessage>): List<ChatMessage> {
        val tail = all.filter { it.role != Role.Tool }.takeLast(maxMessages)
        return tail.dropWhile { it.role != Role.User }
    }
}
