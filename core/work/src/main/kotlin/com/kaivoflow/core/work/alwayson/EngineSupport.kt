package com.kaivoflow.core.work.alwayson

import android.content.Context
import android.os.SystemClock
import com.kaivoflow.core.data.EngineTelemetryRepository
import com.kaivoflow.core.data.ReliabilityEventEntity
import com.kaivoflow.core.engine.alwayson.EngineEvent
import com.kaivoflow.core.engine.alwayson.EngineState
import com.kaivoflow.core.engine.alwayson.EngineStateMachine
import com.kaivoflow.core.engine.alwayson.PayloadFiles
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.WebhookDefaults
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local truth about the Engine service. The database is the durable truth (spec 3.1 rule 2); this object only
 * answers "is the service alive in THIS process", which is exactly what the Pulse and the watchdog need: a cold process
 * starts with [serviceAlive] false, so a dead Engine is noticed by definition.
 */
@Singleton
class EngineRuntime @Inject constructor() {
    private val _state = MutableStateFlow(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted when the Engine's needs may have changed (Flow published or edited). The service re-evaluates. */
    val refresh: SharedFlow<Unit> = _refresh.asSharedFlow()

    @Volatile var serviceAlive: Boolean = false
    /** The current service session was started only to execute Inbox work and ends when it is idle and nothing needs it. */
    @Volatile var transientSession: Boolean = false
    @Volatile var stopRequested: Boolean = false
    @Volatile var sessionId: Long? = null

    fun reduce(event: EngineEvent): EngineState = _state.updateAndGet { EngineStateMachine.reduce(it, event) }

    fun requestRefresh() { _refresh.tryEmit(Unit) }

    private inline fun <T> MutableStateFlow<T>.updateAndGet(f: (T) -> T): T { update(f); return value }
}

/** Engine switches the user controls: "Always-on mode" (spec 3.3) and the webhook reach settings (spec 3.7). */
@Singleton
class EngineSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("kaivo.engine", Context.MODE_PRIVATE)

    var alwaysOnMode: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_ON, false)
        set(value) { prefs.edit().putBoolean(KEY_ALWAYS_ON, value).apply() }

    /**
     * Explicit opt-in for the webhook listener to bind every network interface. Off by default (loopback only). Even when on, the
     * listener accepts only the per-Flow token (see `WebhookAuth`). Use `WebhookAdmin` to change it so the listener is rebound.
     */
    var webhookLanEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEBHOOK_LAN, false)
        set(value) { prefs.edit().putBoolean(KEY_WEBHOOK_LAN, value).apply() }

    var webhookPort: Int
        get() = prefs.getInt(KEY_WEBHOOK_PORT, WebhookDefaults.DEFAULT_PORT)
        set(value) { prefs.edit().putInt(KEY_WEBHOOK_PORT, value).apply() }

    private companion object {
        const val KEY_ALWAYS_ON = "alwaysOnMode"
        const val KEY_WEBHOOK_LAN = "webhookLan"
        const val KEY_WEBHOOK_PORT = "webhookPort"
    }
}

/** Writes `reliability_event` rows. Never throws: evidence keeping must not break the thing it observes. */
@Singleton
class ReliabilityRecorder @Inject constructor(
    private val telemetry: EngineTelemetryRepository,
    private val runtime: EngineRuntime,
) {
    suspend fun record(kind: String, severity: String = Severity.INFO, flowId: String? = null, detail: String? = null) {
        runCatching {
            telemetry.record(
                ReliabilityEventEntity(
                    at = System.currentTimeMillis(), kind = kind, severity = severity, flowId = flowId,
                    detail = detail, sessionId = runtime.sessionId,
                ),
            )
        }
    }
}

/** Spilled Inbox payloads live in app-private storage, one file per payload. */
class FilePayloadFiles(private val dir: File) : PayloadFiles {
    override fun write(name: String, text: String): String {
        dir.mkdirs()
        val safe = name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val tmp = File(dir, "$safe.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        val target = File(dir, "$safe.json")
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        return target.name
    }

    override fun read(ref: String): String? = resolve(ref)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    override fun delete(ref: String) { resolve(ref)?.delete() }

    /** A reference is a bare file name; anything with a path separator is refused. */
    private fun resolve(ref: String): File? = if (ref.contains('/') || ref.contains('\\') || ref.contains("..")) null else File(dir, ref)
}

internal fun bootCount(context: Context): Int =
    runCatching { android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) }
        .getOrDefault(-1)

internal fun sinceBootSeconds(): Long = SystemClock.elapsedRealtime() / 1000

/**
 * Evidence that an event Spark's source is alive (spec 6.4): when the last event of each kind reached the Inbox path, and a short
 * detail (the battery level, a geofence transition). The Reliability Center and the probes read it (M2); it survives process death.
 * Event Sparks have no expected time, so unlike schedules they leave "last seen" evidence rather than `expected_run` rows.
 */
@Singleton
class EventEvidence @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("kaivo.evidence", Context.MODE_PRIVATE)

    data class Seen(val atMs: Long, val detail: String?)

    fun record(kind: String, atMs: Long, detail: String? = null) {
        val editor = prefs.edit().putLong("$kind.at", atMs)
        if (detail != null) editor.putString("$kind.detail", detail) else editor.remove("$kind.detail")
        editor.apply()
    }

    fun last(kind: String): Seen? {
        if (!prefs.contains("$kind.at")) return null
        return Seen(prefs.getLong("$kind.at", 0L), prefs.getString("$kind.detail", null))
    }
}
