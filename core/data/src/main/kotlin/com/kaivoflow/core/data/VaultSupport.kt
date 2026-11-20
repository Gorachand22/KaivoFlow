package com.kaivoflow.core.data

import com.kaivoflow.core.model.CredentialRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Encode and decode the field map that one credential row holds. Shared by every code path that touches the blob. */
internal object VaultCodec {
    fun decode(cipher: SecretCipher, cipherText: String): Map<String, String>? {
        val json = cipher.decrypt(cipherText) ?: return null
        val element = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
        val map = JsonCodec.fromJsonMap(element) ?: return null
        return map.mapValues { it.value?.toString().orEmpty() }
    }

    fun encode(cipher: SecretCipher, fields: Map<String, String>): String? =
        cipher.encrypt(JsonCodec.toJson(fields)?.toString() ?: "{}")
}

/** One-time flags: "this migration has finished". */
class VaultMetaStore(private val access: VaultMetaAccess, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun isDone(key: String): Boolean = access.get(key) == "done"
    suspend fun markDone(key: String) = access.put(VaultMetaEntity(key, "done", clock()))
}

/**
 * Every secret value the vault holds, for redaction. Values are cached; the credential repository calls
 * [invalidate] after a save or delete. Never logs, never throws: an undecryptable row contributes nothing.
 */
interface SecretValueSource {
    suspend fun values(): List<String>
    fun invalidate() {}

    object None : SecretValueSource {
        override suspend fun values(): List<String> = emptyList()
    }
}

class VaultSecretValues(
    private val dao: CredentialDao,
    private val cipher: SecretCipher,
) : SecretValueSource {
    private val lock = Mutex()
    @Volatile private var cache: List<String>? = null

    override suspend fun values(): List<String> {
        cache?.let { return it }
        return lock.withLock {
            cache ?: load().also { cache = it }
        }
    }

    override fun invalidate() { cache = null }

    private suspend fun load(): List<String> {
        val out = LinkedHashSet<String>()
        for (row in runCatching { dao.all() }.getOrDefault(emptyList())) {
            val fields = VaultCodec.decode(cipher, row.cipherText) ?: continue
            if (CredentialRegistry.isInternal(row.id, row.type)) {
                // The pointer row has no secret by design, but an old row may still carry a key.
                fields["apiKey"]?.trim()?.takeIf { it.length >= 4 }?.let(out::add)
                continue
            }
            out.addAll(CredentialRegistry.secretValues(row.type, fields))
        }
        return out.sortedByDescending { it.length }
    }
}
