package com.kaivoflow.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.engine.reliability.SelfTestId
import com.kaivoflow.core.work.alwayson.ReliabilityDump
import com.kaivoflow.core.work.alwayson.ReliabilitySettings
import com.kaivoflow.core.work.alwayson.Reconciler
import com.kaivoflow.core.work.alwayson.SelfTestRunner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiagEntryPoint {
    fun dump(): ReliabilityDump
    fun reconciler(): Reconciler
    fun selfTest(): SelfTestRunner
    fun reliabilitySettings(): ReliabilitySettings
}

/**
 * Debug-only helper for the device test plan (docs/specs/P2-always-on-engine.md section 7). Driven by `scripts/adb-*.sh`:
 *
 *  - `com.kaivoflow.DIAG_DUMP`       writes `files/reliability-dump.json` (read it with `run-as`)
 *  - `com.kaivoflow.DIAG_RECONCILE`  runs a Reconciler pass (extra `trigger`, default Pulse)
 *  - `com.kaivoflow.DIAG_SELFTEST`   starts self-tests (extra `ids`, comma separated, default ST1,ST2,ST4,ST5)
 *  - `com.kaivoflow.DIAG_EVALUATE`   re-evaluates the long self-tests and logs the outcome
 *  - `com.kaivoflow.DIAG_ATTEST_OEM` marks the OEM autostart step as done (the "I did this" tap)
 *
 * Output goes to logcat under the tag `KaivoDiag` and is the machine readable line `DIAG <action> ok|failed ...`.
 */
class DiagReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, DiagEntryPoint::class.java)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_DUMP -> Log.i(TAG, "DIAG dump ok ${entry.dump().writeToFile().absolutePath}")
                    ACTION_RECONCILE -> {
                        val t = intent.getStringExtra("trigger")?.let { runCatching { ReconcileTrigger.valueOf(it) }.getOrNull() } ?: ReconcileTrigger.Pulse
                        val r = entry.reconciler().reconcile(t)
                        Log.i(TAG, "DIAG reconcile ok trigger=${t.name} live=${r.liveFlows} engine=${r.engineAction} failed=${r.failed}")
                    }
                    ACTION_SELFTEST -> {
                        val ids = intent.getStringExtra("ids")?.split(',')?.mapNotNull { runCatching { SelfTestId.valueOf(it.trim().uppercase()) }.getOrNull() }?.toSet()
                            ?: setOf(SelfTestId.ST1, SelfTestId.ST2, SelfTestId.ST4, SelfTestId.ST5)
                        entry.selfTest().start(ids)
                        Log.i(TAG, "DIAG selftest ok started=${ids.joinToString()}")
                    }
                    ACTION_EVALUATE -> {
                        entry.selfTest().evaluate()
                        entry.selfTest().results.value.values.sortedBy { it.id }.forEach { Log.i(TAG, "DIAG result ${it.id} ${it.state} ${it.detail}") }
                    }
                    ACTION_ATTEST_OEM -> { entry.reliabilitySettings().oemAttestedAt = System.currentTimeMillis(); Log.i(TAG, "DIAG attest ok") }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "DIAG ${action.substringAfterLast('.')} failed ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "KaivoDiag"
        const val ACTION_DUMP = "com.kaivoflow.DIAG_DUMP"
        const val ACTION_RECONCILE = "com.kaivoflow.DIAG_RECONCILE"
        const val ACTION_SELFTEST = "com.kaivoflow.DIAG_SELFTEST"
        const val ACTION_EVALUATE = "com.kaivoflow.DIAG_EVALUATE"
        const val ACTION_ATTEST_OEM = "com.kaivoflow.DIAG_ATTEST_OEM"
    }
}
