package com.kaivoflow.core.work.alwayson

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications of the Engine (spec 3.4): the ongoing "KaivoFlow is watching N Flows" notice on a quiet channel, and the
 * "needs a tap" notice on a high-importance channel. Tapping either counts as user interaction, an FGS-start exemption (R8).
 */
@Singleton
class EngineNotifications @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ENGINE, "KaivoFlow engine", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while KaivoFlow keeps your published Flows running in the background."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Flow problems", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tells you when the background engine could not start or Flows were missed."
            },
        )
    }

    /** Ongoing notice. [text] carries the next scheduled time and any problem count. */
    fun engineNotification(flowCount: Int, text: String): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_ENGINE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (flowCount == 1) "KaivoFlow is watching 1 Flow" else "KaivoFlow is watching $flowCount Flows")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(EXTRA_ROUTE_HOME, 10))
            .addAction(0, "Open", openApp(EXTRA_ROUTE_HOME, 11))
            .addAction(0, "Reliability", openApp(EXTRA_ROUTE_RELIABILITY, 12))
            .build()
    }

    fun update(flowCount: Int, text: String) {
        runCatching { manager.notify(ENGINE_NOTIFICATION_ID, engineNotification(flowCount, text)) }
    }

    /** Posted when Android refused a background start of the Engine. Tapping it opens the app, which can start the service. */
    fun postStartBlocked() {
        ensureChannels()
        val n = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("KaivoFlow needs a tap to restart its background engine")
            .setContentText("Android blocked the automatic restart. Scheduled and event Flows still run, but less reliably.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Android blocked the automatic restart. Tap to open KaivoFlow and restart the engine. Scheduled and event Flows still run at lower reliability until then."))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(EXTRA_ROUTE_RELIABILITY, 13))
            .build()
        runCatching { manager.notify(START_BLOCKED_NOTIFICATION_ID, n) }
    }

    fun clearStartBlocked() = manager.cancel(START_BLOCKED_NOTIFICATION_ID)

    private fun openApp(route: String, requestCode: Int): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch.putExtra(EXTRA_ROUTE, route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_ENGINE = "kaivo.engine"
        const val CHANNEL_ALERT = "kaivo.engine.alert"
        const val ENGINE_NOTIFICATION_ID = 4_104
        const val START_BLOCKED_NOTIFICATION_ID = 4_105
        /** Read by the UI (M2) to deep link; harmless today. */
        const val EXTRA_ROUTE = "com.kaivoflow.extra.ROUTE"
        const val EXTRA_ROUTE_HOME = "home"
        const val EXTRA_ROUTE_RELIABILITY = "reliability"
    }
}
