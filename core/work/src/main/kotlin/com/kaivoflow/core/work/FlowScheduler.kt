package com.kaivoflow.core.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
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
import com.kaivoflow.core.engine.SchedulePlanner
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

    /** Re-registers every active Flow and disarms inactive ones; call on app start and after boot. */
    suspend fun rescheduleAll() {
        flowRepository.all().forEach { flow ->
            runCatching { schedule(flow) }
        }
    }

    /**
     * Arms (or disarms) the Flow's schedule.
     *
     * Inactive Flows are never armed. [currentSlot] / [firedFor] are passed by the worker that is
     * about to run an occurrence: the NEXT occurrence is armed in the other unique-work slot, before
     * the Run starts, so it neither depends on the Run succeeding nor cancels the running work.
     */
    fun schedule(flow: Flow, currentSlot: String? = null, firedFor: Long? = null) {
        // Location Sparks are platform proximity alerts, not WorkManager jobs. Re-sync on every
        // save so editing a radius or disabling the Spark/Flow takes effect immediately.
        syncGeofence(flow)
        val trigger = flow.triggers.firstOrNull { it.type == TriggerSpecs.SCHEDULE && !it.disabled }
        if (!flow.isActive || trigger == null) {
            cancelSchedule(flow.id, keepSlot = currentSlot)
            return
        }

        val frequency = trigger.paramString("frequency", "daily")
        val timezone = trigger.paramString("timezone", "Asia/Kolkata")
        val onlyOnWifi = trigger.paramBoolean("runOnlyOnWifi")
        // Battery-not-low is opt-in: a forced constraint silently skipped reminders on low battery.
        val requireBatteryNotLow = trigger.paramBoolean("requireBatteryNotLow")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (onlyOnWifi) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(requireBatteryNotLow)
            .build()

        when (frequency) {
            "minutes", "hourly" -> {
                cancelSchedule(flow.id, keepSlot = currentSlot, keepPeriodic = true)
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
                // Remove stale pending work (old config, legacy name, periodic) but never the running slot.
                cancelSchedule(flow.id, keepSlot = currentSlot)
                val now = System.currentTimeMillis()
                val delayMs = if (firedFor != null) {
                    val zone = runCatching { java.time.ZoneId.of(timezone) }.getOrDefault(java.time.ZoneId.systemDefault())
                    SchedulePlanner.delayAfterFire(
                        firedFor = firedFor,
                        nowMillis = now,
                        timeText = trigger.paramString("time", "08:00"),
                        frequency = frequency,
                        daysOfWeek = trigger.paramString("daysOfWeek", ""),
                        dayOfMonth = trigger.paramNumber("dayOfMonth", 1.0).toInt(),
                        zone = zone,
                    )
                } else {
                    nextOccurrenceDelay(trigger, timezone, now)
                }
                val slot = SchedulePlanner.nextSlot(currentSlot)
                val request = OneTimeWorkRequestBuilder<FlowRunWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setConstraints(constraints)
                    .setInputData(
                        workDataOf(
                            FlowRunWorker.KEY_FLOW_ID to flow.id,
                            FlowRunWorker.KEY_TRIGGER to "scheduled",
                            FlowRunWorker.KEY_SLOT to slot,
                            FlowRunWorker.KEY_FIRE_AT to now + delayMs,
                        )
                    )
                    .addTag(tagFor(flow.id))
                    .addTag(scheduleTagFor(flow.id))
                    .addTag(TAG_RESCHEDULE)
                    .build()
                workManager.enqueueUniqueWork(slotName(flow.id, slot), ExistingWorkPolicy.REPLACE, request)
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
        // Proximity alerts are registered per Flow; "targetFlowId" stops one phone's geofence from
        // waking every other Flow that happens to have a location Spark.
        val targetFlowId = payload["targetFlowId"] as? String
        flowRepository.all()
            .filter { flow -> flow.isActive }
            .filter { flow -> targetFlowId == null || flow.id == targetFlowId }
            .forEach { flow ->
                flow.triggers.filter { EventTriggerMatcher.matches(it, kind, payload, context.packageName) }
                    .forEach { spark -> runNow(flow, kind, listOf(seed), triggerStepId = spark.id) }
            }
    }

    /** Convenience for the share sheet and battery Sparks, which carry no structured payload. */
    suspend fun dispatchSimple(kind: String, reason: String) =
        dispatchTrigger(kind, mapOf("reason" to reason, "at" to System.currentTimeMillis()))

    /**
     * Registers (or removes) the OS-level proximity alert backing a location Spark.
     * `addProximityAlert` is a single low-power system subscription — no polling worker is needed.
     */
    private fun syncGeofence(flow: Flow) {
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        // Intent extras are ignored by filterEquals, so the same PendingIntent can be removed and
        // re-created with fresh coordinates without leaking duplicate alerts.
        runCatching { manager.removeProximityAlert(geofenceIntent(flow.id, null)) }
        if (!flow.isActive) return
        val spark = flow.triggers.firstOrNull { it.type == TriggerSpecs.GEOFENCE && !it.disabled } ?: return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val lat = spark.paramNumber("latitude", 0.0)
        val lon = spark.paramNumber("longitude", 0.0)
        val radius = spark.paramNumber("radiusMeters", 150.0).toFloat().coerceIn(25f, 100_000f)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        runCatching {
            manager.addProximityAlert(lat, lon, radius, -1L, geofenceIntent(flow.id, spark))
        }
    }

    private fun geofenceIntent(flowId: String, spark: StepNode?): PendingIntent {
        val intent = Intent(context, MobileEventReceiver::class.java).apply {
            action = MobileEventReceiver.ACTION_GEOFENCE
            putExtra(MobileEventReceiver.EXTRA_FLOW_ID, flowId)
            if (spark != null) {
                putExtra(MobileEventReceiver.EXTRA_LAT, spark.paramNumber("latitude", 0.0))
                putExtra(MobileEventReceiver.EXTRA_LON, spark.paramNumber("longitude", 0.0))
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, flowId.hashCode() and 0x7fff_ffff, intent, flags)
    }

    fun cancel(flowId: String) {
        workManager.cancelAllWorkByTag(tagFor(flowId))
    }

    /** Cancels pending schedule work by unique name, sparing the slot whose Run is in progress. */
    private fun cancelSchedule(flowId: String, keepSlot: String? = null, keepPeriodic: Boolean = false) {
        listOf(SchedulePlanner.SLOT_A, SchedulePlanner.SLOT_B).filter { it != keepSlot }.forEach {
            workManager.cancelUniqueWork(slotName(flowId, it))
        }
        workManager.cancelUniqueWork("schedule-$flowId") // legacy single-slot name
        if (!keepPeriodic && keepSlot == null) workManager.cancelUniqueWork(flowId)
    }

    private fun slotName(flowId: String, slot: String) = "schedule-$slot-$flowId"

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
