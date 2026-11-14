package com.kaivoflow.core.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kaivoflow.core.nodes.DeviceGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Android implementation of the device Steps.
 *
 * Every operation re-checks its permission at call time: a user can revoke SMS or notification
 * access at any moment, and a Flow must fail with a clear message rather than crashing the process.
 */
@Singleton
class AndroidDeviceGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceGateway {

    override suspend fun sendNotification(title: String, body: String, channel: String, deepLink: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = when (channel) {
            "quiet" -> CHANNEL_QUIET
            "urgent" -> CHANNEL_URGENT
            else -> CHANNEL_DEFAULT
        }
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                when (channelId) {
                    CHANNEL_QUIET -> "Quiet results"
                    CHANNEL_URGENT -> "Urgent alerts"
                    else -> "Flow results"
                },
                when (channelId) {
                    CHANNEL_QUIET -> NotificationManager.IMPORTANCE_LOW
                    CHANNEL_URGENT -> NotificationManager.IMPORTANCE_HIGH
                    else -> NotificationManager.IMPORTANCE_DEFAULT
                },
            )
        )
        val intent = deepLink?.let { link ->
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = intent?.let {
            android.app.PendingIntent.getActivity(
                context, System.currentTimeMillis().toInt(), it,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .apply { pending?.let { setContentIntent(it) } }
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override suspend fun sendSms(to: String, body: String, simSlot: Int?) = withContext(Dispatchers.IO) {
        if (!isSmsPermissionGranted()) throw IllegalStateException("SMS permission is not granted")
        @Suppress("DEPRECATION")
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(
                simSlot?.let { slot -> runCatching { slot }.getOrDefault(0) } ?: 0
            )
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = manager.divideMessage(body)
        if (parts.size > 1) {
            manager.sendMultipartTextMessage(to, null, parts, null, null)
        } else {
            manager.sendTextMessage(to, null, body, null, null)
        }
    }

    override suspend fun copyToClipboard(text: String) = withContext(Dispatchers.Main) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        manager.setPrimaryClip(ClipData.newPlainText("KaivoFlow", text))
    }

    override suspend fun openUrlOrApp(uri: String, packageName: String?) = withContext(Dispatchers.Main) {
        val intent = if (packageName != null) {
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override suspend fun speak(text: String, language: String) {
        // TextToSpeech needs its own init callback; bridging it to a suspend function keeps the
        // Step executor clean and guarantees we do not speak before the engine is ready.
        val tts = suspendCancellableCoroutine<TextToSpeech?> { continuation ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) continuation.resume(engine) else continuation.resume(null)
            }
        } ?: return

        try {
            tts.language = runCatching { Locale.forLanguageTag(language) }.getOrDefault(Locale.getDefault())
            withContext(Dispatchers.Main) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kaivoflow-${System.currentTimeMillis()}")
            }
            // Give the engine a moment to finish before releasing it.
            kotlinx.coroutines.delay((text.length * 55L).coerceIn(600, 15_000))
        } finally {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
    }

    override suspend fun writeFile(fileName: String, content: String): String = withContext(Dispatchers.IO) {
        val dir = workflowDir()
        val file = File(dir, sanitize(fileName))
        file.writeText(content)
        file.absolutePath
    }

    override suspend fun readFile(fileName: String): String? = withContext(Dispatchers.IO) {
        val file = File(workflowDir(), sanitize(fileName))
        if (file.exists()) file.readText() else null
    }

    override suspend fun vibrate(pattern: List<Long>): Unit = withContext(Dispatchers.Main) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        val timings = pattern.ifEmpty { listOf(120L) }.toLongArray()
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        }
        Unit
    }

    override suspend fun isNotificationAccessGranted(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.contains(context.packageName)
    }

    override suspend fun isSmsPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    override suspend fun getBatteryStatus(): Map<String, Any?> = withContext(Dispatchers.IO) {
        val filter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percentage = if (level >= 0 && scale > 0) (level * 100) / scale else level
        val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
        val temperature = (batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        mapOf(
            "level" to percentage,
            "isCharging" to isCharging,
            "temperatureCelsius" to temperature,
            "status" to if (isCharging) "charging" else "discharging"
        )
    }

    override suspend fun getNetworkStatus(): Map<String, Any?> = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val activeNetwork = cm?.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val isConnected = caps != null && (
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        )
        val isWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isMetered = cm?.isActiveNetworkMetered == true

        mapOf(
            "connected" to isConnected,
            "type" to when {
                isWifi -> "wifi"
                isCellular -> "cellular"
                else -> if (isConnected) "other" else "none"
            },
            "metered" to isMetered
        )
    }

    // ---- Binary attachments: app-internal files, never external storage -------------------

    override suspend fun storeBinary(fileName: String, mimeType: String, base64: String): Map<String, Any?>? =
        withContext(Dispatchers.IO) {
            val support = com.kaivoflow.core.nodes.BinarySupport
            val bytes = support.decode(base64) ?: return@withContext null
            val safe = support.safeFileName(fileName)
            val dir = File(context.filesDir, "binary").apply { mkdirs() }
            val file = File(dir, safe)
            file.writeBytes(bytes)
            mapOf(
                "uri" to "kaivo://files/$safe",
                "mimeType" to mimeType.ifBlank { "application/octet-stream" },
                "fileName" to safe,
                "sizeBytes" to bytes.size.toLong(),
                "checksum" to com.kaivoflow.core.nodes.CryptoMath.sha256Hex(bytes),
            )
        }

    override suspend fun loadBinary(uri: String): String? = withContext(Dispatchers.IO) {
        val support = com.kaivoflow.core.nodes.BinarySupport
        when {
            uri.startsWith("data:") -> support.readDataUri(uri)
            uri.startsWith("kaivo://files/") -> {
                val name = support.safeFileName(uri.removePrefix("kaivo://files/"))
                val file = File(File(context.filesDir, "binary"), name)
                if (file.exists() && file.length() <= 25L * 1024 * 1024) {
                    java.util.Base64.getEncoder().encodeToString(file.readBytes())
                } else null
            }
            uri.startsWith("content://") -> runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size <= 25L * 1024 * 1024) {
                        java.util.Base64.getEncoder().encodeToString(bytes)
                    } else null
                }
            }.getOrNull()
            else -> null
        }
    }

    // ---- Extended phone capabilities ---------------------------------------------------

    override suspend fun readClipboard(): String? = withContext(Dispatchers.Main) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        runCatching { manager?.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
    }

    override suspend fun showToast(text: String): Unit = withContext(Dispatchers.Main) {
        android.widget.Toast.makeText(context, text.take(300), android.widget.Toast.LENGTH_SHORT).show()
    }

    override suspend fun shareText(text: String, title: String): Unit = withContext(Dispatchers.Main) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        val chooser = Intent.createChooser(send, title.ifBlank { "Share via KaivoFlow" })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
        Unit
    }

    override suspend fun setTorch(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                runCatching {
                    manager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }.getOrDefault(false)
            }
            if (cameraId == null) {
                false
            } else {
                manager.setTorchMode(cameraId, enabled)
                true
            }
        }.getOrDefault(false)
    }

    override suspend fun setVolume(level: Int): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
            true
        }.getOrDefault(false)
    }

    @Suppress("MissingPermission")
    override suspend fun getLocation(): Map<String, Any?> = withContext(Dispatchers.IO) {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            return@withContext mapOf("available" to false, "reason" to "Location permission is not granted.")
        }
        val manager = context.getSystemService(android.location.LocationManager::class.java)
            ?: return@withContext mapOf("available" to false, "reason" to "Location is unavailable on this device.")
        val location = manager.getProviders(true).firstNotNullOfOrNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
        if (location == null) {
            mapOf("available" to false, "reason" to "No last known location yet.")
        } else {
            mapOf(
                "available" to true,
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "accuracyMeters" to location.accuracy,
                "provider" to location.provider,
                "timestamp" to location.time,
            )
        }
    }

    override suspend fun getWifiInfo(): Map<String, Any?> = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val info = manager?.connectionInfo
            mapOf(
                "connected" to (info != null && info.networkId != -1),
                "ssid" to info?.ssid?.trim('"'),
                "rssi" to info?.rssi,
                "linkSpeedMbps" to info?.linkSpeed,
            )
        } catch (e: Exception) {
            mapOf("connected" to false, "reason" to (e.message ?: "Wi-Fi state is unavailable."))
        }
    }

    override suspend fun lookupContact(query: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return@withContext mapOf("found" to false, "reason" to "Contacts permission is not granted.")
        try {
            val phone = android.provider.ContactsContract.CommonDataKinds.Phone
            val cursor = context.contentResolver.query(
                phone.CONTENT_URI,
                arrayOf(phone.DISPLAY_NAME, phone.NUMBER),
                "${phone.DISPLAY_NAME} LIKE ? OR ${phone.NUMBER} LIKE ?",
                arrayOf("%$query%", "%$query%"),
                null,
            )
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    return@withContext mapOf(
                        "found" to true,
                        "query" to query,
                        "name" to c.getString(0),
                        "number" to c.getString(1),
                    )
                }
            }
            mapOf("found" to false, "query" to query)
        } catch (e: Exception) {
            mapOf("found" to false, "reason" to (e.message ?: "Contacts lookup failed."))
        }
    }

    override suspend fun addCalendarEvent(
        title: String,
        startMillis: Long,
        endMillis: Long,
        description: String,
    ): String? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) return@withContext null
        try {
            val events = android.provider.CalendarContract.Events
            val values = android.content.ContentValues().apply {
                put(events.DTSTART, startMillis)
                put(events.DTEND, endMillis.coerceAtLeast(startMillis + 60_000L))
                put(events.TITLE, title)
                put(events.DESCRIPTION, description)
                put(events.CALENDAR_ID, 1L)
                put(events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            context.contentResolver.insert(events.CONTENT_URI, values)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun workflowDir(): File = File(context.filesDir, "flows").apply { mkdirs() }

    /** Prevent path traversal: a Flow may only write inside its own directory. */
    private fun sanitize(fileName: String): String =
        fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "state.json" }

    private companion object {
        const val CHANNEL_DEFAULT = "kaivo.results"
        const val CHANNEL_QUIET = "kaivo.results.quiet"
        const val CHANNEL_URGENT = "kaivo.results.urgent"
    }
}
