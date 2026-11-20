package com.kaivoflow.core.data

import com.kaivoflow.core.ai.Redactor

/** Bounds for stored assistant chat sessions. Applied on every save, so files cannot grow without limit. */
data class SessionLimits(
    val maxMessagesPerSession: Int = 400,
    val maxCharsPerMessage: Int = 20_000,
    val maxToolCallsPerMessage: Int = 50,
    val maxTitleChars: Int = 120,
    val maxSessions: Int = 100,
    val maxFileBytes: Int = 2 * 1024 * 1024,
)

/** Redacts and cuts text before a session is written. One instance per save, built from the vault's secret values. */
class SessionTextCleaner(secrets: Collection<String>, private val limits: SessionLimits = SessionLimits()) {
    private val redactor = Redactor(secrets)

    fun message(text: String): String {
        val clean = redactor.redact(text)
        return if (clean.length > limits.maxCharsPerMessage) clean.take(limits.maxCharsPerMessage) + "…" else clean
    }

    fun title(text: String): String = redactor.redact(text).replace('\n', ' ').take(limits.maxTitleChars)

    /** The newest [SessionLimits.maxMessagesPerSession] items. */
    fun <T> window(items: List<T>): List<T> = items.takeLast(limits.maxMessagesPerSession)

    fun <T> toolCalls(items: List<T>): List<T> = items.take(limits.maxToolCallsPerMessage)
}
