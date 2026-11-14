package com.kaivoflow.core.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The token manager is pure JVM: no Android, no Keystore, no real network. */
class OAuth2TokenManagerTest {

    private class FakeHttp(var response: HttpResponseSpec = HttpResponseSpec(status = 200)) : HttpGateway {
        val requests = mutableListOf<HttpRequestSpec>()
        override suspend fun request(request: HttpRequestSpec): HttpResponseSpec {
            requests += request
            return response
        }
    }

    private class MemoryStore(initial: Map<String, String> = emptyMap()) : OAuth2TokenStore {
        var saved: Map<String, String> = initial
        var saveCount = 0
        override suspend fun load(credentialId: String): Map<String, String> = saved
        override suspend fun save(credentialId: String, fields: Map<String, String>) {
            saved = fields
            saveCount++
        }
    }

    private fun fields(expiresAt: Long?, access: String = "old"): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map["tokenUrl"] = "https://example.com/token"
        map["clientId"] = "client"
        map["clientSecret"] = "secret"
        map["refreshToken"] = "refresh-1"
        map["accessToken"] = access
        map["grantType"] = "refresh_token"
        if (expiresAt != null) map["expiresAt"] = expiresAt.toString()
        return map
    }

    @Test
    fun `a valid token is used without a network call`() = runBlocking {
        val http = FakeHttp()
        val store = MemoryStore()
        val manager = OAuth2TokenManager(http, store, clock = { 1_000_000L })
        val out = manager.refreshIfNeeded("cred", "oauth2", fields(expiresAt = 1_500_000L))
        assertEquals("old", out["accessToken"])
        assertEquals(0, http.requests.size)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun `an expired token is refreshed encrypted fields are returned and saved`() = runBlocking {
        val http = FakeHttp(
            HttpResponseSpec(
                status = 200,
                body = mapOf(
                    "access_token" to "new-token",
                    "refresh_token" to "refresh-2",
                    "expires_in" to 3600.0,
                    "token_type" to "Bearer",
                ),
            )
        )
        val store = MemoryStore()
        val manager = OAuth2TokenManager(http, store, clock = { 1_000_000L })
        val out = manager.refreshIfNeeded("cred", "oauth2", fields(expiresAt = 1_010_000L))
        assertEquals("new-token", out["accessToken"])
        assertEquals("refresh-2", out["refreshToken"])
        assertEquals(4_600_000L, out["expiresAt"]!!.toLong())
        assertEquals(1, store.saveCount)
        assertEquals("new-token", store.saved["accessToken"])
        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals(HttpRequestSpec.BodyKind.Form, request.bodyKind)
        assertEquals("refresh-1", request.formFields["refresh_token"])
    }

    @Test
    fun `client credentials work without a refresh token`() = runBlocking {
        val http = FakeHttp(HttpResponseSpec(status = 200, body = mapOf("access_token" to "cc-token", "expires_in" to 60.0)))
        val store = MemoryStore()
        val manager = OAuth2TokenManager(http, store, clock = { 2_000L })
        val initial = mapOf(
            "tokenUrl" to "https://example.com/token",
            "clientId" to "id",
            "clientSecret" to "secret",
            "grantType" to "client_credentials",
            "accessToken" to "",
        )
        val out = manager.refreshIfNeeded("cred", "oauth2", initial)
        assertEquals("cc-token", out["accessToken"])
        assertEquals("client_credentials", http.requests.single().formFields["grant_type"])
        assertEquals(null, http.requests.single().formFields["refresh_token"])
    }

    @Test
    fun `basic auth style moves the client secret into the authorization header`() = runBlocking {
        val http = FakeHttp(HttpResponseSpec(status = 200, body = mapOf("access_token" to "basic-token")))
        val store = MemoryStore()
        val manager = OAuth2TokenManager(http, store, clock = { 0L })
        val initial = fields(expiresAt = null, access = "") + ("authStyle" to "basic")
        manager.refreshIfNeeded("cred", "oauth2", initial)
        val request = http.requests.single()
        assertTrue(request.headers["Authorization"]?.startsWith("Basic ") == true)
        assertEquals(null, request.formFields["client_secret"])
    }

    @Test
    fun `plain json text is accepted when a service omits its content type`() = runBlocking {
        val http = FakeHttp(
            HttpResponseSpec(
                status = 200,
                bodyText = """{"access_token":"text-token","expires_in":120,"token_type":"Bearer"}""",
            )
        )
        val store = MemoryStore()
        val manager = OAuth2TokenManager(http, store, clock = { 5_000L })
        val out = manager.refreshIfNeeded("cred", "oauth2", fields(expiresAt = null, access = ""))
        assertEquals("text-token", out["accessToken"])
        assertEquals(125_000L, out["expiresAt"]!!.toLong())
    }

    @Test
    fun `a non oauth credential is passed through untouched`() = runBlocking {
        val http = FakeHttp()
        val store = MemoryStore()
        val manager = OAuth2TokenManager(http, store, clock = { 0L })
        val apiKey = mapOf("apiKey" to "abc")
        assertEquals(apiKey, manager.refreshIfNeeded("cred", "apiKey", apiKey))
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `missing refresh token fails with a human message`() = runBlocking {
        val http = FakeHttp()
        val store = MemoryStore()
        val manager = OAuth2TokenManager(http, store, clock = { 0L })
        val initial = mapOf(
            "tokenUrl" to "https://example.com/token",
            "grantType" to "refresh_token",
            "accessToken" to "",
        )
        val failure = runCatching { manager.refreshIfNeeded("cred", "oauth2", initial) }.exceptionOrNull()
        assertTrue(failure is KaivoException)
        assertTrue(failure!!.message!!.contains("refresh token"))
        assertEquals(0, http.requests.size)
    }
}
