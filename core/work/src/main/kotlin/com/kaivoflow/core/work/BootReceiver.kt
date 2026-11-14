package com.kaivoflow.core.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kaivoflow.core.nodes.TriggerSpecs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint

/**
 * Re-registers schedules after a reboot or an app update.
 *
 * WorkManager survives reboots by itself, but OEM battery managers (MIUI, ColorOS, One UI) routinely
 * drop our jobs — re-enqueueing on boot is the difference between "my morning digest works" and
 * "it worked once".
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: FlowScheduler
    @Inject lateinit var runNotifications: RunNotifications

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_TIME_CHANGED
        ) return

        runNotifications.ensureChannels()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scheduler.rescheduleAll()
                if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
                    scheduler.dispatchSimple(TriggerSpecs.BOOT, action)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
