package com.kaivoflow.core.work.alwayson

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kaivoflow.core.engine.SchedulePlanner
import com.kaivoflow.core.work.FlowRunWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager side of the precision modes: the Flexible mode and the Watchdog. Flexible schedules are a chain of one-shot
 * jobs with an initial delay, two alternating unique names so arming the next slot never replaces the job that is running
 * (the same idea the old scheduler used, now keyed by Spark and driven by the fire path).
 */
@Singleton
class FlexibleWork @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Arms [slotMs] on the letter after [currentLetter]. Returns the letter used, stored in `schedule_state.armedMode`.
     * When the same slot is already armed on [currentLetter], nothing is replaced (KEEP): a Reconciler re-arm must not
     * cancel a job that is about to run or already running.
     */
    fun arm(
        flowId: String,
        sparkId: String,
        slotMs: Long,
        nowMs: Long,
        currentLetter: String?,
        sameSlotAlreadyArmed: Boolean,
        wifiOnly: Boolean,
        batteryNotLow: Boolean,
    ): String {
        val letter = if (sameSlotAlreadyArmed && currentLetter != null) currentLetter else SchedulePlanner.nextSlot(currentLetter)
        val policy = if (sameSlotAlreadyArmed && currentLetter != null) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        val request = OneTimeWorkRequestBuilder<ScheduleSlotWorker>()
            .setInitialDelay((slotMs - nowMs).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(batteryNotLow)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    ScheduleSlotWorker.KEY_FLOW_ID to flowId,
                    ScheduleSlotWorker.KEY_SPARK_ID to sparkId,
                    ScheduleSlotWorker.KEY_FIRE_AT to slotMs,
                ),
            )
            .addTag("flow:$flowId")
            .addTag("schedule:$flowId")
            .build()
        workManager.enqueueUniqueWork(uniqueName(flowId, sparkId, letter), policy, request)
        return letter
    }

    fun cancel(flowId: String, sparkId: String) {
        listOf(SchedulePlanner.SLOT_A, SchedulePlanner.SLOT_B).forEach { workManager.cancelUniqueWork(uniqueName(flowId, sparkId, it)) }
    }

    /** Removes work enqueued by the pre-Engine scheduler (periodic work named by Flow id, A/B slots, the legacy single slot). */
    fun cancelLegacy(flowId: String) {
        listOf(SchedulePlanner.SLOT_A, SchedulePlanner.SLOT_B).forEach { workManager.cancelUniqueWork("schedule-$it-$flowId") }
        workManager.cancelUniqueWork("schedule-$flowId")
        workManager.cancelUniqueWork(flowId)
    }

    fun cancelAllForFlow(flowId: String) = workManager.cancelAllWorkByTag("schedule:$flowId")

    private fun uniqueName(flowId: String, sparkId: String, letter: String) = "kaivo.flex.$letter.$flowId.$sparkId"
}

/** The Watchdog: a periodic WorkManager job that is independent of alarms (spec 3.2, 6.2). */
@Singleton
class WatchdogScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    /** KEEP: re-enqueueing from every Reconciler pass is a no-op while the job exists, and restores it when it vanished. */
    fun ensure() {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).addTag(TAG).build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel() { workManager.cancelUniqueWork(UNIQUE_NAME) }

    /** One immediate pass, used when a Pulse ran out of time inside its receiver. */
    fun runOnceNow() {
        workManager.enqueueUniqueWork(UNIQUE_NAME + ".once", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<WatchdogWorker>().build())
    }

    companion object {
        const val UNIQUE_NAME = "kaivo.watchdog"
        const val TAG = "kaivo.watchdog"
    }
}

/** Enqueues the fallback Run host, [FlowRunWorker], which drains the Inbox from WorkManager when the Engine cannot run (spec 3.11). */
@Singleton
class FallbackHost @Inject constructor(@ApplicationContext private val context: Context) {
    /**
     * Two unique names, both KEEP. A row written just after a running worker saw an empty Inbox gets a follow-up job on the
     * other name instead of being lost, and at most two workers exist at once. Two workers are harmless: both call the same
     * [com.kaivoflow.core.engine.alwayson.RunDispatcher], which serialises per Flow and claims rows atomically.
     * The only input is the Inbox row id (null: sweep). Expedited with a normal-work fallback; the worker supplies `getForegroundInfo`.
     */
    fun ensureDrain(rowId: Long? = null) {
        val input = if (rowId != null && rowId > 0) workDataOf(FlowRunWorker.KEY_INBOX_ID to rowId) else Data.EMPTY
        listOf(UNIQUE_A, UNIQUE_B).forEach { name ->
            val request = OneTimeWorkRequestBuilder<FlowRunWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(input)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        }
    }

    companion object {
        const val UNIQUE_A = "kaivo.inbox.drain.a"
        const val UNIQUE_B = "kaivo.inbox.drain.b"
        const val TAG = "kaivo.inbox.drain"
    }
}
