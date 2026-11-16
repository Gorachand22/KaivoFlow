package com.kaivoflow.core.work.alwayson

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.work.RunNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The independent safety net (spec 3.2 and 6.2): a periodic WorkManager job, separate from alarms because clean-up tools
 * sometimes clear alarms and not jobs, or the reverse. It runs the same Reconciler as everything else; the Reconciler starts
 * a dead Engine (or, when the platform refuses, posts the tap-to-restart notice and arms the fallback worker).
 */
@HiltWorker
class WatchdogWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reconciler: Reconciler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        reconciler.reconcile(ReconcileTrigger.Watchdog)
        return Result.success()
    }
}

/**
 * Fallback Run host (spec 3.11): consumes the Inbox from WorkManager when the Engine is not running. It carries no payload
 * (only the Inbox exists), so the 10 KB `Data` limit is gone. When the Engine is alive the worker only wakes its consumer,
 * so Runs are never hosted by both (spec 3.1 rule 4, Android 16 job quotas).
 */
@HiltWorker
class InboxDrainWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runtime: EngineRuntime,
    private val host: RunHost,
    private val controller: EngineController,
    private val fallback: FallbackHost,
    private val runNotifications: RunNotifications,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (runtime.serviceAlive) {
            host.nudge()
            return Result.success()
        }
        host.dispatcher.drainUntilIdle(maxWaitMs = DRAIN_BUDGET_MS)
        // A worker is cut off at about ten minutes. Anything still waiting gets a follow-up job.
        if (controller.hasInboxWork()) fallback.ensureDrain()
        return Result.success()
    }

    /** Needed for expedited work on API 26 to 30, and the type must match the manifest on 34+. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        runNotifications.ensureChannels()
        val notification = NotificationCompat.Builder(applicationContext, RunNotifications.CHANNEL_RUNNING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("KaivoFlow is running a Flow")
            .setOngoing(true)
            .build()
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 4_243
        const val DRAIN_BUDGET_MS = 8 * 60_000L
    }
}
