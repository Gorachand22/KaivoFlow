package com.kaivoflow.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Error taxonomy and retry rules for model calls. Behaviour follows n8n's model nodes (studied, own code):
 * never retry a cancel; never retry 400, 401, 402, 403, 404, 405, 406, 407, 409, 413; a 429 stops when the
 * provider says the quota or plan is used up or asks for a wait over [RetryPolicy.maxRetryAfterMs], otherwise it
 * is retried and waits for the provider's hint; everything else (5xx, network, timeout) is retried with
 * exponential backoff and jitter, at most [RetryPolicy.maxRetries] times.
 */
object ModelErrors {
    private val NO_RETRY_STATUS = setOf(400, 401, 402, 403, 404, 405, 406, 407, 409, 413)

    private val QUOTA = Regex(
        "(?i)insufficient[_ ]quota|exceeded your current quota|quota (has been )?(exceeded|exhausted|used)|out of (credit|quota)|" +
            "credit balance|billing[_ ](hard[_ ])?(limit|details|not active)|payment required|plan and billing",
    )
    private val TRANSIENT_HINT = Regex("(?i)per[ -]?(minute|second)|retry in|try again in|rate limit|too many requests")
    private val CONTEXT = Regex(
        "(?i)context[_ ]length|maximum context|context window|too many tokens|prompt is too long|input is too long|" +
            "exceeds the (maximum|context)|token count.*exceeds|reduce the length",
    )
    private val RETRY_IN = Regex("(?i)(?:retry|try again) in ([0-9]+(?:\\.[0-9]+)?)\\s*(ms|s|sec|seconds?)")
    private val RETRY_DELAY = Regex("\"retryDelay\"\\s*:\\s*\"([0-9]+(?:\\.[0-9]+)?)s\"")

    fun isRetryable(e: ModelException): Boolean {
        val s = e.status
        if (s != null && s in NO_RETRY_STATUS) return false
        return when (e.kind) {
            ModelErrorKind.Network, ModelErrorKind.Timeout, ModelErrorKind.Server -> true
            ModelErrorKind.RateLimit -> (e.retryAfterMs ?: 0L) <= RetryPolicy.DEFAULT_MAX_RETRY_AFTER_MS
            else -> false
        }
    }

    fun hint(kind: ModelErrorKind): String = when (kind) {
        ModelErrorKind.Auth -> "Check the API key in the credential and that it may use this model."
        ModelErrorKind.Quota -> "The account has no quota or credit left. Add credit or choose another provider."
        ModelErrorKind.RateLimit -> "The provider is limiting requests. Wait a moment or lower the call rate."
        ModelErrorKind.BadRequest -> "The provider refused the request. Check the model name and settings."
        ModelErrorKind.ContextLength -> "The conversation is too long for this model. Shorten it or pick a model with a larger context."
        ModelErrorKind.PayloadTooLarge -> "The request is too large for the provider. Send less data."
        ModelErrorKind.Server -> "The provider had a problem. Try again later or add a fallback model."
        ModelErrorKind.Network -> "The provider could not be reached. Check the connection and the address."
        ModelErrorKind.Timeout -> "The provider took too long. Try again or raise the timeout."
        ModelErrorKind.Redirect -> "The address redirects. Use the final address of the provider."
        ModelErrorKind.Policy -> "This address is not allowed. Use a public https address, or enable local servers."
        ModelErrorKind.Malformed -> "The provider answered with something unexpected. Check the base address."
        ModelErrorKind.Empty -> "The model returned nothing. It may have been blocked or ran out of output tokens."
        ModelErrorKind.Unsupported -> "This model or endpoint cannot do what was asked. Choose another model."
    }

    /** Provider-supplied wait in ms from headers (`retry-after-ms`, `retry-after` in seconds), case-insensitive. */
    fun retryAfterMs(headers: Map<String, String>): Long? {
        fun h(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.trim()
        h("retry-after-ms")?.toDoubleOrNull()?.let { if (it >= 0) return it.toLong() }
        h("retry-after")?.toDoubleOrNull()?.let { if (it >= 0) return (it * 1000).toLong() }
        return null
    }

    /** An error object received inside a stream (`error` field), mapped by its type or code. */
    fun fromStream(err: Map<String, Any?>): ModelException {
        val type = (J.str(err["type"]) ?: J.str(err["code"]) ?: J.str(err["status"])).orEmpty()
        val msg = (J.str(err["message"]) ?: "the provider reported an error").replace(Regex("\\s+"), " ").take(200)
        val t = type.lowercase()
        val kind = when {
            "overload" in t || "unavailable" in t || "server" in t || "internal" in t -> ModelErrorKind.Server
            "rate" in t || "resource_exhausted" in t -> if (QUOTA.containsMatchIn("$t $msg") && !TRANSIENT_HINT.containsMatchIn(msg)) ModelErrorKind.Quota else ModelErrorKind.RateLimit
            "quota" in t -> ModelErrorKind.Quota
            "auth" in t || "permission" in t -> ModelErrorKind.Auth
            "invalid" in t || "bad_request" in t -> if (CONTEXT.containsMatchIn(msg)) ModelErrorKind.ContextLength else ModelErrorKind.BadRequest
            else -> ModelErrorKind.Server
        }
        return ModelException(kind, "The provider reported an error${if (type.isNotEmpty()) " ($type)" else ""}: $msg", null, null, type.ifEmpty { null })
    }

    /** Builds the exception for a non-2xx answer. [safeDetail] is already redacted and shortened. */
    fun fromStatus(status: Int, headers: Map<String, String>, json: Map<String, Any?>?, rawText: String, safeDetail: String): ModelException {
        val code = (J.str(J.path(json, "error.code")) ?: J.str(J.path(json, "error.type")) ?: J.str(J.path(json, "error.status")) ?: J.str(J.path(json, "type")))
            ?.take(80)
        val probe = "${code.orEmpty()} $safeDetail"
        var retryAfter = retryAfterMs(headers)
        if (retryAfter == null) {
            RETRY_DELAY.find(rawText)?.let { retryAfter = (it.groupValues[1].toDouble() * 1000).toLong() }
                ?: RETRY_IN.find(safeDetail)?.let {
                    val n = it.groupValues[1].toDouble()
                    retryAfter = if (it.groupValues[2].lowercase() == "ms") n.toLong() else (n * 1000).toLong()
                }
        }
        val kind = when {
            status == 401 || status == 403 -> ModelErrorKind.Auth
            status == 402 -> ModelErrorKind.Quota
            status == 413 -> ModelErrorKind.PayloadTooLarge
            status == 408 -> ModelErrorKind.Timeout
            status == 429 -> if (QUOTA.containsMatchIn(probe) && !TRANSIENT_HINT.containsMatchIn(safeDetail)) ModelErrorKind.Quota else ModelErrorKind.RateLimit
            status in 400..499 -> if (CONTEXT.containsMatchIn(probe) || status == 400 && CONTEXT.containsMatchIn(rawText)) ModelErrorKind.ContextLength else ModelErrorKind.BadRequest
            else -> ModelErrorKind.Server
        }
        val msg = "The provider returned HTTP $status${if (safeDetail.isNotBlank()) ": $safeDetail" else ""}"
        return ModelException(kind, msg, status, retryAfter, code)
    }
}

/** What a retry loop reports to whoever listens (the event stream), before it sleeps. */
data class RetryInfo(val retryNumber: Int, val delayMs: Long, val reason: String)

/**
 * Retry settings. [sleep] and [random] are injectable so tests run without waiting. [None] never retries.
 */
data class RetryPolicy(
    val maxRetries: Int = 2,
    val baseDelayMs: Long = 500,
    val maxDelayMs: Long = 8_000,
    val maxRetryAfterMs: Long = DEFAULT_MAX_RETRY_AFTER_MS,
    /** Fraction of the delay added or removed at random, 0.25 means plus or minus a quarter. */
    val jitter: Double = 0.25,
    val random: () -> Double = { Math.random() },
    val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    init { require(maxRetries in 0..10) { "maxRetries must be 0 to 10" } }

    fun delayFor(retryIndex: Int, e: ModelException): Long {
        e.retryAfterMs?.let { return it.coerceAtMost(maxRetryAfterMs) + (it * 0.1 * random()).toLong().coerceAtMost(1_000) }
        val exp = baseDelayMs * (1L shl retryIndex.coerceIn(0, 20))
        val capped = exp.coerceAtMost(maxDelayMs)
        val factor = 1.0 + jitter * (2 * random() - 1)
        return (capped * factor).toLong().coerceAtLeast(0)
    }

    /**
     * Runs [block] until it returns, a non-retryable error comes, or retries are used up. Cancellation always
     * passes through at once (also during the sleep). The final error carries the number of attempts.
     */
    suspend fun <T> execute(onRetry: suspend (RetryInfo) -> Unit = {}, block: suspend (attempt: Int) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelException) {
                if (attempt >= maxRetries || !e.retryable || (e.retryAfterMs ?: 0L) > maxRetryAfterMs) throw if (attempt > 0) e.withAttempts(attempt + 1) else e
                val wait = delayFor(attempt, e)
                attempt++
                onRetry(RetryInfo(attempt, wait, e.message.orEmpty().take(160)))
                sleep(wait)
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_RETRY_AFTER_MS = 60_000L
        val None = RetryPolicy(maxRetries = 0)
    }
}
