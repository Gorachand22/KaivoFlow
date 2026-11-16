package com.kaivoflow.core.engine

import com.kaivoflow.core.engine.alwayson.RegistrationStatuses
import com.kaivoflow.core.engine.alwayson.SparkRegState
import com.kaivoflow.core.model.Flow

/*
 * AO-16: the publish lifecycle as a use case (docs/specs/P2-always-on-engine.md section 4). Pure Kotlin over two ports, so
 * every decision (validate, arm, swap, retry, restore in batches) is testable on the JVM. The Android adapter in core:work
 * (`ActivationManager`) only implements the ports. All state changes go through [ActivationStateMachine].
 */

/** An immutable published revision of a Flow, as stored in `flow_versions`. */
data class PublishedRevision(val flowId: String, val revision: Int, val flow: Flow, val savedAt: Long, val label: String)

/** Storage port. [commit] writes columns, log row and the optional revision in one transaction. */
interface ActivationStore {
    suspend fun draft(flowId: String): Flow?
    suspend fun snapshot(flowId: String): LifecycleSnapshot?
    suspend fun allSnapshots(): Map<String, LifecycleSnapshot>
    suspend fun published(flowId: String, revision: Int): Flow?
    suspend fun latestRevision(flowId: String): Int
    suspend fun otherLiveFlows(excluding: String): List<Flow>
    suspend fun pendingInboxRows(flowId: String): Int
    suspend fun commit(flowId: String, snapshot: LifecycleSnapshot, log: LifecycleLogEntry?, revision: PublishedRevision?): Boolean
}

/** Platform port: arming and the side effects of [LifecycleEffect]. Every method must be idempotent. */
interface ActivationPlatform {
    suspend fun facts(): PlatformFacts

    /** Arms every Spark of [flow] (the content of a published revision) and says what really happened per Spark id. */
    suspend fun arm(flow: Flow): Map<String, SparkRegState>

    /** After a successful swap: removes Sparks that are in [old] but not in [new]. */
    suspend fun disarmRemoved(old: Flow, new: Flow)
    suspend fun disarm(flowId: String)
    suspend fun scheduleRetry(flowId: String, at: Long)
    suspend fun cancelRetry(flowId: String)
    suspend fun scheduleResume(flowId: String, at: Long)
    suspend fun cancelResume(flowId: String)
    suspend fun dropInbox(flowId: String, reason: String)
    suspend fun markExpectedPaused(flowId: String, from: Long, to: Long)
    suspend fun recordFailures(flowId: String, failures: List<RegistrationFailure>)
    suspend fun notifyEnteredError(flowId: String, code: String)
    suspend fun notifyBackToNormal(flowId: String)
    suspend fun reassessEngine()
    suspend fun purge(flowId: String)

    /** Written before each registration batch item so a crash during registration names the culprit (spec 4.6). */
    fun log(line: String)
}

enum class ActivationStatus {
    /** Lifecycle changed. */
    Done,
    /** Validation errors: nothing armed. */
    Blocked,
    /** Only warnings: call again with `acknowledgeWarnings = true` ("Publish anyway"). */
    NeedsAcknowledgement,
    /** Spark(s) could not arm: nothing changed, previous revision keeps running. */
    ArmFailed,
    /** A state-machine guard refused (confirmation, pause in the past). */
    Refused,
    NotFound,
    /** Event not valid in the current state. */
    Illegal,
    /** Nothing to do. */
    Unchanged,
}

data class ActivationResult(
    val status: ActivationStatus,
    val report: PublishReport? = null,
    /** Per Spark id, what arming reported (empty when arming was not reached). */
    val sparks: Map<String, SparkRegState> = emptyMap(),
    val snapshot: LifecycleSnapshot? = null,
    val refusal: RefusalReason? = null,
) {
    val ok: Boolean get() = status == ActivationStatus.Done || status == ActivationStatus.Unchanged
}

data class RestoreReport(val considered: Int, val restored: Int, val errored: Int, val resumed: Int, val batches: Int)

/** What the publish sheet needs before the user taps Publish: the checklist, no side effects. */
data class PublishPreview(val report: PublishReport, val isPublished: Boolean, val hasUnpublishedChanges: Boolean)

/** "Changes not live": true when the draft differs from the published revision in anything that changes behaviour. */
object ChangeDetector {
    fun differs(draft: Flow, published: Flow): Boolean = behaviour(draft) != behaviour(published)

    private fun behaviour(f: Flow) = listOf(
        f.steps.map { it.copy(x = 0f, y = 0f, notes = "", frozenOutput = null) },
        f.streams, f.vars, f.settings,
    )
}

class ActivationCoordinator(
    private val store: ActivationStore,
    private val platform: ActivationPlatform,
    private val validator: PublishValidator,
    private val clock: () -> Long,
    private val batchSize: Int = 5,
) {
    suspend fun preview(flowId: String): PublishPreview? {
        val draft = store.draft(flowId) ?: return null
        val snap = store.snapshot(flowId) ?: LifecycleSnapshot()
        val report = validate(draft)
        val published = snap.publishedRevision?.let { store.published(flowId, it) }
        return PublishPreview(report, snap.state != LifecycleState.Draft, published != null && ChangeDetector.differs(draft, published))
    }

    private suspend fun validate(flow: Flow): PublishReport =
        validator.validate(flow, PublishContext(platform.facts(), store.otherLiveFlows(flow.id), dryRun = null))

    suspend fun publish(flowId: String, acknowledgeWarnings: Boolean = false): ActivationResult {
        val draft = store.draft(flowId) ?: return ActivationResult(ActivationStatus.NotFound)
        val snap = store.snapshot(flowId) ?: LifecycleSnapshot()
        if (snap.state != LifecycleState.Draft) return ActivationResult(ActivationStatus.Illegal, snapshot = snap)
        val gate = gate(draft, acknowledgeWarnings)
        if (gate.first != null) return gate.first!!
        val report = gate.second
        platform.log("publish: arming $flowId")
        val sparks = safeArm(draft)
        val failures = RegistrationStatuses.failures(sparks)
        val now = clock()
        val next = store.latestRevision(flowId) + 1
        val result = ActivationStateMachine.reduce(snap, LifecycleEvent.Publish(true, failures, next), now)
        return when (result) {
            is TransitionResult.Moved -> {
                commit(flowId, result, PublishedRevision(flowId, next, draft, now, "Published"))
                ActivationResult(ActivationStatus.Done, report, sparks, result.snapshot)
            }
            is TransitionResult.Refused -> {
                platform.disarm(flowId) // nothing may stay armed after a refused publish
                ActivationResult(ActivationStatus.ArmFailed, report, sparks, snap, result.reason)
            }
            else -> ActivationResult(ActivationStatus.Illegal, report, sparks, snap)
        }
    }

    suspend fun publishChanges(flowId: String, acknowledgeWarnings: Boolean = false): ActivationResult {
        val draft = store.draft(flowId) ?: return ActivationResult(ActivationStatus.NotFound)
        val snap = store.snapshot(flowId) ?: LifecycleSnapshot()
        if (snap.state != LifecycleState.Published) return ActivationResult(ActivationStatus.Illegal, snapshot = snap)
        val old = snap.publishedRevision?.let { store.published(flowId, it) }
        if (old != null && !ChangeDetector.differs(draft, old)) return ActivationResult(ActivationStatus.Unchanged, snapshot = snap)
        val gate = gate(draft, acknowledgeWarnings)
        if (gate.first != null) return gate.first!!
        val report = gate.second
        platform.log("publishChanges: arming $flowId")
        // Atomic swap: arm the new set first. Arming is idempotent, so sparks present in both sets just stay armed.
        val sparks = safeArm(draft)
        val failures = RegistrationStatuses.failures(sparks)
        val now = clock()
        val next = store.latestRevision(flowId) + 1
        return when (val r = ActivationStateMachine.reduce(snap, LifecycleEvent.PublishChanges(true, failures, next), now)) {
            is TransitionResult.Moved -> {
                if (old != null) platform.disarmRemoved(old, draft)
                commit(flowId, r, PublishedRevision(flowId, next, draft, now, "Published changes"))
                ActivationResult(ActivationStatus.Done, report, sparks, r.snapshot)
            }
            is TransitionResult.Refused -> {
                // Keep revision N running: put its own Spark set back.
                if (old != null) platform.arm(old)
                ActivationResult(ActivationStatus.ArmFailed, report, sparks, snap, r.reason)
            }
            else -> ActivationResult(ActivationStatus.Illegal, report, sparks, snap)
        }
    }

    suspend fun pause(flowId: String, until: Long? = null, reason: LifecycleReason = LifecycleReason.UserPaused, actor: LifecycleActor = LifecycleActor.User): ActivationResult =
        simple(flowId) { LifecycleEvent.Pause(until, reason, actor) }

    suspend fun resume(flowId: String, actor: LifecycleActor = LifecycleActor.User): ActivationResult {
        val snap = store.snapshot(flowId) ?: return ActivationResult(ActivationStatus.NotFound)
        if (snap.state != LifecycleState.Paused) return ActivationResult(ActivationStatus.Illegal, snapshot = snap)
        val flow = liveContent(flowId, snap) ?: return ActivationResult(ActivationStatus.NotFound)
        val report = validate(flow)
        if (!report.canPublish) return ActivationResult(ActivationStatus.Blocked, report, snapshot = snap)
        platform.log("resume: arming $flowId")
        val sparks = safeArm(flow)
        val failures = RegistrationStatuses.failures(sparks)
        val now = clock()
        return when (val r = ActivationStateMachine.reduce(snap, LifecycleEvent.Resume(true, failures, actor), now)) {
            is TransitionResult.Moved -> {
                commit(flowId, r, null)
                ActivationResult(ActivationStatus.Done, report, sparks, r.snapshot)
            }
            is TransitionResult.Refused -> { platform.disarm(flowId); ActivationResult(ActivationStatus.ArmFailed, report, sparks, snap, r.reason) }
            else -> ActivationResult(ActivationStatus.Illegal, report, sparks, snap)
        }
    }

    suspend fun unpublish(flowId: String, confirmed: Boolean = false): ActivationResult {
        val pending = store.pendingInboxRows(flowId)
        return simple(flowId) { LifecycleEvent.Unpublish(pending, confirmed) }
    }

    /** Disarms, drops Inbox rows and purges evidence. The caller deletes the Flow row afterwards. */
    suspend fun deleted(flowId: String) {
        val snap = store.snapshot(flowId) ?: LifecycleSnapshot()
        val r = ActivationStateMachine.reduce(snap, LifecycleEvent.Delete(), clock())
        runEffects(flowId, r.effects)
        platform.purge(flowId)
    }

    /**
     * What a retry, a Pulse or the Reconciler found. Re-arms the published content and feeds the state machine, which
     * decides Error, backoff and recovery.
     */
    suspend fun reportArming(flowId: String, actor: LifecycleActor = LifecycleActor.Reconciler): ActivationResult {
        val snap = store.snapshot(flowId) ?: return ActivationResult(ActivationStatus.NotFound)
        if (!snap.state.isLive) return ActivationResult(ActivationStatus.Unchanged, snapshot = snap)
        val flow = liveContent(flowId, snap) ?: return ActivationResult(ActivationStatus.NotFound)
        val sparks = safeArm(flow)
        val failures = RegistrationStatuses.failures(sparks)
        return when (val r = ActivationStateMachine.reduce(snap, LifecycleEvent.ArmReport(failures, actor), clock())) {
            is TransitionResult.Moved -> { commit(flowId, r, null); ActivationResult(ActivationStatus.Done, null, sparks, r.snapshot) }
            else -> ActivationResult(ActivationStatus.Unchanged, null, sparks, snap)
        }
    }

    /** Due retries (Error with `nextRetryAt` passed) and due timed resumes. Called from the retry alarm, Pulse and watchdog. */
    suspend fun runDue(): Int {
        val now = clock()
        var n = 0
        for ((id, s) in store.allSnapshots()) {
            when {
                ActivationStateMachine.retryDue(s, now) -> { reportArming(id); n++ }
                ActivationStateMachine.resumeDue(s, now) -> { resume(id, LifecycleActor.Engine); n++ }
            }
        }
        return n
    }

    /** Earliest time something is due, for the single retry alarm. Null when nothing is queued. */
    suspend fun nextDueAt(): Long? = store.allSnapshots().values
        .flatMap { listOfNotNull(if (it.state == LifecycleState.Error) it.nextRetryAt else null, if (it.state == LifecycleState.Paused) it.pausedUntil else null) }
        .minOrNull()

    /**
     * Process start (or Engine start): re-arm every live Flow in small batches, one after another. A log line precedes each
     * registration. A permanent failure puts the Flow in Error once and is not retried; transient ones enter the retry queue.
     */
    suspend fun restoreAtStart(): RestoreReport {
        val now = clock()
        val snaps = store.allSnapshots()
        val live = snaps.filterValues { it.state.isLive }.keys.sorted()
        val due = snaps.filter { ActivationStateMachine.resumeDue(it.value, now) }.keys.sorted()
        var restored = 0; var errored = 0; var resumed = 0; var batches = 0
        for (batch in live.chunked(batchSize.coerceAtLeast(1))) {
            batches++
            for (id in batch) {
                platform.log("restore: registering $id")
                val r = reportArming(id)
                val state = r.snapshot?.state ?: snaps[id]?.state
                if (state == LifecycleState.Error) errored++ else restored++
            }
        }
        for (id in due) { platform.log("restore: resuming $id"); if (resume(id, LifecycleActor.Engine).ok) resumed++ }
        if (live.isNotEmpty() || due.isNotEmpty()) platform.reassessEngine()
        return RestoreReport(live.size, restored, errored, resumed, batches)
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    private suspend fun safeArm(flow: Flow): Map<String, SparkRegState> = try {
        platform.arm(flow)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        mapOf("" to SparkRegState.failed(FailureCode.Unknown, e.javaClass.simpleName))
    }

    private suspend fun gate(flow: Flow, acknowledge: Boolean): Pair<ActivationResult?, PublishReport> {
        val report = validate(flow)
        return when {
            !report.canPublish -> ActivationResult(ActivationStatus.Blocked, report) to report
            report.needsAcknowledgement && !acknowledge -> ActivationResult(ActivationStatus.NeedsAcknowledgement, report) to report
            else -> null to report
        }
    }

    private suspend fun liveContent(flowId: String, snap: LifecycleSnapshot): Flow? =
        snap.publishedRevision?.let { store.published(flowId, it) } ?: store.draft(flowId)

    private suspend fun simple(flowId: String, event: suspend () -> LifecycleEvent): ActivationResult {
        val snap = store.snapshot(flowId) ?: return ActivationResult(ActivationStatus.NotFound)
        return when (val r = ActivationStateMachine.reduce(snap, event(), clock())) {
            is TransitionResult.Moved -> { commit(flowId, r, null); ActivationResult(ActivationStatus.Done, snapshot = r.snapshot) }
            is TransitionResult.Refused -> ActivationResult(ActivationStatus.Refused, snapshot = snap, refusal = r.reason)
            is TransitionResult.Ignored -> ActivationResult(ActivationStatus.Unchanged, snapshot = snap)
            else -> ActivationResult(ActivationStatus.Illegal, snapshot = snap)
        }
    }

    private suspend fun commit(flowId: String, r: TransitionResult.Moved, revision: PublishedRevision?) {
        store.commit(flowId, r.snapshot, r.log, revision)
        runEffects(flowId, r.effects)
    }

    private suspend fun runEffects(flowId: String, effects: List<LifecycleEffect>) {
        for (e in effects) when (e) {
            is LifecycleEffect.SnapshotRevision, LifecycleEffect.ArmAll, LifecycleEffect.SwapSparks -> Unit // done before the commit
            LifecycleEffect.DisarmAll -> platform.disarm(flowId)
            is LifecycleEffect.ScheduleRetry -> platform.scheduleRetry(flowId, e.at)
            LifecycleEffect.CancelRetry -> platform.cancelRetry(flowId)
            is LifecycleEffect.ScheduleResume -> platform.scheduleResume(flowId, e.at)
            LifecycleEffect.CancelResume -> platform.cancelResume(flowId)
            is LifecycleEffect.DropInboxRows -> platform.dropInbox(flowId, e.reason)
            is LifecycleEffect.MarkExpectedRunsPaused -> platform.markExpectedPaused(flowId, e.from, e.to)
            is LifecycleEffect.RecordFailures -> platform.recordFailures(flowId, e.failures)
            is LifecycleEffect.NotifyEnteredError -> platform.notifyEnteredError(flowId, e.code)
            LifecycleEffect.NotifyBackToNormal -> platform.notifyBackToNormal(flowId)
            LifecycleEffect.ReassessEngine -> platform.reassessEngine()
        }
    }
}
