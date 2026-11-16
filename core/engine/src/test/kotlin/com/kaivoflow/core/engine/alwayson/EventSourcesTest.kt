package com.kaivoflow.core.engine.alwayson

import com.kaivoflow.core.engine.FailureCode
import com.kaivoflow.core.engine.SparkTypes
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSourcesTest {

    // ---- Dedupe keys ----

    @Test fun sameOccurrenceGivesTheSameKeyAndDifferentOnesDiffer() {
        val a = EventKeys.sms("+15551234", 1_000L, "hello")
        assertEquals(a, EventKeys.sms("+15551234", 1_000L, "hello"))
        assertNotEquals(a, EventKeys.sms("+15551234", 1_001L, "hello"))
        assertNotEquals(a, EventKeys.sms("+15551234", 1_000L, "hello!"))
        assertNotEquals(a, EventKeys.sms("+15559999", 1_000L, "hello"))
        assertNotEquals("sender and body are separated", EventKeys.sms("ab", 1L, "c"), EventKeys.sms("a", 1L, "bc"))
        assertEquals(EventKeys.notification("0|pkg|1|null|10", 5L), EventKeys.notification("0|pkg|1|null|10", 5L))
        assertNotEquals(EventKeys.notification("k", 5L), EventKeys.notification("k", 6L))
    }

    @Test fun keysNeverCarryPrivateContent() {
        val keys = listOf(
            EventKeys.sms("+15551234", 1L, "my one time code is 424242"),
            EventKeys.notification("0|com.bank.app|7|secret-tag|10", 1L),
            EventKeys.wifi("connected", "HomeNetwork", 1L),
            EventKeys.bluetooth("connected", "AA:BB:CC:DD:EE:FF", 1L),
            EventKeys.geofence("kaivo:flow:spark", "enter", 1L),
        )
        listOf("15551234", "424242", "bank", "secret-tag", "HomeNetwork", "AA:BB", "kaivo:flow").forEach { leak ->
            keys.forEach { assertFalse("$it leaks $leak", it.contains(leak)) }
        }
    }

    @Test fun burstWindowsCollapseFlappingButSeparateLaterEvents() {
        val t = 1_000_000L
        assertEquals(EventKeys.wifi("connected", "Home", t), EventKeys.wifi("connected", "Home", t + 1_000))
        assertNotEquals(EventKeys.wifi("connected", "Home", t), EventKeys.wifi("disconnected", "Home", t))
        assertNotEquals(EventKeys.wifi("connected", "Home", t), EventKeys.wifi("connected", "Home", t + 60_000))
        assertEquals("bt address case does not matter", EventKeys.bluetooth("connected", "aa:bb", t), EventKeys.bluetooth("connected", "AA:BB", t))
        assertNotEquals(EventKeys.battery("low", 20, t), EventKeys.battery("low", 19, t))
        assertNotEquals(EventKeys.battery("low", 20, t), EventKeys.battery("charging", 20, t))
    }

    // ---- Battery ----

    private fun tracker(vararg th: Int) = BatteryTracker { th.toList() }.also { it.seed(50, false) }

    @Test fun fallingBatteryFiresOncePerCrossing() {
        val t = tracker(20)
        assertNull(t.onBroadcast(BatteryAction.Changed, 25, false))
        assertNull(t.onBroadcast(BatteryAction.Changed, 21, false))
        val s = t.onBroadcast(BatteryAction.Changed, 20, false)!!
        assertEquals("low", s.edge); assertEquals(20, s.level)
        assertNull("still below, no repeat", t.onBroadcast(BatteryAction.Changed, 19, false))
        assertNull(t.onBroadcast(BatteryAction.Changed, 18, false))
    }

    @Test fun twoThresholdsFireSeparately() {
        val t = tracker(30, 10)
        assertNotNull(t.onBroadcast(BatteryAction.Changed, 30, false))
        assertNull(t.onBroadcast(BatteryAction.Changed, 20, false))
        assertNotNull(t.onBroadcast(BatteryAction.Changed, 10, false))
    }

    @Test fun powerConnectedWithoutALevelStillFiresTheChargingEdge() {
        val t = tracker(20)
        t.onBroadcast(BatteryAction.Changed, 40, false)
        val s = t.onBroadcast(BatteryAction.PowerConnected, null, null)!!
        assertEquals("charging", s.edge); assertEquals(40, s.level); assertTrue(s.charging)
        assertNull("the follow-up Changed broadcast is not a second edge", t.onBroadcast(BatteryAction.Changed, 40, true))
        assertNull(t.onBroadcast(BatteryAction.PowerDisconnected, null, null))
    }

    @Test fun platformLowIsOnlyAFallbackWhenNoBaselineExists() {
        val seeded = tracker(20)
        assertNull("the crossing already covers it", seeded.onBroadcast(BatteryAction.Low, 15, false))
        val fresh = BatteryTracker { listOf(20) }
        val s = fresh.onBroadcast(BatteryAction.Low, 15, false)
        assertEquals("low", s!!.edge)
        val none = BatteryTracker { emptyList() }
        assertNull(none.onBroadcast(BatteryAction.Low, 15, false))
    }

    @Test fun unseededChargingStartDoesNotInventAnEdge() {
        val t = BatteryTracker { listOf(20) }
        assertNull(t.onBroadcast(BatteryAction.Changed, 80, true))
        assertNull(t.onBroadcast(BatteryAction.Okay, 80, true))
    }

    @Test fun batteryReceiverEvidence() {
        assertTrue(BatteryEvidence.receiverHealthy(60, 59, 0, 10 * 3_600_000L))
        assertFalse("big mismatch long after the last broadcast", BatteryEvidence.receiverHealthy(60, 40, 0, 3_600_000L))
        assertFalse("nothing ever seen", BatteryEvidence.receiverHealthy(60, null, null, 0))
        assertTrue("big mismatch but a broadcast a minute ago", BatteryEvidence.receiverHealthy(60, 40, 0, 60_000L))
    }

    // ---- Listener tracker ----

    private val clock = FakeClock()

    @Test fun reconnectReportsHowLongTheListenerWasAway() {
        val t = ListenerTracker(clock)
        assertNull("first connect", t.onConnected())
        clock.advance(5_000)
        assertTrue("first drop asks for a rebind", t.onDisconnected())
        clock.advance(7_000)
        assertEquals(7_000L, t.onConnected())
        assertEquals(0, t.snapshot().rebindAttempts)
    }

    @Test fun rebindRequestsBackOffAndResetOnConnect() {
        val t = ListenerTracker(clock)
        t.onConnected()
        assertTrue(t.onDisconnected())              // attempt 1, immediately
        assertEquals(ListenerAction.None, t.reconcile(true))       // too soon: next slot is +10 s
        clock.advance(10_000)
        assertEquals(ListenerAction.RequestRebind, t.reconcile(true)) // attempt 2
        clock.advance(59_000)
        assertEquals(ListenerAction.None, t.reconcile(true))
        clock.advance(1_000)
        assertEquals(ListenerAction.RequestRebind, t.reconcile(true)) // attempt 3 after 60 s
        t.onConnected()
        assertEquals(ListenerAction.None, t.reconcile(true))
        assertTrue("after a reconnect the back-off starts over", t.onDisconnected())
    }

    @Test fun backoffIsCappedAtFifteenMinutes() {
        val t = ListenerTracker(clock)
        repeat(12) { clock.advance(20 * 60_000); t.reconcile(true) }
        val s = t.snapshot()
        assertEquals(clock.now() + 15 * 60_000, s.nextRebindAt)
    }

    @Test fun missingAccessIsReportedNotRebound() {
        val t = ListenerTracker(clock)
        assertEquals(ListenerAction.AccessMissing, t.reconcile(false))
        val reg = t.registration(accessGranted = false)
        assertEquals(FailureCode.PermissionMissing, reg.failure); assertEquals(SparkRegState.Kind.FailedPermanent, reg.kind)
    }

    @Test fun listenerRegistrationStatusFollowsConnection() {
        val t = ListenerTracker(clock)
        val before = t.registration(true)
        assertEquals(FailureCode.ListenerUnbound, before.failure); assertEquals(SparkRegState.Kind.FailedTransient, before.kind)
        t.onConnected()
        assertTrue(t.registration(true).isArmed)
        t.onDisconnected()
        assertFalse(t.registration(true).isArmed)
    }

    // ---- Notification interest and text ----

    private fun notifSpark(id: String, packages: String = "", disabled: Boolean = false) =
        StepNode(id, SparkTypes.NOTIFICATION, id, params = mapOf<String, Any?>("packageNames" to packages).toImmutableMap(), disabled = disabled)

    private fun flow(vararg s: StepNode) = Flow(id = "f", name = "f", steps = persistentListOf(*s))

    @Test fun interestIsUnknownUntilRefreshedSoNothingIsLostAtStart() {
        assertTrue(NotificationInterest.UNKNOWN.mightMatch("any.pkg"))
        assertFalse(NotificationInterest.NONE.mightMatch("any.pkg"))
    }

    @Test fun interestFollowsThePackageFilters() {
        val some = NotificationInterest.from(listOf(flow(notifSpark("a", "com.a, com.b"), notifSpark("b", "com.c"))))
        assertTrue(some.mightMatch("com.a")); assertTrue(some.mightMatch("com.c")); assertFalse(some.mightMatch("com.z"))
        val any = NotificationInterest.from(listOf(flow(notifSpark("a", "com.a"), notifSpark("b", ""))))
        assertTrue("one Spark without a package filter listens to every app", any.mightMatch("com.z"))
        assertFalse(NotificationInterest.from(listOf(flow(notifSpark("a", "com.a", disabled = true)))).mightMatch("com.a"))
        assertFalse(NotificationInterest.from(emptyList()).mightMatch("com.a"))
    }

    @Test fun titleLessNotificationsAreAcceptedAndTextIsTruncated() {
        assertEquals("", NotificationText.title(null))
        assertEquals("Hi", NotificationText.title("Hi"))
        assertEquals(200, NotificationText.title("x".repeat(500)).length)
        assertEquals(500, NotificationText.text("y".repeat(900)).length)
        assertEquals("", NotificationText.text(null))
    }

    // ---- Wi-Fi tracker ----

    @Test fun wifiReportsConnectOnceNotOnEverySignalUpdate() {
        val w = WifiTracker()
        assertEquals(WifiSignal("connected", "Home"), w.onCapabilities("n1", "\"Home\""))
        assertNull(w.onCapabilities("n1", "\"Home\""))
        assertEquals(WifiSignal("disconnected", "Home"), w.onLost("n1"))
        assertNull("a second loss is nothing", w.onLost("n1"))
    }

    @Test fun roamingReportsConnectNewAndLaterDisconnectOld() {
        val w = WifiTracker()
        w.onCapabilities("n1", "A")
        assertEquals(WifiSignal("connected", "B"), w.onCapabilities("n2", "B"))
        assertEquals(WifiSignal("disconnected", "A"), w.onLost("n1"))
        assertEquals(WifiSignal("disconnected", "B"), w.onLost("n2"))
    }

    @Test fun withheldSsidProducesNoEvent() {
        val w = WifiTracker()
        assertNull(w.onCapabilities("n1", null))
        assertNull(w.onCapabilities("n1", "<unknown ssid>"))
        assertNull(w.onCapabilities("n1", "  "))
        assertNull("nothing was learned, so a loss reports nothing", w.onLost("n1"))
    }

    // ---- Bluetooth ----

    @Test fun bluetoothDeliveryPlan() {
        assertEquals(BluetoothPlan(manifest = true, engine = true, permissionMissing = false), BluetoothDelivery.plan(34, true))
        assertTrue(BluetoothDelivery.plan(31, false).permissionMissing)
        assertFalse("below API 31 there is no runtime grant", BluetoothDelivery.plan(30, false).permissionMissing)
        assertEquals(FailureCode.PermissionMissing, BluetoothDelivery.registration(BluetoothDelivery.plan(33, false), true).failure)
        assertTrue(BluetoothDelivery.registration(BluetoothDelivery.plan(33, true), false).isArmed)
    }
}
