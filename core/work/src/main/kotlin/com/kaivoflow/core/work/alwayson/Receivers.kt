package com.kaivoflow.core.work.alwayson

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.LivenessPolicy
import com.kaivoflow.core.engine.alwayson.StartReason
import com.kaivoflow.core.work.ReceiverEntryPoint
import com.kaivoflow.core.work.ReceiverEntryPoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manifest receiver for every alarm the Engine sets: schedule slots, the Pulse and the restart alarm. It does the minimum
 * (spec 3.1 rule 1): `goAsync` plus the intake wake lock, then the fire path or a Reconciler pass, then `finish`.
 * Dependencies come from [ReceiverEntryPoints] (never field injection: Hilt's generated base class injects inside `onReceive`,
 * which Kotlin cannot call through `super`).
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_SCHEDULE && action != ACTION_PULSE && action != ACTION_RESTART) return
        val entry = ReceiverEntryPoints.of(context)
        val pending = goAsync()
        val hold = entry.wakeLocks().acquireIntake("alarm")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_SCHEDULE -> onSchedule(entry, intent)
                    ACTION_PULSE -> onPulse(entry)
                    ACTION_RESTART -> onRestart(entry)
                }
            } catch (t: Throwable) {
                entry.reliabilityRecorder().record(ReliabilityKinds.RECONCILE_FAILED, Severity.ERROR, detail = "$action: ${t.javaClass.simpleName}")
            } finally {
                hold.close()
                pending.finish()
            }
        }
    }

    private suspend fun onSchedule(entry: ReceiverEntryPoint, intent: Intent) {
        val flowId = intent.getStringExtra(EXTRA_FLOW_ID) ?: return
        val sparkId = intent.getStringExtra(EXTRA_SPARK_ID) ?: return
        val slot = intent.getLongExtra(EXTRA_SLOT_MS, -1L).takeIf { it > 0 }
        // The fire path is short (next slot, one Inbox row). Bound it so a stuck database cannot hold the broadcast for ever.
        withTimeoutOrNull(FIRE_BUDGET_MS) { entry.scheduleFirer().fire(flowId, sparkId, slot, IntakeSource.Alarm) }
    }

    private suspend fun onPulse(entry: ReceiverEntryPoint) {
        // Re-arm the next Pulse FIRST, so a slow or failing pass cannot end the chain.
        entry.alarmScheduler().armPulse(System.currentTimeMillis() + LivenessPolicy.PULSE_INTERVAL_MS)
        val done = withTimeoutOrNull(PULSE_BUDGET_MS) { entry.reconciler().reconcile(ReconcileTrigger.Pulse) }
        if (done == null) entry.watchdogScheduler().runOnceNow() // out of receiver time: let WorkManager finish the pass
    }

    private suspend fun onRestart(entry: ReceiverEntryPoint) {
        // Exact-alarm start is an FGS exemption. reassess starts the Engine only if something needs it.
        entry.engineController().reassess(StartReason.RestartAlarm)
        withTimeoutOrNull(PULSE_BUDGET_MS) { entry.reconciler().reconcile(ReconcileTrigger.ServiceStart) }
    }

    companion object {
        const val ACTION_SCHEDULE = "com.kaivoflow.action.SCHEDULE_FIRE"
        const val ACTION_PULSE = "com.kaivoflow.action.PULSE"
        const val ACTION_RESTART = "com.kaivoflow.action.ENGINE_RESTART"
        const val EXTRA_FLOW_ID = "com.kaivoflow.extra.FLOW_ID"
        const val EXTRA_SPARK_ID = "com.kaivoflow.extra.SPARK_ID"
        const val EXTRA_SLOT_MS = "com.kaivoflow.extra.SLOT_MS"
        const val EXTRA_MODE = "com.kaivoflow.extra.MODE"
        private const val FIRE_BUDGET_MS = 8_000L
        private const val PULSE_BUDGET_MS = 8_000L
    }
}

/**
 * `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (API 31+). When the user grants the permission, schedules that were
 * degraded to Flexible return to exact alarms; the planner resolves the effective mode again. (When the user revokes it, the
 * system stops the app and cancels its exact alarms, so recovery happens at the next start.)
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val entry = ReceiverEntryPoints.of(context)
        val pending = goAsync()
        val hold = entry.wakeLocks().acquireIntake("exactAlarmPermission")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8_000L) { entry.reconciler().reconcile(ReconcileTrigger.ExactAlarmPermissionChanged) }
            } finally {
                hold.close()
                pending.finish()
            }
        }
    }

    companion object {
        // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED, spelled out so the class loads on API 26 to 30.
        const val ACTION = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
