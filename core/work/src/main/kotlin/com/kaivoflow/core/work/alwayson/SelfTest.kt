package com.kaivoflow.core.work.alwayson

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kaivoflow.core.data.EngineTelemetryRepository
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.InboxStore
import com.kaivoflow.core.engine.alwayson.ListenerTracker
import com.kaivoflow.core.engine.alwayson.PrecisionMode
import com.kaivoflow.core.engine.alwayson.ReachMode
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.SparkTypes
import com.kaivoflow.core.engine.alwayson.StartReason
import com.kaivoflow.core.engine.alwayson.WebhookListenerStatus
import com.kaivoflow.core.engine.reliability.SelfTestCriteria
import com.kaivoflow.core.engine.reliability.SelfTestId
import com.kaivoflow.core.engine.reliability.SelfTestOutcome
import com.kaivoflow.core.engine.reliability.SelfTestState
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.FlowSettings
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StreamEdge
import com.kaivoflow.core.work.WebhookServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The internal Flow of the self-test suite (spec 6.5). It is built in code and never stored, so it cannot appear in the Flow list,
 * be edited, published or exported. The Run host executes it by id. It posts one low-importance notification; successful Runs are not
 * kept in history.
 */
object SelfTestFlow {
    const val ID = "__kaivo_selftest__"
    const val SPARK_ID = "st-spark"
    const val KIND = "selftest"

    fun build(): Flow = Flow(
        id = ID, name = "KaivoFlow self-test",
        steps = persistentListOf(
            StepNode(id = SPARK_ID, type = SparkTypes.MANUAL, name = "Self-test"),
            StepNode(
                id = "st-notify", type = "device.notify", name = "Post marker",
                params = persistentMapOf<String, Any?>("mode" to "notification", "title" to "KaivoFlow self-test", "body" to "The engine ran this on its own."),
            ),
        ),
        streams = persistentListOf(StreamEdge("st-edge", SPARK_ID, "st-notify")),
        settings = FlowSettings(executionTimeoutMs = 30_000L, saveSuccessfulRuns = false, saveFailedRuns = true),
    )
}

/**
 * AO-21: the runner for ST1 to ST7. It arms, waits and measures; pass and fail come from the pure `SelfTestCriteria`. Anything that
 * must survive the process dying (alarm times, "engine back" time) lives in SharedPreferences, so a test that finishes in another
 * process (an alarm fires long after the screen closed) is still evaluated. Results are written as `SelfTestResult` events.
 */
@Singleton
class SelfTestRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intake: SparkIntake,
    private val inboxStore: InboxStore,
    private val alarms: AlarmScheduler,
    private val controller: EngineController,
    private val runtime: EngineRuntime,
    private val recorder: ReliabilityRecorder,
    private val settings: ReliabilitySettings,
    private val engineSettings: EngineSettings,
    private val webhookServer: WebhookServer,
    private val webhookAdmin: WebhookAdmin,
    private val listener: ListenerTracker,
    private val evidence: EventEvidence,
    private val notifications: EngineNotifications,
    private val battery: com.kaivoflow.core.work.BatteryHealth,
    private val telemetry: EngineTelemetryRepository,
    private val clock: Clock,
) {
    private val prefs = context.getSharedPreferences("kaivo.selftest", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _results = MutableStateFlow<Map<SelfTestId, SelfTestOutcome>>(emptyMap())
    val results: StateFlow<Map<SelfTestId, SelfTestOutcome>> = _results.asStateFlow()

    /** Starts the tests in [ids]. Long ones (ST1, ST3, ST6) finish later; call [evaluate] to refresh their outcome. */
    fun start(ids: Set<SelfTestId>) {
        scope.launch {
            if (SelfTestId.ST2 in ids) publish(runSt2())
            if (SelfTestId.ST4 in ids) publish(runSt4())
            if (SelfTestId.ST5 in ids) publish(runSt5())
            if (SelfTestId.ST1 in ids) armSt1()
            if (SelfTestId.ST3 in ids) beginSt3()
            if (SelfTestId.ST6 in ids) armSt6()
            if (SelfTestId.ST7 in ids) armSt7()
            evaluate()
        }
    }

    // ---- ST1: alarms ---------------------------------------------------------------------------------------------

    private fun armSt1() {
        val now = clock.now()
        val exact = now + 60_000L
        val onTime = now + 90_000L
        prefs.edit().putLong("st1.exactDue", exact).putLong("st1.onTimeDue", onTime).remove("st1.exactFired").remove("st1.onTimeFired").apply()
        alarms.armSchedule(SelfTestFlow.ID, "st1-exact", exact, PrecisionMode.Exact)
        alarms.armSchedule(SelfTestFlow.ID, "st1-ontime", onTime, PrecisionMode.OnTime)
        publish(SelfTestOutcome(SelfTestId.ST1, SelfTestState.Pending, "Waiting for two alarms (60 s and 90 s)."))
    }

    // ---- ST3: engine restart -------------------------------------------------------------------------------------

    private suspend fun beginSt3() {
        if (!controller.needs().engineNeeded) {
            publish(SelfTestOutcome(SelfTestId.ST3, SelfTestState.Skipped, "No published Flow needs the engine, so there is nothing to restart."))
            return
        }
        prefs.edit().putLong("st3.killedAt", clock.now()).remove("st3.backAt").apply()
        // Stop without the clean-stop mark: runtime.stopRequested stays false, so the session is recorded as died.
        context.stopService(Intent(context, AlwaysOnService::class.java))
        publish(SelfTestOutcome(SelfTestId.ST3, SelfTestState.Pending, "Engine stopped on purpose. Waiting for the Pulse or watchdog to bring it back (up to 16 minutes)."))
    }

    /** Called by the service when it starts. */
    fun onEngineStarted() {
        if (prefs.getLong("st3.killedAt", 0L) > 0L && prefs.getLong("st3.backAt", 0L) == 0L) prefs.edit().putLong("st3.backAt", clock.now()).apply()
    }

    // ---- ST6: foreground start from the background ---------------------------------------------------------------

    private fun armSt6() {
        val due = clock.now() + 30_000L
        prefs.edit().putLong("st6.due", due).remove("st6.fired").remove("st6.running").apply()
        alarms.armSchedule(SelfTestFlow.ID, "st6", due, PrecisionMode.Exact)
        publish(SelfTestOutcome(SelfTestId.ST6, SelfTestState.Pending, "Press Home now. In 30 seconds an alarm tries to start the engine."))
    }

    // ---- ST7: overnight soak (exact leg: 16 alarms, one every 30 minutes) -------------------------------------------

    private fun armSt7() {
        val start = clock.now()
        val editor = prefs.edit().putLong("st7.start", start).putInt("st7.count", SOAK_SLOTS)
        for (i in 1..SOAK_SLOTS) {
            val due = start + i * SOAK_STEP_MS
            editor.putLong("st7.due.$i", due).remove("st7.fired.$i")
            alarms.armSchedule(SelfTestFlow.ID, "st7-$i", due, PrecisionMode.Exact)
        }
        editor.apply()
        publish(SelfTestOutcome(SelfTestId.ST7, SelfTestState.Pending, "Soak running for ${SOAK_SLOTS * SOAK_STEP_MS / 3_600_000L} hours. Switch the screen off and unplug the phone. The report appears in the morning."))
    }

    private suspend fun evaluateSt7(now: Long) {
        val start = prefs.getLong("st7.start", 0L).takeIf { it > 0 } ?: return
        val count = prefs.getInt("st7.count", 0)
        val lastDue = prefs.getLong("st7.due.$count", Long.MAX_VALUE)
        if (now < lastDue + 10 * 60_000L) return
        val lateness = (1..count).map { i -> prefs.getLong("st7.fired.$i", 0L).takeIf { it > 0 }?.let { f -> (f - prefs.getLong("st7.due.$i", f)).coerceAtLeast(0) } }
        val deaths = telemetry.eventsOfKinds(listOf(ReliabilityKinds.ENGINE_DEAD), start).count { it.detail.orEmpty().contains("reason=Unknown") }
        val stock = battery.oem() == com.kaivoflow.core.work.BatteryHealth.Oem.Stock
        val o = SelfTestCriteria.st7(lateness, deaths, stock)
        publish(o, record = true)
        if (o.state == SelfTestState.Pass) settings.soakPassedAt = now // upgrades C10 for 30 days
        prefs.edit().remove("st7.start").apply()
    }

    // ---- Alarm entry (AlarmReceiver) -----------------------------------------------------------------------------

    suspend fun onAlarm(sparkId: String) {
        val now = clock.now()
        when (sparkId) {
            "st1-exact" -> prefs.edit().putLong("st1.exactFired", now).apply()
            "st1-ontime" -> prefs.edit().putLong("st1.onTimeFired", now).apply()
            "st6" -> {
                prefs.edit().putLong("st6.fired", now).apply()
                if (!runtime.serviceAlive) controller.start(StartReason.RestartAlarm, transient = true, notifyOnDenial = false)
                var waited = 0L
                while (!runtime.serviceAlive && waited < 8_000L) { delay(250); waited += 250 }
                prefs.edit().putBoolean("st6.running", runtime.serviceAlive).apply()
            }
            else -> if (sparkId.startsWith("st7-")) sparkId.removePrefix("st7-").toIntOrNull()?.let { prefs.edit().putLong("st7.fired.$it", now).apply() }
        }
        evaluate()
    }

    // ---- ST2, ST4, ST5: immediate ----------------------------------------------------------------------------------

    private suspend fun runSt2(): SelfTestOutcome {
        val viaEngine = runtime.serviceAlive
        val start = clock.now()
        val stored = intake.enqueue(SelfTestFlow.ID, SelfTestFlow.SPARK_ID, SelfTestFlow.KIND, listOf(FlowData(json = mapOf("at" to start))), sourceId = "st2-$start")
        val id = (stored as? com.kaivoflow.core.engine.alwayson.EnqueueResult.Stored)?.id
            ?: return SelfTestCriteria.st2(null, viaEngine)
        var startedAfter: Long? = null
        val limit = if (viaEngine) 8_000L else 45_000L
        while (clock.now() - start < limit) {
            val row = inboxStore.get(id)
            if (row?.runId != null || row?.state?.name == "Done") { startedAfter = clock.now() - start; break }
            delay(100)
        }
        return SelfTestCriteria.st2(startedAfter, viaEngine)
    }

    private suspend fun runSt4(): SelfTestOutcome {
        val status = webhookServer.status.value
        if (status !is WebhookListenerStatus.Listening) return SelfTestOutcome(SelfTestId.ST4, SelfTestState.Skipped, "The webhook listener is not running (no published Flow has a webhook Spark).")
        if (webhookAdmin.reachMode == ReachMode.Lan) return SelfTestOutcome(SelfTestId.ST4, SelfTestState.Skipped, "The loopback probe needs the listener in this-phone-only mode.")
        val url = "http://127.0.0.1:${status.port}/hook/__kaivo_probe"
        val token = webhookAdmin.appToken()
        val withToken = probe(url, token)
        val without = probe(url, null)
        settings.webhookProbeOk = SelfTestCriteria.st4(withToken, without).state == SelfTestState.Pass
        return SelfTestCriteria.st4(withToken, without)
    }

    private suspend fun probe(url: String, token: String?): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 2_000; c.readTimeout = 3_000; c.doOutput = true
                token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                c.outputStream.use { it.write("{}".toByteArray()) }
                c.responseCode
            } finally { c.disconnect() }
        }.getOrNull()
    }

    private suspend fun runSt5(): SelfTestOutcome {
        if (!listener.snapshot().connected) return SelfTestOutcome(SelfTestId.ST5, SelfTestState.Skipped, "Notification access is not connected, so there is nothing to probe.")
        val before = evidence.last(EVIDENCE_PROBE_NOTIFICATION)?.atMs
        val start = clock.now()
        notifications.postProbe()
        var seen: Long? = null
        while (clock.now() - start < 5_000L) {
            val l = evidence.last(EVIDENCE_PROBE_NOTIFICATION)?.atMs
            if (l != null && (before == null || l > before)) { seen = l - start; break }
            delay(100)
        }
        return SelfTestCriteria.st5(seen?.coerceAtLeast(0))
    }

    // ---- Evaluation ----------------------------------------------------------------------------------------------

    /** Recomputes the long tests from persisted measurements and publishes every outcome as a `SelfTestResult` event once decided. */
    suspend fun evaluate() {
        val now = clock.now()
        if (prefs.getLong("st1.exactDue", 0L) > 0L) {
            val ed = prefs.getLong("st1.exactDue", 0L); val od = prefs.getLong("st1.onTimeDue", 0L)
            val ef = prefs.getLong("st1.exactFired", 0L).takeIf { it > 0 }; val of = prefs.getLong("st1.onTimeFired", 0L).takeIf { it > 0 }
            val waiting = (ef == null || of == null) && now < od + 120_000L
            if (!waiting) publish(SelfTestCriteria.st1(ef?.minus(ed)?.coerceAtLeast(0), of?.minus(od)?.coerceAtLeast(0)), record = true).also { prefs.edit().remove("st1.exactDue").apply() }
        }
        evaluateSt7(now)
        val killed = prefs.getLong("st3.killedAt", 0L)
        if (killed > 0L) {
            val back = prefs.getLong("st3.backAt", 0L).takeIf { it > 0 }
            val o = SelfTestCriteria.st3(back?.minus(killed), now - killed)
            if (o.state != SelfTestState.Pending) { publish(o, record = true); prefs.edit().remove("st3.killedAt").apply() } else publish(o)
        }
        if (prefs.getLong("st6.due", 0L) > 0L) {
            val fired = prefs.getLong("st6.fired", 0L) > 0L
            if (fired || now > prefs.getLong("st6.due", 0L) + 60_000L) {
                publish(SelfTestCriteria.st6(prefs.getBoolean("st6.running", false), fired), record = true)
                prefs.edit().remove("st6.due").apply()
            }
        }
    }

    private fun publish(o: SelfTestOutcome, record: Boolean = false) {
        _results.value = _results.value + (o.id to o)
        if (record || o.state == SelfTestState.Pass || o.state == SelfTestState.Fail) {
            scope.launch {
                recorder.record(
                    ReliabilityKinds.SELF_TEST_RESULT, if (o.state == SelfTestState.Fail) Severity.WARN else Severity.INFO,
                    detail = "${o.id.code} ${o.state.name}: ${o.detail}",
                )
            }
        }
    }

}

private const val SOAK_SLOTS = 16
private const val SOAK_STEP_MS = 30 * 60_000L

/** Evidence key written when the listener sees the app's own probe notification. */
const val EVIDENCE_PROBE_NOTIFICATION = "probe.notification"
