package com.kaivoflow.core.data

import com.kaivoflow.core.model.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialVaultTest {
    private val dao = FakeCredentialDao()
    private val cipher = SoftCipher()
    private val metaAccess = FakeVaultMeta()
    private val meta = VaultMetaStore(metaAccess) { 5L }
    private val lock = Mutex()
    private var ids = 0
    private val migrations = CredentialMigrations(dao, cipher, meta, lock, { 7L }, { "gen${ids++}-0123456789abcdef" })
    private val secrets = VaultSecretValues(dao, cipher)
    private fun repo(m: CredentialMigrations? = null) =
        EncryptedCredentialRepository(dao, cipher, Dispatchers.Unconfined, null, m, secrets, lock) { 100L }

    private suspend fun put(id: String, type: String, fields: Map<String, String>, created: Long = 1L) =
        dao.upsert(CredentialEntity(id, type, "label-$id", cipher.encrypt(JsonCodec.toJson(fields).toString())!!, created))

    private suspend fun fieldsOf(id: String) = VaultCodec.decode(cipher, dao.byId(id)!!.cipherText)!!

    // ---- DB-18 ----

    @Test fun legacyGoogleSheetsRowsAreRemovedOnceAndNotAgain() = runBlocking {
        put("g1", CredentialRegistry.LEGACY_GOOGLE_SHEETS_TYPE, mapOf("accessToken" to "x"))
        assertEquals(1, migrations.removeLegacyGoogleSheets())
        assertNull(dao.byId("g1"))
        // Someone offers the type again later and saves one: the flag keeps the migration away from it.
        put("g2", CredentialRegistry.LEGACY_GOOGLE_SHEETS_TYPE, mapOf("accessToken" to "y"))
        assertEquals(0, migrations.removeLegacyGoogleSheets())
        assertTrue(dao.byId("g2") != null)
    }

    @Test fun constructingTheRepositoryNoLongerDeletesAnything() = runBlocking {
        put("g1", CredentialRegistry.LEGACY_GOOGLE_SHEETS_TYPE, mapOf("accessToken" to "x"))
        repo(null)
        repo(null)
        assertTrue(dao.byId("g1") != null)
    }

    // ---- assistant key migration ----

    private suspend fun putPointer(fields: Map<String, String>) = put(CredentialRegistry.AI_PROVIDER_CONFIG_ID, "ai_assistant", fields)

    @Test fun plaintextKeyInThePointerMovesIntoANamedCredential() = runBlocking {
        putPointer(mapOf("provider" to "Claude", "model" to "m", "baseUrl" to "https://api.anthropic.com/v1", "apiKey" to "sk-ant-secretsecret"))
        val (moved, pending) = migrations.moveAssistantKeyIntoVault()
        assertEquals(1, moved); assertTrue(pending.isEmpty())
        val pointer = fieldsOf(CredentialRegistry.AI_PROVIDER_CONFIG_ID)
        assertFalse(pointer.containsKey("apiKey"))
        val id = pointer.getValue("credentialId")
        assertEquals("anthropicApi", dao.byId(id)!!.type)
        assertEquals("sk-ant-secretsecret", fieldsOf(id)["apiKey"])
        // No row other than the credential carries the key in clear text.
        dao.rows.values.forEach { assertFalse(it.cipherText.contains("sk-ant")) }
    }

    @Test fun existingCredentialWithoutAKeyReceivesIt() = runBlocking {
        put("cred1", "openAiApi", mapOf("baseUrl" to "https://api.openai.com/v1"))
        putPointer(mapOf("provider" to "OpenAI", "credentialId" to "cred1", "apiKey" to "sk-live-abcdefghijkl"))
        migrations.moveAssistantKeyIntoVault()
        assertEquals("sk-live-abcdefghijkl", fieldsOf("cred1")["apiKey"])
        assertEquals("https://api.openai.com/v1", fieldsOf("cred1")["baseUrl"])
        assertFalse(fieldsOf(CredentialRegistry.AI_PROVIDER_CONFIG_ID).containsKey("apiKey"))
    }

    @Test fun existingCredentialKeyIsNeverOverwritten() = runBlocking {
        put("cred1", "openAiApi", mapOf("apiKey" to "sk-newer-keykeykey"))
        putPointer(mapOf("provider" to "OpenAI", "credentialId" to "cred1", "apiKey" to "sk-old-oldoldoldold"))
        migrations.moveAssistantKeyIntoVault()
        assertEquals("sk-newer-keykeykey", fieldsOf("cred1")["apiKey"])
    }

    @Test fun keyMigrationIsIdempotent() = runBlocking {
        putPointer(mapOf("provider" to "Gemini", "apiKey" to "AIzaSyABCDEFGHIJKLMNOPQRSTUV"))
        migrations.runAll()
        val after1 = dao.rows.mapValues { fieldsOf(it.key) }
        val before = dao.rows.size
        migrations.runAll()
        migrations.moveAssistantKeyIntoVault()
        assertEquals(before, dao.rows.size)
        assertEquals(after1, dao.rows.mapValues { fieldsOf(it.key) })
    }

    @Test fun crashBetweenTheTwoWritesIsFinishedByTheNextRun() = runBlocking {
        // State left by a crash: credential exists with the key, pointer still has it too.
        put("cred1", "geminiApi", mapOf("apiKey" to "AIzaKEY0123456789ABCDEFGH"))
        putPointer(mapOf("provider" to "Gemini", "credentialId" to "cred1", "apiKey" to "AIzaKEY0123456789ABCDEFGH"))
        migrations.moveAssistantKeyIntoVault()
        assertFalse(fieldsOf(CredentialRegistry.AI_PROVIDER_CONFIG_ID).containsKey("apiKey"))
        assertEquals("AIzaKEY0123456789ABCDEFGH", fieldsOf("cred1")["apiKey"])
    }

    @Test fun unreadablePointerIsLeftAloneAndRetried() = runBlocking {
        dao.upsert(CredentialEntity(CredentialRegistry.AI_PROVIDER_CONFIG_ID, "ai_assistant", "x", "not-decryptable", 1))
        val (moved, pending) = migrations.moveAssistantKeyIntoVault()
        assertEquals(0, moved); assertEquals(1, pending.size)
        assertFalse(meta.isDone(CredentialMigrations.FLAG_AI_KEY))
        assertEquals("not-decryptable", dao.byId(CredentialRegistry.AI_PROVIDER_CONFIG_ID)!!.cipherText)
    }

    @Test fun cleanPointerOnlyGetsTheFlag() = runBlocking {
        putPointer(mapOf("provider" to "OpenAI", "credentialId" to "c"))
        assertEquals(0, migrations.moveAssistantKeyIntoVault().first)
        assertTrue(meta.isDone(CredentialMigrations.FLAG_AI_KEY))
    }

    @Test fun repositoryRunsTheMigrationsInTheBackground() = runBlocking {
        putPointer(mapOf("provider" to "OpenAI", "apiKey" to "sk-abcdefghijklmnop"))
        repo(migrations)
        // Dispatchers.Unconfined runs the launch eagerly.
        assertFalse(fieldsOf(CredentialRegistry.AI_PROVIDER_CONFIG_ID).containsKey("apiKey"))
    }

    // ---- repository ----

    @Test fun internalRowIsNeverListedButStillReadable() = runBlocking {
        val r = repo()
        r.save("c1", "openAiApi", "Mine", mapOf("apiKey" to "sk-abcdefghijkl"))
        putPointer(mapOf("provider" to "OpenAI", "credentialId" to "c1"))
        assertEquals(listOf("c1"), r.list().map { it.id })
        assertEquals(listOf("c1"), r.observeRefs().first().map { it.id })
        assertEquals(listOf("c1"), r.observeCredentials().first().map { it.id })
        assertEquals("c1", r.getFields(CredentialRegistry.AI_PROVIDER_CONFIG_ID)!!["credentialId"])
    }

    @Test fun saveKeepsTheOriginalCreationTime() = runBlocking {
        val r = repo()
        put("c1", "openAiApi", mapOf("apiKey" to "k"), created = 42L)
        r.save("c1", "openAiApi", "renamed", mapOf("apiKey" to "k2"))
        assertEquals(42L, dao.byId("c1")!!.createdAt)
        assertEquals("k2", r.resolve("c1")["apiKey"])
    }

    @Test fun savedValuesAppearInTheSecretSourceAfterInvalidation() = runBlocking {
        val r = repo()
        assertTrue(secrets.values().isEmpty())
        r.save("t", "telegramBot", "Bot", mapOf("botToken" to "123456:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        r.save("h", "httpHeaderAuth", "H", mapOf("headerName" to "X-Key", "headerValue" to "hv-secret-1"))
        val v = secrets.values()
        assertTrue(v.contains("123456:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertTrue(v.contains("hv-secret-1"))
        assertFalse(v.contains("X-Key"))
        r.delete("h")
        assertFalse(secrets.values().contains("hv-secret-1"))
    }

    @Test fun undecryptableRowContributesNothing() = runBlocking {
        dao.upsert(CredentialEntity("bad", "openAiApi", "x", "garbage", 1))
        assertTrue(secrets.values().isEmpty())
    }

    @Test fun encryptionRefusalFailsTheSaveInsteadOfWritingPlaintext() {
        cipher.failEncrypt = true
        val r = repo()
        val failed = runCatching { runBlocking { r.save("c", "openAiApi", "n", mapOf("apiKey" to "k")) } }.isFailure
        assertTrue(failed)
        assertNull(dao.rows["c"])
    }

    @Test fun assistantProviderWordsBecomeRegistryTypesOnSave() = runBlocking {
        val r = repo()
        r.save("a", "claude", "A", mapOf("apiKey" to "k1234"))
        r.save("b", "openai", "B", mapOf("apiKey" to "k1234"))
        r.save("c", "groq", "C", mapOf("apiKey" to "k1234"))
        r.save("d", "weirdCustom", "D", mapOf("x" to "y"))
        r.save(CredentialRegistry.AI_PROVIDER_CONFIG_ID, "ai_assistant", "cfg", mapOf("provider" to "OpenAI"))
        assertEquals(listOf("anthropicApi", "openAiApi", "openAiApi", "weirdCustom", "ai_assistant"), listOf("a", "b", "c", "d", CredentialRegistry.AI_PROVIDER_CONFIG_ID).map { dao.byId(it)!!.type })
    }

    @Test fun existingRowsWithProviderWordsAreRetypedOnce() = runBlocking {
        put("a", "ollama", mapOf("baseUrl" to "http://10.0.2.2:11434")); put("b", "openAiApi", mapOf("apiKey" to "k1234"))
        assertEquals(1, migrations.normaliseTypes())
        assertEquals("ollamaApi", dao.byId("a")!!.type)
        assertEquals("http://10.0.2.2:11434", fieldsOf("a")["baseUrl"])
        put("c", "claude", mapOf("apiKey" to "k1234"))
        assertEquals(0, migrations.normaliseTypes()); assertEquals("claude", dao.byId("c")!!.type)
    }

    @Test fun savingAnOllamaCredentialAllowsItsLocalHost() = runBlocking {
        val seen = ArrayList<String>()
        val r = EncryptedCredentialRepository(dao, cipher, Dispatchers.Unconfined, null, null, secrets, lock, com.kaivoflow.core.model.LocalHostRegistrar { seen += it }) { 1L }
        r.save("o", "ollama", "Local", mapOf("baseUrl" to "http://192.168.1.20:11434"))
        r.save("k", "openai", "Cloud", mapOf("baseUrl" to "https://api.openai.com/v1", "apiKey" to "k1234"))
        assertEquals(listOf("http://192.168.1.20:11434"), seen)
    }
}
