package com.kaivoflow.core.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.nodes.TriggerSpecs
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground service that owns the [WebhookServer] socket. It runs only while at least one active
 * Flow has an enabled webhook Spark; [WebhookController.sync] starts and stops it.
 */
@AndroidEntryPoint
class WebhookService : Service() {

    @Inject lateinit var server: WebhookServer
    @Inject lateinit var runNotifications: RunNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runNotifications.ensureChannels()
        val notification = NotificationCompat.Builder(this, RunNotifications.CHANNEL_RUNNING)
            .setContentTitle("KaivoFlow webhook listener")
            .setContentText("Listening on this phone for incoming webhooks")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)

        scope.launch {
            runCatching { server.refreshRoutes() }
            runCatching { server.start() }.onFailure { stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 4_103
    }
}

/** Decides whether [WebhookService] should be running and starts or stops it accordingly. */
@Singleton
class WebhookController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flowRepository: FlowRepository,
    private val server: WebhookServer,
) {
    /** True when any active Flow has an enabled webhook Spark. */
    suspend fun needed(): Boolean = flowRepository.all().any { flow ->
        flow.isActive && flow.triggers.any { it.type == TriggerSpecs.WEBHOOK && !it.disabled }
    }

    suspend fun sync() {
        val intent = Intent(context, WebhookService::class.java)
        if (needed()) {
            if (server.isRunning) {
                server.refreshRoutes()
            } else {
                // Can be refused when the app has no foreground standing (Android 12+); retried on next sync.
                runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
            }
        } else {
            context.stopService(intent)
            server.stop()
        }
    }
}
