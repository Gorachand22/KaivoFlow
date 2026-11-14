package com.kaivoflow.core.nodes

import com.kaivoflow.core.model.StepNode
import kotlinx.collections.immutable.toImmutableMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fail-closed matching for the phone-native Sparks, plus catalog invariants. */
class MobileTriggerTest {

    private fun spark(type: String, params: Map<String, Any?> = emptyMap(), disabled: Boolean = false) =
        StepNode(id = "spark", type = type, name = type, params = params.toImmutableMap(), disabled = disabled)

    private fun match(step: StepNode, payload: Map<String, Any?>, kind: String = step.type) =
        EventTriggerMatcher.matches(step, kind, payload, "com.kaivoflow.app")

    @Test
    fun `geofence requires a valid transition and coordinates inside the radius`() {
        val enter = spark(
            TriggerSpecs.GEOFENCE,
            mapOf("latitude" to 20.2961, "longitude" to 85.8245, "radiusMeters" to 150, "transition" to "enter"),
        )
        val inside = mapOf("transition" to "enter", "latitude" to 20.2962, "longitude" to 85.8246)
        assertTrue(match(enter, inside))
        assertFalse(match(enter, inside + ("transition" to "exit")))
        assertFalse(match(enter, mapOf("transition" to "enter")))
        assertFalse(match(enter, inside + ("latitude" to 21.5)))
        assertFalse(match(enter, inside + ("latitude" to 999.0)))
        assertFalse(match(enter, emptyMap()))
    }

    @Test
    fun `geofence both accepts entering and leaving`() {
        val both = spark(
            TriggerSpecs.GEOFENCE,
            mapOf("latitude" to 0.0, "longitude" to 0.0, "radiusMeters" to 100, "transition" to "both"),
        )
        assertTrue(match(both, mapOf("transition" to "enter", "latitude" to 0.0, "longitude" to 0.0)))
        assertTrue(match(both, mapOf("transition" to "exit", "latitude" to 0.1, "longitude" to 0.1)))
    }

    @Test
    fun `bluetooth fails closed without an address and honours name and event filters`() {
        val step = spark(
            TriggerSpecs.BLUETOOTH,
            mapOf("deviceNameContains" to "Car", "event" to "connected"),
        )
        val good = mapOf("event" to "connected", "address" to "AA:BB:CC:DD:EE:FF", "name" to "My Car Kit")
        assertTrue(match(step, good))
        assertFalse(match(step, good - "address"))
        assertFalse(match(step, good + ("name" to "Headphones")))
        assertFalse(match(step, good + ("event" to "disconnected")))
        assertFalse(match(step, mapOf("address" to "AA:BB:CC:DD:EE:FF")))
        val exact = spark(TriggerSpecs.BLUETOOTH, mapOf("deviceAddress" to "11:22:33", "event" to "any"))
        assertTrue(match(exact, mapOf("event" to "disconnected", "address" to "11:22:33", "name" to "")))
        assertFalse(match(exact, mapOf("event" to "disconnected", "address" to "99:99:99", "name" to "")))
    }

    @Test
    fun `wifi rejects unknown ssids and honours event filters`() {
        val home = spark(TriggerSpecs.WIFI, mapOf("ssidContains" to "Home", "event" to "connected"))
        assertTrue(match(home, mapOf("event" to "connected", "ssid" to "Home-5G")))
        assertFalse(match(home, mapOf("event" to "connected", "ssid" to "<unknown ssid>")))
        assertFalse(match(home, mapOf("event" to "connected", "ssid" to "Office")))
        assertFalse(match(home, mapOf("event" to "disconnected", "ssid" to "Home-5G")))
        assertFalse(match(home, mapOf("event" to "connected")))
        val either = spark(TriggerSpecs.WIFI, mapOf("event" to "any"))
        assertTrue(match(either, mapOf("event" to "disconnected", "ssid" to "Home-5G")))
    }

    @Test
    fun `headset and boot events require their identifying fields`() {
        val plugged = spark(TriggerSpecs.HEADSET, mapOf("event" to "plugged"))
        assertTrue(match(plugged, mapOf("event" to "plugged")))
        assertFalse(match(plugged, mapOf("event" to "unplugged")))
        assertFalse(match(plugged, mapOf("event" to "microphone")))
        assertFalse(match(plugged, emptyMap()))
        assertTrue(match(spark(TriggerSpecs.HEADSET, mapOf("event" to "any")), mapOf("event" to "unplugged")))
        assertTrue(match(spark(TriggerSpecs.BOOT), mapOf("reason" to "android.intent.action.BOOT_COMPLETED")))
        assertFalse(match(spark(TriggerSpecs.BOOT), emptyMap()))
    }

    @Test
    fun `unknown events and disabled sparks never match`() {
        assertFalse(match(spark(TriggerSpecs.WIFI), mapOf("event" to "connected", "ssid" to "Home"), "trigger.unknown"))
        assertFalse(match(spark(TriggerSpecs.BOOT, disabled = true), mapOf("reason" to "boot")))
    }

    @Test
    fun `haversine distance is sane for a known pair`() {
        val meters = MobileTriggerMath.distanceMeters(0.0, 0.0, 0.0, 1.0)
        assertTrue("expected ~111 km, got $meters", meters in 110_000.0..112_000.0)
        assertEquals(0.0, MobileTriggerMath.distanceMeters(1.0, 2.0, 1.0, 2.0), 0.5)
    }

    @Test
    fun `every declared mobile spark exists in the catalog and can start a flow`() {
        val registry = NodeCatalog.registry()
        TriggerSpecs.all.forEach { type ->
            val spec = registry.spec(type)
            assertTrue("$type is missing from the catalog", spec != null)
            assertTrue("$type cannot start a Flow", spec!!.canStartFlow)
        }
        assertTrue(registry.specs.size >= 43)
    }
}
