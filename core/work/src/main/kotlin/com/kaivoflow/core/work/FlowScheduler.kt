package com.kaivoflow.core.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.ScheduleMath
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.nodes.EventTriggerMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.toImmutableList
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a Flow's Spark configuration into real scheduled work.
 *
 * Design rules:
 *  • WorkManager (not AlarmManager) so OEM battery managers treat us as a well-behaved app.
 *  • Periodic work has a 15-minute platform floor; anything faster is rejected with an honest
 *    message rather than silently rounded down.
 *  • Daily/weekly/monthly use one-shot work. Recurrence is not yet wired: do not rely on it.
 *    WorkManager timing is inexact and may be delayed by OS constraints or battery policy.
 */
@Singleton
class FlowScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flowRepository: FlowRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Re-registers every Flow; call on app start and after boot. */
    suspend fun rescheduleAll() {
        flowRepository.all().forEach { flow ->
            runCatching { schedule(flow) }
        }
    }

    fun schedule(flow: Flow) {
        val trigger = flow.triggers.firstOrNull { it.type == TriggerSpecs.SCHEDULE && !it.disabled } ?: run {
            cancelSchedule(flow.id)
            return
        }
        cancelSchedule(flow.id)

        val frequency = trigger.paramString("frequency", "daily")
        val timezone = trigger.paramString("timezone", "Asia/Kolkata")
        val onlyOnWifi = trigger.paramBoolean("runOnlyOnWifi")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (onlyOnWifi) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        when (frequency) {
            "minutes", "hourly" -> {
                val minutes = if (frequency == "hourly") 60L else ScheduleMath.minutesInterval(trigger.paramNumber("interval", 15.0))
                val request = PeriodicWorkRequestBuilder<FlowRunWorker>(minutes, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setInputData(inputFor(flow, "scheduled"))
                    .addTag(tagFor(flow.id))
                    .addTag(scheduleTagFor(flow.id))
                    .build()
                workManager.enqueueUniquePeriodicWork(flow.id, ExistingPeriodicWorkPolicy.UPDATE, request)
            }
            else -> {
                val delayMs = nextOccurrenceDelay(trigger, timezone)
                val request = OneTimeWorkRequestBuilder<FlowRunWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setConstraints(constraints)
                    .setInputData(inputFor(flow, "scheduled"))
                    .addTag(tagFor(flow.id))
                    .addTag(scheduleTagFor(flow.id))
                    .addTag(TAG_RESCHEDULE)
                    .build()
                workManager.enqueueUniqueWork("schedule-${flow.id}", ExistingWorkPolicy.REPLACE, request)
            }
        }
    }

    /** Instant Runs — webhooks, notifications, share sheet. */
    fun runNow(
        flow: Flow,
        triggerKind: String,
        items: List<FlowData> = emptyList(),
        foreground: Boolean = false,
        triggerStepId: String? = null,
    ) {
        val request = OneTimeWorkRequestBuilder<FlowRunWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    FlowRunWorker.KEY_FLOW_ID to flow.id,
                    FlowRunWorker.KEY_TRIGGER to triggerKind,
                    FlowRunWorker.KEY_ITEMS_JSON to JsonItems.encode(items),
                    FlowRunWorker.KEY_FOREGROUND to foreground,
                    FlowRunWorker.KEY_TRIGGER_STEP_ID to triggerStepId,
                )
            )
            .addTag(tagFor(flow.id))
            .build()
        workManager.enqueue(request)
    }

    /**
     * Event-driven Spark (notification, SMS, battery, share).
     *
     * Fans one event out to every Flow that has a matching, enabled Spark. The payload becomes the
     * Run's seed item, so the first Step after the Spark sees real event data.
     */
    suspend fun dispatchTrigger(kind: String, payload: Map<String, Any?>) {
        val seed = FlowData(json = payload)
        flowRepository.all().forEach { flow ->
            flow.triggers.filter { EventTriggerMatcher.matches(it, kind, payload, context.packageName) }
                .forEach { spark -> runNow(flow, kind, listOf(seed), triggerStepId = spark.id) }
        }
    }

    /** Convenience for the share sheet and battery Sparks, which carry no structured payload. */
    suspend fun dispatchSimple(kind: String, reason: String) =
        dispatchTrigger(kind, mapOf("reason" to reason, "at" to System.currentTimeMillis()))

    fun cancel(flowId: String) {
        workManager.cancelAllWorkByTag(tagFor(flowId))
    }

    private fun cancelSchedule(flowId: String) {
        workManager.cancelAllWorkByTag(scheduleTagFor(flowId))
    }

    private fun scheduleTagFor(flowId: String) = "schedule:$flowId"

    private fun inputFor(flow: Flow, trigger: String) = workDataOf(
        FlowRunWorker.KEY_FLOW_ID to flow.id,
        FlowRunWorker.KEY_TRIGGER to trigger,
    )

    private fun tagFor(flowId: String) = "flow:$flowId"

    /**
     * Milliseconds until the next occurrence of the Spark's configured time in the Flow's own
     * timezone. Weekly/monthly filters are honoured; "now" is returned if the target is today but
     * already past only for monthly/weekly-mismatch cases, which then roll to the next period.
     */
    /**
     * Delegates the calendar maths to [ScheduleMath] so it stays JVM-testable. Kept as a distinct
     * function because callers pass a [StepNode] rather than loose fields.
     */
    internal fun nextOccurrenceDelay(trigger: StepNode, timezone: String, nowMillis: Long = System.currentTimeMillis()): Long {
        val zone = runCatching { java.time.ZoneId.of(timezone) }.getOrDefault(java.time.ZoneId.systemDefault())
        return ScheduleMath.nextDelayMillis(
            timeText = trigger.paramString("time", "08:00"),
            frequency = trigger.paramString("frequency", "daily"),
            daysOfWeek = trigger.paramString("daysOfWeek", ""),
            dayOfMonth = trigger.paramNumber("dayOfMonth", 1.0).toInt(),
            zone = zone,
            nowMillis = nowMillis,
        )
    }

    /** Human description of the next run, shown on the Flow list. */
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

    companion object {
        const val TAG_RESCHEDULE = "kaivo.reschedule"
    }
}
