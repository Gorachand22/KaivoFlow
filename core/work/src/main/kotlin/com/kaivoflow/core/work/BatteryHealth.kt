package com.kaivoflow.core.work

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pillar 3: keep scheduled Flows alive on phones owned by OEM battery managers.
 *
 * Instead of a generic "please disable battery optimisation" nag, this inspects the actual OEM and
 * offers the precise screen a user needs. Every intent is validated before use and falls back to
 * the app's settings page, because these OEM screens move between ROM versions and a
 * `ActivityNotFoundException` in a settings screen is an instant Play Store review complaint.
 */
@Singleton
class BatteryHealth @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Check(val id: String, val title: String, val explanation: String, val ok: Boolean, val action: Intent?)

    enum class Oem { Xiaomi, Samsung, OnePlus, Oppo, Vivo, Realme, Huawei, Stock, Other }

    fun oem(): Oem {
        val manufacturer = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> Oem.Xiaomi
            manufacturer.contains("samsung") -> Oem.Samsung
            manufacturer.contains("oneplus") -> Oem.OnePlus
            manufacturer.contains("oppo") -> Oem.Oppo
            manufacturer.contains("vivo") -> Oem.Vivo
            manufacturer.contains("realme") -> Oem.Realme
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> Oem.Huawei
            manufacturer.contains("google") || manufacturer.contains("nothing") -> Oem.Stock
            else -> Oem.Other
        }
    }

    fun checks(): List<Check> = listOfNotNull(
        batteryOptimisationCheck(),
        exactAlarmCheck(),
        oemAutostartCheck(),
        dataSaverCheck(),
    )

    private fun batteryOptimisationCheck(): Check {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val ignoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        return Check(
            id = "battery_optimisation",
            title = "Battery optimisation",
            explanation = "Android may pause scheduled Flows to save power. Allowing KaivoFlow to ignore " +
                "battery optimisation keeps your schedules on time.",
            ok = ignoring,
            action = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    }

    private fun exactAlarmCheck(): Check {
        val canSchedule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
        } else true
        return Check(
            id = "exact_alarms",
            title = "Exact reminders",
            explanation = "Without this, Android may delay a \"daily at 8 AM\" Flow by up to an hour.",
            ok = canSchedule,
            action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } else null,
        )
    }

    private fun oemAutostartCheck(): Check {
        val (title, explanation, component) = when (oem()) {
            Oem.Xiaomi -> Triple(
                "MIUI autostart",
                "Turn on Autostart for KaivoFlow, then set Battery saver to \"No restrictions\".",
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            )
            Oem.Samsung -> Triple(
                "Samsung sleeping apps",
                "Add KaivoFlow to \"Never sleeping apps\" so scheduled Flows are not put to sleep.",
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            )
            Oem.OnePlus, Oem.Oppo, Oem.Realme -> Triple(
                "App launch settings",
                "Allow KaivoFlow to auto-launch and run in the background.",
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            )
            Oem.Vivo -> Triple(
                "Background power",
                "Allow KaivoFlow to run in the background in iManager → Power manager.",
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            )
            Oem.Huawei -> Triple(
                "Protected apps",
                "Add KaivoFlow to Protected apps so it is not closed when the screen locks.",
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )
            else -> Triple(
                "Background data",
                "KaivoFlow follows Android's standard rules on this device. Nothing extra to do.",
                null,
            )
        }
        val action = component?.let {
            Intent().apply {
                this.component = it
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val available = action?.let { intent -> intent.resolveActivity(context.packageManager) != null } ?: true
        return Check(
            id = "oem_autostart",
            title = title,
            explanation = explanation,
            ok = oem() == Oem.Stock || oem() == Oem.Other,
            action = if (available) action else appDetailsIntent(context.packageName),
        )
    }

    private fun dataSaverCheck(): Check {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val restricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        } else false
        return Check(
            id = "data_saver",
            title = "Data Saver",
            explanation = "Data Saver blocks background data, so Flows that call an API will wait until you open the app.",
            ok = !restricted,
            action = Intent(Settings.ACTION_DATA_USAGE_SETTINGS),
        )
    }

    fun appDetailsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") }

    /** Opens [intent] if anything can handle it, otherwise the app's own settings page. */
    fun open(context: Context, intent: Intent?): Boolean {
        val target = intent?.takeIf { it.resolveActivity(context.packageManager) != null }
            ?: appDetailsIntent(context.packageName)
        return runCatching { context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
