package com.kaivoflow.core.work.alwayson

import android.content.Context
import android.util.Log
import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.FlowVersionEntity
import com.kaivoflow.core.data.JsonCodec
import com.kaivoflow.core.data.RegistrationState
import com.kaivoflow.core.data.ScheduleRepository
import com.kaivoflow.core.data.SparkInboxRepository
import com.kaivoflow.core.data.TriggerRegistrationEntity
import com.kaivoflow.core.data.TriggerRegistrationRepository
import com.kaivoflow.core.engine.ActivationCoordinator
import com.kaivoflow.core.engine.ActivationPlatform
import com.kaivoflow.core.engine.ActivationResult
import com.kaivoflow.core.engine.ActivationStore
import com.kaivoflow.core.engine.FlowValidator
import com.kaivoflow.core.engine.LifecycleActor
import com.kaivoflow.core.engine.LifecycleLogEntry
import com.kaivoflow.core.engine.LifecycleReason
import com.kaivoflow.core.engine.LifecycleSnapshot
import com.kaivoflow.core.engine.PlatformFacts
import com.kaivoflow.core.engine.PublishPreview
import com.kaivoflow.core.engine.PublishValidator
import com.kaivoflow.core.engine.PublishedRevision
import com.kaivoflow.core.engine.RegistrationFailure
import com.kaivoflow.core.engine.RestoreReport
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.SparkRegState
import com.kaivoflow.core.engine.alwayson.StartReason
import com.kaivoflow.core.engine.reliability.RegistrationRows
import com.kaivoflow.core.model.Flow
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AO-16 on Android: the one place that changes a Flow's lifecycle. The decisions are in the pure `ActivationCoordinator`; this class
 * implements its two ports over the repositories, the registrars and the alarm scheduler, and exposes the use cases to the UI
 * (publish sheet, Flow list, later the canvas) and to the Reconciler (retries, restore at start).
 */
@Singleton
class ActivationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flows: FlowRepository,
    private val lifecycle: FlowLifecycleRepository,
    private val liveFlows: LiveFlows,
    private val registrations: TriggerRegistrationRepository,
    private val inbox: SparkInboxRepository,
    private val schedules: ScheduleRepository,
    private val scheduleSync: ScheduleSync,
    private val registrars: @JvmSuppressWildcards Set<SparkRegistrar>,
    private val reconciler: Lazy<Reconciler>,
    private val controller: EngineController,
    private val alarms: AlarmScheduler,
    private val recorder: ReliabilityRecorder,
    private val facts: PlatformFactsReader,
    private val alerts: FlowAlerts,
    private val deviceGateway: com.kaivoflow.core.work.AndroidDeviceGateway,
    private val clock: Clock,
) {
    private val store = object : ActivationStore {
        override suspend fun draft(flowId: String) = flows.flow(flowId)
        override suspend fun snapshot(flowId: String) = lifecycle.snapshot(flowId)
        override suspend fun allSnapshots() = lifecycle.allSnapshots()
        override suspend fun published(flowId: String, revision: Int) = liveFlows.published(flowId, revision)
        override suspend fun latestRevision(flowId: String) = lifecycle.latestRevision(flowId)
        override suspend fun otherLiveFlows(excluding: String) = liveFlows.live().filter { it.id != excluding }
        override suspend fun pendingInboxRows(flowId: String) = inbox.openCount(flowId)
        override suspend fun commit(flowId: String, snapshot: LifecycleSnapshot, log: LifecycleLogEntry?, revision: PublishedRevision?) =
            lifecycle.applyTransition(
                flowId, snapshot, log,
                revision?.let { FlowVersionEntity(it.flowId, it.revision, it.savedAt, it.label, JsonCodec.encodeFlow(it.flow)) },
            )
    }

    private val platform = object : ActivationPlatform {
        override suspend fun facts(): PlatformFacts = this@ActivationManager.facts.read()

        override suspend fun arm(flow: Flow): Map<String, SparkRegState> = liveFlows.arming(flow) {
            // Alarms and schedule rows first, then every registrar (they read LiveFlows, which now includes the candidate).
            scheduleSync.syncFlow(flow, live = true, now = clock.now())
            registrars.forEach { it.reconcile(listOf(flow), listOf(flow), ReconcileTrigger.FlowChanged) }
            statuses(flow)
        }

        override suspend fun disarmRemoved(old: Flow, new: Flow) {
            val keep = new.triggers.map { it.id }.toSet()
            old.triggers.filter { it.id !in keep }.forEach { s ->
                scheduleSync.disarmFlowSpark(old.id, s.id)
                registrations.remove(old.id, s.id)
            }
            runCatching { reconciler.get().syncFlow(new) }
        }

        override suspend fun disarm(flowId: String) {
            reconciler.get().disarmFlow(flowId)
            // Registrars read LiveFlows: with the Flow no longer live (and no overlay) they drop their rows and platform state.
            registrars.forEach { runCatching { it.reconcile(emptyList(), flows.all(), ReconcileTrigger.FlowChanged) } }
            registrations.removeAll(flowId)
        }

        override suspend fun scheduleRetry(flowId: String, at: Long) { alarms.armRetry(at) }
        override suspend fun cancelRetry(flowId: String) { rearmDueAlarm() }
        override suspend fun scheduleResume(flowId: String, at: Long) { alarms.armRetry(at) }
        override suspend fun cancelResume(flowId: String) { rearmDueAlarm() }
        override suspend fun dropInbox(flowId: String, reason: String) { inbox.dropOpenForFlow(flowId, reason) }
        override suspend fun markExpectedPaused(flowId: String, from: Long, to: Long) {
            schedules.markPendingInSpan(flowId, from, to, "PAUSED", "Paused")
        }
        override suspend fun recordFailures(flowId: String, failures: List<RegistrationFailure>) {
            failures.forEach { recorder.record(ReliabilityKinds.SPARK_REGISTRATION_FAILED, Severity.WARN, flowId, "${it.sparkId} ${it.code.name} ${it.detail}") }
        }
        override suspend fun notifyEnteredError(flowId: String, code: String) { alerts.flowEnteredError(flowId, code) }
        override suspend fun notifyBackToNormal(flowId: String) { alerts.flowBackToNormal(flowId) }
        override suspend fun reassessEngine() { runCatching { controller.reassess(StartReason.Publish) } }
        override suspend fun purge(flowId: String) {
            lifecycle.purge(flowId); registrations.removeAll(flowId); inbox.purge(flowId); schedules.purge(flowId)
        }
        override fun log(line: String) { Log.i(TAG, line) }
    }

    private val coordinator = ActivationCoordinator(store, platform, PublishValidator(FlowValidator(com.kaivoflow.core.nodes.NodeCatalog.registry(deviceGateway))), { clock.now() })

    // ---- Use cases for the UI ------------------------------------------------------------------------------------

    suspend fun preview(flowId: String): PublishPreview? = coordinator.preview(flowId)
    suspend fun publish(flowId: String, acknowledgeWarnings: Boolean = false): ActivationResult = coordinator.publish(flowId, acknowledgeWarnings).also { afterChange() }
    suspend fun publishChanges(flowId: String, acknowledgeWarnings: Boolean = false): ActivationResult =
        coordinator.publishChanges(flowId, acknowledgeWarnings).also { afterChange() }
    suspend fun pause(flowId: String, untilMs: Long? = null): ActivationResult = coordinator.pause(flowId, untilMs).also { afterChange() }
    suspend fun resume(flowId: String): ActivationResult = coordinator.resume(flowId).also { afterChange() }
    suspend fun unpublish(flowId: String, confirmed: Boolean = false): ActivationResult = coordinator.unpublish(flowId, confirmed).also { afterChange() }

    /** Deletes the Flow: disarm, drop Inbox rows, purge evidence, then the row itself. */
    suspend fun delete(flowId: String) {
        coordinator.deleted(flowId)
        flows.delete(flowId)
        afterChange()
    }

    /** Pauses every live Flow (the "Pause all" action of spec 4.2). Returns how many moved. */
    suspend fun pauseAll(untilMs: Long? = null): Int =
        lifecycle.liveFlowIds().count { coordinator.pause(it, untilMs).ok }.also { afterChange() }

    /** Re-arms the Flow now and lets the state machine decide (the "Fix now" button). */
    suspend fun retryNow(flowId: String): ActivationResult = coordinator.reportArming(flowId, LifecycleActor.User).also { afterChange() }

    // ---- For the Reconciler and the retry alarm ------------------------------------------------------------------

    suspend fun runDue(): Int = coordinator.runDue().also { rearmDueAlarm() }
    suspend fun restoreAtStart(): RestoreReport = coordinator.restoreAtStart().also { rearmDueAlarm() }

    /**
     * Every Reconciler pass: a Published Flow whose registrar rows say a Spark failed goes through the state machine (Error, backoff,
     * alert), and an Error Flow whose rows are healthy again is re-armed and recovers by itself (spec 4.2). Permanent failures wait
     * here for the permission-changed signal or a user fix.
     */
    suspend fun checkLive() {
        for ((id, s) in lifecycle.allSnapshots()) {
            if (!s.state.isLive) continue
            val failing = registrations.forFlow(id).any { it.state == RegistrationState.FAILED_TRANSIENT || it.state == RegistrationState.FAILED_PERMANENT }
            val published = s.state == com.kaivoflow.core.engine.LifecycleState.Published
            if ((published && failing) || (!published && !failing)) coordinator.reportArming(id)
        }
        rearmDueAlarm()
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    private suspend fun afterChange() { rearmDueAlarm() }

    /** One retry alarm at the earliest due time of any retry or timed resume (alarms survive sleep; in-memory timers do not). */
    private suspend fun rearmDueAlarm() {
        val next = coordinator.nextDueAt()
        if (next == null) alarms.cancelRetry() else alarms.armRetry(maxOf(next, clock.now() + MIN_ALARM_AHEAD_MS))
    }

    /** What really happened per Spark: schedule rows plus the registrar rows, in the Flow's Spark order. */
    private suspend fun statuses(flow: Flow): Map<String, SparkRegState> {
        val rows = registrations.forFlow(flow.id).associateBy { it.sparkId }
        val sched = schedules.statesForFlow(flow.id).associateBy { it.sparkId }
        val out = LinkedHashMap<String, SparkRegState>()
        for (spark in flow.triggers.filter { !it.disabled && it.type != com.kaivoflow.core.engine.SparkTypes.MANUAL }) {
            val s = sched[spark.id]
            val r = rows[spark.id]
            out[spark.id] = when {
                r != null -> RegistrationRows.toState(r.kind, r.state, r.detail)
                s != null && s.nextDueAt != null -> SparkRegState.armed(RegistrationRows.nextFireText(s.nextDueAt!!, s.armedMode ?: s.precision))
                s != null -> SparkRegState.failed(com.kaivoflow.core.engine.FailureCode.InvalidConfig, "No next time could be computed")
                // Sparks with no registrar row of their own (boot, share, headset on a stopped Engine) are armed by definition.
                else -> SparkRegState.armed("")
            }
        }
        return out
    }

    private companion object {
        const val TAG = "KaivoActivation"
        const val MIN_ALARM_AHEAD_MS = 1_000L
    }
}
