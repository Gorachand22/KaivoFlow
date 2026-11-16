package com.kaivoflow.core.work

import android.content.Context
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.ScheduleMath
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.work.alwayson.IntakeSource
import com.kaivoflow.core.work.alwayson.Reconciler
import com.kaivoflow.core.work.alwayson.SparkIntake
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The stable entry point the UI and the receivers already call. Since the Always-On Engine it is a thin facade:
 *
 *  - arming and disarming (`schedule`, `rescheduleAll`, `cancel`) go to the [Reconciler], which owns alarms, WorkManager jobs,
 *    registrations and geofences;
 *  - events (`dispatchTrigger`, `runNow`) are written to the durable Spark Inbox through [SparkIntake] and run by the Engine
 *    or the fallback worker, with no payload size limit and no in-memory queue.
 *
 * Public signatures are unchanged so `feature:*` and `app` need no edits.
 */
@Singleton
class FlowScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reconciler: Reconciler,
    private val intake: SparkIntake,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Repairs everything: app start, after an update, a timezone change or an OEM clean-up. */
    suspend fun rescheduleAll() { reconciler.reconcile(ReconcileTrigger.AppStart) }

    /** Re-plans this Flow's alarms, geofence and Engine need after an edit or a publish change. Returns immediately. */
    fun schedule(flow: Flow) {
        scope.launch { runCatching { reconciler.syncFlow(flow) } }
    }

    /** Disarms the Flow (deleted or unpublished). */
    fun cancel(flowId: String) {
        scope.launch { runCatching { reconciler.disarmFlow(flowId) } }
    }

    /**
     * An instant Run for one Flow (share sheet and similar). Written to the Inbox like any other event. [foreground] is kept for
     * source compatibility; the Engine decides how a Run is hosted.
     */
    fun runNow(
        flow: Flow,
        triggerKind: String,
        items: List<FlowData> = emptyList(),
        @Suppress("UNUSED_PARAMETER") foreground: Boolean = false,
        triggerStepId: String? = null,
    ) {
        val sparkId = triggerStepId ?: flow.triggers.firstOrNull { !it.disabled }?.id ?: ""
        scope.launch { runCatching { intake.enqueue(flow.id, sparkId, triggerKind, items, source = IntakeSource.Event) } }
    }

    /**
     * Event-driven Spark (notification, SMS, battery, geofence, Wi-Fi, Bluetooth, share).
     *
     * Fans one event out to every live Flow that has a matching, enabled Spark and persists one Inbox row per match.
     * The payload becomes the Run's seed item, so the first Step after the Spark sees real event data.
     */
    suspend fun dispatchTrigger(kind: String, payload: Map<String, Any?>) {
        intake.dispatch(kind, payload)
    }

    /** Convenience for the share sheet and battery Sparks, which carry no structured payload. */
    suspend fun dispatchSimple(kind: String, reason: String) =
        dispatchTrigger(kind, mapOf("reason" to reason, "at" to System.currentTimeMillis()))

    /** Human description of the schedule, shown on the Flow list. */
    fun describeSchedule(trigger: StepNode): String {
        val frequency = trigger.paramString("frequency", "daily")
        if (frequency == "minutes") return "Every ${trigger.paramString("interval", "15")} min"
        return ScheduleMath.describe(
            frequency = frequency,
            timeText = trigger.paramString("time", "08:00"),
            daysOfWeek = trigger.paramString("daysOfWeek", ""),
            dayOfMonth = trigger.paramNumber("dayOfMonth", 1.0).toInt(),
        )
    }
}
