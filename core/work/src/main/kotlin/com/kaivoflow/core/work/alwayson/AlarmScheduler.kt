package com.kaivoflow.core.work.alwayson

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.kaivoflow.core.engine.alwayson.AlarmKeys
import com.kaivoflow.core.engine.alwayson.PrecisionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ArmResult {
    data object Armed : ArmResult
    /** The platform refused (exact-alarm permission missing or revoked). The caller degrades to Flexible. */
    data class Denied(val detail: String) : ArmResult
}

/**
 * Every timer is an alarm (spec 3.1 rule 3). Thin wrapper over [AlarmManager]: identity comes from [AlarmKeys], so setting the
 * same alarm again replaces it, which is what makes the Reconciler's blanket re-arm safe.
 *
 * API notes (26 to 36): `canScheduleExactAlarms` exists from API 31 (below that exact alarms need no permission). On 31 and 32
 * `SCHEDULE_EXACT_ALARM` is granted by default, on 33+ the sideload flavour holds `USE_EXACT_ALARM`, on 34+ a fresh install of the
 * play flavour is denied until the user grants it. All PendingIntents are IMMUTABLE and explicit.
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)

    /** Intent for the Reliability Center's fix button (API 31+). Null below, where nothing needs granting. */
    fun exactAlarmSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else null

    /** Sets the alarm for one Schedule Spark slot. [mode] must be [PrecisionMode.OnTime] or [PrecisionMode.Exact]. */
    fun armSchedule(flowId: String, sparkId: String, slotMs: Long, mode: PrecisionMode): ArmResult {
        require(mode.usesAlarm) { "Flexible schedules use WorkManager, not an alarm" }
        val pi = schedulePi(flowId, sparkId, slotMs, mode, create = true) ?: return ArmResult.Denied("PendingIntent unavailable")
        return try {
            val show = showIntent()
            if (mode == PrecisionMode.OnTime && show != null) {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(slotMs, show), pi)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slotMs, pi)
            }
            ArmResult.Armed
        } catch (e: SecurityException) {
            ArmResult.Denied(e.javaClass.simpleName)
        }
    }

    fun disarmSchedule(flowId: String, sparkId: String) {
        schedulePi(flowId, sparkId, 0L, PrecisionMode.Exact, create = false)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    /** Pulse: a liveness alarm every 10 minutes. Falls back to an inexact while-idle alarm when exact is not allowed. */
    fun armPulse(atMs: Long) = armUtility(AlarmReceiver.ACTION_PULSE, AlarmKeys.PULSE_URI, AlarmKeys.PULSE_CODE, atMs)

    fun cancelPulse() = cancelUtility(AlarmReceiver.ACTION_PULSE, AlarmKeys.PULSE_URI, AlarmKeys.PULSE_CODE)

    /** Restart alarm armed by `onTaskRemoved`, a few seconds ahead (spec 3.10). */
    fun armRestart(atMs: Long) = armUtility(AlarmReceiver.ACTION_RESTART, AlarmKeys.RESTART_URI, AlarmKeys.RESTART_CODE, atMs)

    private fun armUtility(action: String, uri: String, code: Int, atMs: Long) {
        val pi = utilityPi(action, uri, code, create = true) ?: return
        try {
            if (canScheduleExact()) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    private fun cancelUtility(action: String, uri: String, code: Int) {
        utilityPi(action, uri, code, create = false)?.let { alarmManager.cancel(it); it.cancel() }
    }

    private fun schedulePi(flowId: String, sparkId: String, slotMs: Long, mode: PrecisionMode, create: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_SCHEDULE
            data = Uri.parse(AlarmKeys.scheduleUri(flowId, sparkId))
            putExtra(AlarmReceiver.EXTRA_FLOW_ID, flowId)
            putExtra(AlarmReceiver.EXTRA_SPARK_ID, sparkId)
            putExtra(AlarmReceiver.EXTRA_SLOT_MS, slotMs)
            putExtra(AlarmReceiver.EXTRA_MODE, mode.db)
        }
        return PendingIntent.getBroadcast(context, AlarmKeys.requestCode(flowId, sparkId), intent, flags(create))
    }

    private fun utilityPi(action: String, uri: String, code: Int, create: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            data = Uri.parse(uri)
        }
        return PendingIntent.getBroadcast(context, code, intent, flags(create))
    }

    private fun flags(create: Boolean): Int =
        PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE

    /** Where the system alarm-clock UI sends the user when they tap the next-alarm indicator. */
    private fun showIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
