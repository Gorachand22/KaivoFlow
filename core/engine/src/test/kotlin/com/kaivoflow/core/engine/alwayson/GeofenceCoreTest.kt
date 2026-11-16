package com.kaivoflow.core.engine.alwayson

import com.kaivoflow.core.engine.FailureCode
import com.kaivoflow.core.engine.SparkTypes
import com.kaivoflow.core.model.StepNode
import kotlinx.collections.immutable.toImmutableMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class GeofenceCoreTest {

    private fun step(vararg p: Pair<String, Any?>) = StepNode("s1", SparkTypes.GEOFENCE, "Home", params = mapOf(*p).toImmutableMap())

    private fun spec(vararg p: Pair<String, Any?>): GeofenceSpec =
        (GeofenceSpec.parse("f1", step("latitude" to 20.0, "longitude" to 85.0, *p)) as GeofenceSpec.Parsed.Valid).spec

    // ---- Parsing ----

    @Test fun validSparkParsesWithDefaults() {
        val s = spec()
        assertEquals(150f, s.radiusM); assertEquals("enter", s.wanted); assertEquals(0L, s.dwellMs)
        assertEquals("kaivo:f1:s1", s.requestId)
    }

    @Test fun invalidConfigurationIsReportedNotClamped() {
        fun bad(vararg p: Pair<String, Any?>) = (GeofenceSpec.parse("f", step(*p)) as GeofenceSpec.Parsed.Invalid).detail
        assertEquals("InvalidCoordinates", bad("latitude" to 91.0, "longitude" to 0.0))
        assertEquals("InvalidCoordinates", bad("latitude" to 0.0, "longitude" to 181.0))
        assertEquals("InvalidRadius", bad("latitude" to 1.0, "longitude" to 1.0, "radiusMeters" to 10))
        assertEquals("InvalidRadius", bad("latitude" to 1.0, "longitude" to 1.0, "radiusMeters" to 200_000))
        assertEquals("InvalidTransition", bad("latitude" to 1.0, "longitude" to 1.0, "transition" to "sideways"))
        assertEquals("InvalidDwell", bad("latitude" to 1.0, "longitude" to 1.0, "dwellSeconds" to -5))
    }

    // ---- Transition mask, dwell ----

    @Test fun transitionMaskUsesDwellInPlaceOfEnter() {
        assertEquals(GeofenceConstants.ENTER, spec().transitionMask)
        assertEquals(GeofenceConstants.EXIT, spec("transition" to "exit").transitionMask)
        assertEquals(GeofenceConstants.ENTER or GeofenceConstants.EXIT, spec("transition" to "both").transitionMask)
        assertEquals(GeofenceConstants.DWELL, spec("dwellSeconds" to 120).transitionMask)
        assertEquals(GeofenceConstants.DWELL or GeofenceConstants.EXIT, spec("transition" to "both", "dwellSeconds" to 120).transitionMask)
        assertEquals("dwell is meaningless for exit-only", GeofenceConstants.EXIT, spec("transition" to "exit", "dwellSeconds" to 60).transitionMask)
        assertEquals(120_000L, spec("dwellSeconds" to 120).dwellMs)
    }

    @Test fun signatureChangesWithAnySettingThatMatters() {
        val base = spec().signature
        assertEquals(base, spec().signature)
        assertTrue(base != spec("radiusMeters" to 300).signature)
        assertTrue(base != spec("dwellSeconds" to 60).signature)
        assertTrue(base != spec("transition" to "both").signature)
    }

    @Test fun requestIdsRoundTripEvenWithColonsInTheFlowId() {
        assertEquals("f1" to "s1", GeofenceRequestIds.parse("kaivo:f1:s1"))
        assertEquals("a:b" to "s", GeofenceRequestIds.parse("kaivo:a:b:s"))
        assertNull(GeofenceRequestIds.parse("other:f:s"))
        assertNull(GeofenceRequestIds.parse("kaivo:onlyone"))
        assertNull(GeofenceRequestIds.parse("kaivo:f:"))
    }

    // ---- Backend and honest status ----

    private fun env(play: Boolean = true, fine: Boolean = true, bg: Boolean = true, sdk: Int = 34, on: Boolean = true) =
        GeofenceEnvironment(play, fine, bg, sdk, on)

    @Test fun playServicesWhenPresentProximityOtherwise() {
        assertEquals(GeofenceBackend.PlayServices, GeofencePlanner.backend(env(play = true)))
        assertEquals(GeofenceBackend.Proximity, GeofencePlanner.backend(env(play = false)))
    }

    @Test fun precheckNamesTheMissingThing() {
        assertNull(GeofencePlanner.precheck(env()))
        assertEquals("ACCESS_FINE_LOCATION", GeofencePlanner.precheck(env(fine = false))!!.detail)
        val bg = GeofencePlanner.precheck(env(bg = false))!!
        assertEquals("ACCESS_BACKGROUND_LOCATION", bg.detail); assertEquals(FailureCode.PermissionMissing, bg.failure)
        assertEquals(SparkRegState.Kind.FailedPermanent, bg.kind)
        assertNull("no separate background grant before API 29", GeofencePlanner.precheck(env(bg = false, sdk = 28)))
        val off = GeofencePlanner.precheck(env(on = false))!!
        assertEquals(SparkRegState.Kind.FailedTransient, off.kind); assertEquals("LocationSwitchedOff", off.detail)
        assertEquals("permission is reported before the switch", "ACCESS_FINE_LOCATION", GeofencePlanner.precheck(env(fine = false, on = false))!!.detail)
    }

    @Test fun playStatusCodesMapToHonestStates() {
        assertEquals(SparkRegState.Kind.FailedTransient, GeofencePlanner.playStatus(1000).kind)
        assertEquals(FailureCode.InvalidConfig, GeofencePlanner.playStatus(1001).failure)
        assertTrue(GeofencePlanner.playStatus(1001).detail.contains("100"))
        assertEquals(FailureCode.GeofenceRejected, GeofencePlanner.playStatus(1002).failure)
        assertEquals(FailureCode.PermissionMissing, GeofencePlanner.playStatus(1004).failure)
        assertEquals("PlayServicesError:7", GeofencePlanner.playStatus(7).detail)
    }

    @Test fun staleFencesAreThoseNotWantedAnymore() {
        val a = spec(); val b = spec().copy(sparkId = "s2")
        assertEquals(listOf("kaivo:f1:s2"), GeofencePlanner.staleRequestIds(listOf("kaivo:f1:s1", "kaivo:f1:s2", "foreign:1"), listOf(a)))
        assertTrue(GeofencePlanner.staleRequestIds(listOf("kaivo:f1:s1", "kaivo:f1:s2"), listOf(a, b)).isEmpty())
    }

    // ---- Payload vs the matcher's geometry ----

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    @Test fun exitWithoutALocationLandsOutsideTheFenceAndEnterInside() {
        val s = spec("radiusMeters" to 200)
        val exit = GeofencePayload.build(s, GeofenceTransition.Exit, null, 1L)
        assertEquals("exit", exit["transition"])
        val d = distanceM(s.latitude, s.longitude, exit["latitude"] as Double, exit["longitude"] as Double)
        assertTrue("exit point $d m must pass the matcher's distance >= 0.85 * radius", d >= 0.85 * 200)
        val enter = GeofencePayload.build(s, GeofenceTransition.Enter, null, 1L)
        assertEquals("enter", enter["transition"]); assertEquals(s.latitude, enter["latitude"])
        assertEquals("f1", enter["targetFlowId"])
    }

    @Test fun realLocationIsUsedWhenThePlatformGivesOne() {
        val p = GeofencePayload.build(spec(), GeofenceTransition.Exit, 20.5 to 85.5, 1L)
        assertEquals(20.5, p["latitude"]); assertEquals(85.5, p["longitude"])
    }

    @Test fun dwellIsAnEnterThatStayed() {
        val p = GeofencePayload.build(spec("dwellSeconds" to 60), GeofenceTransition.Dwell, null, 9L)
        assertEquals("enter", p["transition"]); assertEquals(true, p["dwell"]); assertEquals(9L, p["at"])
    }

    @Test fun northPoleExitStaysInRange() {
        val s = GeofenceSpec("f", "s", 89.9999, 0.0, 100_000f, "exit", 0)
        assertTrue((GeofencePayload.build(s, GeofenceTransition.Exit, null, 0)["latitude"] as Double) <= 90.0)
    }

    // ---- Dwell tracker (proximity fallback) ----

    private class MemStore : DwellTracker.DwellStore {
        var data = emptyMap<String, Long>()
        override fun load() = data
        override fun save(entries: Map<String, Long>) { data = entries.toMap() }
    }

    @Test fun dwellFiresOnceAfterThePeriodWithoutAnExit() {
        val t = DwellTracker(MemStore())
        assertTrue(t.onEnter("k", 60_000, 1_000))
        assertTrue(t.due(30_000).isEmpty())
        assertEquals(listOf("k"), t.due(61_000))
        assertTrue("each dwell fires once", t.due(120_000).isEmpty())
    }

    @Test fun exitCancelsAPendingDwellAndRepeatedEntersKeepTheOriginalStart() {
        val t = DwellTracker(MemStore())
        t.onEnter("k", 60_000, 0)
        t.onEnter("k", 60_000, 30_000) // the platform re-reported "entering"
        assertEquals(60_000L, t.nextDue())
        t.onExit("k")
        assertTrue(t.due(10_000_000).isEmpty())
        assertFalse("no dwell configured means no tracking", t.onEnter("k2", 0, 0))
        t.onEnter("k3", 10, 0); t.forget(listOf("k3"))
        assertNull(t.nextDue())
    }
}
