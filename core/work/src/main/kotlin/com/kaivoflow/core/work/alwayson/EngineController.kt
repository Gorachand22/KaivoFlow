package com.kaivoflow.core.work.alwayson

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.engine.EngineFlow
import com.kaivoflow.core.engine.EngineNeeds
import com.kaivoflow.core.engine.EngineNeedsResult
import com.kaivoflow.core.engine.LifecycleState
import com.kaivoflow.core.engine.alwayson.AlertThrottle
import com.kaivoflow.core.engine.alwayson.EngineAction
import com.kaivoflow.core.engine.alwayson.EngineDecision
import com.kaivoflow.core.engine.alwayson.EngineEvent
import com.kaivoflow.core.engine.alwayson.EngineState
import com.kaivoflow.core.engine.alwayson.FgsStartPolicy
import com.kaivoflow.core.engine.alwayson.InboxStore
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.StartReason
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Start, stop and state of the Always-On Engine (spec 3.3 and 3.5), driven by `EngineNeeds`.
 *
 * Starting from the background is the part that used to fail silently. Here a refusal (a
 * `ForegroundServiceStartNotAllowedException`, or a `SecurityException` for a missing type permission) is never swallowed:
 * it becomes the Start-blocked state, a `FgsStartDenied` reliability event, a throttled "needs a tap" notification, and the
 * fallback worker, so Runs still happen at WorkManager quality.
 */
@Singleton
class EngineController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flows: FlowRepository,
    private val lifecycle: FlowLifecycleRepository,
    private val runtime: EngineRuntime,
    private val settings: EngineSettings,
    private val notifications: EngineNotifications,
    private val recorder: ReliabilityRecorder,
    private val fallback: FallbackHost,
    private val host: RunHost,
    private val inboxStore: InboxStore,
    private val throttle: AlertThrottle,
) {
    val state: StateFlow<EngineState> get() = runtime.state

    /** What the published Flows need from the Engine right now. Pure decision in `EngineNeeds`, inputs read here. */
    suspend fun needs(): EngineNeedsResult {
        val snapshots = lifecycle.allSnapshots()
        val engineFlows = flows.all().map { flow ->
            EngineFlow(flow, snapshots[flow.id]?.state ?: if (flow.isActive) LifecycleState.Published else LifecycleState.Draft)
        }
        return EngineNeeds.compute(engineFlows, settings.alwaysOnMode)
    }

    /**
     * Starts or stops the Engine to match [needs]. Safe to call from anywhere and as often as you like: nothing happens when the
     * state already matches. Also tells a running service to refresh its notification and registrations.
     */
    suspend fun reassess(reason: StartReason): EngineAction {
        val needs = needs()
        val action = EngineDecision.decide(needs.engineNeeded, runtime.state.value, transientHold = runtime.transientSession && hasInboxWork())
        when (action) {
            EngineAction.Start -> start(reason, transient = false, notifyOnDenial = reason !in QUIET_REASONS)
            EngineAction.Stop -> stop()
            EngineAction.None -> if (runtime.serviceAlive) runtime.requestRefresh()
        }
        return action
    }

    /**
     * Requests the foreground service. Returns true when the platform accepted the start (the service then reports
     * `ServiceStarted` itself); false when it was refused.
     */
    suspend fun start(reason: StartReason, transient: Boolean = false, notifyOnDenial: Boolean = true): Boolean {
        // Never call startForegroundService from a state the platform forbids (spec 3.5): the policy decides, the platform is not asked.
        if (!FgsStartPolicy.mayStart(reason, batteryExempt())) {
            onStartSkipped(reason, notifyOnDenial)
            return false
        }
        runtime.reduce(EngineEvent.StartRequested)
        val intent = Intent(context, AlwaysOnService::class.java)
            .putExtra(AlwaysOnService.EXTRA_REASON, reason.name)
            .putExtra(AlwaysOnService.EXTRA_TRANSIENT, transient)
        return try {
            ContextCompat.startForegroundService(context, intent)
            notifications.clearStartBlocked()
            true
        } catch (e: Exception) {
            // API 31+: ForegroundServiceStartNotAllowedException (an IllegalStateException). API 34+: SecurityException when the
            // type permission or the specialUse property is missing. Anything else is a bug and is reported the same way.
            onStartDenied(e, reason, notifyOnDenial)
            false
        }
    }

    /** The policy refused the start without asking the platform. Same visible outcome as a refusal: Start-blocked and the fallback host. */
    private suspend fun onStartSkipped(reason: StartReason, notify: Boolean) {
        runtime.reduce(EngineEvent.StartDenied("NotAttempted"))
        if (throttle.tryAcquire("fgs-start-skipped:${reason.name}")) {
            recorder.record(ReliabilityKinds.FGS_START_SKIPPED, Severity.INFO, detail = reason.name)
        }
        if (notify && throttle.tryAcquire("fgs-start-denied")) notifications.postStartBlocked()
        if (hasInboxWork()) fallback.ensureDrain()
    }

    /** Holding the battery-optimisation exemption also exempts the app from the background-start limits (spec 3.5, R8). */
    fun batteryExempt(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    suspend fun onStartDenied(e: Exception, reason: StartReason, notify: Boolean) {
        runtime.reduce(EngineEvent.StartDenied(e.javaClass.simpleName))
        recorder.record(ReliabilityKinds.FGS_START_DENIED, Severity.WARN, detail = "${reason.name}: ${e.javaClass.simpleName}")
        if (notify && throttle.tryAcquire("fgs-start-denied")) notifications.postStartBlocked()
        fallback.ensureDrain()
    }

    /** Stops the service on purpose, which stamps a clean stop on its session. */
    fun stop() {
        runtime.stopRequested = true
        runtime.reduce(EngineEvent.StopRequested)
        context.stopService(Intent(context, AlwaysOnService::class.java))
    }

    /**
     * Called after an Inbox write. Run in the Engine when it is alive, in WorkManager when it is not, never both (spec 3.1 rule 4).
     * A source that the platform does not exempt (SMS, notification, Wi-Fi, Bluetooth, a webhook that outlived its Engine) never
     * starts the service: the row is already durable, so the fallback worker hosts the Run. Exempt sources (an exact alarm, a
     * geofence transition) try to start the Engine, as a short session when nothing needs it permanently, and fall back the same
     * way when the platform still refuses. [firstRowId] only names the row the worker was created for.
     */
    suspend fun onInboxWritten(source: IntakeSource, firstRowId: Long? = null) {
        if (runtime.serviceAlive) {
            host.nudge()
            return
        }
        if (FgsStartPolicy.mayStart(source.reason, batteryExempt())) {
            val needs = needs()
            if (start(source.reason, transient = !needs.engineNeeded, notifyOnDenial = source.exemptFromFgsLimits)) return
        }
        fallback.ensureDrain(firstRowId)
    }

    private companion object {
        /**
         * Starts that are expected to be refused when the process was merely woken (app start from a receiver, an event):
         * recorded and handed to the fallback worker, but no notification. Everything else is an exempt start path or the
         * watchdog, where a refusal deserves the "needs a tap" notice (throttled to one per hour).
         */
        val QUIET_REASONS = setOf(StartReason.IntakeEvent, StartReason.UserOpen, StartReason.Reassess, StartReason.StickyRestart, StartReason.Geofence)
    }

    /** True while Inbox rows are waiting or running. A transient session stays up until this is false. */
    suspend fun hasInboxWork(): Boolean = inboxStore.openCount() > 0
}
