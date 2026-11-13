package com.kaivoflow.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kaivoflow.core.engine.ExecutionMode
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.RunEvent
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.RunTrigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

/**
 * Background Runs.
 *
 * Two shapes:
 *  • **Instant** events request expedited work, with fallback to normal work if quota is exhausted.
 *    The OS does not guarantee a start time.
 *  • **Scheduled**: periodic work whose minimum interval is aligned by [FlowScheduler].
 *
 * Foreground mode is opt-in; long-run promotion and timeout handling remain unimplemented.
 */
@HiltWorker
class FlowRunWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val flowEngine: FlowEngine,
    private val runNotifications: RunNotifications,
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

        val record = flowEngine.runById(
            flowId = flowId,
            trigger = RunTrigger(
                kind = triggerKind,
                startAtStepId = inputData.getString(KEY_TRIGGER_STEP_ID),
                items = items.toImmutableList(),
            ),
            mode = ExecutionMode.Production,
        ) ?: return Result.failure(workDataOf(KEY_ERROR to "flow not found"))

        val output = Data.Builder()
            .putString(KEY_RUN_ID, record.id)
            .putString(KEY_STATUS, record.status.name)
            .putInt(KEY_ITEMS_OUT, record.itemsOut)
            .putLong(KEY_DURATION, record.durationMs)
            .build()

        return when (record.status) {
            RunStatus.Succeeded, RunStatus.PartialSuccess -> Result.success(output)
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
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
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
