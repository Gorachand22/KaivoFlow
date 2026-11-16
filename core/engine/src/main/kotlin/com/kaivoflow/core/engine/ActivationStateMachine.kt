package com.kaivoflow.core.engine

/**
 * Flow publish lifecycle (docs/specs/P2-always-on-engine.md section 4). Pure Kotlin, no Android and
 * no clock: every function takes `now`, so the whole table is testable on the JVM.
 *
 * Stored enums carry a stable database spelling in [db]; never rename a constant without a migration.
 */
internal fun String.toDbName(): String = replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

enum class LifecycleState {
    Draft, Published, Paused, Error;

    val db: String get() = name.toDbName()

    /** Sparks are armed (fully or partly) and Runs can start from them. Replaces the old `isActive`. */
    val isLive: Boolean get() = this == Published || this == Error

    companion object {
        fun fromDb(value: String?): LifecycleState = entries.firstOrNull { it.db == value } ?: Draft
    }
}

enum class LifecycleReason {
    None, Published, PublishedChanges, UserPaused, AutoPaused, Resumed, Unpublished, Imported,
    MigratedActive, ArmFailed, Recovered;

    val db: String get() = name.toDbName()

    companion object {
        fun fromDb(value: String?): LifecycleReason = entries.firstOrNull { it.db == value } ?: None
    }
}

/** Who caused a transition. Stored lower-case in `flow_lifecycle_log.actor`. */
enum class LifecycleActor {
    User, Engine, Reconciler, Import;

    val db: String get() = name.lowercase()
}

/** Why a Spark could not be armed. Permanent codes are never retried (section 4.6). */
enum class FailureCode(val permanent: Boolean) {
    PermissionMissing(true),
    InvalidConfig(true),
    CredentialMissing(true),
    AlarmRefused(false),
    PortInUse(false),
    ListenerUnbound(false),
    NetworkCallbackRejected(false),
    GeofenceRejected(false),
    EngineStartBlocked(false),
    Unknown(false),
}

data class RegistrationFailure(val sparkId: String, val code: FailureCode, val detail: String = "")

/** The persisted lifecycle columns of one Flow (`flows` table, section 4.5). */
data class LifecycleSnapshot(
    val state: LifecycleState = LifecycleState.Draft,
    val publishedRevision: Int? = null,
    val reason: LifecycleReason = LifecycleReason.None,
    val since: Long = 0L,
    val pausedUntil: Long? = null,
    val errorCode: String? = null,
    val errorSince: Long? = null,
    val retryCount: Int = 0,
    val nextRetryAt: Long? = null,
    /** Set when an Error recovers; drives the stable-period reset of [retryCount]. */
    val healthySince: Long? = null,
)

sealed interface LifecycleEvent {
    val actor: LifecycleActor

    /** Draft to Published. [failures] is the result of the synchronous arming dry run. */
    data class Publish(
        val validationOk: Boolean,
        val failures: List<RegistrationFailure>,
        val nextRevision: Int,
        override val actor: LifecycleActor = LifecycleActor.User,
    ) : LifecycleEvent

    /** Published to Published with a new revision (atomic swap is the caller's job, see effects). */
    data class PublishChanges(
        val validationOk: Boolean,
        val failures: List<RegistrationFailure>,
        val nextRevision: Int,
        override val actor: LifecycleActor = LifecycleActor.User,
    ) : LifecycleEvent

    /** [until] null means open ended. Use [LifecycleReason.AutoPaused] for the failed-Runs rule. */
    data class Pause(
        val until: Long? = null,
        val reason: LifecycleReason = LifecycleReason.UserPaused,
        override val actor: LifecycleActor = LifecycleActor.User,
    ) : LifecycleEvent

    /** User resume or the end of a timed pause (actor Engine). */
    data class Resume(
        val validationOk: Boolean,
        val failures: List<RegistrationFailure>,
        override val actor: LifecycleActor = LifecycleActor.User,
    ) : LifecycleEvent

    /**
     * What the Reconciler (or a retry) found when it tried to arm or check the Sparks. Empty
     * [failures] means everything is armed and healthy.
     */
    data class ArmReport(
        val failures: List<RegistrationFailure>,
        override val actor: LifecycleActor = LifecycleActor.Reconciler,
    ) : LifecycleEvent

    data class Unpublish(
        val pendingInboxRows: Int = 0,
        val confirmed: Boolean = true,
        override val actor: LifecycleActor = LifecycleActor.User,
    ) : LifecycleEvent

    data class Delete(override val actor: LifecycleActor = LifecycleActor.User) : LifecycleEvent
}

/** Side effects the caller (ActivationManager, AO-16) must carry out, in list order. */
sealed interface LifecycleEffect {
    data class SnapshotRevision(val revision: Int) : LifecycleEffect
    data object ArmAll : LifecycleEffect
    /** Arm the new Spark set first, then disarm removed Sparks; on failure keep the old revision. */
    data object SwapSparks : LifecycleEffect
    data object DisarmAll : LifecycleEffect
    data class ScheduleRetry(val at: Long) : LifecycleEffect
    data object CancelRetry : LifecycleEffect
    data class ScheduleResume(val at: Long) : LifecycleEffect
    data object CancelResume : LifecycleEffect
    data class DropInboxRows(val reason: String) : LifecycleEffect
    data class MarkExpectedRunsPaused(val from: Long, val to: Long) : LifecycleEffect
    data class RecordFailures(val failures: List<RegistrationFailure>) : LifecycleEffect
    data class NotifyEnteredError(val code: String) : LifecycleEffect
    data object NotifyBackToNormal : LifecycleEffect
    /** Start or stop the Engine according to [EngineNeeds]. */
    data object ReassessEngine : LifecycleEffect
}

data class LifecycleLogEntry(
    val at: Long,
    val from: LifecycleState,
    val to: LifecycleState,
    val reason: LifecycleReason,
    val actor: LifecycleActor,
)

enum class RefusalReason { ValidationFailed, ArmFailed, NeedsConfirmation, PauseInPast }

sealed interface TransitionResult {
    val snapshot: LifecycleSnapshot
    val effects: List<LifecycleEffect>

    /** The snapshot changed. [log] is null for a self transition that is not a state or revision change. */
    data class Moved(
        override val snapshot: LifecycleSnapshot,
        override val effects: List<LifecycleEffect>,
        val log: LifecycleLogEntry?,
    ) : TransitionResult

    /** A guard failed. Nothing changes and nothing is armed or disarmed. */
    data class Refused(
        override val snapshot: LifecycleSnapshot,
        val reason: RefusalReason,
        val failures: List<RegistrationFailure> = emptyList(),
    ) : TransitionResult {
        override val effects: List<LifecycleEffect> get() = emptyList()
    }

    /** Valid event with nothing to do (for example a healthy report for a Published Flow). */
    data class Ignored(override val snapshot: LifecycleSnapshot) : TransitionResult {
        override val effects: List<LifecycleEffect> get() = emptyList()
    }

    /** The event is not in the transition table for this state. */
    data class Illegal(override val snapshot: LifecycleSnapshot, val event: LifecycleEvent) : TransitionResult {
        override val effects: List<LifecycleEffect> get() = emptyList()
    }

    data class Deleted(override val snapshot: LifecycleSnapshot, override val effects: List<LifecycleEffect>) : TransitionResult
}

/** Registration retry schedule (section 4.6): 5 s, 15 s, 1 min, 5 min, 15 min, then hourly. */
object RetryBackoff {
    private val DELAYS_MS = longArrayOf(5_000L, 15_000L, 60_000L, 300_000L, 900_000L, 3_600_000L)
    const val STABLE_PERIOD_MS: Long = 10 * 60_000L
    const val BACK_TO_NORMAL_THRESHOLD_MS: Long = 5 * 60_000L

    /** Delay before retry number [attempt] (0 based). Anything past the table stays at one hour. */
    fun delayMs(attempt: Int): Long = DELAYS_MS[attempt.coerceIn(0, DELAYS_MS.size - 1)]

    fun isStable(healthySince: Long?, now: Long): Boolean = healthySince != null && now - healthySince >= STABLE_PERIOD_MS

    /** The counter resets only after the Flow stayed healthy for the stable period. */
    fun effectiveCount(retryCount: Int, healthySince: Long?, now: Long): Int =
        if (isStable(healthySince, now)) 0 else retryCount.coerceAtLeast(0)
}

object ActivationStateMachine {

    /** Imported Flows are always Draft, whatever the file says (section 4.2). */
    fun initialImported(now: Long) =
        LifecycleSnapshot(LifecycleState.Draft, reason = LifecycleReason.Imported, since = now)

    /** True when the Flow is in Error and a transient retry is due. */
    fun retryDue(s: LifecycleSnapshot, now: Long): Boolean =
        s.state == LifecycleState.Error && s.nextRetryAt != null && now >= s.nextRetryAt

    /** True when a timed pause has ended. */
    fun resumeDue(s: LifecycleSnapshot, now: Long): Boolean =
        s.state == LifecycleState.Paused && s.pausedUntil != null && now >= s.pausedUntil

    fun reduce(s: LifecycleSnapshot, event: LifecycleEvent, now: Long): TransitionResult {
        if (event is LifecycleEvent.Delete) {
            return TransitionResult.Deleted(
                s,
                listOf(
                    LifecycleEffect.DisarmAll, LifecycleEffect.CancelRetry, LifecycleEffect.CancelResume,
                    LifecycleEffect.DropInboxRows("Deleted"), LifecycleEffect.ReassessEngine,
                ),
            )
        }
        return when (s.state) {
            LifecycleState.Draft -> fromDraft(s, event, now)
            LifecycleState.Published -> fromPublished(s, event, now)
            LifecycleState.Paused -> fromPaused(s, event, now)
            LifecycleState.Error -> fromError(s, event, now)
        }
    }

    // ---- Draft -------------------------------------------------------------------------------

    private fun fromDraft(s: LifecycleSnapshot, e: LifecycleEvent, now: Long): TransitionResult = when (e) {
        is LifecycleEvent.Publish -> guard(s, e.validationOk, e.failures) ?: move(
            s, e, now,
            to = LifecycleState.Published, reason = LifecycleReason.Published,
            snapshot = published(e.nextRevision, LifecycleReason.Published, now),
            effects = listOf(
                LifecycleEffect.SnapshotRevision(e.nextRevision), LifecycleEffect.ArmAll, LifecycleEffect.ReassessEngine,
            ),
        )
        is LifecycleEvent.ArmReport -> TransitionResult.Ignored(s)
        else -> TransitionResult.Illegal(s, e)
    }

    // ---- Published ---------------------------------------------------------------------------

    private fun fromPublished(s: LifecycleSnapshot, e: LifecycleEvent, now: Long): TransitionResult = when (e) {
        is LifecycleEvent.PublishChanges -> guard(s, e.validationOk, e.failures) ?: move(
            s, e, now,
            to = LifecycleState.Published, reason = LifecycleReason.PublishedChanges,
            snapshot = s.copy(publishedRevision = e.nextRevision, reason = LifecycleReason.PublishedChanges, since = now),
            effects = listOf(
                LifecycleEffect.SnapshotRevision(e.nextRevision), LifecycleEffect.SwapSparks, LifecycleEffect.ReassessEngine,
            ),
        )
        is LifecycleEvent.Pause -> pause(s, e, now)
        is LifecycleEvent.ArmReport ->
            if (e.failures.isEmpty()) TransitionResult.Ignored(s) else enterError(s, e, now)
        is LifecycleEvent.Unpublish -> unpublish(s, e, now)
        else -> TransitionResult.Illegal(s, e)
    }

    // ---- Paused ------------------------------------------------------------------------------

    private fun fromPaused(s: LifecycleSnapshot, e: LifecycleEvent, now: Long): TransitionResult = when (e) {
        is LifecycleEvent.Resume -> guard(s, e.validationOk, e.failures) ?: move(
            s, e, now,
            to = LifecycleState.Published, reason = LifecycleReason.Resumed,
            snapshot = s.copy(
                state = LifecycleState.Published, reason = LifecycleReason.Resumed, since = now,
                pausedUntil = null, errorCode = null, errorSince = null, retryCount = 0, nextRetryAt = null,
                healthySince = null,
            ),
            // The paused span is evidence, not a miss: its expected runs become Paused (section 4.2).
            effects = listOf(
                LifecycleEffect.CancelResume, LifecycleEffect.ArmAll,
                LifecycleEffect.MarkExpectedRunsPaused(from = s.since, to = now), LifecycleEffect.ReassessEngine,
            ),
        )
        is LifecycleEvent.Unpublish -> unpublish(s, e, now)
        is LifecycleEvent.ArmReport -> TransitionResult.Ignored(s)
        else -> TransitionResult.Illegal(s, e)
    }

    // ---- Error -------------------------------------------------------------------------------

    private fun fromError(s: LifecycleSnapshot, e: LifecycleEvent, now: Long): TransitionResult = when (e) {
        is LifecycleEvent.ArmReport ->
            if (e.failures.isEmpty()) recover(s, e, now) else stayInError(s, e, now)
        is LifecycleEvent.Pause -> pause(s, e, now)
        is LifecycleEvent.Unpublish -> unpublish(s, e, now)
        else -> TransitionResult.Illegal(s, e)
    }

    // ---- Shared pieces -----------------------------------------------------------------------

    private fun published(revision: Int, reason: LifecycleReason, now: Long) = LifecycleSnapshot(
        state = LifecycleState.Published, publishedRevision = revision, reason = reason, since = now,
    ) // A fresh publish starts with clean retry bookkeeping.

    private fun guard(s: LifecycleSnapshot, validationOk: Boolean, failures: List<RegistrationFailure>): TransitionResult? = when {
        !validationOk -> TransitionResult.Refused(s, RefusalReason.ValidationFailed)
        failures.isNotEmpty() -> TransitionResult.Refused(s, RefusalReason.ArmFailed, failures)
        else -> null
    }

    private fun move(
        s: LifecycleSnapshot,
        e: LifecycleEvent,
        now: Long,
        to: LifecycleState,
        reason: LifecycleReason,
        snapshot: LifecycleSnapshot,
        effects: List<LifecycleEffect>,
    ): TransitionResult =
        TransitionResult.Moved(snapshot, effects, LifecycleLogEntry(now, s.state, to, reason, e.actor))

    private fun pause(s: LifecycleSnapshot, e: LifecycleEvent.Pause, now: Long): TransitionResult {
        if (e.until != null && e.until <= now) return TransitionResult.Refused(s, RefusalReason.PauseInPast)
        val effects = buildList {
            add(LifecycleEffect.DisarmAll)
            add(LifecycleEffect.CancelRetry)
            if (e.until != null) add(LifecycleEffect.ScheduleResume(e.until))
            add(LifecycleEffect.ReassessEngine)
        }
        return move(
            s, e, now, LifecycleState.Paused, e.reason,
            s.copy(
                state = LifecycleState.Paused, reason = e.reason, since = now, pausedUntil = e.until,
                errorCode = null, errorSince = null, nextRetryAt = null, healthySince = null,
            ),
            effects,
        )
    }

    private fun unpublish(s: LifecycleSnapshot, e: LifecycleEvent.Unpublish, now: Long): TransitionResult {
        if (e.pendingInboxRows > 0 && !e.confirmed) return TransitionResult.Refused(s, RefusalReason.NeedsConfirmation)
        return move(
            s, e, now, LifecycleState.Draft, LifecycleReason.Unpublished,
            LifecycleSnapshot(LifecycleState.Draft, reason = LifecycleReason.Unpublished, since = now),
            listOf(
                LifecycleEffect.DisarmAll, LifecycleEffect.CancelRetry, LifecycleEffect.CancelResume,
                LifecycleEffect.DropInboxRows("Unpublished"), LifecycleEffect.ReassessEngine,
            ),
        )
    }

    /** The most informative failure: permanent ones first (they need the user), then the first reported. */
    private fun primary(failures: List<RegistrationFailure>) = failures.firstOrNull { it.code.permanent } ?: failures.first()

    private fun retryPlan(count: Int, failures: List<RegistrationFailure>, now: Long): Triple<Int, Long?, List<LifecycleEffect>> =
        if (failures.any { !it.code.permanent }) {
            val at = now + RetryBackoff.delayMs(count)
            Triple(count + 1, at, listOf(LifecycleEffect.ScheduleRetry(at)))
        } else {
            // Only permanent failures: wait for a user fix or a permission-changed signal.
            Triple(count, null, listOf(LifecycleEffect.CancelRetry))
        }

    private fun enterError(s: LifecycleSnapshot, e: LifecycleEvent.ArmReport, now: Long): TransitionResult {
        val code = primary(e.failures).code.name
        val base = RetryBackoff.effectiveCount(s.retryCount, s.healthySince, now)
        val (count, at, retryEffects) = retryPlan(base, e.failures, now)
        return move(
            s, e, now, LifecycleState.Error, LifecycleReason.ArmFailed,
            s.copy(
                state = LifecycleState.Error, reason = LifecycleReason.ArmFailed, since = now, errorCode = code,
                errorSince = now, retryCount = count, nextRetryAt = at, healthySince = null,
            ),
            listOf(LifecycleEffect.RecordFailures(e.failures)) + retryEffects + LifecycleEffect.NotifyEnteredError(code),
        )
    }

    private fun stayInError(s: LifecycleSnapshot, e: LifecycleEvent.ArmReport, now: Long): TransitionResult {
        val code = primary(e.failures).code.name
        val (count, at, retryEffects) = retryPlan(s.retryCount, e.failures, now)
        return TransitionResult.Moved(
            s.copy(errorCode = code, retryCount = count, nextRetryAt = at),
            listOf(LifecycleEffect.RecordFailures(e.failures)) + retryEffects,
            log = null,
        )
    }

    private fun recover(s: LifecycleSnapshot, e: LifecycleEvent.ArmReport, now: Long): TransitionResult {
        val longError = s.errorSince != null && now - s.errorSince > RetryBackoff.BACK_TO_NORMAL_THRESHOLD_MS
        val effects = buildList {
            add(LifecycleEffect.CancelRetry)
            add(LifecycleEffect.RecordFailures(emptyList()))
            if (longError) add(LifecycleEffect.NotifyBackToNormal)
            add(LifecycleEffect.ReassessEngine)
        }
        return move(
            s, e, now, LifecycleState.Published, LifecycleReason.Recovered,
            // retryCount is kept: it resets only once the Flow has stayed healthy for the stable period.
            s.copy(
                state = LifecycleState.Published, reason = LifecycleReason.Recovered, since = now,
                errorCode = null, errorSince = null, nextRetryAt = null, healthySince = now,
            ),
            effects,
        )
    }
}
