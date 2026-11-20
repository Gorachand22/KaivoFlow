package com.kaivoflow.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * Credential encryption.
 *
 * An AES-256-GCM key is generated inside the Android Keystore and never leaves it; only ciphertext
 * is ever written to Room. If the device is wiped or the key is invalidated the vault reads as
 * empty rather than crashing — a user can then re-enter their keys.
 *
 * This is the "keys stay on your phone" promise the Privacy Centre makes, in code.
 */
class CryptoBox(private val alias: String = "kaivo.vault.v1") : SecretCipher {

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Synchronized: two threads on a fresh install must not both create the key (the second would orphan the first ciphertext). */
    @Synchronized
    private fun secretKey(): SecretKey? {
        val store = keyStore()
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plainText: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // iv length is stored first so the payload is self-describing.
        Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + cipherText, Base64.NO_WRAP)
    }.getOrNull()

    override fun decrypt(payload: String): String? = runCatching {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        val ivLength = bytes.first().toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val cipherText = bytes.copyOfRange(1 + ivLength, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
