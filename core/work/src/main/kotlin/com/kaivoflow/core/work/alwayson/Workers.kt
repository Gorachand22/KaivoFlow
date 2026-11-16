package com.kaivoflow.core.work.alwayson

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
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
 * Fires one Flexible schedule slot (precision `Flexible`, and the fallback when exact alarms are denied). The job carries the Spark
 * and the slot; it goes through the same fire path as an alarm ([ScheduleFirer]: next slot first, Inbox row, evidence). The Run
 * itself is hosted by the Engine, or by `FlowRunWorker` when the Engine is not running.
 */
@HiltWorker
class ScheduleSlotWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduleFirer: ScheduleFirer,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val flowId = inputData.getString(KEY_FLOW_ID) ?: return Result.failure()
        val sparkId = inputData.getString(KEY_SPARK_ID) ?: return Result.failure()
        val slot = inputData.getLong(KEY_FIRE_AT, -1L).takeIf { it > 0 }
        scheduleFirer.fire(flowId, sparkId, slot, IntakeSource.Worker)
        return Result.success()
    }

    companion object {
        const val KEY_FLOW_ID = "flowId"
        const val KEY_SPARK_ID = "sparkId"
        const val KEY_FIRE_AT = "fireAt"
    }
}
