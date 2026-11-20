package com.kaivoflow.core.data

import com.kaivoflow.core.model.CredentialRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * One-time vault clean-ups. Each step is idempotent (running it twice changes nothing) and is also
 * guarded by a flag in `vault_meta`, so it normally runs once per install. A step that cannot finish (a row
 * will not decrypt) does not set its flag and tries again next start; it never deletes what it cannot read.
 */
class CredentialMigrations(
    private val dao: CredentialDao,
    private val cipher: SecretCipher,
    private val meta: VaultMetaStore,
    private val lock: Mutex,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    data class Report(val removedLegacyRows: Int, val keysMoved: Int, val pending: List<String>, val typesNormalised: Int = 0)

    suspend fun runAll(): Report = lock.withLock {
        val removed = removeLegacyGoogleSheets()
        val normalised = normaliseTypes()
        val (moved, pending) = moveAssistantKeyIntoVault()
        Report(removed, moved, pending, normalised)
    }

    /** DB-18: the old constructor deleted these rows on every start. Now it happens once. */
    internal suspend fun removeLegacyGoogleSheets(): Int {
        if (meta.isDone(FLAG_GOOGLE_SHEETS)) return 0
        val rows = dao.all().filter { it.type == CredentialRegistry.LEGACY_GOOGLE_SHEETS_TYPE }
        rows.forEach { dao.delete(it.id) }
        meta.markDone(FLAG_GOOGLE_SHEETS)
        return rows.size
    }

    /**
     * The assistant used to save credentials under provider words (`openai`, `claude`, `ollama`, `groq`). The
     * registry knows one spelling per type, so pickers and the edit sheet can tell what a row is. Rewrites only
     * the `type` column; the encrypted fields are not touched.
     */
    internal suspend fun normaliseTypes(): Int {
        if (meta.isDone(FLAG_TYPES)) return 0
        var changed = 0
        for (row in dao.all()) {
            if (CredentialRegistry.isInternal(row.id, row.type)) continue
            val canonical = CredentialRegistry.canonicalType(row.type) ?: continue
            if (canonical != row.type) { dao.upsert(row.copy(type = canonical)); changed++ }
        }
        meta.markDone(FLAG_TYPES)
        return changed
    }

    /**
     * Older builds could keep the assistant's API key inside the `ai_provider_config` pointer row. Move it
     * into a named vault credential (creating one when the pointer names none) and rewrite the pointer
     * without the key. The credential is written first, so a crash between the two writes leaves the key in
     * both places, never in neither, and the next run finishes the job.
     */
    internal suspend fun moveAssistantKeyIntoVault(): Pair<Int, List<String>> {
        if (meta.isDone(FLAG_AI_KEY)) return 0 to emptyList()
        val row = dao.byId(CredentialRegistry.AI_PROVIDER_CONFIG_ID)
        if (row == null) { meta.markDone(FLAG_AI_KEY); return 0 to emptyList() }
        val config = VaultCodec.decode(cipher, row.cipherText)
            ?: return 0 to listOf("ai_provider_config could not be decrypted")
        val key = config["apiKey"]?.trim().orEmpty()
        if (key.isEmpty()) {
            if (config.containsKey("apiKey")) rewritePointer(row, config - "apiKey")
            meta.markDone(FLAG_AI_KEY)
            return 0 to emptyList()
        }
        var credentialId = config["credentialId"]?.takeIf { it.isNotBlank() }
        val existing = credentialId?.let { dao.byId(it) }
        if (existing != null) {
            val fields = VaultCodec.decode(cipher, existing.cipherText)
                ?: return 0 to listOf("credential ${existing.id} could not be decrypted")
            if (fields["apiKey"].isNullOrBlank()) {
                val enc = VaultCodec.encode(cipher, fields + ("apiKey" to key)) ?: return 0 to listOf("encryption refused")
                dao.upsert(existing.copy(cipherText = enc))
            }
        } else {
            credentialId = credentialId ?: newId().take(16)
            val provider = config["provider"].orEmpty()
            val type = CredentialRegistry.canonicalType(provider) ?: "openAiApi"
            val fields = buildMap {
                put("apiKey", key)
                config["baseUrl"]?.takeIf { it.isNotBlank() }?.let { put("baseUrl", it) }
                config["model"]?.takeIf { it.isNotBlank() }?.let { put("model", it) }
            }
            val enc = VaultCodec.encode(cipher, fields) ?: return 0 to listOf("encryption refused")
            val label = config["credentialName"]?.takeIf { it.isNotBlank() } ?: "Migrated ${provider.ifBlank { "AI" }} key"
            dao.upsert(CredentialEntity(credentialId, type, label, enc, clock()))
        }
        rewritePointer(row, (config - "apiKey") + ("credentialId" to credentialId!!))
        meta.markDone(FLAG_AI_KEY)
        return 1 to emptyList()
    }

    private suspend fun rewritePointer(row: CredentialEntity, fields: Map<String, String>) {
        val enc = VaultCodec.encode(cipher, fields) ?: return
        dao.upsert(row.copy(cipherText = enc))
    }

    companion object {
        const val FLAG_GOOGLE_SHEETS = "migration.googleSheetsOAuth2.removed.v1"
        const val FLAG_TYPES = "migration.credentialTypes.v1"
        const val FLAG_AI_KEY = "migration.assistantKeyToVault.v1"
    }
}
