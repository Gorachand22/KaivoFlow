package com.kaivoflow.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Software AES-256-GCM with the same payload layout as CryptoBox, so tests exercise real encryption. */
class SoftCipher(private val key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) : SecretCipher {
    var failEncrypt = false
    override fun encrypt(plainText: String): String? {
        if (failEncrypt) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plainText.toByteArray()))
    }
    override fun decrypt(payload: String): String? = runCatching {
        val b = Base64.getDecoder().decode(payload)
        val n = b[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b.copyOfRange(1, 1 + n)))
        String(c.doFinal(b.copyOfRange(1 + n, b.size)))
    }.getOrNull()
}

class FakeCredentialDao : CredentialDao {
    val rows = LinkedHashMap<String, CredentialEntity>()
    private val flow = MutableStateFlow<List<CredentialEntity>>(emptyList())
    private fun publish() { flow.value = rows.values.toList() }
    override fun observeAll(): Flow<List<CredentialEntity>> = flow
    override suspend fun all() = rows.values.toList()
    override suspend fun byId(id: String) = rows[id]
    override suspend fun upsert(entity: CredentialEntity) { rows[entity.id] = entity; publish() }
    override suspend fun delete(id: String) { rows.remove(id); publish() }
}

class FakeVaultMeta : VaultMetaAccess {
    val map = HashMap<String, String>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(entity: VaultMetaEntity) { map[entity.key] = entity.value }
}
