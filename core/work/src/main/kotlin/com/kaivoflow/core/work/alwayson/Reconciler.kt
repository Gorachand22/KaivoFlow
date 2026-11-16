package com.kaivoflow.core.work.alwayson

import android.content.Context
import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.SparkInboxRepository
import com.kaivoflow.core.engine.LifecycleSnapshot
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.LivenessPolicy
import com.kaivoflow.core.engine.alwayson.ReconcileScope
import com.kaivoflow.core.engine.alwayson.ReconcilePolicy
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.StartReason
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.nodes.TriggerSpecs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class ReconcileReport(
    val trigger: ReconcileTrigger,
    val liveFlows: Int,
    val schedules: SyncReport,
    val engineAction: String,
    val failed: Boolean = false,
)

/**
 * The single idempotent repair routine (spec 3.2). Compares what should be armed (live Flows, from the database) with what the
 * platform holds and fixes the difference: alarms, WorkManager jobs, registrations, geofences, the Pulse, the watchdog, the
 * Engine itself. Missed schedule slots get their misfire policy applied here.
 *
 * Every trigger-in calls [reconcile]: boot, package replaced, time and zone change, Pulse, watchdog, app start, service start,
 * exact-alarm permission change, "Fix now". A mutex serialises passes, so a second caller waits and then finds nothing to do.
 */
@Singleton
class Reconciler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flows: FlowRepository,
    private val lifecycle: FlowLifecycleRepository,
    private val scheduleSync: ScheduleSync,
    private val registrars: @JvmSuppressWildcards Set<SparkRegistrar>,
    private val controller: EngineController,
    private val alarms: AlarmScheduler,
    private val flexible: FlexibleWork,
    private val watchdog: WatchdogScheduler,
    private val inboxRepo: SparkInboxRepository,
    private val intake: SparkIntake,
    private val recorder: ReliabilityRecorder,
    private val settings: EngineSettings,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("kaivo.reconciler", Context.MODE_PRIVATE)

    suspend fun reconcile(trigger: ReconcileTrigger): ReconcileReport = mutex.withLock { pass(trigger) }

    /** Re-plans one Flow right after an edit, publish or unpublish, without a full pass. */
    suspend fun syncFlow(flow: Flow): SyncReport = mutex.withLock {
        val live = isLive(flow, lifecycle.snapshot(flow.id))
        val report = scheduleSync.syncFlow(flow, live, clock.now())
        registrars.forEach { runCatching { it.reconcile(if (live) listOf(flow) else emptyList(), listOf(flow), ReconcileTrigger.FlowChanged) } }
        ensurePulseAndWatchdog(liveCount = if (live) 1 else flows.all().count { isLive(it, lifecycle.snapshot(it.id)) })
        runCatching { controller.reassess(StartReason.Publish) }
        report
    }

    suspend fun disarmFlow(flowId: String) = mutex.withLock {
        scheduleSync.disarmFlow(flowId)
        flexible.cancelLegacy(flowId)
    }

    private suspend fun pass(trigger: ReconcileTrigger): ReconcileReport {
        val scope = ReconcilePolicy.scopeFor(trigger)
        val now = clock.now()
        var report = ReconcileReport(trigger, 0, SyncReport(), "None")
        try {
            val all = flows.all()
            val snapshots = lifecycle.allSnapshots()
            val live = all.filter { isLive(it, snapshots[it.id]) }

            cancelLegacyWorkOnce(all)

            var schedules = SyncReport()
            if (scope.rearmAlarms) schedules = scheduleSync.sync(live, scope.applyMisfire, scope.recomputeSchedules, now)
            if (scope.rearmRegistrations) registrars.forEach { r ->
                runCatching { r.reconcile(live, all, trigger) }.onFailure { if (it is CancellationException) throw it }
            }
            ensurePulseAndWatchdog(live.size)

            if (scope.dispatchBootSpark) {
                val boot = bootCount(context)
                intake.dispatch(
                    TriggerSpecs.BOOT, mapOf("reason" to "boot", "at" to now),
                    sourceId = "boot:$boot", source = IntakeSource.Reconcile,
                )
            }

            var engineAction = "None"
            if (scope.ensureEngine) engineAction = controller.reassess(startReasonFor(trigger)).name
            // Rows that were enqueued while no consumer was alive (catch-up slots, a dead Engine) are picked up here.
            if (schedules.caughtUp > 0 || controller.hasInboxWork()) controller.onInboxWritten(IntakeSource.Reconcile)

            if (trigger == ReconcileTrigger.AppStart || trigger == ReconcileTrigger.Boot || trigger == ReconcileTrigger.Watchdog) {
                runCatching { inboxRepo.deleteFinishedOlderThan(now - FINISHED_ROW_RETENTION_MS) }
            }
            scope.eventKind?.let { recorder.record(it, Severity.INFO, detail = eventDetail(trigger, schedules)) }
            report = ReconcileReport(trigger, live.size, schedules, engineAction)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            recorder.record(ReliabilityKinds.RECONCILE_FAILED, Severity.ERROR, detail = "${trigger.name}: ${t.javaClass.simpleName}")
            report = report.copy(failed = true)
        }
        return report
    }

    /** The Pulse and the watchdog run only while something is published (or Always-on mode is on): no standing wake-ups for nothing. */
    private fun ensurePulseAndWatchdog(liveCount: Int) {
        if (liveCount > 0 || settings.alwaysOnMode) {
            alarms.armPulse(clock.now() + LivenessPolicy.PULSE_INTERVAL_MS)
            watchdog.ensure()
        } else {
            alarms.cancelPulse()
            watchdog.cancel()
        }
    }

    /** Work enqueued by the pre-Engine scheduler is replaced by alarms; remove it once, after the first run of this version. */
    private fun cancelLegacyWorkOnce(all: List<Flow>) {
        if (prefs.getBoolean(KEY_LEGACY_CANCELLED, false)) return
        all.forEach { flexible.cancelLegacy(it.id) }
        prefs.edit().putBoolean(KEY_LEGACY_CANCELLED, true).apply()
    }

    private fun isLive(flow: Flow, snapshot: LifecycleSnapshot?): Boolean = snapshot?.state?.isLive ?: flow.isActive

    private fun startReasonFor(t: ReconcileTrigger) = when (t) {
        ReconcileTrigger.Boot -> StartReason.Boot
        ReconcileTrigger.PackageReplaced -> StartReason.PackageReplaced
        ReconcileTrigger.TimeChanged, ReconcileTrigger.TimezoneChanged -> StartReason.TimeChanged
        ReconcileTrigger.Pulse -> StartReason.Pulse
        ReconcileTrigger.Watchdog -> StartReason.Watchdog
        ReconcileTrigger.AppStart, ReconcileTrigger.UserFix -> StartReason.UserOpen
        ReconcileTrigger.ExactAlarmPermissionChanged -> StartReason.ExactAlarmPermission
        else -> StartReason.Reassess
    }

    private fun eventDetail(t: ReconcileTrigger, s: SyncReport): String = when (t) {
        ReconcileTrigger.Boot -> "sinceBootSec=${sinceBootSeconds()} armed=${s.armed} caughtUp=${s.caughtUp} skipped=${s.skipped}"
        else -> "armed=${s.armed} caughtUp=${s.caughtUp} skipped=${s.skipped}"
    }

    private companion object {
        const val KEY_LEGACY_CANCELLED = "legacySchedulerWorkCancelled"
        const val FINISHED_ROW_RETENTION_MS = 7L * 24 * 3_600_000L
    }
}
