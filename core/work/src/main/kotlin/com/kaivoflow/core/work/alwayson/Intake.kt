package com.kaivoflow.core.work.alwayson

import android.content.Context
import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.engine.alwayson.DedupeKeys
import com.kaivoflow.core.engine.alwayson.EnqueueResult
import com.kaivoflow.core.engine.alwayson.NewSpark
import com.kaivoflow.core.engine.alwayson.SparkInbox
import com.kaivoflow.core.engine.alwayson.StartReason
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.nodes.EventTriggerMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Where an Inbox write came from. Decides whether a failed Engine start may nag the user (spec 3.5). */
enum class IntakeSource(val reason: StartReason, val exemptFromFgsLimits: Boolean) {
    /** Exact alarm: an FGS-start exemption. */
    Alarm(StartReason.Alarm, true),
    /** SMS, notification, Wi-Fi, Bluetooth, webhook: not exempt, so a refusal is expected and quiet. */
    Event(StartReason.IntakeEvent, false),
    Worker(StartReason.Watchdog, false),
    Reconcile(StartReason.Reassess, false),
    /** A geofence transition: a platform-listed foreground-start exemption. */
    Geofence(StartReason.Geofence, true),
}

/**
 * The one door into the Inbox for Spark events (spec 3.1 rule 1). Replaces the old direct `runNow` path: the payload is
 * persisted first (no 10 KB limit), the consumer is woken second.
 */
@Singleton
class SparkIntake @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inbox: SparkInbox,
    private val flows: FlowRepository,
    private val liveFlowSource: LiveFlows,
    private val heartbeat: HeartbeatWriter,
    private val controller: EngineController,
    private val wakeLocks: WakeLockManager,
) {
    /**
     * Stores one event for one Spark. [sourceId] makes a repeated delivery of the same occurrence a no-op; null means unique.
     * The row is committed before the consumer is woken, and a failure to wake never turns a stored event into a failure.
     */
    suspend fun enqueue(
        flowId: String,
        sparkId: String,
        kind: String,
        items: List<FlowData>,
        meta: Map<String, Any?> = emptyMap(),
        sourceId: String? = null,
        source: IntakeSource = IntakeSource.Event,
    ): EnqueueResult {
        val key = DedupeKeys.event(kind, flowId, sparkId, sourceId ?: UUID.randomUUID().toString())
        val result = inbox.enqueue(NewSpark(flowId, sparkId, kind, InboxPayloads.encode(items, meta), key))
        if (result is EnqueueResult.Stored) { heartbeat.beat(); wake(source, result.id) }
        return result
    }

    private suspend fun wake(source: IntakeSource, firstRowId: Long? = null) {
        try {
            controller.onInboxWritten(source, firstRowId)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) {
            // The row is durable; the Pulse and the watchdog pick it up even if this wake-up failed.
        }
    }

    /**
     * Fans an event out to every live Flow with a matching, enabled Spark (the old `dispatchTrigger`). Returns the number of
     * rows stored. Holds the intake wake lock for the duration, so a screen-off event cannot be lost to CPU sleep mid-write.
     */
    suspend fun dispatch(kind: String, payload: Map<String, Any?>, sourceId: String? = null, source: IntakeSource = IntakeSource.Event): Int {
        wakeLocks.acquireIntake(kind).use {
            heartbeat.beat()
            val seed = FlowData(json = payload)
            val targetFlowId = payload["targetFlowId"] as? String
            var stored = 0
            var firstId: Long? = null
            for (flow in liveFlowSource.live()) {
                if (targetFlowId != null && flow.id != targetFlowId) continue
                for (spark in flow.triggers.filter { EventTriggerMatcher.matches(it, kind, payload, context.packageName) }) {
                    val r = enqueueQuiet(flow.id, spark.id, kind, listOf(seed), sourceId)
                    if (r is EnqueueResult.Stored) { stored++; if (firstId == null) firstId = r.id }
                }
            }
            if (stored > 0) wake(source, firstId)
            return stored
        }
    }

    /** Same as [enqueue] without waking the consumer, so [dispatch] wakes it once for the whole fan-out. */
    private suspend fun enqueueQuiet(flowId: String, sparkId: String, kind: String, items: List<FlowData>, sourceId: String?): EnqueueResult {
        val key = DedupeKeys.event(kind, flowId, sparkId, sourceId ?: UUID.randomUUID().toString())
        return inbox.enqueue(NewSpark(flowId, sparkId, kind, InboxPayloads.encode(items, emptyMap()), key))
    }
}
