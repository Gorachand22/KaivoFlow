package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.HttpResponseSpec
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.nodes.app.messaging.TelegramNode
import com.kaivoflow.core.nodes.net.web.HttpNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A credential made in the credential sheet (UI field names) must work in the Steps that accept it (audit I-01). */
class CredentialStepsTest {
    private fun creds(fields: Map<String, String>) = TestCredentials(mapOf("c" to fields))

    private fun runHttp(fields: Map<String, String>): Map<String, String> = runBlocking {
        val http = RecordingHttp()
        http.responder = { HttpResponseSpec(status = 200, body = mapOf("ok" to true)) }
        HttpNode().execute(
            ctx(stepOf("net.http", params = mapOf("method" to "GET", "url" to "https://api.test/x", "auth" to "credential", "credential" to "c")),
                listOf(item("a" to 1)), http = http, credentials = creds(fields))
        )
        http.requests.single().headers
    }

    @Test fun headerAuthFromTheSheetSendsItsHeader() {
        val h = runHttp(mapOf("headerName" to "X-Api-Key", "headerValue" to "abc123"))
        assertEquals("abc123", h["X-Api-Key"]); assertFalse(h.containsKey("Authorization"))
    }

    @Test fun headerAuthSavedWithOldNamesStillWorks() {
        assertEquals("v", runHttp(mapOf("header" to "X-Old", "value" to "v"))["X-Old"])
    }

    @Test fun bearerFromTheSheetSendsAuthorization() {
        assertEquals("Bearer tok", runHttp(mapOf("token" to "tok"))["Authorization"])
    }

    @Test fun basicAndApiKeyCredentialsStillWork() {
        assertTrue(runHttp(mapOf("username" to "u", "password" to "p"))["Authorization"]!!.startsWith("Basic "))
        assertEquals("Bearer k", runHttp(mapOf("apiKey" to "k"))["Authorization"])
        assertEquals("Bearer t", runHttp(mapOf("accessToken" to "t", "apiKey" to "k"))["Authorization"])
    }

    @Test fun headerNameThatCouldInjectAHeaderIsIgnored() {
        assertTrue(runHttp(mapOf("headerName" to "X\r\nEvil", "headerValue" to "v")).keys.none { it.contains("Evil") })
    }

    private fun telegram(fields: Map<String, String>) = runBlocking {
        val http = RecordingHttp()
        TelegramNode().execute(
            ctx(stepOf("app.telegram", params = mapOf("credential" to "c", "chatId" to "1", "text" to "hi")), listOf(item("a" to 1)), http = http, credentials = creds(fields))
        )
        http.requests.single().url
    }

    @Test fun telegramCredentialFromTheSheetWorks() {
        assertTrue(telegram(mapOf("botToken" to "123:AAA")).contains("bot123:AAA/sendMessage"))
    }

    @Test fun telegramCredentialFromTheOldSettingsScreenStillWorks() {
        assertTrue(telegram(mapOf("apiKey" to "123:BBB")).contains("bot123:BBB/"))
    }

    @Test fun telegramWithAnEmptyCredentialExplainsWhy() {
        val e = runCatching { telegram(mapOf("baseUrl" to "x")) }.exceptionOrNull()
        assertTrue(e is StepExecutionException && e.message!!.contains("bot token"))
    }
}
