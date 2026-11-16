package com.kaivoflow.feature.permissions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kaivoflow.core.work.ReceiverEntryPoints
import com.kaivoflow.core.work.alwayson.DeviceEvents
import com.kaivoflow.core.work.alwayson.NotificationListenerHub
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Notification Spark source (AO-12). The system binds this service; it reports connection changes to [NotificationListenerHub]
 * (which calls `requestRebind` with back-off when the system drops it) and hands every notification to [DeviceEvents], which
 * pre-filters by package, truncates the text, writes the Inbox row and dedupes by notification key and post time. Title and text
 * filters are applied later by the matcher, the single fail-closed gate. Notifications without a title are accepted.
 *
 * A bound listener is a live process by definition, so nothing here starts the Engine: the Inbox write wakes it through the
 * allowed path, or the WorkManager fallback runs the row.
 */
@AndroidEntryPoint
class KaivoNotificationListener : NotificationListenerService() {

    @Inject lateinit var events: DeviceEvents
    @Inject lateinit var hub: NotificationListenerHub

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope.launch { runCatching { hub.onConnected() }.onFailure { if (it is CancellationException) throw it } }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Not tied to this service's scope: the service is being torn down, and the rebind request must still go out.
        detached.launch { runCatching { hub.onDisconnected() } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val extras = n.notification?.extras
        val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)
        val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)
        scope.launch {
            runCatching { events.notification(n.packageName, n.key, n.postTime, title, text, n.isOngoing) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        val detached = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/**
 * SMS Spark source. Sideload flavour only: the Play manifest neither declares this receiver nor holds an SMS permission. It
 * writes the Inbox row inside `goAsync` and returns; it never starts a service (SMS is a forbidden foreground-start trigger,
 * spec 3.5). The Engine or the WorkManager fallback runs the row afterwards. Dependencies come from [ReceiverEntryPoints],
 * not field injection.
 */
class SmsSparkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return
        val sender = messages.first().originatingAddress.orEmpty()
        // Multipart SMS arrive as several PDUs; join them in order.
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val sentAt = messages.first().timestampMillis
        val entry = ReceiverEntryPoints.of(context)
        val pending = goAsync()
        val hold = entry.wakeLocks().acquireIntake("sms")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8_000L) { entry.deviceEvents().sms(sender, body, sentAt) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Evidence and the Inbox are written inside DeviceEvents; a failure here must not crash the broadcast.
            } finally {
                hold.close()
                pending.finish()
            }
        }
    }
}
