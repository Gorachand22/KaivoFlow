package com.kaivoflow.core.work.alwayson

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.RegistrationState
import com.kaivoflow.core.data.TriggerRegistrationRepository
import com.kaivoflow.core.engine.FailureCode
import com.kaivoflow.core.engine.SparkTypes
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.DwellTracker
import com.kaivoflow.core.engine.alwayson.GeofenceBackend
import com.kaivoflow.core.engine.alwayson.GeofenceEnvironment
import com.kaivoflow.core.engine.alwayson.GeofencePlanner
import com.kaivoflow.core.engine.alwayson.GeofenceSpec
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.engine.alwayson.SparkQueries
import com.kaivoflow.core.engine.alwayson.SparkRegState
import com.kaivoflow.core.model.Flow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the platform allows right now, read fresh on every pass (permissions and the location switch change behind our back). */
@Singleton
class GeofenceEnvironmentReader @Inject constructor(@ApplicationContext private val context: Context) {
    fun read(): GeofenceEnvironment {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val locationOn = runCatching {
            context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false
        }.getOrDefault(false)
        return GeofenceEnvironment(playServicesAvailable(), fine, background, Build.VERSION.SDK_INT, locationOn)
    }

    /** Any failure (the class missing on a stripped device, a service that is updating) means "not available": the fallback is used. */
    private fun playServicesAvailable(): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** `GeofencingClient` (Google Play services). Batches fences in the platform, supports dwell, and survives better than proximity alerts. */
@Singleton
class PlayGeofenceOps @Inject constructor(@ApplicationContext private val context: Context) {
    private val client get() = LocationServices.getGeofencingClient(context)

    /** One PendingIntent for every fence, so the platform's limit of pending intents per app is never reached. */
    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION_GEOFENCE_PLAY)
        // The platform adds the event to the intent, so the PendingIntent must be mutable from API 31; the intent stays explicit.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** Adding a fence with an existing request id replaces it. No initial trigger: re-registering after a reboot must not fire "entered". */
    @SuppressLint("MissingPermission") // checked by GeofencePlanner.precheck before this is called
    suspend fun add(spec: GeofenceSpec): SparkRegState = try {
        val fence = Geofence.Builder()
            .setRequestId(spec.requestId)
            .setCircularRegion(spec.latitude, spec.longitude, spec.radiusM)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(spec.transitionMask)
            .apply { if (spec.dwellMs > 0) setLoiteringDelay(spec.dwellMs.toInt()) }
            .build()
        val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(fence).build()
        client.addGeofences(request, pendingIntent()).await()
        SparkRegState.armed(BACKEND_TAG)
    } catch (e: ApiException) {
        GeofencePlanner.playStatus(e.statusCode)
    } catch (e: SecurityException) {
        SparkRegState.failed(FailureCode.PermissionMissing, "SecurityException")
    }

    suspend fun remove(requestIds: List<String>) {
        if (requestIds.isEmpty()) return
        runCatching { client.removeGeofences(requestIds).await() }.onFailure { if (it is CancellationException) throw it }
    }

    companion object {
        const val BACKEND_TAG = "play"
        private const val REQUEST_CODE = 0x6501
    }
}

/** The platform's own proximity alerts: no Google dependency, no dwell, one PendingIntent per fence. */
@Singleton
class ProximityGeofenceOps @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager get() = context.getSystemService(LocationManager::class.java)

    private fun pendingIntent(requestId: String): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
            .setAction(GeofenceReceiver.ACTION_GEOFENCE_PROXIMITY)
            .putExtra(DeviceEvents.EXTRA_REQUEST_ID, requestId)
        // Extras are ignored by filterEquals, so the request code identifies the fence. The platform adds the entering flag: mutable from API 31.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, requestId.hashCode() and 0x7fff_ffff, intent, flags)
    }

    @SuppressLint("MissingPermission") // checked by GeofencePlanner.precheck before this is called
    fun add(spec: GeofenceSpec): SparkRegState {
        val lm = manager ?: return SparkRegState.failed(FailureCode.GeofenceRejected, "NoLocationManager")
        return try {
            runCatching { lm.removeProximityAlert(pendingIntent(spec.requestId)) }
            lm.addProximityAlert(spec.latitude, spec.longitude, spec.radiusM, -1L, pendingIntent(spec.requestId))
            SparkRegState.armed(BACKEND_TAG)
        } catch (e: SecurityException) {
            SparkRegState.failed(FailureCode.PermissionMissing, "SecurityException")
        } catch (e: Exception) {
            SparkRegState.failed(FailureCode.GeofenceRejected, e.javaClass.simpleName)
        }
    }

    fun remove(requestIds: List<String>) {
        val lm = manager ?: return
        requestIds.forEach { runCatching { lm.removeProximityAlert(pendingIntent(it)) } }
    }

    companion object { const val BACKEND_TAG = "prox" }
}

/**
 * Geofence Sparks (AO-13): `GeofencingClient` when Google Play services exist, the platform's proximity alerts otherwise, with the
 * dwell option on both (native on Play services, emulated by `DwellTracker` for proximity).
 *
 * Honest status: every Spark gets a `trigger_registration` row that says what is true: Armed with the backend, or Failed with the
 * exact reason (fine location, "all the time" location, location switched off, a platform status code, a bad configuration). A row is
 * written before anything is attempted for a Spark that cannot work, so Publish validation and the Reliability Center never see a
 * hopeful "armed" that is not.
 *
 * Re-registration: the registration signature includes the backend, so an unchanged fence is left alone on a Pulse (re-adding can make
 * the platform report "entering" again), while boot, update, the user's Fix now, a flow change and a backend switch re-register.
 */
@Singleton
class GeofenceRegistrar @Inject constructor(
    private val flows: FlowRepository,
    private val liveFlowSource: LiveFlows,
    private val lifecycle: FlowLifecycleRepository,
    private val registrations: TriggerRegistrationRepository,
    private val environment: GeofenceEnvironmentReader,
    private val play: PlayGeofenceOps,
    private val proximity: ProximityGeofenceOps,
    private val dwell: DwellTracker,
    private val events: DeviceEvents,
    private val recorder: ReliabilityRecorder,
    private val clock: Clock,
) : SparkRegistrar {
    private val mutex = Mutex()
    private val writer by lazy { RegistrationWriter(registrations, recorder) { clock.now() } }

    override suspend fun reconcile(live: List<Flow>, all: List<Flow>, trigger: ReconcileTrigger) {
        mutex.withLock { pass(trigger) }
        // A dwell that ended while nothing was watching the clock fires now (late by at most one Pulse).
        runCatching { events.resolveDueDwells() }.onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun pass(trigger: ReconcileTrigger) {
        val liveFlows = liveFlowSource.live()
        val wantedSteps = SparkQueries.enabled(liveFlows, SparkTypes.GEOFENCE)
        val existing = registrations.all().filter { it.kind == SparkTypes.GEOFENCE }
        val env = environment.read()
        val backend = GeofencePlanner.backend(env)
        val tag = if (backend == GeofenceBackend.PlayServices) PlayGeofenceOps.BACKEND_TAG else ProximityGeofenceOps.BACKEND_TAG
        val force = trigger in FORCE_TRIGGERS

        val specs = ArrayList<GeofenceSpec>()
        for ((flow, step) in wantedSteps) {
            val old = existing.firstOrNull { it.flowId == flow.id && it.sparkId == step.id }
            when (val parsed = GeofenceSpec.parse(flow.id, step)) {
                is GeofenceSpec.Parsed.Invalid -> writer.write(flow.id, step.id, SparkTypes.GEOFENCE, SparkRegState.failed(FailureCode.InvalidConfig, parsed.detail), old)
                is GeofenceSpec.Parsed.Valid -> specs += parsed.spec
            }
        }

        // Remove what no longer should exist: Sparks that are gone, disabled, unpublished, invalid, or armed on the other backend.
        val keepIds = specs.map { it.requestId }.toSet()
        val stale = existing.filter { "kaivo:${it.flowId}:${it.sparkId}" !in keepIds }
        val wrongBackend = existing.filter { "kaivo:${it.flowId}:${it.sparkId}" in keepIds && it.state == RegistrationState.ARMED && it.detail?.substringBefore('|') != tag }
        removeFromBothBackends((stale + wrongBackend).map { "kaivo:${it.flowId}:${it.sparkId}" })
        stale.filter { r -> wantedSteps.none { (f, s) -> f.id == r.flowId && s.id == r.sparkId } }.forEach { registrations.remove(it.flowId, it.sparkId) }

        val blocked = GeofencePlanner.precheck(env)
        for (spec in specs) {
            val old = existing.firstOrNull { it.flowId == spec.flowId && it.sparkId == spec.sparkId }
            if (blocked != null) { writer.write(spec.flowId, spec.sparkId, SparkTypes.GEOFENCE, blocked, old); continue }
            val signature = "$tag|${spec.signature}"
            val same = old != null && old.state == RegistrationState.ARMED && old.detail == signature
            if (same && !force) continue
            val result = when (backend) {
                GeofenceBackend.PlayServices -> play.add(spec)
                GeofenceBackend.Proximity -> proximity.add(spec)
            }
            val state = if (result.isArmed) SparkRegState.armed(signature) else result
            writer.write(spec.flowId, spec.sparkId, SparkTypes.GEOFENCE, state, old)
        }
    }

    private suspend fun removeFromBothBackends(requestIds: List<String>) {
        if (requestIds.isEmpty()) return
        runCatching { proximity.remove(requestIds) }
        runCatching { play.remove(requestIds) }
        dwell.forget(requestIds)
    }

    private companion object {
        val FORCE_TRIGGERS = setOf(ReconcileTrigger.Boot, ReconcileTrigger.PackageReplaced, ReconcileTrigger.UserFix, ReconcileTrigger.FlowChanged)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) cont.resume(task.result)
        else cont.resumeWithException(task.exception ?: IllegalStateException("task failed"))
    }
}
