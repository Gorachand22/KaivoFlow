package com.kaivoflow.core.work

import java.security.MessageDigest

/**
 * Explicit webhook security settings. Every default is the safe one; widening exposure means
 * editing [BIND_ALL_INTERFACES] on purpose (a future Settings screen can flip it).
 */
object WebhookDefaults {
    /** When false the listener binds to 127.0.0.1 only. */
    const val BIND_ALL_INTERFACES: Boolean = false
    const val REQUIRE_AUTH: Boolean = true
    const val MAX_BODY_BYTES: Int = 1024 * 1024
    const val MAX_HEADER_BYTES: Int = 16 * 1024
    const val READ_TIMEOUT_MS: Int = 8_000
    /** Wall-clock budget for receiving a whole request, so a slow sender cannot hold a socket. */
    const val REQUEST_DEADLINE_MS: Long = 15_000
    const val MAX_CONNECTIONS: Int = 16
    const val DEFAULT_PORT: Int = 8787
}

/** Pure request-checking helpers; no Android or socket types so they are unit-testable. */
object WebhookPolicy {

    const val PATH_PREFIX = "hook/"

    /**
     * Maps a request target such as `/hook/my-flow?x=1` to the route key `my-flow`.
     * Returns null when the target is not under `/hook/` or the key is empty or contains `..`.
     */
    fun routeKey(target: String): String? {
        val path = target.substringBefore('?').substringBefore('#').trim('/')
        if (!path.startsWith(PATH_PREFIX)) return null
        val key = path.removePrefix(PATH_PREFIX).trim('/')
        if (key.isEmpty() || key.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return key
    }

    /** Normalises the user-entered Spark `path` param into a route key. */
    fun normalizeSparkPath(raw: String): String {
        val trimmed = raw.trim().trim('/').removePrefix(PATH_PREFIX).trim('/')
        return trimmed.ifBlank { "my-flow" }
    }

    /** Token from `Authorization: Bearer <t>` or `X-Kaivo-Token: <t>`; headers are lower-cased keys. */
    fun presentedToken(headers: Map<String, String>): String? {
        val auth = headers["authorization"]
        if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) {
            return auth.substring(7).trim().ifEmpty { null }
        }
        return headers["x-kaivo-token"]?.trim()?.ifEmpty { null }
    }

    /** Constant-time token check. A blank expected token never authenticates. */
    fun tokenMatches(expected: String, presented: String?): Boolean {
        if (expected.isBlank() || presented == null) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))
    }

    fun authorized(requireAuth: Boolean, expectedToken: String, headers: Map<String, String>): Boolean =
        !requireAuth || tokenMatches(expectedToken, presentedToken(headers))

    sealed interface Length {
        data class Ok(val bytes: Int) : Length
        data object Invalid : Length
        data object TooLarge : Length
        data object Unsupported : Length
    }

    /** Validates Content-Length / Transfer-Encoding against [maxBytes]. */
    fun bodyLength(headers: Map<String, String>, maxBytes: Int): Length {
        if (headers.containsKey("transfer-encoding")) return Length.Unsupported
        val raw = headers["content-length"] ?: return Length.Ok(0)
        val n = raw.trim().toLongOrNull() ?: return Length.Invalid
        if (n < 0) return Length.Invalid
        if (n > maxBytes) return Length.TooLarge
        return Length.Ok(n.toInt())
    }
}

/**
 * Edge detection for the battery Spark so a falling battery fires once per threshold crossing
 * instead of once per percent.
 */
object BatteryEdges {
    fun shouldDispatch(
        previousLevel: Int?,
        level: Int,
        wasCharging: Boolean?,
        charging: Boolean,
        thresholds: Collection<Int>,
    ): Boolean {
        if (previousLevel == null || wasCharging == null) return false
        if (!wasCharging && charging) return true
        if (charging) return false
        return thresholds.any { previousLevel > it && level <= it }
    }
}
