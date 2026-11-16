package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.StepExecutionException

/**
 * Android capabilities, expressed as Steps.
 *
 * The executors are pure Kotlin and talk to [DeviceGateway]; the Android implementation lives in
 * `:core:work`. That keeps every Step unit-testable and keeps permission-handling code out of the
 * node definitions.
 */
interface DeviceGateway {
    suspend fun sendNotification(title: String, body: String, channel: String, deepLink: String?)
    suspend fun sendSms(to: String, body: String, simSlot: Int?)
    suspend fun copyToClipboard(text: String)
    suspend fun openUrlOrApp(uri: String, packageName: String?)
    suspend fun speak(text: String, language: String)
    suspend fun writeFile(fileName: String, content: String): String
    suspend fun readFile(fileName: String): String?
    suspend fun vibrate(pattern: List<Long>)
    suspend fun isNotificationAccessGranted(): Boolean
    suspend fun isSmsPermissionGranted(): Boolean
    suspend fun getBatteryStatus(): Map<String, Any?> = mapOf("level" to 100, "isCharging" to false, "status" to "ok")
    suspend fun getNetworkStatus(): Map<String, Any?> = mapOf("connected" to true, "type" to "wifi", "metered" to false)

    // ---- Binary attachments ------------------------------------------------------------
    // Default implementations keep test doubles and older gateways source-compatible while still
    // giving every pure-Kotlin executor a working, bounded data-URI store.

    /** Persists base64 bytes and returns a JSON-ish [com.kaivoflow.core.model.BinaryDataRef] map. */
    suspend fun storeBinary(fileName: String, mimeType: String, base64: String): Map<String, Any?>? =
        BinarySupport.storeAsDataUri(fileName, mimeType, base64)

    /** Returns base64 bytes for a ref, or null when the URI is not readable on this device. */
    suspend fun loadBinary(uri: String): String? = BinarySupport.readDataUri(uri)

    // ---- Extended phone capabilities ---------------------------------------------------

    suspend fun readClipboard(): String? = null
    suspend fun showToast(text: String) = Unit
    suspend fun shareText(text: String, title: String) = Unit
    suspend fun setTorch(enabled: Boolean): Boolean = false
    suspend fun setVolume(level: Int): Boolean = false
    suspend fun getLocation(): Map<String, Any?> = emptyMap()
    suspend fun getWifiInfo(): Map<String, Any?> = emptyMap()
    suspend fun lookupContact(query: String): Map<String, Any?> = emptyMap()
    suspend fun addCalendarEvent(
        title: String,
        startMillis: Long,
        endMillis: Long,
        description: String,
    ): String? = null
}

/** A gateway that refuses everything, used by tests and previews. */
object NoopDeviceGateway : DeviceGateway {
    override suspend fun sendNotification(title: String, body: String, channel: String, deepLink: String?) = Unit
    override suspend fun sendSms(to: String, body: String, simSlot: Int?) {
        throw StepExecutionException("", "", "sending SMS is not available in this build")
    }
    override suspend fun copyToClipboard(text: String) = Unit
    override suspend fun openUrlOrApp(uri: String, packageName: String?) = Unit
    override suspend fun speak(text: String, language: String) = Unit
    override suspend fun writeFile(fileName: String, content: String): String = fileName
    override suspend fun readFile(fileName: String): String? = null
    override suspend fun vibrate(pattern: List<Long>) = Unit
    override suspend fun isNotificationAccessGranted(): Boolean = false
    override suspend fun isSmsPermissionGranted(): Boolean = false
    override suspend fun getBatteryStatus(): Map<String, Any?> = mapOf("level" to 100, "isCharging" to false, "status" to "ok")
    override suspend fun getNetworkStatus(): Map<String, Any?> = mapOf("connected" to true, "type" to "wifi", "metered" to false)
}
