package com.kaivoflow.core.work.alwayson

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.engine.alwayson.BatterySignal
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.DwellTracker
import com.kaivoflow.core.engine.alwayson.EventKeys
import com.kaivoflow.core.engine.alwayson.GeofencePayload
import com.kaivoflow.core.engine.alwayson.GeofenceRequestIds
import com.kaivoflow.core.engine.alwayson.GeofenceSpec
import com.kaivoflow.core.engine.alwayson.GeofenceTransition
import com.kaivoflow.core.engine.alwayson.NotificationInterest
import com.kaivoflow.core.engine.alwayson.NotificationText
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.WifiSignal
import com.kaivoflow.core.nodes.TriggerSpecs
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that turns a platform event into an Inbox write: payload, dedupe key, intake source and evidence. Receivers,
 * the notification listener and the Engine's registered sources call these and nothing else, so every Spark kind gets the same
 * treatment (persist first, dedupe, record that an event arrived) and none of them starts a service itself.
 */
@Singleton
class DeviceEvents @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intake: SparkIntake,
    private val evidence: EventEvidence,
    private val flows: FlowRepository,
    private val dwell: DwellTracker,
    private val recorder: ReliabilityRecorder,
    private val clock: Clock,
) {
    /** Set by the notification registrar on every reconcile; unknown until then, so nothing is dropped early. */
    @Volatile var notificationInterest: NotificationInterest = NotificationInterest.UNKNOWN

    // ---- SMS ----

    /** Called from the manifest receiver inside `goAsync`. Writes the Inbox row and returns; no service is started from here. */
    suspend fun sms(sender: String, body: String, timestampMs: Long): Int {
        val at = clock.now()
        evidence.record(TriggerSpecs.SMS, at)
        return intake.dispatch(
            TriggerSpecs.SMS,
            mapOf("from" to sender, "body" to body.take(SMS_MAX), "receivedAt" to at),
            sourceId = EventKeys.sms(sender, timestampMs, body),
            source = IntakeSource.Event,
        )
    }

    // ---- Notification ----

    suspend fun notification(packageName: String, sbnKey: String, postTime: Long, title: CharSequence?, text: CharSequence?, ongoing: Boolean): Int {
        if (!notificationInterest.mightMatch(packageName)) return 0
        evidence.record(TriggerSpecs.NOTIFICATION, clock.now())
        return intake.dispatch(
            TriggerSpecs.NOTIFICATION,
            mapOf(
                "app" to packageName,
                "title" to NotificationText.title(title),
                "text" to NotificationText.text(text),
                "postedAt" to postTime,
                "isOngoing" to ongoing,
            ),
            sourceId = EventKeys.notification(sbnKey, postTime),
            source = IntakeSource.Event,
        )
    }

    // ---- Battery, Wi-Fi, headset (sourced by the Engine) ----

    suspend fun battery(signal: BatterySignal, platformAction: String): Int {
        val at = clock.now()
        evidence.record(TriggerSpecs.BATTERY_LOW, at, "level=${signal.level}")
        return intake.dispatch(
            TriggerSpecs.BATTERY_LOW,
            mapOf("level" to signal.level, "isCharging" to signal.charging, "event" to platformAction, "at" to at),
            sourceId = EventKeys.battery(signal.edge, signal.level, at),
            source = IntakeSource.Event,
        )
    }

    suspend fun wifi(signal: WifiSignal): Int {
        val at = clock.now()
        evidence.record(TriggerSpecs.WIFI, at)
        return intake.dispatch(
            TriggerSpecs.WIFI,
            mapOf("event" to signal.event, "ssid" to signal.ssid, "at" to at),
            sourceId = EventKeys.wifi(signal.event, signal.ssid, at),
            source = IntakeSource.Event,
        )
    }

    suspend fun headset(intent: Intent): Int {
        val state = intent.getIntExtra("state", -1)
        if (state != 0 && state != 1) return 0
        val event = if (state == 1) "plugged" else "unplugged"
        val at = clock.now()
        evidence.record(TriggerSpecs.HEADSET, at)
        return intake.dispatch(
            TriggerSpecs.HEADSET,
            mapOf("event" to event, "name" to (intent.getStringExtra("name") ?: ""), "at" to at),
            sourceId = EventKeys.headset(event, at),
            source = IntakeSource.Event,
        )
    }

    // ---- Bluetooth (manifest receiver and Engine receiver both land here; the dedupe key absorbs a double delivery) ----

    suspend fun bluetooth(intent: Intent): Int {
        val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
        if (!connected && intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return 0
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val canRead = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
        }
        val address = runCatching { device?.address }.getOrNull() ?: intent.getStringExtra("address")
        if (address.isNullOrBlank()) return 0
        val name = if (canRead) runCatching { device?.name }.getOrNull().orEmpty() else ""
        val event = if (connected) "connected" else "disconnected"
        val at = clock.now()
        evidence.record(TriggerSpecs.BLUETOOTH, at, event)
        return intake.dispatch(
            TriggerSpecs.BLUETOOTH,
            mapOf("event" to event, "address" to address, "name" to name, "at" to at),
            sourceId = EventKeys.bluetooth(event, address, at),
            source = IntakeSource.Event,
        )
    }

    // ---- Geofence ----

    /** Result of `GeofencingClient`. A transition is a platform-listed foreground-start exemption, so the source is [IntakeSource.Geofence]. */
    suspend fun geofencePlay(intent: Intent): Int {
        val event = GeofencingEvent.fromIntent(intent) ?: return 0
        if (event.hasError()) {
            val state = com.kaivoflow.core.engine.alwayson.GeofencePlanner.playStatus(event.errorCode)
            recorder.record(ReliabilityKinds.SPARK_REGISTRATION_FAILED, Severity.WARN, detail = "geofence event: ${state.detail}")
            return 0
        }
        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceTransition.Enter
            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceTransition.Exit
            Geofence.GEOFENCE_TRANSITION_DWELL -> GeofenceTransition.Dwell
            else -> return 0
        }
        val where = event.triggeringLocation?.let { it.latitude to it.longitude }
        return event.triggeringGeofences.orEmpty().sumOf { fence -> fire(fence.requestId, transition, where) }
    }

    /** Proximity-alert fallback: enter or exit only, so dwell is emulated here by [DwellTracker]. */
    suspend fun geofenceProximity(intent: Intent): Int {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return 0
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)
        val spec = specFor(requestId) ?: return 0
        // The platform already decided the transition. A last-known fix can be stale, so no location is sent and the payload
        // builder places the point on the right side of the fence (see GeofencePayload).
        val here: Pair<Double, Double>? = null
        return if (entering) {
            if (dwell.onEnter(requestId, spec.dwellMs, clock.now())) 0 else fire(requestId, GeofenceTransition.Enter, here)
        } else {
            dwell.onExit(requestId)
            fire(requestId, GeofenceTransition.Exit, here)
        }
    }

    /** Dwell periods that ran out without an exit become Dwell transitions. Called by the Engine's ticker and by every Reconciler pass. */
    suspend fun resolveDueDwells(): Int = dwell.due(clock.now()).sumOf { fire(it, GeofenceTransition.Dwell, null) }

    private suspend fun fire(requestId: String, transition: GeofenceTransition, where: Pair<Double, Double>?): Int {
        val spec = specFor(requestId) ?: return 0
        val at = clock.now()
        evidence.record(TriggerSpecs.GEOFENCE, at, transition.wire)
        return intake.dispatch(
            TriggerSpecs.GEOFENCE,
            GeofencePayload.build(spec, transition, where, at),
            sourceId = EventKeys.geofence(requestId, transition.wire, at),
            source = IntakeSource.Geofence,
        )
    }

    private suspend fun specFor(requestId: String): GeofenceSpec? {
        val (flowId, sparkId) = GeofenceRequestIds.parse(requestId) ?: return null
        val step = flows.flow(flowId)?.step(sparkId) ?: return null
        return (GeofenceSpec.parse(flowId, step) as? GeofenceSpec.Parsed.Valid)?.spec
    }

    companion object {
        const val EXTRA_REQUEST_ID = "com.kaivoflow.extra.GEOFENCE_REQUEST_ID"
        private const val SMS_MAX = 500
    }
}
