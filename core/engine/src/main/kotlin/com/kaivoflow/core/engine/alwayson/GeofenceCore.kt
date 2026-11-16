package com.kaivoflow.core.engine.alwayson

import com.kaivoflow.core.engine.FailureCode
import com.kaivoflow.core.model.StepNode

/*
 * Geofence Spark decisions (AO-13): which backend, whether it can honestly work, what the platform's answer means, and what
 * payload an OS transition becomes. The Android registrar and receiver only call the platform and apply these results.
 */

object GeofenceConstants {
    // Values of com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_*; spelled out so this file stays Android-free.
    const val ENTER = 1
    const val EXIT = 2
    const val DWELL = 4

    const val MIN_RADIUS_M = 25f
    const val MAX_RADIUS_M = 100_000f
    const val MAX_DWELL_SEC = 24 * 3600

    /** The platform keeps at most 100 geofences per app and user. */
    const val PLATFORM_LIMIT = 100
    const val REQUEST_PREFIX = "kaivo:"
}

enum class GeofenceTransition(val wire: String) { Enter("enter"), Exit("exit"), Dwell("dwell") }

/** One validated geofence. [wanted] is the Spark's `transition` param: enter, exit or both. */
data class GeofenceSpec(
    val flowId: String,
    val sparkId: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Float,
    val wanted: String,
    /** 0 means no dwell: fire on entering. Above 0 means fire only after staying inside this long. */
    val dwellMs: Long,
) {
    val requestId: String get() = "${GeofenceConstants.REQUEST_PREFIX}$flowId:$sparkId"

    /** Detects a changed configuration, so an unchanged fence is not re-added (re-adding can report "entering" again). */
    val signature: String get() = "%.6f,%.6f,%.0f,%s,%d".format(java.util.Locale.ROOT, latitude, longitude, radiusM, wanted, dwellMs)

    /** Transitions to register with `GeofencingClient`. Dwell replaces Enter: you either want "arrived" or "arrived and stayed". */
    val transitionMask: Int
        get() {
            val arrive = if (dwellMs > 0) GeofenceConstants.DWELL else GeofenceConstants.ENTER
            return when (wanted) {
                "exit" -> GeofenceConstants.EXIT
                "both" -> arrive or GeofenceConstants.EXIT
                else -> arrive
            }
        }

    companion object {
        /** Reads the Spark's params. Invalid configuration is reported, never silently clamped to something the user did not ask for. */
        fun parse(flowId: String, step: StepNode): Parsed {
            val lat = step.paramNumber("latitude", 0.0)
            val lon = step.paramNumber("longitude", 0.0)
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return Parsed.Invalid("InvalidCoordinates")
            val radius = step.paramNumber("radiusMeters", 150.0)
            if (!radius.isFinite() || radius < GeofenceConstants.MIN_RADIUS_M || radius > GeofenceConstants.MAX_RADIUS_M) return Parsed.Invalid("InvalidRadius")
            val wanted = step.paramString("transition", "enter").ifBlank { "enter" }
            if (wanted != "enter" && wanted != "exit" && wanted != "both") return Parsed.Invalid("InvalidTransition")
            val dwellSec = step.paramNumber("dwellSeconds", 0.0)
            if (!dwellSec.isFinite() || dwellSec < 0 || dwellSec > GeofenceConstants.MAX_DWELL_SEC) return Parsed.Invalid("InvalidDwell")
            return Parsed.Valid(GeofenceSpec(flowId, step.id, lat, lon, radius.toFloat(), wanted, (dwellSec * 1000).toLong()))
        }
    }

    sealed interface Parsed {
        data class Valid(val spec: GeofenceSpec) : Parsed
        data class Invalid(val detail: String) : Parsed
    }
}

object GeofenceRequestIds {
    /** `kaivo:<flowId>:<sparkId>` back to its parts. Flow ids may contain ':' themselves, Spark ids do not, so split at the last one. */
    fun parse(requestId: String): Pair<String, String>? {
        if (!requestId.startsWith(GeofenceConstants.REQUEST_PREFIX)) return null
        val rest = requestId.removePrefix(GeofenceConstants.REQUEST_PREFIX)
        val cut = rest.lastIndexOf(':')
        if (cut <= 0 || cut == rest.length - 1) return null
        return rest.substring(0, cut) to rest.substring(cut + 1)
    }
}

enum class GeofenceBackend { PlayServices, Proximity }

data class GeofenceEnvironment(
    val playServices: Boolean,
    val fineGranted: Boolean,
    val backgroundGranted: Boolean,
    val sdkInt: Int,
    val locationEnabled: Boolean,
)

object GeofencePlanner {
    /** Play services give batching, dwell and survive better; the platform proximity alert is the no-Google fallback. */
    fun backend(env: GeofenceEnvironment): GeofenceBackend = if (env.playServices) GeofenceBackend.PlayServices else GeofenceBackend.Proximity

    /**
     * Null means "attempt the registration". Otherwise the honest reason it cannot work right now. From API 29 a geofence only
     * reaches an app in the background with the separate "all the time" location grant, so that one is checked too, and
     * not left to fail silently on a device.
     */
    fun precheck(env: GeofenceEnvironment): SparkRegState? = when {
        !env.fineGranted -> SparkRegState.failed(FailureCode.PermissionMissing, "ACCESS_FINE_LOCATION")
        env.sdkInt >= 29 && !env.backgroundGranted -> SparkRegState.failed(FailureCode.PermissionMissing, "ACCESS_BACKGROUND_LOCATION")
        !env.locationEnabled -> SparkRegState.failed(FailureCode.GeofenceRejected, "LocationSwitchedOff")
        else -> null
    }

    /** Meaning of `GeofenceStatusCodes` and `ApiException` codes from `addGeofences`. */
    fun playStatus(code: Int): SparkRegState = when (code) {
        1000 -> SparkRegState.failed(FailureCode.GeofenceRejected, "GeofenceNotAvailable")
        1001 -> SparkRegState.failed(FailureCode.InvalidConfig, "TooManyGeofences(limit ${GeofenceConstants.PLATFORM_LIMIT})")
        1002 -> SparkRegState.failed(FailureCode.GeofenceRejected, "TooManyPendingIntents")
        1004 -> SparkRegState.failed(FailureCode.PermissionMissing, "InsufficientLocationPermission")
        else -> SparkRegState.failed(FailureCode.GeofenceRejected, "PlayServicesError:$code")
    }

    /** Which already-registered fences must go: Sparks that are no longer live, disabled, or changed. */
    fun staleRequestIds(registered: Collection<String>, wanted: Collection<GeofenceSpec>): List<String> {
        val keep = wanted.map { it.requestId }.toSet()
        return registered.filter { it.startsWith(GeofenceConstants.REQUEST_PREFIX) && it !in keep }
    }
}

/**
 * Builds the payload `EventTriggerMatcher` checks. The matcher recomputes the distance from the event location to the
 * fence centre, so a transition without a location still has to land on the correct side: an exit is placed just outside
 * the fence and an enter or dwell at its centre. (The earlier code always sent the centre, which made every exit fail the matcher.)
 * A dwell transition is an Enter that stayed: the Spark's own transition filter still applies.
 */
object GeofencePayload {
    private const val METERS_PER_DEGREE_LAT = 111_320.0

    fun build(spec: GeofenceSpec, transition: GeofenceTransition, location: Pair<Double, Double>?, atMs: Long): Map<String, Any?> {
        val (lat, lon) = location ?: when (transition) {
            GeofenceTransition.Exit -> (spec.latitude + (spec.radiusM * 1.05) / METERS_PER_DEGREE_LAT).coerceAtMost(90.0) to spec.longitude
            else -> spec.latitude to spec.longitude
        }
        val payload = linkedMapOf<String, Any?>(
            "transition" to if (transition == GeofenceTransition.Exit) "exit" else "enter",
            "latitude" to lat,
            "longitude" to lon,
            "at" to atMs,
            "targetFlowId" to spec.flowId,
            "sparkId" to spec.sparkId,
        )
        if (transition == GeofenceTransition.Dwell) payload["dwell"] = true
        return payload
    }
}

/**
 * Dwell for the proximity-alert fallback, which has no dwell of its own: an enter starts a timer, an exit cancels it, and a timer
 * that ran out without an exit becomes a Dwell transition. Resolved by the Engine while it runs and by every Pulse and watchdog
 * pass otherwise, so without the Engine it is as late as the next Pulse (about 10 minutes). Entries are persisted through [store].
 */
class DwellTracker(private val store: DwellStore) {
    interface DwellStore {
        fun load(): Map<String, Long>
        fun save(entries: Map<String, Long>)
    }

    @Synchronized
    fun onEnter(requestId: String, dwellMs: Long, nowMs: Long): Boolean {
        if (dwellMs <= 0) return false
        val all = store.load().toMutableMap()
        // A repeated enter keeps the original start: re-adding an alert makes the platform report "entering" again.
        if (requestId !in all) { all[requestId] = nowMs + dwellMs; store.save(all) }
        return true
    }

    @Synchronized
    fun onExit(requestId: String) {
        val all = store.load().toMutableMap()
        if (all.remove(requestId) != null) store.save(all)
    }

    /** Request ids whose dwell period is over. They are removed, so each fires once. */
    @Synchronized
    fun due(nowMs: Long): List<String> {
        val all = store.load().toMutableMap()
        val ready = all.filterValues { it <= nowMs }.keys.sorted()
        if (ready.isNotEmpty()) { ready.forEach { all.remove(it) }; store.save(all) }
        return ready
    }

    @Synchronized
    fun forget(requestIds: Collection<String>) {
        val all = store.load().toMutableMap()
        if (requestIds.any { all.remove(it) != null }) store.save(all)
    }

    @Synchronized
    fun nextDue(): Long? = store.load().values.minOrNull()
}
