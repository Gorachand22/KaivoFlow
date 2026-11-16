package com.kaivoflow.core.engine.alwayson

import com.kaivoflow.core.engine.FailureCode
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import java.security.MessageDigest

/*
 * Event Spark sources (AO-12, AO-14): the decisions behind SMS, notification, battery, Wi-Fi, Bluetooth and headset events.
 * The Android classes only translate platform callbacks into calls on these.
 */

/**
 * Source ids for Inbox dedupe keys (the Inbox prefixes kind, Flow and Spark). A repeated delivery of the same occurrence gets the
 * same id, so it is dropped with a record instead of running twice. Identifiers that are private (message text, sender, SSID,
 * device address) are hashed: a key is stored in the database and shown in the diagnostics, and must not leak content.
 */
object EventKeys {
    /** Occurrences of the same kind within this window are one occurrence (flapping Wi-Fi, a doubled broadcast). */
    const val BURST_WINDOW_MS = 10_000L
    const val BATTERY_WINDOW_MS = 60_000L

    fun sms(sender: String, timestampMs: Long, body: String) = "sms:${digest("$sender\u0000$body")}:$timestampMs"

    /** [sbnKey] is `StatusBarNotification.key` (package, id, tag, user); [postTime] changes when the notification is updated. */
    fun notification(sbnKey: String, postTime: Long) = "notif:${digest(sbnKey)}:$postTime"

    fun battery(edge: String, level: Int, atMs: Long) = "battery:$edge:$level:${atMs / BATTERY_WINDOW_MS}"

    fun wifi(event: String, ssid: String, atMs: Long) = "wifi:$event:${digest(ssid)}:${atMs / BURST_WINDOW_MS}"

    fun bluetooth(event: String, address: String, atMs: Long) = "bt:$event:${digest(address.uppercase())}:${atMs / BURST_WINDOW_MS}"

    fun headset(event: String, atMs: Long) = "headset:$event:${atMs / BURST_WINDOW_MS}"

    fun geofence(requestId: String, transition: String, atMs: Long) = "geo:${digest(requestId)}:$transition:${atMs / 30_000L}"

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
}

// ---- Battery -------------------------------------------------------------------------------------

/**
 * Edge detection for the battery Spark so a falling battery fires once per threshold crossing instead of once per percent.
 * (Moved here from core:work so it runs in the JVM tests.)
 */
object BatteryEdges {
    fun shouldDispatch(previousLevel: Int?, level: Int, wasCharging: Boolean?, charging: Boolean, thresholds: Collection<Int>): Boolean {
        if (previousLevel == null || wasCharging == null) return false
        if (!wasCharging && charging) return true
        if (charging) return false
        return thresholds.any { previousLevel > it && level <= it }
    }
}

enum class BatteryAction { Changed, Low, Okay, PowerConnected, PowerDisconnected }

data class BatterySignal(val edge: String, val level: Int, val charging: Boolean)

/**
 * Turns the battery broadcasts the Engine receives into at most one signal per real edge. Power connect and disconnect
 * broadcasts carry no level, so the tracker keeps the last known one (the earlier code returned early for them and never fired).
 * The platform "battery low" broadcast fires near 15 percent on its own schedule; it is only a fallback for the first sample after
 * start, otherwise it would duplicate the crossing the thresholds already produce.
 */
class BatteryTracker(private val thresholds: () -> Collection<Int>) {
    private var lastLevel: Int? = null
    private var lastCharging: Boolean? = null

    /** Seeds from the sticky `ACTION_BATTERY_CHANGED` at registration, so a restart does not invent an edge. */
    @Synchronized
    fun seed(level: Int, charging: Boolean) { lastLevel = level; lastCharging = charging }

    @Synchronized
    fun onBroadcast(action: BatteryAction, levelPercent: Int?, chargingNow: Boolean?): BatterySignal? {
        val previousLevel = lastLevel
        val wasCharging = lastCharging
        val level = levelPercent ?: previousLevel
        val charging = when (action) {
            BatteryAction.PowerConnected -> true
            BatteryAction.PowerDisconnected -> false
            else -> chargingNow ?: wasCharging
        }
        if (level == null || charging == null) return null
        if (action != BatteryAction.PowerConnected && action != BatteryAction.PowerDisconnected) lastLevel = level
        lastCharging = charging
        return when (action) {
            BatteryAction.Changed, BatteryAction.PowerConnected -> {
                val fire = BatteryEdges.shouldDispatch(previousLevel ?: level, level, wasCharging, charging, thresholds())
                when {
                    !fire -> null
                    !(wasCharging ?: charging) && charging -> BatterySignal("charging", level, true)
                    else -> BatterySignal("low", level, charging)
                }
            }
            BatteryAction.Low ->
                if (previousLevel == null && !charging && thresholds().any { it >= level }) BatterySignal("low", level, false) else null
            BatteryAction.Okay, BatteryAction.PowerDisconnected -> null
        }
    }
}

/** The live probe of spec 6.4: does the dynamic receiver still receive? A sticky level far from what the Engine last saw says no. */
object BatteryEvidence {
    const val LEVEL_TOLERANCE = 3
    const val STALE_AFTER_MS = 30 * 60_000L

    fun receiverHealthy(stickyLevel: Int, lastSeenLevel: Int?, lastSeenAt: Long?, now: Long): Boolean {
        if (lastSeenLevel == null || lastSeenAt == null) return false
        // A phone that sat at one level for a long time sends no broadcast: only a mismatch proves a dead receiver.
        if (Math.abs(stickyLevel - lastSeenLevel) <= LEVEL_TOLERANCE) return true
        return now - lastSeenAt < STALE_AFTER_MS
    }
}

// ---- Wi-Fi ---------------------------------------------------------------------------------------

data class WifiSignal(val event: String, val ssid: String)

/**
 * Wi-Fi edges from a `NetworkCallback` that reports capability changes many times per connection (signal strength) and one
 * `onLost` per network. Tracks networks by their handle, so roaming from A to B reports "connected B" and later "disconnected A"
 * (the lost handle remembers its own SSID), and a signal-strength update reports nothing.
 */
class WifiTracker {
    private val known = HashMap<String, String>()

    /** [ssid] null or "<unknown ssid>" means the platform withheld it (no location grant): no event can be produced. */
    @Synchronized
    fun onCapabilities(networkKey: String, ssid: String?): WifiSignal? {
        val clean = ssid?.trim('"')?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) } ?: return null
        if (known[networkKey] == clean) return null
        known[networkKey] = clean
        return WifiSignal("connected", clean)
    }

    @Synchronized
    fun onLost(networkKey: String): WifiSignal? = known.remove(networkKey)?.let { WifiSignal("disconnected", it) }

    @Synchronized
    fun reset() = known.clear()
}

// ---- Notification listener -----------------------------------------------------------------------

/** What to do about the notification listener on a Pulse (spec 3.10: "requestRebind; state shown; retried on each Pulse"). */
sealed interface ListenerAction {
    data object None : ListenerAction
    data object RequestRebind : ListenerAction
    /** The user has not granted notification access, or revoked it. Nothing to rebind. */
    data object AccessMissing : ListenerAction
}

/**
 * Connection bookkeeping for `KaivoNotificationListener`. The system binds the listener; the app can only watch the
 * connected/disconnected callbacks and call `requestRebind` when the connection drops. Calls are rate-limited with a back-off
 * that resets on a successful connection, so a listener the system keeps refusing does not spin.
 */
class ListenerTracker(private val clock: Clock, private val backoffMs: LongArray = BACKOFF_MS) {
    data class Snapshot(
        val connected: Boolean,
        val lastConnectedAt: Long?,
        val lastDisconnectedAt: Long?,
        val rebindAttempts: Int,
        val nextRebindAt: Long?,
    )

    private var connected = false
    private var lastConnectedAt: Long? = null
    private var lastDisconnectedAt: Long? = null
    private var attempts = 0
    private var nextRebindAt: Long? = null

    /** Returns how long the listener was away when this is a reconnect (for the `ListenerReconnected` event), else null. */
    @Synchronized
    fun onConnected(): Long? {
        val now = clock.now()
        val away = lastDisconnectedAt?.takeIf { lastConnectedAt != null && it >= lastConnectedAt!! }?.let { now - it }
        connected = true
        lastConnectedAt = now
        attempts = 0
        nextRebindAt = null
        return away
    }

    /** The system dropped the connection. Returns true when a rebind should be requested right now. */
    @Synchronized
    fun onDisconnected(): Boolean {
        val now = clock.now()
        connected = false
        lastDisconnectedAt = now
        return takeRebindSlot(now)
    }

    /** Called by the Reconciler. [accessGranted] comes from the enabled-listeners setting. */
    @Synchronized
    fun reconcile(accessGranted: Boolean): ListenerAction {
        if (!accessGranted) { connected = false; return ListenerAction.AccessMissing }
        if (connected) return ListenerAction.None
        return if (takeRebindSlot(clock.now())) ListenerAction.RequestRebind else ListenerAction.None
    }

    @Synchronized
    fun snapshot() = Snapshot(connected, lastConnectedAt, lastDisconnectedAt, attempts, nextRebindAt)

    private fun takeRebindSlot(now: Long): Boolean {
        val due = nextRebindAt
        if (due != null && now < due) return false
        nextRebindAt = now + backoffMs[minOf(attempts, backoffMs.size - 1)]
        attempts++
        return true
    }

    /** Honest status for `trigger_registration`. A disconnected listener is transient: the system or a rebind brings it back. */
    fun registration(accessGranted: Boolean): SparkRegState = when {
        !accessGranted -> SparkRegState.failed(FailureCode.PermissionMissing, "NotificationAccessNotGranted")
        snapshot().connected -> SparkRegState.armed("listener connected")
        else -> SparkRegState.failed(FailureCode.ListenerUnbound, "ListenerNotConnected")
    }

    companion object {
        /** Wait after the first, second, third... request. The first request itself is immediate. */
        val BACKOFF_MS = longArrayOf(10_000L, 60_000L, 5 * 60_000L, 15 * 60_000L)
    }
}

/**
 * Which packages any live Notification Spark could match, so the listener can drop the rest without reading every Flow from the
 * database on every notification of every app. This is only a cheap pre-filter: [com.kaivoflow.core.nodes.EventTriggerMatcher]
 * remains the fail-closed gate. Before the first refresh the answer is "maybe" for everything, so nothing is lost at start.
 */
class NotificationInterest private constructor(private val anyPackage: Boolean, private val packages: Set<String>, private val known: Boolean) {
    fun mightMatch(packageName: String): Boolean = !known || anyPackage || packageName in packages

    companion object {
        val UNKNOWN = NotificationInterest(true, emptySet(), false)
        val NONE = NotificationInterest(false, emptySet(), true)

        fun from(liveFlows: List<Flow>): NotificationInterest {
            val sparks = liveFlows.flatMap { f -> f.triggers.filter { it.type == com.kaivoflow.core.engine.SparkTypes.NOTIFICATION && !it.disabled } }
            if (sparks.isEmpty()) return NONE
            val all = HashSet<String>()
            sparks.forEach { s ->
                val listed = s.paramString("packageNames").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (listed.isEmpty()) return NotificationInterest(true, emptySet(), true)
                all += listed
            }
            return NotificationInterest(false, all, true)
        }
    }
}

object NotificationText {
    const val TITLE_MAX = 200
    const val TEXT_MAX = 500

    /** Title-less notifications are accepted (AO-12): a missing title becomes empty, never a reason to drop the event. */
    fun title(raw: CharSequence?): String = raw?.toString()?.take(TITLE_MAX).orEmpty()
    fun text(raw: CharSequence?): String = raw?.toString()?.take(TEXT_MAX).orEmpty()
}

// ---- Bluetooth -----------------------------------------------------------------------------------

/**
 * Where ACL connect and disconnect events come from (AO-14). The platform decides whether a manifest receiver gets them for the
 * target SDK, and that cannot be known from code (test T-BT, per device). So both paths exist and the Inbox dedupe key makes a
 * double delivery harmless: the manifest receiver covers a dead process, the Engine's dynamic receiver covers a platform that only
 * delivers to a live one. From API 31 both need the runtime `BLUETOOTH_CONNECT` grant.
 */
data class BluetoothPlan(val manifest: Boolean, val engine: Boolean, val permissionMissing: Boolean)

object BluetoothDelivery {
    fun plan(sdkInt: Int, connectGranted: Boolean): BluetoothPlan {
        val needsRuntimeGrant = sdkInt >= 31
        if (needsRuntimeGrant && !connectGranted) return BluetoothPlan(manifest = false, engine = false, permissionMissing = true)
        return BluetoothPlan(manifest = true, engine = true, permissionMissing = false)
    }

    fun registration(plan: BluetoothPlan, engineRunning: Boolean): SparkRegState = when {
        plan.permissionMissing -> SparkRegState.failed(FailureCode.PermissionMissing, "BLUETOOTH_CONNECT")
        engineRunning -> SparkRegState.armed("manifest and engine receivers")
        else -> SparkRegState.armed("manifest receiver (engine off)")
    }
}

/** Looks up Spark steps the sources care about, shared by the Android registrars. */
object SparkQueries {
    fun enabled(flows: List<Flow>, type: String): List<Pair<Flow, StepNode>> =
        flows.flatMap { f -> f.triggers.filter { it.type == type && !it.disabled }.map { f to it } }

    fun batteryThresholds(liveFlows: List<Flow>): Set<Int> =
        enabled(liveFlows, com.kaivoflow.core.engine.SparkTypes.BATTERY).map { it.second.paramNumber("threshold", 20.0).toInt() }.toSet()
}
