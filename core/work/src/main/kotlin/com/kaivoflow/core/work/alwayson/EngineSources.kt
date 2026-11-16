package com.kaivoflow.core.work.alwayson

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.engine.SparkTypes
import com.kaivoflow.core.engine.alwayson.BatteryAction
import com.kaivoflow.core.engine.alwayson.BatteryTracker
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.SparkQueries
import com.kaivoflow.core.engine.alwayson.WifiSignal
import com.kaivoflow.core.engine.alwayson.WifiTracker
import com.kaivoflow.core.model.Flow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sources that Android delivers only to a live process: the battery and headset broadcasts, the Wi-Fi `NetworkCallback`, the
 * Bluetooth ACL receiver, and the dwell ticker (AO-12, AO-14). They exist exactly while the Engine service runs and only for
 * the kinds a live Spark needs, so a phone with no battery Spark registers no battery receiver. Every event goes to
 * [DeviceEvents], which writes the Inbox row; nothing here starts a service (spec 3.5: Wi-Fi, Bluetooth and battery are
 * forbidden foreground-start triggers, and the Engine is already running by definition).
 *
 * [refresh] is idempotent: it registers what is newly needed and unregisters what is not. The service calls it on start and on
 * every needs change, and [SourcesRegistrar] calls it on every Reconciler pass.
 */
@Singleton
class EngineSources @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: DeviceEvents,
    private val flows: FlowRepository,
    private val lifecycle: FlowLifecycleRepository,
    private val clock: Clock,
) {
    private val lock = Any()
    private var scope: CoroutineScope? = null
    private var ticker: Job? = null

    @Volatile private var thresholds: Set<Int> = emptySet()
    private val battery = BatteryTracker { thresholds }
    private val wifiTracker = WifiTracker()

    private var batteryReceiver: BroadcastReceiver? = null
    private var headsetReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    /** What is registered right now, for the registration rows. */
    data class Active(val battery: Boolean, val headset: Boolean, val bluetooth: Boolean, val wifi: Boolean)

    fun active(): Active = synchronized(lock) {
        Active(batteryReceiver != null, headsetReceiver != null, bluetoothReceiver != null, wifiCallback != null)
    }

    /** Called when the service is up. Registers nothing until [refresh] knows what is wanted. */
    fun start() {
        synchronized(lock) {
            if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            Unit
        }
    }

    fun stop() {
        synchronized(lock) {
            unregisterBattery(); unregisterHeadset(); unregisterBluetooth(); unregisterWifi()
            ticker?.cancel(); ticker = null
            wifiTracker.reset()
            scope?.cancel(); scope = null
        }
    }

    suspend fun refresh() {
        val snapshots = lifecycle.allSnapshots()
        val live = flows.all().filter { snapshots[it.id]?.state?.isLive ?: it.isActive }
        thresholds = SparkQueries.batteryThresholds(live)
        val wantBattery = SparkQueries.enabled(live, SparkTypes.BATTERY).isNotEmpty()
        val wantHeadset = SparkQueries.enabled(live, SparkTypes.HEADSET).isNotEmpty()
        val wantBluetooth = SparkQueries.enabled(live, SparkTypes.BLUETOOTH).isNotEmpty()
        val wantWifi = SparkQueries.enabled(live, SparkTypes.WIFI).isNotEmpty()
        val wantDwell = SparkQueries.enabled(live, SparkTypes.GEOFENCE).isNotEmpty()
        synchronized(lock) {
            if (scope == null) return  // stopped meanwhile
            if (wantBattery) registerBattery() else unregisterBattery()
            if (wantHeadset) registerHeadset() else unregisterHeadset()
            if (wantBluetooth) registerBluetooth() else unregisterBluetooth()
            if (wantWifi) registerWifi() else unregisterWifi()
            if (wantDwell) startTicker() else { ticker?.cancel(); ticker = null }
        }
    }

    // ---- battery ----

    private fun registerBattery() {
        if (batteryReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onBattery(intent)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // ACTION_BATTERY_CHANGED is sticky: the return value seeds the tracker so a start does not invent an edge.
        val sticky = runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }.getOrNull()
        batteryReceiver = receiver
        if (sticky != null) {
            val level = percent(sticky)
            if (level != null) battery.seed(level, isCharging(sticky))
        }
    }

    private fun unregisterBattery() {
        batteryReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        batteryReceiver = null
    }

    private fun onBattery(intent: Intent) {
        val action = when (intent.action) {
            Intent.ACTION_BATTERY_CHANGED -> BatteryAction.Changed
            Intent.ACTION_BATTERY_LOW -> BatteryAction.Low
            Intent.ACTION_BATTERY_OKAY -> BatteryAction.Okay
            Intent.ACTION_POWER_CONNECTED -> BatteryAction.PowerConnected
            Intent.ACTION_POWER_DISCONNECTED -> BatteryAction.PowerDisconnected
            else -> return
        }
        val carriesLevel = action == BatteryAction.Changed
        val signal = battery.onBroadcast(action, if (carriesLevel) percent(intent) else null, if (carriesLevel) isCharging(intent) else null) ?: return
        launch { events.battery(signal, intent.action ?: "changed") }
    }

    private fun percent(intent: Intent): Int? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) null else level * 100 / scale
    }

    private fun isCharging(intent: Intent): Boolean {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    // ---- headset ----

    private fun registerHeadset() {
        if (headsetReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { launch { events.headset(intent) } }
        }
        runCatching { ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_HEADSET_PLUG), ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onSuccess { headsetReceiver = receiver }
    }

    private fun unregisterHeadset() {
        headsetReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        headsetReceiver = null
    }

    // ---- bluetooth ----

    private fun registerBluetooth() {
        if (bluetoothReceiver != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return  // the registration row says PermissionMissing; the manifest receiver gets nothing either until granted
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { launch { events.bluetooth(intent) } }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Exported so the Bluetooth stack (a different uid) can deliver. These are protected broadcasts: no app can forge them.
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
            .onSuccess { bluetoothReceiver = receiver }
    }

    private fun unregisterBluetooth() {
        bluetoothReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bluetoothReceiver = null
    }

    // ---- wifi ----

    private fun registerWifi() {
        if (wifiCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val callback: ConnectivityManager.NetworkCallback =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationAwareWifiCallback(this) else PlainWifiCallback(this)
        runCatching { cm.registerNetworkCallback(request, callback) }.onSuccess { wifiCallback = callback }
    }

    private fun unregisterWifi() {
        val callback = wifiCallback ?: return
        runCatching { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(callback) }
        wifiCallback = null
        wifiTracker.reset()
    }

    internal fun onWifiCapabilities(network: Network, caps: NetworkCapabilities) {
        val ssid = ssidOf(caps)
        val signal: WifiSignal = wifiTracker.onCapabilities(network.networkHandle.toString(), ssid) ?: return
        launch { events.wifi(signal) }
    }

    internal fun onWifiLost(network: Network) {
        val signal = wifiTracker.onLost(network.networkHandle.toString()) ?: return
        launch { events.wifi(signal) }
    }

    /**
     * API 29+ puts the SSID in `NetworkCapabilities.transportInfo`, and API 31+ only when the callback was built with
     * `FLAG_INCLUDE_LOCATION_INFO` and the app holds fine location. Below 29 the deprecated `connectionInfo` is the only source
     * and also needs the location grant. Without the grant the SSID is withheld, no event is produced, and the registration
     * row says so (see [SourcesRegistrar]).
     */
    @Suppress("DEPRECATION")
    private fun ssidOf(caps: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (caps.transportInfo as? WifiInfo)?.ssid?.let { return it }
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return runCatching { wifi?.connectionInfo?.ssid }.getOrNull()
    }

    // ---- dwell ticker ----

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope?.launch {
            while (isActive) {
                delay(DWELL_TICK_MS)
                try { events.resolveDueDwells() } catch (e: CancellationException) { throw e } catch (_: Throwable) { /* next tick retries */ }
            }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        scope?.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (_: Throwable) { /* DeviceEvents already keeps evidence */ }
        }
    }

    private companion object { const val DWELL_TICK_MS = 30_000L }
}

private class PlainWifiCallback(private val sources: EngineSources) : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = sources.onWifiCapabilities(network, caps)
    override fun onLost(network: Network) = sources.onWifiLost(network)
}

/** API 31+: `FLAG_INCLUDE_LOCATION_INFO` is what makes the platform put the SSID in `transportInfo`. */
@RequiresApi(Build.VERSION_CODES.S)
private class LocationAwareWifiCallback(private val sources: EngineSources) :
    ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = sources.onWifiCapabilities(network, caps)
    override fun onLost(network: Network) = sources.onWifiLost(network)
}

/**
 * Writes `trigger_registration` for the sources above, so Publish validation and the Reliability Center show what is really
 * armed: Wi-Fi without fine location is `PermissionMissing`, Bluetooth without `BLUETOOTH_CONNECT` (API 31+) likewise, and
 * everything is Pending while the Engine is not running.
 */
@Singleton
class SourcesRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: EngineSources,
    private val flows: FlowRepository,
    private val lifecycle: FlowLifecycleRepository,
    private val registrations: com.kaivoflow.core.data.TriggerRegistrationRepository,
    private val runtime: EngineRuntime,
    private val recorder: ReliabilityRecorder,
    private val clock: Clock,
) : SparkRegistrar {
    private val writer by lazy { RegistrationWriter(registrations, recorder) { clock.now() } }

    override suspend fun reconcile(live: List<Flow>, all: List<Flow>, trigger: com.kaivoflow.core.engine.alwayson.ReconcileTrigger) {
        val running = runtime.serviceAlive
        if (running) sources.refresh()
        val snapshots = lifecycle.allSnapshots()
        val liveFlows = flows.all().filter { snapshots[it.id]?.state?.isLive ?: it.isActive }
        val existing = registrations.all()
        val active = sources.active()
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val btPlan = com.kaivoflow.core.engine.alwayson.BluetoothDelivery.plan(Build.VERSION.SDK_INT, connect)

        suspend fun write(type: String, state: com.kaivoflow.core.engine.alwayson.SparkRegState) {
            for ((flow, step) in SparkQueries.enabled(liveFlows, type)) {
                val old = existing.firstOrNull { it.flowId == flow.id && it.sparkId == step.id }
                writer.write(flow.id, step.id, type, state, old)
            }
        }
        val pending = com.kaivoflow.core.engine.alwayson.SparkRegState.pending("EngineNotRunning")
        val armed = com.kaivoflow.core.engine.alwayson.SparkRegState.armed("engine receiver")
        write(SparkTypes.BATTERY, if (!running) pending else if (active.battery) armed else com.kaivoflow.core.engine.alwayson.SparkRegState.pending("registering"))
        write(SparkTypes.HEADSET, if (!running) pending else if (active.headset) armed else com.kaivoflow.core.engine.alwayson.SparkRegState.pending("registering"))
        write(
            SparkTypes.WIFI,
            when {
                !running -> pending
                !fine -> com.kaivoflow.core.engine.alwayson.SparkRegState.failed(com.kaivoflow.core.engine.FailureCode.PermissionMissing, "ACCESS_FINE_LOCATION")
                active.wifi -> com.kaivoflow.core.engine.alwayson.SparkRegState.armed("network callback")
                else -> com.kaivoflow.core.engine.alwayson.SparkRegState.pending("registering")
            },
        )
        write(SparkTypes.BLUETOOTH, com.kaivoflow.core.engine.alwayson.BluetoothDelivery.registration(btPlan, running && active.bluetooth))
    }
}
