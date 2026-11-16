package com.kaivoflow.core.work.alwayson

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.kaivoflow.core.data.EngineSessionEntity
import com.kaivoflow.core.data.EngineTelemetryRepository
import com.kaivoflow.core.data.ScheduleRepository
import com.kaivoflow.core.engine.alwayson.EngineEvent
import com.kaivoflow.core.engine.alwayson.InboxStore
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.StartReason
import com.kaivoflow.core.work.WebhookController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * The Always-On Engine's foreground service (spec 3.4): type `specialUse`, sticky, one stable notification.
 *
 * It hosts the Inbox consumer ([RunHost]) and the webhook listener, and runs the Reconciler on every start. It does not hold a
 * standing wake lock. After a kill the system restarts it with a null intent (START_STICKY); after a swipe from Recents on an
 * OEM that kills the process, the restart alarm armed by [onTaskRemoved] brings it back.
 */
@AndroidEntryPoint
class AlwaysOnService : Service() {

    @Inject lateinit var controller: EngineController
    @Inject lateinit var runtime: EngineRuntime
    @Inject lateinit var notifications: EngineNotifications
    @Inject lateinit var host: RunHost
    @Inject lateinit var reconciler: Reconciler
    @Inject lateinit var webhook: WebhookController
    @Inject lateinit var telemetry: EngineTelemetryRepository
    @Inject lateinit var recorder: ReliabilityRecorder
    @Inject lateinit var alarms: AlarmScheduler
    @Inject lateinit var schedules: ScheduleRepository
    @Inject lateinit var inboxStore: InboxStore
    @Inject lateinit var fallback: FallbackHost

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bootstrapped = false
    private var refreshJob: Job? = null
    private var idleJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote first: the platform gives a few seconds between startForegroundService and startForeground.
        if (!goForeground("Starting")) {
            stopSelf()
            return START_NOT_STICKY
        }
        runtime.serviceAlive = true
        runtime.stopRequested = false
        runtime.reduce(EngineEvent.ServiceStarted)
        // A null intent is the system's sticky restart.
        val reason = intent?.getStringExtra(EXTRA_REASON)?.let { runCatching { StartReason.valueOf(it) }.getOrNull() } ?: StartReason.StickyRestart
        if (intent?.getBooleanExtra(EXTRA_TRANSIENT, false) == true && !bootstrapped) runtime.transientSession = true

        if (!bootstrapped) {
            bootstrapped = true
            scope.launch { bootstrap(reason) }
        } else {
            scope.launch { refresh() }
        }
        return START_STICKY
    }

    /** API 35+ signature. Compiled against 34, so it carries no `override`; the platform dispatches by name and descriptor. */
    @Suppress("unused")
    fun onTimeout(startId: Int, fgsType: Int) = handleTimeout("fgsType=$fgsType")

    /** API 34 signature (called for the types that have a timeout; `specialUse` has none today). */
    override fun onTimeout(startId: Int) = handleTimeout("api34")

    private fun handleTimeout(detail: String) {
        scope.launch { recorder.record(ReliabilityKinds.SERVICE_TIMEOUT, Severity.WARN, detail = detail) }
        // Stop within the platform's few seconds and come straight back through an exempt alarm start. No crash.
        alarms.armRestart(System.currentTimeMillis() + RESTART_DELAY_MS)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // stopWithTask=false keeps the service alive on stock Android. On OEMs that kill the process anyway, an alarm
        // the system holds restarts the Engine a few seconds later.
        alarms.armRestart(System.currentTimeMillis() + RESTART_DELAY_MS)
        scope.launch { recorder.record(ReliabilityKinds.TASK_REMOVED, Severity.INFO) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val deliberate = runtime.stopRequested
        runtime.serviceAlive = false
        runtime.transientSession = false
        runtime.reduce(EngineEvent.ServiceStopped(clean = deliberate))
        host.stop()
        webhook.stopServer()
        val session = runtime.sessionId
        // Fire and forget on a scope that outlives the service: onDestroy must not block the main thread on the database.
        farewell.launch {
            runCatching {
                if (session != null) {
                    if (deliberate) telemetry.stopSession(session, System.currentTimeMillis(), "Stopped")
                    else telemetry.markSessionDied(session, "ServiceDestroyed")
                }
            }
            if (!deliberate) runCatching { if (inboxStore.openCount() > 0) fallback.ensureDrain() }
        }
        runtime.sessionId = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun bootstrap(reason: StartReason) {
        runCatching {
            val boot = bootCount(this)
            runtime.sessionId = telemetry.startSession(
                EngineSessionEntity(startedAt = System.currentTimeMillis(), startReason = reason.name, bootCount = boot, cleanStopAt = null, endReason = null),
            )
        }
        if (reason == StartReason.StickyRestart || reason == StartReason.RestartAlarm) {
            recorder.record(ReliabilityKinds.ENGINE_RESTART, Severity.INFO, detail = reason.name)
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) runtime.reduce(EngineEvent.DegradedReported(true))
        host.start()
        refreshJob = scope.launch { runtime.refresh.collect { refresh() } }
        // Repair first (re-arm, misfire, registrations), then consume: rows the Reconciler enqueues run straight away.
        runCatching { reconciler.reconcile(ReconcileTrigger.ServiceStart) }
        refresh()
        host.nudge()
    }

    /** Re-evaluates needs: notification text, webhook listener, and the end of a transient session. */
    private suspend fun refresh() {
        val needs = runCatching { controller.needs() }.getOrNull() ?: return
        runCatching { webhook.sync(needs.engineNeeded) }
        val next = runCatching { schedules.allStates().mapNotNull { it.nextDueAt }.filter { it > System.currentTimeMillis() }.minOrNull() }.getOrNull()
        val text = buildString {
            append(next?.let { "Next scheduled run ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" } ?: "Listening for events")
        }
        notifications.update(needs.liveFlowCount, text)
        if (needs.engineNeeded) {
            runtime.transientSession = false
            idleJob?.cancel(); idleJob = null
        } else if (runtime.transientSession) {
            watchIdle()
        } else {
            // No Spark needs the Engine any more and this is not a transient session: stop (clean stop, session stamped).
            controller.stop()
        }
    }

    /** A transient session ends when the Inbox has been empty and nothing ran for two checks in a row. */
    private fun watchIdle() {
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            var quiet = 0
            while (isActive) {
                delay(IDLE_CHECK_MS)
                val busy = host.dispatcher.activeCount() > 0 || inboxStore.openCount() > 0
                quiet = if (busy) 0 else quiet + 1
                if (quiet >= 2) { controller.stop(); break }
            }
        }
    }

    /** Foreground promotion with the right type per API level. False when the platform refuses it. */
    private fun goForeground(text: String): Boolean = try {
        val notification = notifications.engineNotification(0, text)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, EngineNotifications.ENGINE_NOTIFICATION_ID, notification, type)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (API 31+) or SecurityException (missing type permission, API 34+).
        runtime.reduce(EngineEvent.StartDenied(e.javaClass.simpleName))
        farewell.launch { recorder.record(ReliabilityKinds.FGS_START_DENIED, Severity.WARN, detail = "promotion: ${e.javaClass.simpleName}") }
        false
    }

    companion object {
        const val EXTRA_REASON = "com.kaivoflow.extra.START_REASON"
        const val EXTRA_TRANSIENT = "com.kaivoflow.extra.TRANSIENT"
        private const val RESTART_DELAY_MS = 5_000L
        private const val IDLE_CHECK_MS = 10_000L
        private val farewell = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
