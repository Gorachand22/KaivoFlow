package com.kaivoflow.core.work.alwayson

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.TriggerRegistrationRepository
import com.kaivoflow.core.engine.SparkTypes
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.ListenerAction
import com.kaivoflow.core.engine.alwayson.ListenerTracker
import com.kaivoflow.core.engine.alwayson.NotificationInterest
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.SparkQueries
import com.kaivoflow.core.model.Flow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection bookkeeping for `KaivoNotificationListener`, which lives in `feature/permissions` and cannot be referenced from here
 * (its class name is spelled out in [LISTENER_CLASS]). The system binds the listener; the app can only watch
 * `onListenerConnected` / `onListenerDisconnected` and call `requestRebind` when the connection drops (AO-12).
 * `requestRebind` is a plain binder call, not a foreground-service start, so it is allowed from any state.
 */
@Singleton
class NotificationListenerHub @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tracker: ListenerTracker,
    private val recorder: ReliabilityRecorder,
) {
    /** The "Notification access" switch in system Settings. Revoking it is invisible to the app except through this list. */
    fun accessGranted(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    suspend fun onConnected() {
        val away = tracker.onConnected()
        if (away != null) recorder.record(ReliabilityKinds.LISTENER_RECONNECTED, Severity.INFO, detail = "away ${away / 1000}s")
    }

    suspend fun onDisconnected() {
        recorder.record(ReliabilityKinds.LISTENER_DISCONNECTED, Severity.WARN)
        if (tracker.onDisconnected()) rebind()
    }

    /** Asks the system to bind the listener again. No effect (and no throw) when access is not granted. */
    suspend fun rebind() {
        val ok = runCatching { NotificationListenerService.requestRebind(ComponentName(context.packageName, LISTENER_CLASS)) }.isSuccess
        recorder.record(ReliabilityKinds.LISTENER_REBIND_REQUESTED, if (ok) Severity.INFO else Severity.WARN, detail = if (ok) "requested" else "failed")
    }

    fun reconcile(): ListenerAction = tracker.reconcile(accessGranted())

    fun registration() = tracker.registration(accessGranted())

    companion object {
        const val LISTENER_CLASS = "com.kaivoflow.feature.permissions.KaivoNotificationListener"
    }
}

/** Keeps the listener bound (retried on each Pulse, with back-off), refreshes the pre-filter, and writes honest registration rows. */
@Singleton
class NotificationListenerRegistrar @Inject constructor(
    private val hub: NotificationListenerHub,
    private val events: DeviceEvents,
    private val flows: FlowRepository,
    private val lifecycle: FlowLifecycleRepository,
    private val registrations: TriggerRegistrationRepository,
    private val recorder: ReliabilityRecorder,
    private val clock: Clock,
) : SparkRegistrar {
    private val writer by lazy { RegistrationWriter(registrations, recorder) { clock.now() } }

    override suspend fun reconcile(live: List<Flow>, all: List<Flow>, trigger: ReconcileTrigger) {
        val snapshots = lifecycle.allSnapshots()
        val liveFlows = flows.all().filter { snapshots[it.id]?.state?.isLive ?: it.isActive }
        events.notificationInterest = NotificationInterest.from(liveFlows)
        val wanted = SparkQueries.enabled(liveFlows, SparkTypes.NOTIFICATION)
        if (wanted.isNotEmpty() && hub.reconcile() == ListenerAction.RequestRebind) hub.rebind()
        val state = hub.registration()
        val existing = registrations.all().filter { it.kind == SparkTypes.NOTIFICATION }
        for ((flow, step) in wanted) {
            writer.write(flow.id, step.id, SparkTypes.NOTIFICATION, state, existing.firstOrNull { it.flowId == flow.id && it.sparkId == step.id })
        }
        existing.filter { r -> wanted.none { (f, s) -> f.id == r.flowId && s.id == r.sparkId } }.forEach { registrations.remove(it.flowId, it.sparkId) }
    }
}
