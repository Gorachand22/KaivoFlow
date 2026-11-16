package com.kaivoflow.core.engine.reliability

import com.kaivoflow.core.engine.FailureCode
import com.kaivoflow.core.engine.alwayson.SparkRegState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns a stored `trigger_registration` row back into a [SparkRegState] and words for the publish sheet. The table keeps the
 * state and a detail string but not the failure code, so the code is recovered from the detail (the registrars write stable
 * words: SecurityException, InvalidConfig, EngineStartBlocked, PortInUse, ...).
 */
object RegistrationRows {
    fun toState(kind: String, state: String, detail: String?): SparkRegState {
        val d = detail.orEmpty()
        return when (state) {
            "ARMED" -> SparkRegState.armed(d)
            "PENDING" -> SparkRegState.pending(d)
            "FAILED_PERMANENT" -> SparkRegState.failed(codeFor(kind, d, permanent = true), d)
            else -> SparkRegState.failed(codeFor(kind, d, permanent = false), d)
        }
    }

    fun codeFor(kind: String, detail: String, permanent: Boolean): FailureCode {
        val d = detail.lowercase()
        return when {
            "securityexception" in d || "permission" in d || d.startsWith("access_") || d.startsWith("receive_") -> FailureCode.PermissionMissing
            "credential" in d -> FailureCode.CredentialMissing
            "invalid" in d || "conflict" in d || "duplicate" in d -> FailureCode.InvalidConfig
            "enginestartblocked" in d -> FailureCode.EngineStartBlocked
            "port" in d || "bind" in d || "address" in d -> FailureCode.PortInUse
            "listener" in d || "notaccess" in d -> FailureCode.ListenerUnbound
            "network" in d -> FailureCode.NetworkCallbackRejected
            "geofence" in d || kind == "spark.geofence" -> FailureCode.GeofenceRejected
            "alarm" in d -> FailureCode.AlarmRefused
            permanent -> FailureCode.InvalidConfig
            else -> FailureCode.Unknown
        }
    }

    /** "Armed: next fire Mon 08:00 (exact)". [zone] is injectable for tests. */
    fun nextFireText(epochMs: Long, mode: String, zone: TimeZone = TimeZone.getDefault()): String {
        val fmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).apply { timeZone = zone }
        return "next fire ${fmt.format(Date(epochMs))}, ${mode.lowercase().replace('_', ' ')}"
    }

    /** One line per Spark for the publish sheet, spec 4.4 wording. */
    fun resultLine(state: SparkRegState): String = when (state.kind) {
        SparkRegState.Kind.Armed -> if (state.detail.isBlank()) "Armed" else "Armed: ${state.detail}"
        SparkRegState.Kind.Pending -> "Will arm when the engine starts"
        else -> when (state.failure) {
            FailureCode.PermissionMissing -> "Needs permission: ${permissionHint(state.detail)}"
            FailureCode.InvalidConfig -> "Needs a fix: ${state.detail.ifBlank { "check this Spark's settings" }}"
            FailureCode.PortInUse -> "Port in use. Pick another webhook port."
            FailureCode.ListenerUnbound -> "Notification access is not connected yet."
            FailureCode.EngineStartBlocked -> "Android blocked the background engine from starting."
            else -> "Could not arm: ${state.detail.ifBlank { state.failure?.name ?: "unknown reason" }}"
        }
    }

    private fun permissionHint(detail: String): String = when {
        detail.contains("LOCATION", ignoreCase = true) || detail.contains("SecurityException") -> "allow location \"all the time\""
        detail.contains("SMS", ignoreCase = true) -> "allow SMS access"
        else -> "allow ${detail.ifBlank { "the missing permission" }}"
    }
}
