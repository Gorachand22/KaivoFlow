package com.kaivoflow.core.engine.alwayson

/*
 * Android-free building blocks of the Always-On Engine (docs/specs/P2-always-on-engine.md, M1).
 * Everything that decides what happens lives in this package and is unit-tested on the JVM; the
 * Android classes in core:work are thin adapters over it.
 */

/** Wall clock, injectable so lease, grace and lateness rules are testable with a fake. */
fun interface Clock { fun now(): Long }

object WallClock : Clock { override fun now(): Long = System.currentTimeMillis() }

/** Event kinds written to `reliability_event.kind` by the Engine (spec sections 3.10 and 6). */
object ReliabilityKinds {
    const val FGS_START_DENIED = "FgsStartDenied"
    const val EXACT_ALARM_DENIED = "ExactAlarmDenied"
    const val ENGINE_RESTART = "EngineRestart"
    const val TASK_REMOVED = "TaskRemoved"
    const val BOOT_RECOVERED = "BootRecovered"
    const val PACKAGE_REPLACED = "PackageReplaced"
    const val CLOCK_CHANGED = "ClockChanged"
    const val SERVICE_TIMEOUT = "ServiceTimeout"
    const val INBOX_DUPLICATE = "InboxDuplicateDropped"
    const val INBOX_RUN_INTERRUPTED = "InboxRunInterrupted"
    const val SCHEDULE_CAUGHT_UP = "ScheduleCaughtUp"
    const val SCHEDULE_SKIPPED = "ScheduleSkipped"
    const val RECONCILE_FAILED = "ReconcileFailed"
    const val WAKELOCK_TIMEOUT = "WakeLockTimeout"
    const val RUN_CRASHED = "RunCrashed"
    const val WEBHOOK_BIND_FAILED = "WebhookBindFailed"
    const val WEBHOOK_REACH_CHANGED = "WebhookReachChanged"
    const val LISTENER_DISCONNECTED = "ListenerDisconnected"
    const val LISTENER_RECONNECTED = "ListenerReconnected"
    const val LISTENER_REBIND_REQUESTED = "ListenerRebindRequested"
    const val SPARK_REGISTRATION_FAILED = "SparkRegistrationFailed"
    const val FGS_START_SKIPPED = "FgsStartSkipped"
}

object Severity { const val INFO = "info"; const val WARN = "warn"; const val ERROR = "error" }

/** Why the Engine or a Reconciler pass was started. Stored as text in `engine_session.startReason`. */
enum class StartReason {
    UserOpen, Publish, Boot, PackageReplaced, TimeChanged, Pulse, Watchdog, Alarm, StickyRestart,
    RestartAlarm, IntakeEvent, Reassess, UserTap, ExactAlarmPermission,
    /** A geofence transition: a platform-listed foreground-start exemption. */
    Geofence;
}

/**
 * Allows at most one alert per key per [intervalMs] (spec 6.2: one notification per kind per hour).
 * The caller persists nothing; a process restart may repeat an alert once, which is acceptable.
 */
class AlertThrottle(private val clock: Clock, private val intervalMs: Long = 60 * 60_000L) {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock.now()
        val previous = last[key]
        if (previous != null && now - previous in 0 until intervalMs) return false
        last[key] = now
        return true
    }
}

/** Liveness thresholds from spec 6.1 and 6.2. */
object LivenessPolicy {
    const val PULSE_INTERVAL_MS = 10 * 60_000L
    const val WATCHDOG_INTERVAL_MS = 15 * 60_000L
    const val DEAD_AFTER_SCREEN_OFF_MS = 25 * 60_000L
    const val DEAD_AFTER_SCREEN_ON_MS = 3 * 60_000L

    /** Jitter the system may add to an inexact job or a while-idle alarm. */
    const val SCHEDULING_SLACK_MS = 5 * 60_000L

    /** AO-09 acceptance: a dead Engine is restarted within 16 minutes. */
    const val RESTART_BOUND_MS = 16 * 60_000L

    /** Two independent safety nets, so the slower of the two never matters: the faster one plus slack. */
    fun worstCaseRestartMs(): Long = minOf(PULSE_INTERVAL_MS, WATCHDOG_INTERVAL_MS) + SCHEDULING_SLACK_MS

    fun isStale(lastBeatWall: Long?, now: Long, screenOn: Boolean): Boolean {
        if (lastBeatWall == null) return true
        val limit = if (screenOn) DEAD_AFTER_SCREEN_ON_MS else DEAD_AFTER_SCREEN_OFF_MS
        return now - lastBeatWall > limit
    }
}

/** Engine states of spec 3.3. */
enum class EngineState { Stopped, Starting, Running, Degraded, DeadSuspected, StartBlocked }

sealed interface EngineEvent {
    data object StartRequested : EngineEvent
    data object ServiceStarted : EngineEvent
    data class StartDenied(val reason: String) : EngineEvent
    data object StopRequested : EngineEvent
    /** [clean] is true for a deliberate stop. A false value means the process or service died. */
    data class ServiceStopped(val clean: Boolean) : EngineEvent
    data class DegradedReported(val degraded: Boolean) : EngineEvent
    data object HeartbeatStale : EngineEvent
}

object EngineStateMachine {
    fun reduce(state: EngineState, event: EngineEvent): EngineState = when (event) {
        EngineEvent.StartRequested -> when (state) {
            EngineState.Stopped, EngineState.StartBlocked, EngineState.DeadSuspected -> EngineState.Starting
            else -> state
        }
        EngineEvent.ServiceStarted -> if (state == EngineState.Degraded) state else EngineState.Running
        is EngineEvent.StartDenied -> when (state) {
            EngineState.Running, EngineState.Degraded -> state
            else -> EngineState.StartBlocked
        }
        EngineEvent.StopRequested -> EngineState.Stopped
        is EngineEvent.ServiceStopped -> when {
            event.clean -> EngineState.Stopped
            state == EngineState.Stopped -> state
            else -> EngineState.DeadSuspected
        }
        is EngineEvent.DegradedReported -> when {
            state == EngineState.Running && event.degraded -> EngineState.Degraded
            state == EngineState.Degraded && !event.degraded -> EngineState.Running
            else -> state
        }
        EngineEvent.HeartbeatStale -> when (state) {
            EngineState.Running, EngineState.Degraded -> EngineState.DeadSuspected
            else -> state
        }
    }
}

enum class EngineAction { Start, Stop, None }

object EngineDecision {
    /**
     * [needed] comes from `EngineNeeds.compute`. [transientHold] keeps a short-lived session (started by
     * an alarm for a schedule Run) alive until its Inbox work is done, even when no Spark needs the Engine.
     */
    fun decide(needed: Boolean, state: EngineState, transientHold: Boolean = false): EngineAction = when {
        needed -> when (state) {
            EngineState.Stopped, EngineState.DeadSuspected, EngineState.StartBlocked -> EngineAction.Start
            else -> EngineAction.None
        }
        transientHold -> EngineAction.None
        state == EngineState.Running || state == EngineState.Degraded -> EngineAction.Stop
        else -> EngineAction.None
    }
}

/** Which parts of the repair routine each trigger-in runs (spec 3.2, 3.9 and 3.10). */
enum class ReconcileTrigger {
    AppStart, Boot, PackageReplaced, TimeChanged, TimezoneChanged, Pulse, Watchdog, ServiceStart,
    ExactAlarmPermissionChanged, FlowChanged, UserFix, RoutesChanged;
}

data class ReconcileScope(
    /** Re-set every alarm (idempotent: same PendingIntent identity replaces). */
    val rearmAlarms: Boolean = true,
    /** Ignore stored next-due times (clock or zone changed) and supersede slots computed under the old clock. */
    val recomputeSchedules: Boolean = false,
    val applyMisfire: Boolean = true,
    /** Geofences, network callbacks, webhook routes and other registrars. */
    val rearmRegistrations: Boolean = true,
    val ensureEngine: Boolean = true,
    val dispatchBootSpark: Boolean = false,
    val eventKind: String? = null,
)

object ReconcilePolicy {
    fun scopeFor(trigger: ReconcileTrigger): ReconcileScope = when (trigger) {
        ReconcileTrigger.Boot -> ReconcileScope(dispatchBootSpark = true, eventKind = ReliabilityKinds.BOOT_RECOVERED)
        ReconcileTrigger.PackageReplaced -> ReconcileScope(eventKind = ReliabilityKinds.PACKAGE_REPLACED)
        ReconcileTrigger.TimeChanged, ReconcileTrigger.TimezoneChanged ->
            ReconcileScope(recomputeSchedules = true, applyMisfire = false, eventKind = ReliabilityKinds.CLOCK_CHANGED)
        ReconcileTrigger.RoutesChanged -> ReconcileScope(rearmAlarms = false, applyMisfire = false)
        else -> ReconcileScope()
    }
}
