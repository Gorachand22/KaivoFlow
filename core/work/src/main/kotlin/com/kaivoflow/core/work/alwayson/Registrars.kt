package com.kaivoflow.core.work.alwayson

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.kaivoflow.core.data.RegistrationState
import com.kaivoflow.core.data.TriggerRegistrationEntity
import com.kaivoflow.core.data.TriggerRegistrationRepository
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.nodes.TriggerSpecs
import com.kaivoflow.core.work.MobileEventReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One kind of platform registration the Reconciler keeps true: geofences, network callbacks, webhook routes. Contributed to
 * a Hilt multibinding so later tasks (AO-11 to AO-14) add theirs without touching the Reconciler. Must be idempotent and
 * must write `trigger_registration` rows that say what is really armed.
 */
interface SparkRegistrar {
    /** [live] are the Flows that should be armed, [all] every stored Flow (to remove what no longer should be). */
    suspend fun reconcile(live: List<Flow>, all: List<Flow>, trigger: ReconcileTrigger)
}

/**
 * Proximity alerts for the Geofence Spark (moved out of the old scheduler; AO-13 replaces the API with `GeofencingClient`).
 *
 * Re-registering an alert can make the platform report "entering" again, so a registration is only redone when the stored
 * signature differs or the system is known to have dropped it (boot, update, the user's Fix now). A Pulse never re-adds.
 */
@Singleton
class GeofenceRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registrations: TriggerRegistrationRepository,
) : SparkRegistrar {

    override suspend fun reconcile(live: List<Flow>, all: List<Flow>, trigger: ReconcileTrigger) {
        val liveIds = live.map { it.id }.toSet()
        for (flow in all) sync(flow, flow.id in liveIds, force = trigger in FORCE_TRIGGERS)
    }

    suspend fun sync(flow: Flow, live: Boolean, force: Boolean) {
        val manager = context.getSystemService(LocationManager::class.java)
        val spark = flow.triggers.firstOrNull { it.type == TriggerSpecs.GEOFENCE && !it.disabled }
        val existing = registrations.forFlow(flow.id).filter { it.kind == TriggerSpecs.GEOFENCE }
        if (!live || spark == null) {
            if (existing.isNotEmpty()) {
                runCatching { manager?.removeProximityAlert(pendingIntent(flow.id, null)) }
                existing.forEach { registrations.remove(flow.id, it.sparkId) }
            }
            return
        }
        val since = existing.firstOrNull()?.since ?: System.currentTimeMillis()
        val lat = spark.paramNumber("latitude", 0.0)
        val lon = spark.paramNumber("longitude", 0.0)
        val radius = spark.paramNumber("radiusMeters", 150.0).toFloat().coerceIn(25f, 100_000f)
        val signature = "%.6f,%.6f,%.0f".format(lat, lon, radius)

        fun row(state: String, detail: String) = TriggerRegistrationEntity(flow.id, spark.id, TriggerSpecs.GEOFENCE, state, detail, since, 0, null)

        if (manager == null) return registrations.upsert(row(RegistrationState.FAILED_PERMANENT, "NoLocationManager"))
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return registrations.upsert(row(RegistrationState.FAILED_PERMANENT, "PermissionMissing"))
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return registrations.upsert(row(RegistrationState.FAILED_PERMANENT, "InvalidConfig"))

        val armedSame = existing.any { it.sparkId == spark.id && it.state == RegistrationState.ARMED && it.detail == signature }
        if (armedSame && !force) return

        // Intent extras are ignored by filterEquals, so the same PendingIntent can be removed and re-created with new coordinates.
        runCatching { manager.removeProximityAlert(pendingIntent(flow.id, null)) }
        try {
            manager.addProximityAlert(lat, lon, radius, -1L, pendingIntent(flow.id, spark))
            registrations.upsert(row(RegistrationState.ARMED, signature))
        } catch (e: Exception) {
            registrations.upsert(row(RegistrationState.FAILED_TRANSIENT, e.javaClass.simpleName).copy(retryCount = 1))
        }
    }

    private fun pendingIntent(flowId: String, spark: StepNode?): PendingIntent {
        val intent = Intent(context, MobileEventReceiver::class.java).apply {
            action = MobileEventReceiver.ACTION_GEOFENCE
            putExtra(MobileEventReceiver.EXTRA_FLOW_ID, flowId)
            if (spark != null) {
                putExtra(MobileEventReceiver.EXTRA_LAT, spark.paramNumber("latitude", 0.0))
                putExtra(MobileEventReceiver.EXTRA_LON, spark.paramNumber("longitude", 0.0))
            }
        }
        // The platform adds the entering flag to the intent, so the PendingIntent must be mutable on API 31+.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, flowId.hashCode() and 0x7fff_ffff, intent, flags)
    }

    private companion object {
        val FORCE_TRIGGERS = setOf(ReconcileTrigger.Boot, ReconcileTrigger.PackageReplaced, ReconcileTrigger.UserFix, ReconcileTrigger.FlowChanged)
    }
}
