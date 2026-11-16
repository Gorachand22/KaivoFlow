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

/** Engine switches the user controls. Only "Always-on mode" exists in M1 (spec 3.3). */
@Singleton
class EngineSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("kaivo.engine", Context.MODE_PRIVATE)

    var alwaysOnMode: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_ON, false)
        set(value) { prefs.edit().putBoolean(KEY_ALWAYS_ON, value).apply() }

    private companion object { const val KEY_ALWAYS_ON = "alwaysOnMode" }
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
