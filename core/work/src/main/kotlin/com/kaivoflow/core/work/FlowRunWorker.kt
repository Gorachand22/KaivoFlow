package com.kaivoflow.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kaivoflow.core.engine.ExecutionMode
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.work.alwayson.EngineRuntime
import com.kaivoflow.core.work.alwayson.FireResult
import com.kaivoflow.core.work.alwayson.IntakeSource
import com.kaivoflow.core.work.alwayson.RunHost
import com.kaivoflow.core.work.alwayson.ScheduleFirer
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.RunEvent
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.RunTrigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

/**
 * WorkManager host for two things.
 *
 *  • **Flexible schedule slots** (precision mode Flexible, and the fallback when exact alarms are denied). The job carries the
 *    Spark and slot; it goes through the same fire path as an alarm ([ScheduleFirer]: next slot first, Inbox row, evidence) and
 *    then drains the Inbox when the Engine is not running.
 *  • **Work enqueued by the pre-Engine scheduler** (a payload in WorkManager `Data`). Kept so jobs already queued when the app
 *    updates still complete; nothing enqueues this shape any more. Event Runs now go through the Spark Inbox.
 */
@HiltWorker
class FlowRunWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val flowEngine: FlowEngine,
    private val runNotifications: RunNotifications,
    private val flowRepository: com.kaivoflow.core.data.FlowRepository,
    private val scheduleFirer: ScheduleFirer,
    private val runHost: RunHost,
    private val runtime: EngineRuntime,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val flowId = inputData.getString(KEY_FLOW_ID) ?: return Result.failure(
            workDataOf(KEY_ERROR to "missing flowId")
        )
        val triggerKind = inputData.getString(KEY_TRIGGER) ?: "scheduled"
        val useForeground = inputData.getBoolean(KEY_FOREGROUND, false)

        if (useForeground) {
            runCatching { setForeground(createForegroundInfo(flowId)) }
        }

        val items = runCatching {
            val raw = inputData.getString(KEY_ITEMS_JSON)
            JsonItems.decode(raw)
        }.getOrDefault(emptyList())

        val isScheduled = triggerKind in setOf("schedule", "scheduled")
        if (isScheduled) {
            // Schedule slots always go through the fire path; the Run itself comes from the Inbox row it writes.
            val flow = flowRepository.flow(flowId)
            val sparkId = inputData.getString(KEY_SPARK_ID)
                ?: flow?.triggers?.firstOrNull { it.type == TriggerSpecs.SCHEDULE && !it.disabled }?.id
                ?: return Result.success()
            val slot = inputData.getLong(KEY_FIRE_AT, -1L).takeIf { it > 0 }
            val fired = scheduleFirer.fire(flowId, sparkId, slot, IntakeSource.Worker)
            if (fired is FireResult.Enqueued && !runtime.serviceAlive) runHost.dispatcher.drainUntilIdle()
            return Result.success()
        }

        val record = flowEngine.runById(
            flowId = flowId,
            trigger = RunTrigger(
                kind = triggerKind,
                startAtStepId = inputData.getString(KEY_TRIGGER_STEP_ID),
                items = items.toImmutableList(),
            ),
            mode = ExecutionMode.Production,
        )

        if (record == null) return Result.failure(workDataOf(KEY_ERROR to "flow not found"))

        val output = Data.Builder()
            .putString(KEY_RUN_ID, record.id)
            .putString(KEY_STATUS, record.status.name)
            .putInt(KEY_ITEMS_OUT, record.itemsOut)
            .putLong(KEY_DURATION, record.durationMs)
            .build()

        return when (record.status) {
            RunStatus.Succeeded, RunStatus.PartialSuccess, RunStatus.Queued -> Result.success(output)
            RunStatus.Canceled -> Result.failure(output)
            // Never replay the entire Flow automatically: earlier Steps may have sent messages
            // or performed other non-idempotent actions. Explicit Step retries remain opt-in.
            else -> Result.failure(output)
        }
    }

    private fun createForegroundInfo(flowId: String): ForegroundInfo {
        runNotifications.ensureChannels()
        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, RunNotifications.CHANNEL_RUNNING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("KaivoFlow is running a Flow")
            .setContentText("Tap to watch progress")
            .setOngoing(true)
            .build()
        // specialUse exists from API 34; below that the type is not enforced and none is passed.
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_FLOW_ID = "flowId"
        const val KEY_TRIGGER = "trigger"
        const val KEY_TRIGGER_STEP_ID = "triggerStepId"
        const val KEY_ITEMS_JSON = "items"
        const val KEY_FOREGROUND = "foreground"
        const val KEY_RUN_ID = "runId"
        const val KEY_STATUS = "status"
        const val KEY_ITEMS_OUT = "itemsOut"
        const val KEY_DURATION = "duration"
        const val KEY_ERROR = "error"
        const val KEY_SLOT = "slot"
        const val KEY_SPARK_ID = "sparkId"
        const val KEY_FIRE_AT = "fireAt"
        const val FOREGROUND_NOTIFICATION_ID = 4242
    }
}

/** Minimal item (de)serialisation for passing trigger payloads through WorkManager Data. */
internal object JsonItems {
    fun encode(items: List<FlowData>): String =
        com.kaivoflow.core.engine.Values.stringify(items.map { it.json })

    fun decode(raw: String?): List<FlowData> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(raw)
        val array = parsed as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val map = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            FlowData(map.mapValues { fromJson(it.value) })
        }
    }

    private fun fromJson(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonObject -> element.mapValues { fromJson(it.value) }
        is kotlinx.serialization.json.JsonArray -> element.map { fromJson(it) }
        else -> {
            val primitive = element as kotlinx.serialization.json.JsonPrimitive
            when {
                primitive.isString -> primitive.content
                primitive.content == "true" -> true
                primitive.content == "false" -> false
                else -> primitive.content.toDoubleOrNull()
            }
        }
    }
}
