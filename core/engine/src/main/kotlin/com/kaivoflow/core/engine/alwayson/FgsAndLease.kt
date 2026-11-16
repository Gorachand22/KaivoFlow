package com.kaivoflow.core.engine.alwayson

/*
 * Two small rules the Android layer must not get wrong:
 *  - when a foreground service may be started from the background (spec 3.5, R8),
 *  - what happens to a Run whose process died (spec 3.1 rule 5 and 3.11, AO-15).
 */

/** How a start request relates to the platform's background-start restrictions. */
enum class StartClass {
    /** The platform lists the trigger as an exemption (user interaction, boot, update, time change, exact alarm, geofence). */
    Exempt,
    /** Only works while the app holds the battery-optimisation exemption, which also exempts background starts. */
    NeedsBatteryExemption,
    /** Never attempted: SMS, notification, Wi-Fi, Bluetooth, webhook, generic reassess. Write the Inbox row and use the fallback worker. */
    Forbidden,
    /** The system restarts a sticky service itself; the app does not call `startForegroundService`. */
    SystemOwned,
}

object FgsStartPolicy {
    fun classify(reason: StartReason): StartClass = when (reason) {
        StartReason.UserOpen, StartReason.UserTap, StartReason.Publish,
        StartReason.Boot, StartReason.PackageReplaced, StartReason.TimeChanged,
        StartReason.Alarm, StartReason.RestartAlarm, StartReason.Pulse, StartReason.Geofence -> StartClass.Exempt
        StartReason.Watchdog, StartReason.ExactAlarmPermission -> StartClass.NeedsBatteryExemption
        StartReason.StickyRestart -> StartClass.SystemOwned
        StartReason.IntakeEvent, StartReason.Reassess -> StartClass.Forbidden
    }

    /** May the app call `startForegroundService` for [reason] given the battery exemption it holds right now? */
    fun mayStart(reason: StartReason, batteryExempt: Boolean): Boolean = when (classify(reason)) {
        StartClass.Exempt -> true
        StartClass.NeedsBatteryExemption -> batteryExempt
        StartClass.Forbidden, StartClass.SystemOwned -> false
    }
}

/** A started Run whose lease ran out: the process died mid-Run. Never replayed (side effects such as SMS must not repeat). */
data class CrashedClaim(val record: InboxRecord, val detectedAt: Long) {
    val runId: String? get() = record.runId
    val leaseExpiredAt: Long? get() = record.leaseUntil

    /** Stored in the Run record's `error` and read by the Run log. */
    fun message(): String = RunCrash.message(runId, leaseExpiredAt, detectedAt)
}

object RunCrash {
    const val DROP_REASON = "Crashed"
    const val ERROR_PREFIX = "Crashed:"

    fun message(runId: String?, leaseExpiredAt: Long?, detectedAt: Long): String {
        val late = leaseExpiredAt?.let { " Detected ${((detectedAt - it).coerceAtLeast(0)) / 1000} s after the lease ended." }.orEmpty()
        return "$ERROR_PREFIX the app or the device stopped during this Run, so it was not repeated.$late"
    }

    fun isCrashMessage(error: String?): Boolean = error?.startsWith(ERROR_PREFIX) == true

    /** The rows that are crashed claims at [now], by the one lease rule. */
    fun find(rows: List<InboxRecord>, now: Long): List<CrashedClaim> =
        rows.filter { LeasePolicy.isCrashed(it.state, it.leaseUntil, it.runId, now) }.map { CrashedClaim(it, now) }
}
