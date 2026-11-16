package com.kaivoflow.core.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.engine.alwayson.InboxRowState
import com.kaivoflow.core.engine.alwayson.InboxStore
import com.kaivoflow.core.engine.alwayson.RunDispatcher
import com.kaivoflow.core.work.alwayson.EngineController
import com.kaivoflow.core.work.alwayson.EngineRuntime
import com.kaivoflow.core.work.alwayson.FallbackHost
import com.kaivoflow.core.work.alwayson.RunHost
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The fallback Run host (spec 3.11, AO-15): consumes the Spark Inbox from WorkManager when the Always-On Engine is not running
 * (a first event after a reboot, an SMS while the platform refuses a service start, a Start-blocked Engine).
 *
 * Input is **only an Inbox row id**, never a payload, so the 10 KB `Data` limit is gone and a retried job cannot carry stale data. The
 * worker drives the same [RunDispatcher] as the Engine, so the rules are the Engine's: one Run per Flow in arrival order, rows are
 * claimed atomically (a Run id is never started twice, whichever host gets there first), a Run that began is never replayed, and a
 * claim whose lease expired is recorded as Crashed. When the Engine is alive the worker only wakes it: Runs are hosted by one
 * side, never both (spec 3.1 rule 4).
 *
 * Foreground: the job is expedited, so API 26 to 30 need [getForegroundInfo], and a Flow whose timeout is long promotes the job
 * with `setForeground` using the same `specialUse` type as the Engine (never `dataSync`, which has a daily cap).
 */
@HiltWorker
class FlowRunWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runtime: EngineRuntime,
    private val host: RunHost,
    private val controller: EngineController,
    private val fallback: FallbackHost,
    private val runNotifications: RunNotifications,
    private val inboxStore: InboxStore,
    private val flows: FlowRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (runtime.serviceAlive) {
            host.nudge()
            return Result.success()
        }
        val rowId = inputData.getLong(KEY_INBOX_ID, NO_ROW).takeIf { it > 0 }
        val row = rowId?.let { inboxStore.get(it) }
        if (row != null && (row.state == InboxRowState.Done || row.state == InboxRowState.Dropped)) {
            // Someone else ran or dropped it already. Nothing to do, and nothing can run twice.
            return Result.success()
        }
        if (row != null && expectsLongRun(row.flowId)) {
            // Not allowed from every background state (a non-expedited fallback on API 31+): then the Run just stays a normal job.
            runCatching { setForeground(getForegroundInfo()) }
        }
        if (rowId != null) host.dispatcher.drainUntilFinished(rowId, maxWaitMs = DRAIN_BUDGET_MS)
        // Rows of other Flows that arrived meanwhile, and rows that waited behind this one.
        host.dispatcher.drainUntilIdle(maxWaitMs = DRAIN_BUDGET_MS / 4)
        // A worker is cut off at about ten minutes. Anything still waiting gets a follow-up job.
        if (controller.hasInboxWork()) fallback.ensureDrain(rowId)
        return Result.success()
    }

    private suspend fun expectsLongRun(flowId: String): Boolean =
        (flows.flow(flowId)?.settings?.executionTimeoutMs ?: 0L) > FOREGROUND_AFTER_MS

    /** Required for expedited work on API 26 to 30; used by `setForeground` above. The type must match the manifest on API 34+. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        runNotifications.ensureChannels()
        val notification = NotificationCompat.Builder(applicationContext, RunNotifications.CHANNEL_RUNNING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("KaivoFlow is running a Flow")
            .setContentText("The background engine is not running, so this Run is hosted by a job.")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** The only input: the Inbox row this job was created for. Absent means "sweep the Inbox". */
        const val KEY_INBOX_ID = "inboxId"
        const val NO_ROW = -1L
        const val FOREGROUND_NOTIFICATION_ID = 4242
        private const val DRAIN_BUDGET_MS = 8 * 60_000L
        private const val FOREGROUND_AFTER_MS = 2 * 60_000L
    }
}
