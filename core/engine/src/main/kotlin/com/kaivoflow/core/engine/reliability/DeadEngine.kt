package com.kaivoflow.core.engine.reliability

import com.kaivoflow.core.engine.alwayson.LivenessPolicy

/*
 * AO-18 (spec 6.1 and 6.2): after-the-fact dead-engine detection, ApplicationExitInfo reason mapping and the alert text.
 */

/** The facts compared at any start of the Engine, the app or the Reconciler. */
data class HeartbeatFacts(
    val lastBeatWall: Long?,
    val previousSessionClean: Boolean,
    val previousSessionId: Long?,
    val bootCountThen: Int?,
    val bootCountNow: Int?,
)

data class EngineOutage(val from: Long, val to: Long, val reason: String)

object DeadEngineDetector {
    /**
     * An outage exists when the gap since the last beat exceeds the dead threshold and the last session did not stop
     * cleanly. A reboot (boot count changed) explains the gap: it is still reported, as reason `Reboot`, because the Flows
     * did not run in it, but it is not alarming. Screen state is unknown after the fact, so the longer threshold is used.
     */
    fun detect(f: HeartbeatFacts, now: Long, exitReason: String? = null): EngineOutage? {
        val last = f.lastBeatWall ?: return null
        if (f.previousSessionClean) return null
        if (now - last <= LivenessPolicy.DEAD_AFTER_SCREEN_OFF_MS) return null
        val rebooted = f.bootCountThen != null && f.bootCountNow != null && f.bootCountThen != f.bootCountNow
        return EngineOutage(last, now, if (rebooted) "Reboot" else exitReason ?: "Unknown")
    }
}

/** `ApplicationExitInfo.REASON_*` values (API 30+ constants, copied so this stays Android-free). */
object ExitReasons {
    fun name(reason: Int): String = when (reason) {
        1 -> "ExitSelf"
        2 -> "Signaled"
        3 -> "LowMemory"
        4 -> "Crash"
        5 -> "CrashNative"
        6 -> "Anr"
        7 -> "InitializationFailure"
        8 -> "PermissionChange"
        9 -> "ExcessiveResourceUsage"
        10 -> "UserRequested"
        11 -> "UserStopped"
        12 -> "DependencyDied"
        13 -> "Other"
        14 -> "Freezer"
        15 -> "PackageStateChange"
        16 -> "PackageUpdated"
        else -> "Unknown($reason)"
    }

    /** Plain language for the Event log and the alert. */
    fun plain(name: String): String = when (name) {
        "LowMemory" -> "Android ended KaivoFlow to free memory"
        "Crash", "CrashNative" -> "KaivoFlow crashed"
        "Anr" -> "KaivoFlow stopped responding"
        "UserRequested", "UserStopped" -> "KaivoFlow was force-stopped"
        "ExcessiveResourceUsage" -> "Android stopped KaivoFlow for using too many resources"
        "PackageUpdated", "PackageStateChange" -> "KaivoFlow was updated"
        "Freezer" -> "Android froze KaivoFlow"
        "Reboot" -> "the phone restarted"
        else -> "Android ended KaivoFlow"
    }

    val forceStopped = setOf("UserRequested", "UserStopped")
}

/** The user-visible alert (spec 6.2). One per kind per hour is enforced by `AlertThrottle`. */
object OutageAlert {
    data class Text(val title: String, val body: String, val high: Boolean)

    fun build(outage: EngineOutage, missed: Int, caughtUp: Int, time: (Long) -> String): Text {
        val window = "from ${time(outage.from)} to ${time(outage.to)}"
        val body = if (missed > 0) {
            "KaivoFlow's engine was not running $window. $missed scheduled ${plural(missed, "run was", "runs were")} missed" +
                if (caughtUp > 0) ", $caughtUp ${plural(caughtUp, "was", "were")} caught up." else "."
        } else "KaivoFlow's engine was not running $window. No scheduled runs were missed."
        return Text("KaivoFlow engine was not running", body, high = missed > 0)
    }

    /** Never alert for a clean stop or a reboot that restored by itself with nothing missed. */
    fun shouldAlert(outage: EngineOutage, missed: Int): Boolean = outage.reason != "Reboot" || missed > 0

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many
}

/** How many scheduled runs an outage cost, from the `expected_run` rows whose due time fell inside it. */
object OutageImpact {
    data class Row(val dueAt: Long, val state: String, val reason: String?)
    data class Count(val missed: Int, val caughtUp: Int)

    /** Caught up = the misfire policy ran it late. Missed = never ran, or ran only as a catch-up, or was dropped as too late. */
    fun count(rows: List<Row>, outage: EngineOutage): Count {
        val inGap = rows.filter { it.dueAt in outage.from..outage.to && it.state != "PAUSED" && it.state != "SUPERSEDED" && it.state != "PENDING" }
        val caught = inGap.count { it.reason == "CaughtUp" }
        val missed = inGap.count { it.state == "MISSED" || it.state == "SKIPPED" || it.reason == "CaughtUp" }
        return Count(missed, caught)
    }
}

/** Plain-language reasons for a Flow card in Error (spec 4.4). */
object FailureText {
    fun plain(code: String?): String = when (code) {
        "PermissionMissing" -> "A permission it needs was turned off."
        "InvalidConfig" -> "One of its Sparks has a setting that cannot work."
        "CredentialMissing" -> "A credential it uses is gone."
        "AlarmRefused" -> "Android refused to set its alarm."
        "PortInUse" -> "The webhook port is used by another app."
        "ListenerUnbound" -> "Android disconnected the notification listener."
        "NetworkCallbackRejected" -> "Android refused the network watcher."
        "GeofenceRejected" -> "Android refused the location trigger."
        "EngineStartBlocked" -> "Android blocked the background engine from starting."
        else -> "A Spark could not be armed."
    }

    /** "Retrying in 5 min" or "Waiting for you to fix it" for a permanent failure. */
    fun retryText(nextRetryAt: Long?, now: Long): String {
        if (nextRetryAt == null) return "Waiting for you to fix it"
        val s = ((nextRetryAt - now) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "Retrying in $s s"
            s < 3600 -> "Retrying in ${(s + 30) / 60} min"
            else -> "Retrying in ${(s + 1800) / 3600} h"
        }
    }
}
