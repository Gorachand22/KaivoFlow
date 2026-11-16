package com.kaivoflow.core.work.alwayson

import android.content.Context
import android.os.Build
import com.kaivoflow.core.data.EngineTelemetryRepository
import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.ScheduleRepository
import com.kaivoflow.core.data.TriggerRegistrationRepository
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.reliability.RunAggregation
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reliability dump (spec 5.2 footer "Export diagnostics" and 7.1 `DIAG_DUMP`): heartbeat, sessions, Flow lifecycles, Spark
 * registrations, schedule states, 14 days of expected versus actual runs, the event log. Contains no Flow content, no payloads and no
 * tokens, so it is safe to attach to a bug report.
 */
@Singleton
class ReliabilityDump @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flows: FlowRepository,
    private val lifecycle: FlowLifecycleRepository,
    private val registrations: TriggerRegistrationRepository,
    private val schedules: ScheduleRepository,
    private val telemetry: EngineTelemetryRepository,
    private val clock: Clock,
) {
    suspend fun build(): String {
        val now = clock.now()
        val root = JSONObject()
        root.put("generatedAt", now)
        root.put("device", JSONObject().put("sdk", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL))
        telemetry.heartbeat()?.let { h ->
            root.put("heartbeat", JSONObject().put("sessionId", h.sessionId).put("lastBeatWall", h.lastBeatWall).put("ageMs", now - h.lastBeatWall)
                .put("bootCount", h.bootCount).put("engineState", h.engineState).put("pendingInbox", h.pendingInbox).put("armedAlarms", h.armedAlarms)
                .put("lastAlarmFiredAt", h.lastAlarmFiredAt).put("wakeLockMillisToday", h.wakeLockMillisToday).put("appVersion", h.appVersion))
        }
        root.put("sessions", JSONArray().also { a -> telemetry.recentSessions(20).forEach {
            a.put(JSONObject().put("id", it.id).put("startedAt", it.startedAt).put("startReason", it.startReason).put("bootCount", it.bootCount).put("cleanStopAt", it.cleanStopAt).put("endReason", it.endReason))
        } })
        val names = flows.all().associate { it.id to it.name }
        root.put("flows", JSONArray().also { a -> lifecycle.allSnapshots().forEach { (id, s) ->
            a.put(JSONObject().put("flowId", id).put("name", names[id]).put("state", s.state.db).put("revision", s.publishedRevision).put("reason", s.reason.db)
                .put("since", s.since).put("pausedUntil", s.pausedUntil).put("errorCode", s.errorCode).put("retryCount", s.retryCount).put("nextRetryAt", s.nextRetryAt))
        } })
        root.put("registrations", JSONArray().also { a -> registrations.all().forEach {
            a.put(JSONObject().put("flowId", it.flowId).put("sparkId", it.sparkId).put("kind", it.kind).put("state", it.state).put("detail", it.detail).put("since", it.since).put("retryCount", it.retryCount))
        } })
        root.put("scheduleStates", JSONArray().also { a -> schedules.allStates().forEach {
            a.put(JSONObject().put("flowId", it.flowId).put("sparkId", it.sparkId).put("precision", it.precision).put("armedMode", it.armedMode).put("nextDueAt", it.nextDueAt).put("lastFiredAt", it.lastFiredAt).put("missedPolicy", it.missedPolicy).put("lastStatus", it.lastStatus))
        } })
        root.put("expectedRuns", JSONArray().also { a -> schedules.dueSince(now - 14 * RunAggregation.DAY_MS).forEach {
            a.put(JSONObject().put("flowId", it.flowId).put("sparkId", it.sparkId).put("slotKey", it.slotKey).put("dueAt", it.dueAt).put("state", it.state).put("firedAt", it.firedAt).put("latenessMs", it.latenessMs).put("reason", it.reason))
        } })
        root.put("dailyAggregates", JSONArray().also { a -> schedules.dailySince(now - 180 * RunAggregation.DAY_MS).forEach {
            a.put(JSONObject().put("flowId", it.flowId).put("dayStart", it.dayStart).put("fired", it.fired).put("late", it.late).put("missed", it.missed).put("skipped", it.skipped).put("paused", it.paused).put("p50Ms", it.p50Ms).put("p95Ms", it.p95Ms).put("maxMs", it.maxMs))
        } })
        root.put("events", JSONArray().also { a -> telemetry.recentEvents(1000).forEach {
            a.put(JSONObject().put("at", it.at).put("kind", it.kind).put("severity", it.severity).put("flowId", it.flowId).put("detail", it.detail).put("sessionId", it.sessionId).put("acknowledged", it.acknowledged))
        } })
        return root.toString(2)
    }

    /** Writes the dump to app-private storage (`files/reliability-dump.json`) and returns the file. */
    suspend fun writeToFile(): File = File(context.filesDir, "reliability-dump.json").also { it.writeText(build(), Charsets.UTF_8) }
}
