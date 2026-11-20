package com.kaivoflow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRegistryTest {
    private fun ref(id: String, type: String) = CredentialRef(id, type, id)

    @Test fun everyUiTypeIsRegisteredWithAKind() {
        CredentialTypeInfo.ALL.forEach {
            assertEquals(it.type, CredentialRegistry.info(it.type)?.type)
            assertTrue(it.type, CredentialRegistry.kindOf(it.type) != CredentialKind.Custom || it.type == "ollamaApi")
        }
    }

    @Test fun telegramCredentialFromTheSheetYieldsABotToken() {
        assertEquals("1:AAA", CredentialFields.botToken(mapOf("botToken" to "1:AAA")))
        assertEquals("1:AAA", CredentialFields.botToken(mapOf("apiKey" to "1:AAA")))
    }

    @Test fun telegramTokenIsNotABearerTokenForOtherServices() {
        assertEquals("", CredentialFields.bearer(mapOf("botToken" to "1:AAA")))
    }

    @Test fun bearerTypeUsesTokenField() {
        assertEquals(mapOf("Authorization" to "Bearer abc"), CredentialFields.authHeaders(mapOf("token" to "abc")))
        assertEquals(mapOf("Authorization" to "Bearer abc"), CredentialFields.authHeaders(mapOf("token" to "Bearer abc")))
        assertEquals("tok", CredentialFields.bearer(mapOf("accessToken" to "tok", "apiKey" to "k")))
    }

    @Test fun headerAuthReadsNewAndOldNames() {
        assertEquals(mapOf("X-Api-Key" to "s3"), CredentialFields.authHeaders(mapOf("headerName" to "X-Api-Key", "headerValue" to "s3")))
        assertEquals(mapOf("X-Api-Key" to "s3"), CredentialFields.authHeaders(mapOf("header" to "X-Api-Key", "value" to "s3")))
    }

    @Test fun headerNameWithNewlineIsRefused() {
        assertNull(CredentialFields.header(mapOf("headerName" to "X\r\nEvil: 1", "headerValue" to "v")))
        assertTrue(CredentialFields.authHeaders(mapOf("headerName" to "A B", "headerValue" to "v")).isEmpty())
    }

    @Test fun basicAuthEncodes() {
        assertEquals(mapOf("Authorization" to "Basic dTpw"), CredentialFields.authHeaders(mapOf("username" to "u", "password" to "p")))
    }

    @Test fun apiKeyAndOauthStillWork() {
        assertEquals("Bearer k", CredentialFields.authHeaders(mapOf("apiKey" to "k"))["Authorization"])
        assertEquals("Bearer t", CredentialFields.authHeaders(mapOf("accessToken" to "t"))["Authorization"])
    }

    @Test fun pickerFiltersBySlotAndHidesInternalRows() {
        val refs = listOf(ref("a", "openAiApi"), ref("b", "telegramBot"), ref("c", "ai_assistant"),
            ref(CredentialRegistry.AI_PROVIDER_CONFIG_ID, "openAiApi"), ref("d", "googleSheetsOAuth2"))
        assertEquals(listOf("b"), CredentialRegistry.pickable(refs, "app.telegram").map { it.id })
        assertEquals(listOf("a"), CredentialRegistry.pickable(refs, "ai.agent").map { it.id })
        assertEquals(listOf("a", "b"), CredentialRegistry.pickable(refs, "unknown.step").map { it.id })
        assertEquals(listOf("a", "b"), CredentialRegistry.pickable(refs, null).map { it.id })
    }

    @Test fun telegramStepRejectsOpenAiCredential() {
        assertFalse(CredentialRegistry.accepts("app.telegram", "credential", "openAiApi"))
        assertTrue(CredentialRegistry.accepts("app.telegram", "credential", "telegramBot"))
        assertTrue(CredentialRegistry.accepts("net.http", "credential", "HTTPHEADERAUTH"))
    }

    @Test fun oldSpellingsMapToRegisteredTypes() {
        assertEquals("anthropicApi", CredentialRegistry.canonicalType("credential_type_claude"))
        assertEquals("openAiApi", CredentialRegistry.canonicalType("OpenAI"))
        assertNull(CredentialRegistry.canonicalType("nothing"))
    }

    @Test fun secretValuesComeFromDeclaredAndHintedFields() {
        val v = CredentialRegistry.secretValues("httpHeaderAuth", mapOf("headerName" to "X-Key", "headerValue" to "topsecret"))
        assertEquals(listOf("topsecret"), v)
        val u = CredentialRegistry.secretValues("custom", mapOf("note" to "hi", "clientSecret" to "abcd1234"))
        assertEquals(listOf("abcd1234"), u)
    }

    @Test fun everyRegisteredStepSlotAcceptsOnlyRegisteredTypes() {
        CredentialRegistry.stepsWithSlots().forEach { step ->
            CredentialRegistry.acceptedTypes(step).forEach { assertEquals(it, CredentialRegistry.canonicalType(it)) }
        }
    }
}
