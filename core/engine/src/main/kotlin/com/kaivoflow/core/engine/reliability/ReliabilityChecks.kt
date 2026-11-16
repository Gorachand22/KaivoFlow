package com.kaivoflow.core.engine.reliability

import com.kaivoflow.core.engine.alwayson.EngineState
import com.kaivoflow.core.engine.alwayson.LivenessPolicy

/*
 * AO-20 (spec 5.3 and 5.4): the live checks C1 to C17 and the health score as pure functions of [DeviceFacts]. The Android
 * side only fills the facts. Principle: measured over guessed, and anything that cannot be read is Unknown, never Ok.
 */

enum class CheckState(val credit: Double) { Ok(1.0), Warn(0.5), Unknown(0.6), Fail(0.0), NotNeeded(0.0) }

enum class CheckGroup { Critical, Recommended, Informational }

/** The one tap that fixes a check. The UI maps each to a deep link with fallbacks (see `FixLauncher`). */
enum class FixAction {
    None, RestartEngine, BatteryExemption, NotificationSettings, ExactAlarm, AppDetails, ListenerSettings, SmsPermission,
    LocationPermission, OemAutostart, DataSaver, BatterySaver, DateSettings, WebhookSettings, StorageSettings, ReduceMemory,
    OemGuide,
}

data class CheckResult(
    val id: String,
    val title: String,
    val state: CheckState,
    val group: CheckGroup,
    val weight: Int,
    /** What breaks, in plain words (spec: say what breaks, not what the setting is called). */
    val plain: String,
    /** The measurement behind the state. Always set, so a green is always backed by a number or an API answer. */
    val evidence: String,
    val fix: FixAction = FixAction.None,
) {
    val needsAttention: Boolean get() = state == CheckState.Fail || state == CheckState.Warn
}

data class WebhookFacts(val bound: Boolean, val port: Int, val lan: Boolean, val lanAddress: String?, val probeOk: Boolean?)

/** Everything the checks read. No defaults on purpose: a forgotten fact must not quietly pass. */
data class DeviceFacts(
    val now: Long,
    val sdk: Int,
    // Usage: which checks apply.
    val publishedFlows: Int,
    val errorFlows: Int,
    val alwaysOnMode: Boolean,
    val usesExactSchedule: Boolean,
    val usesNotificationSpark: Boolean,
    val usesSmsSpark: Boolean,
    val usesSendSms: Boolean,
    val usesGeofence: Boolean,
    val usesWebhook: Boolean,
    // C1
    val heartbeatWall: Long?,
    val engineState: EngineState,
    // C2 to C5
    val batteryExempt: Boolean,
    val notificationsEnabled: Boolean,
    val engineChannelBlocked: Boolean,
    val exactAlarmAllowed: Boolean,
    val backgroundRestricted: Boolean,
    /** `UsageStatsManager` standby bucket constant (10 active, 20 working set, 30 frequent, 40 rare, 45 restricted), or null. */
    val standbyBucket: Int?,
    // C6, C7, C9
    val listenerGranted: Boolean,
    val listenerConnectedAt: Long?,
    val listenerDisconnectedAt: Long?,
    val receiveSmsGranted: Boolean,
    val sendSmsGranted: Boolean,
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val locationServicesOn: Boolean,
    // C8
    val flowPermissionGaps: List<String>,
    // C10
    val aggressiveOem: Boolean,
    val oemName: String,
    val oemAttested: Boolean,
    val soakPassedAt: Long?,
    // C11, C12, C14
    /** `ConnectivityManager.RESTRICT_BACKGROUND_STATUS_*`: 1 disabled, 2 whitelisted, 3 enabled. Null when unknown. */
    val dataSaverStatus: Int?,
    val batterySaverOn: Boolean,
    val autoTime: Boolean,
    val autoZone: Boolean,
    // C13, C17
    val exitInfoAvailable: Boolean,
    val forceStoppedAt: Long?,
    val lowMemoryKills7d: Int,
    // C15, C16
    val webhook: WebhookFacts?,
    val freeStorageBytes: Long,
    val databaseBytes: Long,
    val integrityOk: Boolean?,
) {
    val engineRelevant: Boolean get() = publishedFlows > 0 || alwaysOnMode
}

object CheckEvaluator {
    private const val DAY = 24 * 3_600_000L
    private const val LOW_STORAGE_BYTES = 200L * 1024 * 1024
    const val SOAK_VALID_MS = 30 * DAY

    fun evaluate(f: DeviceFacts): List<CheckResult> = listOf(
        c1(f), c2(f), c3(f), c4(f), c5(f), c6(f), c7(f), c8(f), c9(f), c10(f), c11(f), c12(f), c13(f), c14(f), c15(f), c16(f), c17(f),
    )

    /** Hidden checks (NotNeeded) are removed from the list the UI shows and from the score. */
    fun visible(all: List<CheckResult>) = all.filter { it.state != CheckState.NotNeeded }

    private fun r(id: String, title: String, state: CheckState, group: CheckGroup, weight: Int, plain: String, evidence: String, fix: FixAction = FixAction.None) =
        CheckResult(id, title, state, group, weight, plain, evidence, fix)

    private fun notNeeded(id: String, title: String, group: CheckGroup) =
        r(id, title, CheckState.NotNeeded, group, 0, "", "No published Flow uses this.")

    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 90 -> "$s s"
            s < 90 * 60 -> "${(s + 30) / 60} min"
            s < 48 * 3600 -> "${(s + 1800) / 3600} h"
            else -> "${s / 86400} days"
        }
    }

    private fun c1(f: DeviceFacts): CheckResult {
        val title = "Background engine"
        if (!f.engineRelevant) return notNeeded("C1", title, CheckGroup.Critical)
        val pulse = LivenessPolicy.PULSE_INTERVAL_MS
        val plainBase = "KaivoFlow's background engine is the part that listens for SMS, webhooks and notifications."
        if (f.engineState == EngineState.StartBlocked)
            return r("C1", title, CheckState.Fail, CheckGroup.Critical, 20, "$plainBase Android refused to start it.", "Engine state: start blocked", FixAction.RestartEngine)
        val beat = f.heartbeatWall
            ?: return r("C1", title, CheckState.Unknown, CheckGroup.Critical, 20, "$plainBase It has not reported in yet.", "No heartbeat recorded", FixAction.RestartEngine)
        val age = f.now - beat
        val state = when {
            age < 2 * pulse -> CheckState.Ok
            age < 4 * pulse -> CheckState.Warn
            else -> CheckState.Fail
        }
        return r("C1", title, state, CheckGroup.Critical, 20, "$plainBase It last reported in ${ago(age)} ago.", "Last heartbeat ${ago(age)} ago, state ${f.engineState.name}", if (state == CheckState.Ok) FixAction.None else FixAction.RestartEngine)
    }

    private fun c2(f: DeviceFacts): CheckResult {
        val t = "Battery optimisation"
        if (!f.engineRelevant) return notNeeded("C2", t, CheckGroup.Critical)
        return if (f.batteryExempt) r("C2", t, CheckState.Ok, CheckGroup.Critical, 20, "KaivoFlow is exempt from battery optimisation.", "isIgnoringBatteryOptimizations = true")
        else r("C2", t, CheckState.Fail, CheckGroup.Critical, 20, "Without this, Android cuts the network and pauses KaivoFlow whenever the screen is off for a while.", "isIgnoringBatteryOptimizations = false", FixAction.BatteryExemption)
    }

    private fun c3(f: DeviceFacts): CheckResult {
        val t = "Notifications"
        if (!f.engineRelevant) return notNeeded("C3", t, CheckGroup.Recommended)
        return if (f.notificationsEnabled && !f.engineChannelBlocked) r("C3", t, CheckState.Ok, CheckGroup.Recommended, 10, "Engine notice and problem alerts can be shown.", "Notifications on, engine channel visible")
        else r("C3", t, CheckState.Warn, CheckGroup.Recommended, 10, "Android may hide the engine notice. Flows still run, but you will not see problems.",
            if (!f.notificationsEnabled) "Notifications blocked for the app" else "Engine channel set to none", FixAction.NotificationSettings)
    }

    private fun c4(f: DeviceFacts): CheckResult {
        val t = "Exact alarms"
        if (!f.usesExactSchedule) return notNeeded("C4", t, CheckGroup.Critical)
        return if (f.exactAlarmAllowed) r("C4", t, CheckState.Ok, CheckGroup.Critical, 10, "Schedules fire at their exact time.", "canScheduleExactAlarms = true")
        else r("C4", t, CheckState.Fail, CheckGroup.Critical, 10, "Without this, \"every day at 08:00\" becomes \"somewhere around 08:00 to 09:00\".", "canScheduleExactAlarms = false", FixAction.ExactAlarm)
    }

    private fun c5(f: DeviceFacts): CheckResult {
        val t = "Background restrictions"
        if (!f.engineRelevant) return notNeeded("C5", t, CheckGroup.Critical)
        val plain = "Android has been told to restrict KaivoFlow in the background (or has parked it because it has not been opened for days)."
        val bucket = f.standbyBucket
        return when {
            f.backgroundRestricted -> r("C5", t, CheckState.Fail, CheckGroup.Critical, 10, plain, "isBackgroundRestricted = true", FixAction.AppDetails)
            bucket != null && bucket >= 45 -> r("C5", t, CheckState.Fail, CheckGroup.Critical, 10, plain, "Standby bucket restricted ($bucket)", FixAction.AppDetails)
            bucket != null && bucket >= 40 -> r("C5", t, CheckState.Warn, CheckGroup.Critical, 10, plain, "Standby bucket rare ($bucket). Open the app once to leave it.", FixAction.AppDetails)
            bucket == null -> r("C5", t, CheckState.Unknown, CheckGroup.Critical, 10, "Android does not say whether it parked KaivoFlow.", "Standby bucket not readable", FixAction.AppDetails)
            else -> r("C5", t, CheckState.Ok, CheckGroup.Critical, 10, "KaivoFlow is not restricted in the background.", "Not restricted, standby bucket $bucket")
        }
    }

    private fun c6(f: DeviceFacts): CheckResult {
        val t = "Notification listener"
        if (!f.usesNotificationSpark) return notNeeded("C6", t, CheckGroup.Critical)
        val plain = "KaivoFlow can only react to notifications while Android has connected it to the notification system."
        if (!f.listenerGranted) return r("C6", t, CheckState.Fail, CheckGroup.Critical, 5, plain, "Notification access not granted", FixAction.ListenerSettings)
        val up = f.listenerConnectedAt
            ?: return r("C6", t, CheckState.Unknown, CheckGroup.Critical, 5, plain, "Access granted, listener never connected", FixAction.ListenerSettings)
        val down = f.listenerDisconnectedAt
        return if (down != null && down > up) r("C6", t, CheckState.Fail, CheckGroup.Critical, 5, plain, "Disconnected ${ago(f.now - down)} ago and not reconnected", FixAction.ListenerSettings)
        else r("C6", t, CheckState.Ok, CheckGroup.Critical, 5, "KaivoFlow is connected to the notification system.", "Connected ${ago(f.now - up)} ago")
    }

    private fun c7(f: DeviceFacts): CheckResult {
        val t = "SMS permission"
        if (!f.usesSmsSpark && !f.usesSendSms) return notNeeded("C7", t, CheckGroup.Critical)
        val missing = buildList {
            if (f.usesSmsSpark && !f.receiveSmsGranted) add("receive")
            if (f.usesSendSms && !f.sendSmsGranted) add("send")
        }
        val note = if (f.sdk >= 36) " Some one-time-code messages may arrive up to 3 hours late." else ""
        return if (missing.isEmpty()) r("C7", t, CheckState.Ok, CheckGroup.Critical, 5, "SMS permission is granted.$note", "RECEIVE_SMS granted${if (f.usesSendSms) ", SEND_SMS granted" else ""}")
        else r("C7", t, CheckState.Fail, CheckGroup.Critical, 5, "Without this, an SMS Spark never sees a message.", "Missing: ${missing.joinToString()}", FixAction.SmsPermission)
    }

    private fun c8(f: DeviceFacts): CheckResult {
        val t = "Permissions per Flow"
        if (f.publishedFlows == 0) return notNeeded("C8", t, CheckGroup.Critical)
        return if (f.flowPermissionGaps.isEmpty()) r("C8", t, CheckState.Ok, CheckGroup.Critical, 0, "Every published Flow has the permissions it needs.", "No gaps in ${f.publishedFlows} published Flows")
        else r("C8", t, CheckState.Fail, CheckGroup.Critical, 0, f.flowPermissionGaps.first(), f.flowPermissionGaps.joinToString("; "), FixAction.AppDetails)
    }

    private fun c9(f: DeviceFacts): CheckResult {
        val t = "Background location"
        if (!f.usesGeofence) return notNeeded("C9", t, CheckGroup.Critical)
        val plain = "Geofences stop firing when location is limited to \"while using the app\"."
        return when {
            !f.fineLocation -> r("C9", t, CheckState.Fail, CheckGroup.Critical, 5, plain, "Fine location not granted", FixAction.LocationPermission)
            !f.backgroundLocation -> r("C9", t, CheckState.Fail, CheckGroup.Critical, 5, plain, "Background location (\"all the time\") not granted", FixAction.LocationPermission)
            !f.locationServicesOn -> r("C9", t, CheckState.Fail, CheckGroup.Critical, 5, "Location is switched off on this phone, so geofences cannot fire.", "Location services off", FixAction.LocationPermission)
            else -> r("C9", t, CheckState.Ok, CheckGroup.Critical, 5, "Location access allows geofences in the background.", "Fine and background location granted, location on")
        }
    }

    private fun c10(f: DeviceFacts): CheckResult {
        val t = "${f.oemName} autostart and background launch"
        if (!f.engineRelevant || !f.aggressiveOem) return notNeeded("C10", t, CheckGroup.Recommended)
        val plain = "${f.oemName} phones often stop apps in the background even with every Android setting correct. KaivoFlow cannot read this setting."
        val soak = f.soakPassedAt?.takeIf { f.now - it <= SOAK_VALID_MS }
        return if (f.oemAttested && soak != null)
            r("C10", t, CheckState.Ok, CheckGroup.Recommended, 5, "You set it up and the overnight test proved it works.", "Attested, soak test passed ${ago(f.now - soak)} ago", FixAction.OemGuide)
        else r("C10", t, CheckState.Unknown, CheckGroup.Recommended, 5, plain,
            when {
                f.oemAttested -> "You said you did this. Not yet proven by the overnight test."
                soak != null -> "Soak test passed, but the setting is not confirmed by you."
                else -> "Cannot be read. Not confirmed, not tested."
            }, FixAction.OemAutostart)
    }

    private fun c11(f: DeviceFacts): CheckResult {
        val t = "Data Saver"
        if (!f.engineRelevant) return notNeeded("C11", t, CheckGroup.Recommended)
        return when (f.dataSaverStatus) {
            3 -> r("C11", t, CheckState.Warn, CheckGroup.Recommended, 3, "Data Saver blocks network for background Flows.", "restrictBackgroundStatus = enabled", FixAction.DataSaver)
            1, 2 -> r("C11", t, CheckState.Ok, CheckGroup.Recommended, 3, "Background data is allowed.", "restrictBackgroundStatus = ${if (f.dataSaverStatus == 1) "disabled" else "whitelisted"}")
            else -> r("C11", t, CheckState.Unknown, CheckGroup.Recommended, 3, "Android does not say whether Data Saver affects KaivoFlow.", "Not readable", FixAction.DataSaver)
        }
    }

    private fun c12(f: DeviceFacts): CheckResult {
        val t = "Battery saver"
        if (!f.engineRelevant) return notNeeded("C12", t, CheckGroup.Recommended)
        return if (f.batterySaverOn) r("C12", t, CheckState.Warn, CheckGroup.Recommended, 2, "Battery saver slows or stops background work.", "isPowerSaveMode = true", FixAction.BatterySaver)
        else r("C12", t, CheckState.Ok, CheckGroup.Recommended, 2, "Battery saver is off.", "isPowerSaveMode = false")
    }

    private fun c13(f: DeviceFacts): CheckResult {
        val t = "Force-stop history"
        if (!f.engineRelevant) return notNeeded("C13", t, CheckGroup.Informational)
        if (!f.exitInfoAvailable) return r("C13", t, CheckState.Unknown, CheckGroup.Informational, 0, "This Android version does not keep a record of force-stops.", "ApplicationExitInfo needs Android 11")
        val at = f.forceStoppedAt
        return if (at != null && f.now - at <= 7 * DAY)
            r("C13", t, CheckState.Warn, CheckGroup.Informational, 0, "KaivoFlow was force-stopped ${ago(f.now - at)} ago. Nothing ran until you opened it.", "Force-stop recorded ${ago(f.now - at)} ago", FixAction.OemGuide)
        else r("C13", t, CheckState.Ok, CheckGroup.Informational, 0, "No force-stop in the last 7 days.", "No force-stop recorded in 7 days")
    }

    private fun c14(f: DeviceFacts): CheckResult {
        val t = "Time and time zone"
        if (!f.engineRelevant) return notNeeded("C14", t, CheckGroup.Recommended)
        return if (f.autoTime && f.autoZone) r("C14", t, CheckState.Ok, CheckGroup.Recommended, 3, "The clock and zone are set by the network.", "AUTO_TIME and AUTO_TIME_ZONE on")
        else r("C14", t, CheckState.Warn, CheckGroup.Recommended, 3, "A wrong clock means schedules fire at the wrong time.", "AUTO_TIME=${f.autoTime}, AUTO_TIME_ZONE=${f.autoZone}", FixAction.DateSettings)
    }

    private fun c15(f: DeviceFacts): CheckResult {
        val t = "Webhook listener"
        val w = f.webhook
        if (!f.usesWebhook || w == null) return notNeeded("C15", t, CheckGroup.Informational)
        if (!w.bound) return r("C15", t, CheckState.Fail, CheckGroup.Informational, 0, "The webhook listener is not running, so webhook Flows cannot be triggered.", "Port ${w.port} not bound", FixAction.WebhookSettings)
        val reach = if (w.lan) "Reachable on ${w.lanAddress ?: "the LAN"}:${w.port} (same Wi-Fi only)." else "Your webhook is reachable only from this phone (loopback)."
        val probe = when (w.probeOk) { true -> "probe ok"; false -> "probe failed"; null -> "no probe yet" }
        val state = when (w.probeOk) { false -> CheckState.Warn; null -> CheckState.Unknown; true -> CheckState.Ok }
        return r("C15", t, state, CheckGroup.Informational, 0, reach, "Bound on port ${w.port}, ${if (w.lan) "LAN" else "loopback"}, $probe", FixAction.WebhookSettings)
    }

    private fun c16(f: DeviceFacts): CheckResult {
        val t = "Storage and database"
        if (!f.engineRelevant) return notNeeded("C16", t, CheckGroup.Recommended)
        val low = f.freeStorageBytes < LOW_STORAGE_BYTES
        val state = when { f.integrityOk == false -> CheckState.Fail; low -> CheckState.Warn; f.integrityOk == null -> CheckState.Unknown; else -> CheckState.Ok }
        return r("C16", t, state, CheckGroup.Recommended, 2, if (low) "Low storage can stop KaivoFlow from recording events." else "Enough free storage to record events.",
            "Free ${f.freeStorageBytes / (1024 * 1024)} MB, database ${f.databaseBytes / 1024} KB, integrity ${f.integrityOk ?: "not checked"}",
            if (state == CheckState.Ok) FixAction.None else FixAction.StorageSettings)
    }

    private fun c17(f: DeviceFacts): CheckResult {
        val t = "Memory kills"
        if (!f.engineRelevant) return notNeeded("C17", t, CheckGroup.Informational)
        if (!f.exitInfoAvailable) return r("C17", t, CheckState.Unknown, CheckGroup.Informational, 0, "This Android version does not record why apps were ended.", "ApplicationExitInfo needs Android 11")
        return if (f.lowMemoryKills7d > 0) r("C17", t, CheckState.Warn, CheckGroup.Informational, 0, "Android ended KaivoFlow ${f.lowMemoryKills7d} ${if (f.lowMemoryKills7d == 1) "time" else "times"} this week to free memory.", "${f.lowMemoryKills7d} low-memory exits in 7 days", FixAction.ReduceMemory)
        else r("C17", t, CheckState.Ok, CheckGroup.Informational, 0, "No memory kills in the last 7 days.", "0 low-memory exits in 7 days")
    }
}

data class MeasuredFacts(val expectedRuns14d: Int, val onTimeRatio: Double, val engineDeadIncidents7d: Int, val missedRuns7d: Int)

enum class HealthBand(val label: String) { Reliable("Reliable"), MostlyReliable("Mostly reliable"), AtRisk("At risk"), Unreliable("Unreliable") }

data class HealthScore(
    val score: Int,
    val band: HealthBand,
    val configScore: Int,
    val measuredScore: Int?,
    /** The cap that decided the final value, or null. */
    val capReason: String?,
    val sentence: String,
)

object HealthScorer {
    const val MIN_EXPECTED_FOR_MEASUREMENT = 10
    private const val NOT_PROVEN_CAP = 85

    fun score(all: List<CheckResult>, measured: MeasuredFacts?): HealthScore {
        val applicable = CheckEvaluator.visible(all).filter { it.weight > 0 }
        val totalWeight = applicable.sumOf { it.weight }
        val config = if (totalWeight == 0) 100.0 else 100.0 * applicable.sumOf { it.weight * it.state.credit } / totalWeight
        val usable = measured?.takeIf { it.expectedRuns14d >= MIN_EXPECTED_FOR_MEASUREMENT }
        val measuredScore = usable?.let {
            (100 * it.onTimeRatio - 15 * minOf(it.engineDeadIncidents7d, 3) - 10 * minOf(it.missedRuns7d, 3)).coerceIn(0.0, 100.0)
        }
        var final = if (measuredScore == null) minOf(config, NOT_PROVEN_CAP.toDouble()) else minOf(config, measuredScore)
        var cap: String? = if (measuredScore == null && config > NOT_PROVEN_CAP) "Not yet proven by real runs" else null

        fun capAt(limit: Int, why: String) { if (final > limit) { final = limit.toDouble(); cap = why } }
        val byId = all.associateBy { it.id }
        if (byId["C1"]?.state == CheckState.Fail) capAt(30, "The background engine is not reporting")
        if (byId["C1"]?.evidence?.contains("start blocked") == true) capAt(40, "Android refuses to start the engine")
        val oem = byId["C10"]
        if (oem != null && oem.state == CheckState.Unknown) capAt(85, "${oem.title} is not verified")

        val s = Math.round(final).toInt().coerceIn(0, 100)
        val band = bandFor(s)
        return HealthScore(s, band, Math.round(config).toInt(), measuredScore?.let { Math.round(it).toInt() }, cap, sentence(band, all))
    }

    fun bandFor(score: Int) = when {
        score >= 90 -> HealthBand.Reliable
        score >= 70 -> HealthBand.MostlyReliable
        score >= 40 -> HealthBand.AtRisk
        else -> HealthBand.Unreliable
    }

    /** The top [n] checks to fix, by weight lost. */
    fun fixFirst(all: List<CheckResult>, n: Int = 3): List<CheckResult> =
        CheckEvaluator.visible(all).filter { it.state == CheckState.Fail || it.state == CheckState.Warn || (it.state == CheckState.Unknown && it.fix != FixAction.None) }
            .sortedWith(compareByDescending<CheckResult> { it.weight * (1.0 - it.state.credit) }.thenBy { it.id.drop(1).toInt() })
            .take(n)

    /** Home banner rule (spec 4.4): show it below 70. */
    fun showHomeBanner(score: HealthScore) = score.score < 70

    private fun sentence(band: HealthBand, all: List<CheckResult>): String = when (band) {
        HealthBand.Reliable -> "Your Flows should run on time, even with the screen off."
        HealthBand.MostlyReliable -> "Mostly fine. A few settings could make Flows more dependable."
        HealthBand.AtRisk -> "At risk: Android may pause your Flows when the screen is off."
        HealthBand.Unreliable -> "Unreliable: your Flows are likely to miss runs until the red items are fixed."
    }
}
