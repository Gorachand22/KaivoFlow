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
