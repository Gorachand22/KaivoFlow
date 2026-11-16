package com.kaivoflow.core.work.alwayson

import android.content.Context
import android.os.PowerManager
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.WakeLockLedger
import com.kaivoflow.core.engine.alwayson.WakeLockMetrics
import com.kaivoflow.core.engine.alwayson.WakeLockPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only place that takes a wake lock (spec 3.6). There is no standing lock: a Run holds one for the Flow's timeout
 * plus 30 seconds (hard maximum 15 minutes) and receiver intake holds one for at most 20 seconds. Every lock is acquired
 * with a timeout, so even a forgotten release cannot keep the CPU awake, and every hold is counted in the ledger.
 */
@Singleton
class WakeLockManager @Inject constructor(
    @ApplicationContext context: Context,
    private val ledger: WakeLockLedger,
    private val recorder: ReliabilityRecorder,
) {
    private val power = context.getSystemService(PowerManager::class.java)

    /** A held intake lock. Close it when the Inbox row is committed. Safe to close twice. */
    inner class Hold internal constructor(
        private val lock: PowerManager.WakeLock?,
        private val token: WakeLockLedger.Token,
    ) : AutoCloseable {
        override fun close() {
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: RuntimeException) {
                // Already released by the platform timeout.
            } finally {
                ledger.released(token)
            }
        }
    }

    fun acquireIntake(label: String, maxMs: Long = WakeLockPolicy.INTAKE_MAX_MS): Hold =
        hold("KaivoFlow:intake:$label", WakeLockPolicy.intakeTimeoutMs(maxMs))

    /** Holds the CPU for a Run. Always released in `finally`, whatever the Run does. */
    suspend fun <T> withRunLock(label: String, flowTimeoutMs: Long, block: suspend () -> T): T {
        val timeout = WakeLockPolicy.runTimeoutMs(flowTimeoutMs)
        val h = hold("KaivoFlow:run:$label", timeout)
        try {
            return block()
        } finally {
            h.close()
            if (ledger.metrics().timedOutToday > 0) ledger.sweepExpired().forEach {
                recorder.record(ReliabilityKinds.WAKELOCK_TIMEOUT, Severity.WARN, detail = it.tag)
            }
        }
    }

    fun metrics(): WakeLockMetrics = ledger.metrics()

    /** One line for the diagnostics dump (AO-10: the metric is visible in the dump). */
    fun dump(): String = metrics().let {
        "wakelocks heldNow=${it.heldNow} acquiredToday=${it.acquiredToday} heldTodayMs=${it.millisToday} " +
            "longestMs=${it.longestTodayMs} timedOutToday=${it.timedOutToday} overDailyWarning=${it.overDailyWarning}"
    }

    private fun hold(tag: String, timeoutMs: Long): Hold {
        val lock = runCatching {
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)?.apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        }.getOrNull()
        return Hold(lock, ledger.acquired(tag, timeoutMs))
    }
}
