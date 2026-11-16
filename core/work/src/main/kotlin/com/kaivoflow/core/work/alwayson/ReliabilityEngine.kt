package com.kaivoflow.core.work.alwayson

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.kaivoflow.core.data.EngineHeartbeatEntity
import com.kaivoflow.core.data.EngineTelemetryRepository
import com.kaivoflow.core.data.ExpectedRunDailyEntity
import com.kaivoflow.core.data.ExpectedRunState
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.ScheduleRepository
import com.kaivoflow.core.data.SparkInboxRepository
import com.kaivoflow.core.engine.alwayson.AlertThrottle
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.ListenerTracker
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.WakeLockLedger
import com.kaivoflow.core.engine.reliability.DeadEngineDetector
import com.kaivoflow.core.engine.reliability.EngineOutage
import com.kaivoflow.core.engine.reliability.ExitReasons
import com.kaivoflow.core.engine.reliability.ExpectedSample
import com.kaivoflow.core.engine.reliability.FailureText
import com.kaivoflow.core.engine.reliability.HeartbeatFacts
import com.kaivoflow.core.engine.reliability.MissedRule
import com.kaivoflow.core.engine.reliability.OutageAlert
import com.kaivoflow.core.engine.reliability.OutageImpact
import com.kaivoflow.core.engine.reliability.RetentionPolicy
import com.kaivoflow.core.engine.reliability.RunAggregation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/** Small switches and facts of the Reliability Center that must survive a restart. SharedPreferences: per-install, tiny. */
@Singleton
class ReliabilitySettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("kaivo.reliability", Context.MODE_PRIVATE)

    /** The user tapped "I did this" for the OEM autostart step (C10). Not proof by itself. */
    var oemAttestedAt: Long
        get() = prefs.getLong("oemAttestedAt", 0L)
        set(v) { prefs.edit().putLong("oemAttestedAt", v).apply() }

    /** A passed overnight soak (ST7). Upgrades C10 for 30 days. */
    var soakPassedAt: Long
        get() = prefs.getLong("soakPassedAt", 0L)
        set(v) { prefs.edit().putLong("soakPassedAt", v).apply() }

    var lastExitTimestamp: Long
        get() = prefs.getLong("lastExitTs", 0L)
        set(v) { prefs.edit().putLong("lastExitTs", v).apply() }

    var lastForceStopAt: Long
        get() = prefs.getLong("lastForceStopAt", 0L)
        set(v) { prefs.edit().putLong("lastForceStopAt", v).apply() }

    var lastCompactionAt: Long
        get() = prefs.getLong("lastCompactionAt", 0L)
        set(v) { prefs.edit().putLong("lastCompactionAt", v).apply() }

    var lastIntegrityOkAt: Long
        get() = prefs.getLong("lastIntegrityOkAt", 0L)
        set(v) { prefs.edit().putLong("lastIntegrityOkAt", v).apply() }

    /** "Restored N Flows after restart" quiet notification (spec 4.4), default on. */
    /** Last loopback webhook probe (ST4): true, false, or null when never run. */
    var webhookProbeOk: Boolean?
        get() = prefs.getInt("webhookProbe", -1).let { if (it < 0) null else it == 1 }
        set(v) { prefs.edit().putInt("webhookProbe", when (v) { null -> -1; true -> 1; false -> 0 }).apply() }

    var notifyRestored: Boolean
        get() = prefs.getBoolean("notifyRestored", true)
        set(v) { prefs.edit().putBoolean("notifyRestored", v).apply() }
}

/**
 * AO-18: the single `engine_heartbeat` row (spec 6.1). Written on every wake that passes through here (Reconciler pass, schedule
 * fire, Inbox intake, the 30 second ticker while the screen is on or the phone is charging, the watchdog). Writes closer than
 * [MIN_GAP_MS] are skipped, so an event storm does not turn into a database storm.
 */
@Singleton
class HeartbeatWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: EngineTelemetryRepository,
    private val runtime: EngineRuntime,
    private val inbox: SparkInboxRepository,
    private val schedules: ScheduleRepository,
    private val ledger: WakeLockLedger,
    private val listener: ListenerTracker,
    private val clock: Clock,
) {
    @Volatile private var lastWritten = 0L
    private val processStartWall: Long = clock.now() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())

    suspend fun beat(force: Boolean = false) {
        val now = clock.now()
        if (!force && now - lastWritten in 0 until MIN_GAP_MS) return
        lastWritten = now
        runCatching {
            val l = listener.snapshot()
            val states = schedules.allStates()
            val am = context.getSystemService(ActivityManager::class.java)
            telemetry.writeHeartbeat(
                EngineHeartbeatEntity(
                    sessionId = runtime.sessionId ?: telemetry.latestSession()?.id ?: 0L,
                    lastBeatWall = now,
                    lastBeatElapsed = SystemClock.elapsedRealtime(),
                    bootCount = bootCount(context),
                    processStart = processStartWall,
                    engineState = runtime.state.value.name,
                    pendingInbox = inbox.openCount(),
                    armedAlarms = states.count { it.nextDueAt != null },
                    listenerLastConnected = l.lastConnectedAt,
                    listenerLastDisconnected = l.lastDisconnectedAt,
                    lastAlarmFiredAt = states.mapNotNull { it.lastFiredAt }.maxOrNull(),
                    wakeLockMillisToday = ledger.metrics().millisToday,
                    memoryClass = am?.memoryClass ?: 0,
                    appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty(),
                ),
            )
        }
    }

    companion object { const val MIN_GAP_MS = 5_000L }
}

/** What `ApplicationExitInfo` told us since the last look. */
data class ExitCapture(val reasons: List<Pair<Long, String>>, val forceStoppedAt: Long?)

/**
 * AO-18: reads `ApplicationExitInfo` (API 30+) and `ApplicationStartInfo.wasForceStopped()` (API 35+, read by reflection because the
 * project compiles against 34) and writes one `ProcessDied` event per new exit, plus `ForceStopDetected`. Each exit is recorded once
 * (a high-water mark in [ReliabilitySettings]).
 */
@Singleton
class ExitInfoCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: ReliabilitySettings,
    private val recorder: ReliabilityRecorder,
) {
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    suspend fun capture(): ExitCapture {
        if (!available) return ExitCapture(emptyList(), null)
        val am = context.getSystemService(ActivityManager::class.java) ?: return ExitCapture(emptyList(), null)
        val seen = settings.lastExitTimestamp
        val fresh = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS) }.getOrDefault(emptyList())
            .filter { it.timestamp > seen }
            .sortedBy { it.timestamp }
        var forceAt: Long? = null
        val out = ArrayList<Pair<Long, String>>()
        for (e in fresh) {
            val name = ExitReasons.name(e.reason)
            out += e.timestamp to name
            val bad = name != "ExitSelf" && name != "PackageUpdated"
            recorder.record(
                ReliabilityKinds.PROCESS_DIED, if (bad) Severity.WARN else Severity.INFO,
                detail = "$name at=${e.timestamp} importance=${e.importance} ${e.description.orEmpty()}".trim(),
            )
            if (name in ExitReasons.forceStopped) {
                forceAt = e.timestamp
                recorder.record(ReliabilityKinds.FORCE_STOP_DETECTED, Severity.WARN, detail = "ApplicationExitInfo $name at=${e.timestamp}")
            }
        }
        fresh.lastOrNull()?.let { settings.lastExitTimestamp = it.timestamp }
        if (wasForceStoppedAtLastStart() && fresh.none { it.reason == 10 || it.reason == 11 }) {
            // API 35+: the platform says the last start followed a force-stop even when the exit record was rotated out.
            forceAt = forceAt ?: System.currentTimeMillis()
            recorder.record(ReliabilityKinds.FORCE_STOP_DETECTED, Severity.WARN, detail = "ApplicationStartInfo.wasForceStopped")
        }
        forceAt?.let { settings.lastForceStopAt = it }
        return ExitCapture(out, forceAt)
    }

    /** Reflection: `ActivityManager.getHistoricalProcessStartReasons(1)[0].wasForceStopped()`, API 35+. False on any failure. */
    private fun wasForceStoppedAtLastStart(): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val list = ActivityManager::class.java.getMethod("getHistoricalProcessStartReasons", Int::class.javaPrimitiveType).invoke(am, 1) as List<*>
            val info = list.firstOrNull() ?: return false
            // Count it once per process start: the start timestamp is the identity.
            val ts = info.javaClass.getMethod("getStartupTimestamps").invoke(info)?.toString().orEmpty()
            val key = "fs.$ts"
            val prefs = context.getSharedPreferences("kaivo.reliability", Context.MODE_PRIVATE)
            if (prefs.getBoolean(key, false)) return false
            val flag = info.javaClass.getMethod("wasForceStopped").invoke(info) as? Boolean ?: false
            prefs.edit().putBoolean(key, true).apply()
            flag
        }.getOrDefault(false)
    }

    private companion object { const val MAX_EXITS = 15 }
}

/**
 * AO-19: keeps the `expected_run` evidence honest. [sweep] marks slots whose time plus the missed threshold has passed MISSED
 * (the Reconciler and the Pulse call it); [compactAndPrune] folds rows older than 30 days into daily aggregates and applies the
 * retention windows (at most every six hours).
 */
@Singleton
class ExpectedRunMaintenance @Inject constructor(
    private val schedules: ScheduleRepository,
    private val telemetry: EngineTelemetryRepository,
    private val recorder: ReliabilityRecorder,
    private val settings: ReliabilitySettings,
    private val clock: Clock,
) {
    suspend fun sweep(): Int {
        val now = clock.now()
        val pending = schedules.pendingDueBefore(now)
        var marked = 0
        for (row in pending) {
            val interval = schedules.state(row.flowId, row.sparkId)?.nextDueAt?.minus(row.dueAt)?.takeIf { it > 0 } ?: RunAggregation.DAY_MS
            if (!MissedRule.isMissed(row.dueAt, interval, now)) continue
            if (schedules.markExpected(row.id, ExpectedRunState.MISSED, null, null, null, "NotFired")) {
                marked++
                if (marked <= MAX_EVENTS_PER_SWEEP) recorder.record(ReliabilityKinds.MISSED_RUN, Severity.WARN, row.flowId, "expectedAt=${row.dueAt} spark=${row.sparkId} reason=NotFired")
            }
        }
        return marked
    }

    suspend fun compactAndPrune(force: Boolean = false) {
        val now = clock.now()
        if (!force && now - settings.lastCompactionAt < COMPACT_EVERY_MS) return
        settings.lastCompactionAt = now
        runCatching {
            val cutoff = RetentionPolicy.expectedCutoff(now)
            val old = schedules.compactable(cutoff)
            if (old.isNotEmpty()) {
                val samples = old.map { ExpectedSample(it.flowId, it.dueAt, it.state, it.latenessMs) }
                val days = RunAggregation.compact(samples, cutoff, java.util.TimeZone.getDefault().getOffset(now).toLong())
                schedules.saveDaily(days.map { ExpectedRunDailyEntity(it.flowId, it.dayStart, it.fired, it.late, it.missed, it.skipped, it.paused, it.p50Ms, it.p95Ms, it.maxMs) })
            }
            schedules.deleteExpectedOlderThan(cutoff)
            schedules.deleteDailyOlderThan(RetentionPolicy.aggregateCutoff(now))
            telemetry.deleteEventsOlderThan(RetentionPolicy.eventCutoffForRowCap(telemetry.eventTimes(), now))
            telemetry.deleteSessionsOlderThan(now - RetentionPolicy.SESSION_DAYS * RunAggregation.DAY_MS)
        }
    }

    private companion object {
        const val MAX_EVENTS_PER_SWEEP = 20
        const val COMPACT_EVERY_MS = 6 * 3_600_000L
    }
}

/** User-visible notifications about Flows and the engine (spec 4.4 and 6.2), throttled to one per kind per Flow per hour. */
@Singleton
class FlowAlerts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flows: FlowRepository,
    private val notifications: EngineNotifications,
    private val throttle: AlertThrottle,
    private val recorder: ReliabilityRecorder,
    private val settings: ReliabilitySettings,
) {
    suspend fun flowEnteredError(flowId: String, code: String) {
        recorder.record(ReliabilityKinds.FLOW_ERROR, Severity.ERROR, flowId, code)
        if (!throttle.tryAcquire("error:$flowId")) return
        val name = flows.flow(flowId)?.name ?: "A Flow"
        notifications.postAlert(
            "flow-error-$flowId", "\"$name\" has stopped listening",
            "${FailureText.plain(code)} KaivoFlow will keep trying and tell you when it works again.", EngineNotifications.EXTRA_ROUTE_RELIABILITY,
        )
    }

    suspend fun flowBackToNormal(flowId: String) {
        recorder.record(ReliabilityKinds.FLOW_RECOVERED, Severity.INFO, flowId)
        if (!throttle.tryAcquire("recovered:$flowId")) return
        val name = flows.flow(flowId)?.name ?: "A Flow"
        notifications.postAlert("flow-recovered-$flowId", "\"$name\" is back to normal", "It is listening again.", EngineNotifications.EXTRA_ROUTE_HOME, high = false)
    }

    fun outage(text: OutageAlert.Text) {
        if (!throttle.tryAcquire("outage")) return
        notifications.postAlert("engine-outage", text.title, text.body, EngineNotifications.EXTRA_ROUTE_RELIABILITY, high = text.high)
    }

    fun restored(count: Int) {
        if (count <= 0 || !settings.notifyRestored || !throttle.tryAcquire("restored")) return
        notifications.postAlert("flows-restored", "Restored $count ${if (count == 1) "Flow" else "Flows"} after restart", "Everything you published is listening again.", EngineNotifications.EXTRA_ROUTE_HOME, high = false)
    }
}

/**
 * AO-18: dead-engine detection after the fact (spec 6.2 layer 1). Called by the Reconciler after its pass, before the new heartbeat
 * is written, so the gap since the last beat is still visible.
 */
@Singleton
class OutageDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetry: EngineTelemetryRepository,
    private val schedules: ScheduleRepository,
    private val runtime: EngineRuntime,
    private val exitInfo: ExitInfoCapture,
    private val maintenance: ExpectedRunMaintenance,
    private val recorder: ReliabilityRecorder,
    private val alerts: FlowAlerts,
    private val clock: Clock,
) {
    /** Returns the outage found, or null. At most one outage is reported per gap: the caller writes a heartbeat right after. */
    suspend fun check(): EngineOutage? {
        val now = clock.now()
        val exits = runCatching { exitInfo.capture() }.getOrDefault(ExitCapture(emptyList(), null))
        val hb = telemetry.heartbeat()
        val prev = telemetry.recentSessions(3).firstOrNull { it.id != runtime.sessionId }
        val facts = HeartbeatFacts(
            lastBeatWall = hb?.lastBeatWall,
            previousSessionClean = prev == null || prev.cleanStopAt != null,
            previousSessionId = prev?.id,
            bootCountThen = hb?.bootCount?.takeIf { it >= 0 },
            bootCountNow = bootCount(context).takeIf { it >= 0 },
        )
        val reason = hb?.let { b -> exits.reasons.lastOrNull { it.first >= b.lastBeatWall - SLACK_MS }?.second }
        val outage = DeadEngineDetector.detect(facts, now, reason) ?: return null
        runCatching { maintenance.sweep() }
        val rows = schedules.dueSince(outage.from).map { OutageImpact.Row(it.dueAt, it.state, it.reason) }
        val impact = OutageImpact.count(rows, outage)
        recorder.record(
            ReliabilityKinds.ENGINE_DEAD, if (impact.missed > 0) Severity.ERROR else Severity.WARN,
            detail = "from=${outage.from} to=${outage.to} reason=${outage.reason} missed=${impact.missed} caughtUp=${impact.caughtUp}",
        )
        if (OutageAlert.shouldAlert(outage, impact.missed)) {
            val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
            alerts.outage(OutageAlert.build(outage, impact.missed, impact.caughtUp) { fmt.format(Date(it)) })
        }
        return outage
    }

    private companion object { const val SLACK_MS = 60_000L }
}
