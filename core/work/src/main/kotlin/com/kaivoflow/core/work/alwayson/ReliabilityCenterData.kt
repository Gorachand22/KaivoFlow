package com.kaivoflow.core.work.alwayson

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kaivoflow.core.data.EngineTelemetryRepository
import com.kaivoflow.core.data.KaivoDatabase
import com.kaivoflow.core.data.ReliabilityEventEntity
import com.kaivoflow.core.data.ScheduleRepository
import com.kaivoflow.core.engine.PlatformFacts
import com.kaivoflow.core.engine.SparkTypes
import com.kaivoflow.core.engine.alwayson.Clock
import com.kaivoflow.core.engine.alwayson.ListenerTracker
import com.kaivoflow.core.engine.alwayson.ReachMode
import com.kaivoflow.core.engine.alwayson.WebhookListenerStatus
import com.kaivoflow.core.engine.reliability.CheckEvaluator
import com.kaivoflow.core.engine.reliability.CheckResult
import com.kaivoflow.core.engine.reliability.DayOutcome
import com.kaivoflow.core.engine.reliability.DeviceFacts
import com.kaivoflow.core.engine.reliability.ExpectedSample
import com.kaivoflow.core.engine.reliability.FixAction
import com.kaivoflow.core.engine.reliability.FlowReliabilityCalc
import com.kaivoflow.core.engine.reliability.HealthScore
import com.kaivoflow.core.engine.reliability.HealthScorer
import com.kaivoflow.core.engine.reliability.MeasuredFacts
import com.kaivoflow.core.engine.reliability.RunAggregation
import com.kaivoflow.core.engine.reliability.WebhookFacts
import com.kaivoflow.core.engine.LifecycleState
import com.kaivoflow.core.work.BatteryHealth
import com.kaivoflow.core.work.WebhookServer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The device facts the publish validator needs (spec 4.3 rules 4 and warnings). Every value is read from the platform, never assumed. */
@Singleton
class PlatformFactsReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarms: AlarmScheduler,
    private val settings: ReliabilitySettings,
    private val battery: BatteryHealth,
) {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun read(): PlatformFacts {
        val pm = context.getSystemService(PowerManager::class.java)
        return PlatformFacts(
            receiveSmsGranted = granted(Manifest.permission.RECEIVE_SMS),
            fineLocationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundLocationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notificationAccessBound = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
            webhookTokenExists = true, // the token is created on first use and cannot be missing
            batteryOptimisationExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true,
            exactAlarmAllowed = alarms.canScheduleExact(),
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            // Cannot be read: only an attestation backed by the soak test counts, and that is checked in C10, not here.
            oemAutostartVerified = battery.oem().let { it == BatteryHealth.Oem.Stock || it == BatteryHealth.Oem.Other } ||
                (settings.oemAttestedAt > 0 && settings.soakPassedAt > 0),
        )
    }
}

/** Flow health row of the Reliability Center. */
data class FlowHealth(
    val flowId: String,
    val name: String,
    val state: LifecycleState,
    val onTimeRatio: Double?,
    val measured: Int,
    val strip: List<DayOutcome>,
    val nextRunAt: Long?,
    val lastLateOrMissedAt: Long?,
    val errorCode: String?,
    val retryAt: Long?,
)

data class ReliabilitySnapshot(
    val at: Long,
    val checks: List<CheckResult>,
    val score: HealthScore,
    val fixFirst: List<CheckResult>,
    val engineState: String,
    val lastHeartbeatAt: Long?,
    val publishedFlows: Int,
    val errorFlows: Int,
    val flowHealth: List<FlowHealth>,
    val oemName: String,
)

/** Deep links with fallbacks (spec 5.3). Every intent is resolved before use: an ActivityNotFoundException is a review complaint. */
@Singleton
class FixLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val battery: BatteryHealth,
    private val controller: EngineController,
    private val alarmScheduler: AlarmScheduler,
) {
    private val pkg get() = context.packageName
    private fun pkgUri() = Uri.parse("package:$pkg")
    private fun appDetails() = battery.appDetailsIntent(pkg)

    /** Candidate intents, best first. The first that resolves wins; the last is always app details. */
    fun candidates(fix: FixAction): List<Intent> = when (fix) {
        FixAction.BatteryExemption -> listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(pkgUri()),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        FixAction.NotificationSettings ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) listOf(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg).putExtra(Settings.EXTRA_CHANNEL_ID, EngineNotifications.CHANNEL_ENGINE),
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
            ) else listOf(appDetails())
        FixAction.ExactAlarm -> listOfNotNull(alarmScheduler.exactAlarmSettingsIntent())
        FixAction.ListenerSettings -> buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    android.content.ComponentName(context.packageName, NotificationListenerHub.LISTENER_CLASS).flattenToString(),
                ),
            )
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        FixAction.DataSaver ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) listOf(Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS).setData(pkgUri()), Intent(Settings.ACTION_DATA_USAGE_SETTINGS))
            else listOf(Intent(Settings.ACTION_DATA_USAGE_SETTINGS))
        FixAction.BatterySaver -> listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        FixAction.DateSettings -> listOf(Intent(Settings.ACTION_DATE_SETTINGS))
        FixAction.StorageSettings -> listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        FixAction.LocationPermission -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), appDetails())
        FixAction.OemAutostart -> listOf(battery.autostartIntent())
        FixAction.OemGuide -> listOf(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/${oemSlug()}")))
        FixAction.AppDetails, FixAction.SmsPermission, FixAction.WebhookSettings, FixAction.ReduceMemory, FixAction.RestartEngine, FixAction.None -> emptyList()
    } + appDetails()

    private fun oemSlug() = when (battery.oem()) {
        BatteryHealth.Oem.Xiaomi -> "xiaomi"; BatteryHealth.Oem.Samsung -> "samsung"; BatteryHealth.Oem.OnePlus -> "oneplus"
        BatteryHealth.Oem.Oppo -> "oppo"; BatteryHealth.Oem.Vivo -> "vivo"; BatteryHealth.Oem.Realme -> "realme"
        BatteryHealth.Oem.Huawei -> "huawei"; else -> ""
    }

    /** Intent-based fixes only. [FixAction.RestartEngine] and in-app actions are handled by the caller. Returns true when something opened. */
    fun launch(activityContext: Context, fix: FixAction): Boolean {
        val pm = activityContext.packageManager
        for (intent in candidates(fix)) {
            if (intent.resolveActivity(pm) == null) continue
            if (runCatching { activityContext.startActivity(intent.addFlags(if (activityContext !is android.app.Activity) Intent.FLAG_ACTIVITY_NEW_TASK else 0)) }.isSuccess) return true
        }
        return false
    }

    suspend fun restartEngine(): Boolean = controller.start(com.kaivoflow.core.engine.alwayson.StartReason.UserTap, transient = false, notifyOnDenial = false)
}

/**
 * AO-20 data: reads every fact, runs the pure checks and scorer, adds the Flow health strips. Called when the screen opens, on
 * resume (the user may have changed a setting) and every 30 seconds while visible. Nothing here writes.
 */
@Singleton
class ReliabilityCenterRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val liveFlows: LiveFlows,
    private val telemetry: EngineTelemetryRepository,
    private val schedules: ScheduleRepository,
    private val runtime: EngineRuntime,
    private val settings: ReliabilitySettings,
    private val engineSettings: EngineSettings,
    private val alarms: AlarmScheduler,
    private val battery: BatteryHealth,
    private val listener: ListenerTracker,
    private val webhookServer: WebhookServer,
    private val webhookAdmin: WebhookAdmin,
    private val platformFacts: PlatformFactsReader,
    private val exitInfo: ExitInfoCapture,
    private val clock: Clock,
) {
    suspend fun snapshot(): ReliabilitySnapshot {
        val now = clock.now()
        val entries = liveFlows.entries()
        val live = entries.filter { it.snapshot.state.isLive }
        val facts = readFacts(now, live)
        val checks = CheckEvaluator.evaluate(facts)
        val dailySince = now - 15 * RunAggregation.DAY_MS
        val aggregates = schedules.dailySince(dailySince).map {
            com.kaivoflow.core.engine.reliability.DailyAggregate(it.flowId, it.dayStart, it.fired, it.late, it.missed, it.skipped, it.paused, it.p50Ms, it.p95Ms, it.maxMs)
        }
        val raw = runCatching { schedules.dueSince(dailySince) }.getOrDefault(emptyList())
        val samples = raw.map { ExpectedSample(it.flowId, it.dueAt, it.state, it.latenessMs) }
        val offset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val nextByFlow = schedules.allStates().filter { it.nextDueAt != null }.groupBy { it.flowId }.mapValues { (_, v) -> v.minOf { it.nextDueAt!! } }
        val health = live.map { e ->
            val rel = FlowReliabilityCalc.compute(e.flow.id, aggregates, samples, now, 14, offset)
            FlowHealth(e.flow.id, e.flow.name, e.snapshot.state, rel.onTimeRatio, rel.measured, rel.strip, nextByFlow[e.flow.id], rel.lastLateOrMissed, e.snapshot.errorCode, e.snapshot.nextRetryAt)
        }
        // Measured facts across all live Flows over 14 days.
        val allRel = live.map { FlowReliabilityCalc.compute(it.flow.id, aggregates, samples, now, 14, offset) }
        val measured = allRel.sumOf { it.measured }
        val weighted = allRel.sumOf { (it.onTimeRatio ?: 0.0) * it.measured }
        val dead7d = telemetry.countSince("EngineDead", now - 7 * RunAggregation.DAY_MS)
        val missed7d = samples.count { it.state == "MISSED" && it.dueAt >= now - 7 * RunAggregation.DAY_MS }
        val score = HealthScorer.score(checks, MeasuredFacts(measured, if (measured == 0) 0.0 else weighted / measured, dead7d, missed7d))
        return ReliabilitySnapshot(
            now, CheckEvaluator.visible(checks), score, HealthScorer.fixFirst(checks), runtime.state.value.name,
            telemetry.heartbeat()?.lastBeatWall, live.size, live.count { it.snapshot.state == LifecycleState.Error }, health, battery.oem().name,
        )
    }

    private suspend fun readFacts(now: Long, live: List<LiveFlows.Entry>): DeviceFacts {
        val pf = platformFacts.read()
        val triggers = live.flatMap { e -> e.flow.triggers.filter { !it.disabled }.map { e.flow to it } }
        fun uses(type: String) = triggers.any { it.second.type == type }
        val usesExact = triggers.any { it.second.type == SparkTypes.SCHEDULE && it.second.paramString("precision", "exact").lowercase() != "flexible" }
        val pm = context.getSystemService(PowerManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(ActivityManager::class.java)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val ln = listener.snapshot()
        val oem = battery.oem()
        val aggressive = oem !in setOf(BatteryHealth.Oem.Stock, BatteryHealth.Oem.Other)
        val gaps = buildList {
            for ((flow, spark) in triggers) when (spark.type) {
                SparkTypes.SMS -> if (!pf.receiveSmsGranted) add("Flow \"${flow.name}\" needs permission to receive SMS.")
                SparkTypes.GEOFENCE -> if (!pf.backgroundLocationGranted || !pf.fineLocationGranted) add("Flow \"${flow.name}\" needs location \"Allow all the time\".")
                SparkTypes.NOTIFICATION -> if (!pf.notificationAccessBound) add("Flow \"${flow.name}\" needs notification access.")
            }
        }.distinct()
        val channelBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm?.getNotificationChannel(EngineNotifications.CHANNEL_ENGINE)?.importance == NotificationManager.IMPORTANCE_NONE
        val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket }.getOrNull() else null
        val status = webhookServer.status.value
        val webhook = if (uses(SparkTypes.WEBHOOK)) WebhookFacts(
            bound = status is WebhookListenerStatus.Listening, port = engineSettings.webhookPort,
            lan = webhookAdmin.reachMode == ReachMode.Lan, lanAddress = webhookAdmin.lanAddresses().firstOrNull(),
            probeOk = settings.webhookProbeOk,
        ) else null
        val dbFile = context.getDatabasePath(KaivoDatabase.NAME)
        val lowMem = runCatching {
            telemetry.eventsOfKinds(listOf("ProcessDied"), now - 7 * RunAggregation.DAY_MS).count { it.detail.orEmpty().startsWith("LowMemory") }
        }.getOrDefault(0)
        return DeviceFacts(
            now = now, sdk = Build.VERSION.SDK_INT, publishedFlows = live.size,
            errorFlows = live.count { it.snapshot.state == LifecycleState.Error }, alwaysOnMode = engineSettings.alwaysOnMode,
            usesExactSchedule = usesExact, usesNotificationSpark = uses(SparkTypes.NOTIFICATION), usesSmsSpark = uses(SparkTypes.SMS),
            usesSendSms = live.any { e -> e.flow.steps.any { it.type == "device.sms" && !it.disabled } }, usesGeofence = uses(SparkTypes.GEOFENCE),
            usesWebhook = uses(SparkTypes.WEBHOOK),
            heartbeatWall = telemetry.heartbeat()?.lastBeatWall, engineState = runtime.state.value,
            batteryExempt = pf.batteryOptimisationExempt, notificationsEnabled = pf.notificationsAllowed, engineChannelBlocked = channelBlocked,
            exactAlarmAllowed = pf.exactAlarmAllowed,
            backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && am?.isBackgroundRestricted == true,
            standbyBucket = bucket, listenerGranted = pf.notificationAccessBound,
            listenerConnectedAt = ln.lastConnectedAt, listenerDisconnectedAt = ln.lastDisconnectedAt,
            receiveSmsGranted = pf.receiveSmsGranted, sendSmsGranted = platformFacts.granted(Manifest.permission.SEND_SMS),
            fineLocation = pf.fineLocationGranted, backgroundLocation = pf.backgroundLocationGranted,
            locationServicesOn = runCatching { androidx.core.location.LocationManagerCompat.isLocationEnabled(context.getSystemService(android.location.LocationManager::class.java)) }.getOrDefault(true),
            flowPermissionGaps = gaps, aggressiveOem = aggressive, oemName = oem.name,
            oemAttested = settings.oemAttestedAt > 0, soakPassedAt = settings.soakPassedAt.takeIf { it > 0 },
            dataSaverStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) cm?.restrictBackgroundStatus else null,
            batterySaverOn = pm?.isPowerSaveMode == true,
            autoTime = Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) == 1,
            autoZone = Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE, 1) == 1,
            exitInfoAvailable = exitInfo.available, forceStoppedAt = settings.lastForceStopAt.takeIf { it > 0 }, lowMemoryKills7d = lowMem,
            webhook = webhook, freeStorageBytes = context.filesDir.usableSpace, databaseBytes = dbFile.length() + context.getDatabasePath(KaivoDatabase.NAME + "-wal").length(),
            integrityOk = integrityOk(dbFile, now),
        )
    }

    /** `PRAGMA quick_check` on a read-only handle, at most once a day. Null when it has never completed. */
    private fun integrityOk(db: java.io.File, now: Long): Boolean? {
        if (now - settings.lastIntegrityOkAt < 24 * 3_600_000L && settings.lastIntegrityOkAt > 0) return true
        return runCatching {
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                d.rawQuery("PRAGMA quick_check(1)", null).use { it.moveToFirst() && it.getString(0) == "ok" }
            }.also { ok -> if (ok) settings.lastIntegrityOkAt = now }
        }.getOrNull()
    }

    suspend fun events(limit: Int = 300): List<ReliabilityEventEntity> = telemetry.recentEvents(limit)
    fun unacknowledged() = telemetry.observeUnacknowledged()
    suspend fun acknowledgeAll() { telemetry.acknowledgeAll() }
    fun attestOem() { settings.oemAttestedAt = clock.now() }
}
