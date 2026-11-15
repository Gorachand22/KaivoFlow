package com.kaivoflow.core.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothDevice
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.nodes.TriggerSpecs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lifetime event sources for Sparks that Android only delivers to runtime-registered
 * receivers (battery, Wi-Fi, Bluetooth, headset), plus webhook-service syncing.
 * [start] is called once from `KaivoApplication.onCreate`.
 */
@Singleton
class SparkRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: FlowScheduler,
    private val flowRepository: FlowRepository,
    private val webhookController: WebhookController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false
    @Volatile private var thresholds: Set<Int> = emptySet()
    private var lastLevel: Int? = null
    private var lastCharging: Boolean? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val percent = level * 100 / scale
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> true
                Intent.ACTION_POWER_DISCONNECTED -> false
                else -> status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            }
            val fire = synchronized(this@SparkRuntime) {
                val previous = lastLevel
                val wasCharging = lastCharging
                // ACTION_BATTERY_LOW is a platform-level "low" edge; treat it as a crossing too.
                val platformLow = intent.action == Intent.ACTION_BATTERY_LOW
                if (intent.action != Intent.ACTION_POWER_CONNECTED && intent.action != Intent.ACTION_POWER_DISCONNECTED &&
                    intent.action != Intent.ACTION_BATTERY_LOW
                ) lastLevel = percent
                lastCharging = charging
                platformLow || BatteryEdges.shouldDispatch(previous, percent, wasCharging, charging, thresholds)
            }
            if (!fire) return
            scope.launch {
                runCatching {
                    scheduler.dispatchTrigger(
                        TriggerSpecs.BATTERY_LOW,
                        mapOf(
                            "level" to percent,
                            "isCharging" to charging,
                            "event" to (intent.action ?: "changed"),
                            "at" to System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true

        val battery = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // ACTION_BATTERY_CHANGED is sticky: the return value seeds the previous level.
        ContextCompat.registerReceiver(context, batteryReceiver, battery, ContextCompat.RECEIVER_NOT_EXPORTED)?.let { sticky ->
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level >= 0 && scale > 0) {
                synchronized(this) {
                    lastLevel = level * 100 / scale
                    lastCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                }
            }
        }

        // Wi-Fi, Bluetooth and headset events cannot be received through the manifest on modern
        // Android, so the (non-exported) MobileEventReceiver is registered here instead.
        val mobile = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        ContextCompat.registerReceiver(context, MobileEventReceiver(), mobile, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Re-evaluate battery thresholds and the webhook service whenever any Flow changes.
        scope.launch {
            flowRepository.observeSummaries().debounce(500).collect {
                runCatching { refresh() }
            }
        }
    }

    private suspend fun refresh() {
        thresholds = flowRepository.all()
            .filter { it.isActive }
            .flatMap { it.triggers }
            .filter { it.type == TriggerSpecs.BATTERY_LOW && !it.disabled }
            .map { it.paramNumber("threshold", 20.0).toInt() }
            .toSet()
        webhookController.sync()
    }
}
