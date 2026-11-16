package com.kaivoflow.core.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Boot, app update, and clock or zone changes all delegate to the Reconciler (spec 3.10): it re-arms every alarm and
 * registration, applies the misfire policy to slots that passed while the phone was off, restarts the Engine and, once per
 * boot, dispatches the Boot Spark. Alarms are cleared by a reboot, so without this nothing would fire again (R18).
 *
 * The intake wake lock keeps the CPU awake while the pass runs inside `goAsync`. Dependencies come from the entry point
 * (never field injection). `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are on the list of foreground-start exemptions.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT -> ReconcileTrigger.Boot
            Intent.ACTION_MY_PACKAGE_REPLACED -> ReconcileTrigger.PackageReplaced
            Intent.ACTION_TIMEZONE_CHANGED -> ReconcileTrigger.TimezoneChanged
            Intent.ACTION_TIME_CHANGED -> ReconcileTrigger.TimeChanged
            else -> return
        }
        val entry = ReceiverEntryPoints.of(context)
        entry.runNotifications().ensureChannels()
        val pending = goAsync()
        val hold = entry.wakeLocks().acquireIntake("boot")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                entry.reconciler().reconcile(trigger)
            } finally {
                hold.close()
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }
}
