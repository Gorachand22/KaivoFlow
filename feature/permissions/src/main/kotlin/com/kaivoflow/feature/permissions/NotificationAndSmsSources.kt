package com.kaivoflow.feature.permissions

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.work.FlowScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import javax.inject.Inject

/**
 * Notification Spark source.
 *
 * Privacy choices that matter:
 *  - notification data is received before matching; configured filters are checked by [FlowScheduler],
 *  - the posted text is truncated before it leaves this class,
 *  - nothing is persisted unless a Flow actually matches.
 */
@AndroidEntryPoint
class KaivoNotificationListener : NotificationListenerService() {

    @Inject lateinit var scheduler: FlowScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val extras: Bundle = notification.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(200) ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(500).orEmpty()

        val payload = mapOf(
            "app" to notification.packageName,
            "title" to title,
            "text" to text,
            "postedAt" to notification.postTime,
            "isOngoing" to notification.isOngoing,
        )

        scope.launch {
            runCatching {
                scheduler.dispatchTrigger(TriggerSpecs.NOTIFICATION, payload)
            }
        }
    }
}

/**
 * SMS Spark source. Kept in its own receiver so the permission is only ever exercised by a Step the
 * user created; the payload is trimmed and never written to disk unless a Flow matches it.
 */
class SmsSparkReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        val messages = android.telephony.SmsMessage.createFromPdu(intent.extras?.getByteArray("pdus") ?: return)
        val sender = messages?.originatingAddress.orEmpty()
        val body = messages?.messageBody.orEmpty().take(500)
        // Re-dispatch through the scheduler via an Intent service, keeping the receiver synchronous.
        val forwarding = Intent(context, SmsTriggerService::class.java).apply {
            putExtra("sender", sender)
            putExtra("body", body)
        }
        context.startForegroundService(forwarding)
    }
}

@AndroidEntryPoint
class SmsTriggerService : android.app.Service() {

    @Inject lateinit var scheduler: FlowScheduler

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sender = intent?.getStringExtra("sender").orEmpty()
        val body = intent?.getStringExtra("body").orEmpty()
        // Minimal foreground notification: an SMS Spark is a user-visible event, so showing it is honest.
        val notification = android.app.Notification.Builder(this, "kaivo.running")
            .setContentTitle("KaivoFlow")
            .setContentText("Checking your SMS Flows…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        startForeground(4_102, notification)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            runCatching {
                scheduler.dispatchTrigger(
                    TriggerSpecs.SMS,
                    mapOf("from" to sender, "body" to body, "receivedAt" to System.currentTimeMillis()),
                )
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
