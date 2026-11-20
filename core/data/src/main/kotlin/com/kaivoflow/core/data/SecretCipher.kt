package com.kaivoflow.core.data

/**
 * What the vault needs from an encryption provider. [CryptoBox] (Android Keystore AES-GCM) is the app
 * implementation; JVM tests use a software AES-GCM cipher. Both return null on any failure and never throw.
 */
interface SecretCipher {
    fun encrypt(plainText: String): String?
    fun decrypt(payload: String): String?
}
