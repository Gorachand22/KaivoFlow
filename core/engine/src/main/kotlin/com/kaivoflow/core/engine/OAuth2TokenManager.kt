package com.kaivoflow.core.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * A small, sovereign OAuth2 token manager.
 *
 * There is no browser redirect, no account and no background sync: the user pastes the refresh
 * token (or client credentials) once, it is encrypted by the credential repository, and this
 * manager refreshes it on first use. One mutex per credential prevents a dozen Steps in one Run
 * from racing to spend the same refresh token.
 */
class OAuth2TokenManager(
    private val http: HttpGateway,
    private val store: OAuth2TokenStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshSkewMs: Long = 60_000L,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Returns usable credential fields, refreshing when the access token is missing or near expiry. */
    suspend fun refreshIfNeeded(
        credentialId: String,
        credentialType: String,
        fields: Map<String, String>,
    ): Map<String, String> {
        val isOAuth = credentialType.equals("oauth2", true) ||
            fields["grantType"].equals("refresh_token", true) ||
            fields["grantType"].equals("client_credentials", true)
        if (!isOAuth) return fields
        val lock = locks.computeIfAbsent(credentialId) { Mutex() }
        return lock.withLock { refreshLocked(credentialId, fields) }
    }

    private suspend fun refreshLocked(id: String, fields: Map<String, String>): Map<String, String> {
        val accessToken = fields["accessToken"].orEmpty()
        val expiresAt = fields["expiresAt"]?.toLongOrNull()
        val stillValid = accessToken.isNotBlank() &&
            (expiresAt == null || clock() + refreshSkewMs < expiresAt)
        if (stillValid) return fields

        val tokenUrl = fields["tokenUrl"].orEmpty()
        if (!tokenUrl.startsWith("https://")) {
            throw KaivoException("A connected account needs an https:// token address.")
        }
        val refreshToken = fields["refreshToken"].orEmpty()
        val clientId = fields["clientId"].orEmpty()
        val clientSecret = fields["clientSecret"].orEmpty()
        val grantType = fields["grantType"].orEmpty().ifBlank {
            if (refreshToken.isBlank()) "client_credentials" else "refresh_token"
        }
        if (grantType.equals("refresh_token", true) && refreshToken.isBlank()) {
            throw KaivoException("This connected account needs a refresh token. Add it in Credentials.")
        }

        val form = LinkedHashMap<String, String>()
        form["grant_type"] = grantType
        if (grantType.equals("refresh_token", true)) form["refresh_token"] = refreshToken
        if (clientId.isNotBlank()) form["client_id"] = clientId
        if (clientSecret.isNotBlank()) form["client_secret"] = clientSecret
        fields["scope"]?.takeIf { it.isNotBlank() }?.let { form["scope"] = it }

        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        if (fields["authStyle"].equals("basic", true) && clientId.isNotBlank()) {
            val raw = "$clientId:$clientSecret"
            headers["Authorization"] = "Basic " + Base64.getEncoder()
                .encodeToString(raw.toByteArray(Charsets.UTF_8))
            form.remove("client_id")
            form.remove("client_secret")
        }

        val response = http.request(
            HttpRequestSpec(
                method = "POST",
                url = tokenUrl,
                headers = headers,
                body = form,
                formFields = form,
                bodyKind = HttpRequestSpec.BodyKind.Form,
                timeoutMs = 30_000L,
            )
        )
        if (!response.isSuccess) {
            val detail = Values.asString(Values.deepGet(response.body, "error_description"))
                .ifBlank { Values.asString(Values.deepGet(response.body, "error")) }
            throw KaivoException(
                if (detail.isBlank()) "The token service answered ${response.status}. Re-connect this account."
                else "The token service said: ${detail.take(180)}",
            )
        }

        val token = parseToken(response.body, response.bodyText)
        if (token.accessToken.isBlank()) {
            throw KaivoException("The token service did not return an access token. Check the connected account.")
        }
        val expiresIn = token.expiresIn?.coerceIn(1.0, 31_536_000.0) ?: 3600.0
        val updated = LinkedHashMap(fields)
        updated["accessToken"] = token.accessToken
        updated["tokenType"] = token.tokenType.ifBlank { "Bearer" }
        updated["expiresAt"] = (clock() + (expiresIn * 1000L).toLong()).toString()
        token.refreshToken?.takeIf { it.isNotBlank() }?.let { updated["refreshToken"] = it }
        token.scope?.takeIf { it.isNotBlank() }?.let { updated["scope"] = it }
        updated["grantType"] = grantType
        store.save(id, updated)
        return updated
    }

    private fun parseToken(body: Any?, bodyText: String): TokenResponse {
        val map = Values.asMap(body)
        if (map.isNotEmpty()) {
            return TokenResponse(
                accessToken = Values.asString(map["access_token"]),
                refreshToken = map["refresh_token"]?.let { Values.asString(it) }?.takeIf { it.isNotBlank() },
                tokenType = Values.asString(map["token_type"], "Bearer"),
                scope = map["scope"]?.let { Values.asString(it) }?.takeIf { it.isNotBlank() },
                expiresIn = Values.asDouble(map["expires_in"]),
            )
        }
        // Some providers answer with JSON but a missing content type. A narrow, quote-safe reader
        // keeps the common fields available without pulling a JSON library into the engine.
        fun stringField(key: String): String? =
            Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(bodyText)?.groupValues?.get(1)
                ?.replace(Regex("\\\\(.)")) { it.groupValues[1] }
        fun doubleField(key: String): Double? =
            Regex("\"$key\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(bodyText)?.groupValues?.get(1)?.toDoubleOrNull()
        return TokenResponse(
            accessToken = stringField("access_token").orEmpty(),
            refreshToken = stringField("refresh_token"),
            tokenType = stringField("token_type") ?: "Bearer",
            scope = stringField("scope"),
            expiresIn = doubleField("expires_in"),
        )
    }

    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val tokenType: String,
        val scope: String?,
        val expiresIn: Double?,
    )
}

/** Persistence contract for refreshed tokens; the Android implementation encrypts before writing. */
interface OAuth2TokenStore {
    suspend fun load(credentialId: String): Map<String, String>
    suspend fun save(credentialId: String, fields: Map<String, String>)
}
