package com.kaivoflow.core.work.alwayson

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kaivoflow.core.work.ReceiverEntryPoint
import com.kaivoflow.core.work.ReceiverEntryPoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val INTAKE_BUDGET_MS = 8_000L

/**
 * Geofence transitions: from `GeofencingClient` (Play services) or from the proximity-alert fallback. The PendingIntents that
 * [PlayGeofenceOps] and [ProximityGeofenceOps] build target this component explicitly, so it is not exported. A geofence
 * transition is a platform-listed foreground-start exemption (spec 3.5); the Inbox write decides whether the Engine starts.
 */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_GEOFENCE_PLAY && action != ACTION_GEOFENCE_PROXIMITY) return
        val entry = ReceiverEntryPoints.of(context)
        val pending = goAsync()
        val hold = entry.wakeLocks().acquireIntake("geofence")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(INTAKE_BUDGET_MS) {
                    when (action) {
                        ACTION_GEOFENCE_PLAY -> entry.deviceEvents().geofencePlay(intent)
                        else -> entry.deviceEvents().geofenceProximity(intent)
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
            } finally {
                hold.close()
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_GEOFENCE_PLAY = "com.kaivoflow.action.GEOFENCE_PLAY"
        const val ACTION_GEOFENCE_PROXIMITY = "com.kaivoflow.action.GEOFENCE_PROXIMITY"
    }
}

/**
 * Bluetooth ACL connect and disconnect (AO-14). Declared in the manifest so a dead process can be woken by the broadcast where the
 * platform still allows it for the target SDK, and registered dynamically by the Engine for platforms where it does not. Both
 * land in `DeviceEvents.bluetooth`, whose dedupe key makes a double delivery harmless. Exported because the Bluetooth stack is
 * the sender; these actions are protected broadcasts, so no other app can send them. It never starts a service.
 */
class BluetoothAclReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val entry = ReceiverEntryPoints.of(context)
        val pending = goAsync()
        val hold = entry.wakeLocks().acquireIntake("bluetooth")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(INTAKE_BUDGET_MS) { entry.deviceEvents().bluetooth(intent) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
            } finally {
                hold.close()
                pending.finish()
            }
        }
    }
}
