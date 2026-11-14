package com.kaivoflow.core.work

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.kaivoflow.core.nodes.TriggerSpecs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Turns phone-wide broadcasts into seeded Runs for the mobile-native Sparks.
 *
 * The receiver does no matching itself — [com.kaivoflow.core.nodes.EventTriggerMatcher] is the
 * single fail-closed gate — and it never starts a Run directly. Every event goes through
 * [FlowScheduler.dispatchTrigger], which fans out to matching, enabled Sparks only.
 */
@AndroidEntryPoint
class MobileEventReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: FlowScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_GEOFENCE &&
            action != WifiManager.NETWORK_STATE_CHANGED_ACTION &&
            action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECTED &&
            action != Intent.ACTION_HEADSET_PLUG
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_GEOFENCE -> handleGeofence(intent)
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> handleWifi(context, intent)
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> handleBluetooth(context, intent, action)
                    Intent.ACTION_HEADSET_PLUG -> handleHeadset(intent)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleGeofence(intent: Intent) {
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)
        val payload = mutableMapOf<String, Any?>(
            "transition" to if (entering) "enter" else "exit",
            "latitude" to intent.getDoubleExtra(EXTRA_LAT, 0.0),
            "longitude" to intent.getDoubleExtra(EXTRA_LON, 0.0),
            "at" to System.currentTimeMillis(),
        )
        intent.getStringExtra(EXTRA_FLOW_ID)?.let { payload["targetFlowId"] = it }
        scheduler.dispatchTrigger(TriggerSpecs.GEOFENCE, payload)
    }

    @Suppress("DEPRECATION")
    private suspend fun handleWifi(context: Context, intent: Intent) {
        val info = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
        val connected = info?.isConnected == true
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val current = runCatching { wifi?.connectionInfo?.ssid?.trim('"') }.getOrNull()
            ?.takeUnless { it.equals("<unknown ssid>", ignoreCase = true) }
        val prefs = context.getSharedPreferences(PREFS_WIFI, Context.MODE_PRIVATE)
        val ssid = if (connected && !current.isNullOrBlank()) {
            prefs.edit().putString(KEY_LAST_SSID, current).apply()
            current
        } else {
            // A disconnect broadcast carries no usable SSID on modern Android; reuse the last one.
            prefs.getString(KEY_LAST_SSID, null)
        }
        if (ssid.isNullOrBlank()) return
        scheduler.dispatchTrigger(
            TriggerSpecs.WIFI,
            mapOf(
                "event" to if (connected) "connected" else "disconnected",
                "ssid" to ssid,
                "at" to System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun handleBluetooth(context: Context, intent: Intent, action: String) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        val canReadName = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
        }
        val address = runCatching { device?.address }.getOrNull()
            ?: intent.getStringExtra("address")
        if (address.isNullOrBlank()) return
        val name = if (canReadName) runCatching { device?.name }.getOrNull().orEmpty() else ""
        scheduler.dispatchTrigger(
            TriggerSpecs.BLUETOOTH,
            mapOf(
                "event" to if (action == BluetoothDevice.ACTION_ACL_CONNECTED) "connected" else "disconnected",
                "address" to address,
                "name" to name,
                "at" to System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun handleHeadset(intent: Intent) {
        val state = intent.getIntExtra("state", -1)
        if (state != 0 && state != 1) return
        scheduler.dispatchTrigger(
            TriggerSpecs.HEADSET,
            mapOf(
                "event" to if (state == 1) "plugged" else "unplugged",
                "name" to (intent.getStringExtra("name") ?: ""),
                "at" to System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        const val ACTION_GEOFENCE = "com.kaivoflow.action.GEOFENCE"
        const val EXTRA_FLOW_ID = "com.kaivoflow.extra.FLOW_ID"
        const val EXTRA_LAT = "com.kaivoflow.extra.LAT"
        const val EXTRA_LON = "com.kaivoflow.extra.LON"
        private const val PREFS_WIFI = "kaivo.wifi"
        private const val KEY_LAST_SSID = "lastSsid"
    }
}
