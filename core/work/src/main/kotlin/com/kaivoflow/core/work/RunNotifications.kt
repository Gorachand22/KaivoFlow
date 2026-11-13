package com.kaivoflow.core.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.RunEvent
import com.kaivoflow.core.model.RunStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live progress for Runs that outlive the app being in the foreground.
 *
 * Behaviour (Pillar 3):
 *  • Runs under ~10s show no persistent notification at all — the canvas HUD is enough.
 *  • Longer Runs post an ongoing notification naming the current Step, and the same notification
 *    backs the foreground service so Android does not kill the Run.
 *  • When a Run ends, the ongoing notification is replaced by a single result line (or removed for
 *    silent Runs), so the shade does not fill with noise.
 */
@Singleton
class RunNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val startedAt = mutableMapOf<String, Long>()

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Running Flows", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Flow results", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun onRunEvent(flow: Flow, event: RunEvent) {
        ensureChannels()
        when (event) {
            is RunEvent.RunStarted -> {
                startedAt[flow.id] = System.currentTimeMillis()
                notifyRunning(flow, "Starting…", 0, 0)
            }
            is RunEvent.StepStarted -> {
                notifyRunning(flow, "Running a Step…", 1, 0)
            }
            is RunEvent.StepFinished -> Unit
            is RunEvent.RunFinished -> {
                val elapsed = System.currentTimeMillis() - (startedAt.remove(flow.id) ?: System.currentTimeMillis())
                notifyResult(flow, event.status, elapsed)
            }
            else -> Unit
        }
    }

    private fun notifyRunning(flow: Flow, text: String, progress: Int, max: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(flow.name)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, max == 0)
            .build()
        manager.notify(runningId(flow.id), notification)
    }

    private fun notifyResult(flow: Flow, status: RunStatus, elapsedMs: Long) {
        manager.cancel(runningId(flow.id))
        val title = when (status) {
            RunStatus.Succeeded -> "${flow.name} finished"
            RunStatus.PartialSuccess -> "${flow.name} finished with warnings"
            RunStatus.Failed -> "${flow.name} failed"
            RunStatus.Canceled -> "${flow.name} was stopped"
            else -> flow.name
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText("Took ${elapsedMs / 1000}s")
            .setAutoCancel(true)
            .build()
        manager.notify(resultId(flow.id), notification)
    }

    fun clear(flowId: String) {
        manager.cancel(runningId(flowId))
        startedAt.remove(flowId)
    }

    private fun runningId(flowId: String) = 1000 + (flowId.hashCode() and 0xFFF)
    private fun resultId(flowId: String) = 2000 + (flowId.hashCode() and 0xFFF)

    companion object {
        const val CHANNEL_RUNNING = "kaivo.running"
        const val CHANNEL_RESULT = "kaivo.result"
    }
}
