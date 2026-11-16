package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.IssueSeverity
import com.kaivoflow.core.engine.alwayson.GeofenceSpec
import com.kaivoflow.core.engine.alwayson.ResponseMode
import com.kaivoflow.core.engine.alwayson.ResponsePlan
import com.kaivoflow.core.model.StepNode
import kotlinx.collections.immutable.toImmutableMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Webhook response params and Geofence dwell, declared on the Sparks the always-on engine already reads. */
class SparkParamsTest {
    private val registry = NodeCatalog.registry()
    private fun spark(type: String, params: Map<String, Any?>) = StepNode("s", type, "S", params = params.toImmutableMap())
    private fun issues(step: StepNode) = registry.validate(step)
    private fun codes(step: StepNode) = issues(step).map { it.code }

    @Test fun `webhook declares responseMode and responseTimeoutSec with safe defaults`() {
        val spec = registry.spec("spark.webhook")!!
        val mode = spec.params.first { it.key == "responseMode" }
        val timeout = spec.params.first { it.key == "responseTimeoutSec" }
        assertEquals("immediate", mode.default)
        assertEquals(10, timeout.default)
        assertEquals(mapOf("responseMode" to listOf("wait")), timeout.showWhen)
        // the defaults mean "answer right away", exactly what a Spark without the params did
        val plan = ResponsePlan.parse(mode.default.toString(), (timeout.default as Number).toDouble())
        assertEquals(ResponseMode.Immediate, plan.mode)
    }

    @Test fun `webhook validation accepts good values and rejects bad ones`() {
        val base = mapOf("path" to "hook")
        assertTrue(codes(spark("spark.webhook", base)).isEmpty())
        assertTrue(codes(spark("spark.webhook", base + mapOf("responseMode" to "wait", "responseTimeoutSec" to 30))).isEmpty())
        assertTrue("bad_response_mode" in codes(spark("spark.webhook", base + ("responseMode" to "later"))))
        listOf(0, 0.5, 56, 600, -1, Double.NaN).forEach {
            assertTrue("timeout $it", "bad_response_timeout" in codes(spark("spark.webhook", base + mapOf("responseMode" to "wait", "responseTimeoutSec" to it))))
        }
        // the timeout only matters while waiting
        assertTrue(codes(spark("spark.webhook", base + mapOf("responseMode" to "immediate", "responseTimeoutSec" to 9999))).isEmpty())
        val wait = ResponsePlan.parse("wait", 7.0)
        assertEquals(7_000L, wait.timeoutMs)
    }

    @Test fun `geofence declares dwellSeconds defaulting to arrive immediately`() {
        val spec = registry.spec("spark.geofence")!!
        val dwell = spec.params.first { it.key == "dwellSeconds" }
        assertEquals(0, dwell.default)
        assertEquals(mapOf("transition" to listOf("enter", "both")), dwell.showWhen)
        val step = spark("spark.geofence", mapOf("latitude" to 20.0, "longitude" to 85.0, "radiusMeters" to 150))
        val parsed = GeofenceSpec.parse("f", step) as GeofenceSpec.Parsed.Valid
        assertEquals(0L, parsed.spec.dwellMs)
    }

    @Test fun `geofence validation covers range and ignored dwell`() {
        val base = mapOf("latitude" to 20.0, "longitude" to 85.0, "radiusMeters" to 150)
        assertTrue(codes(spark("spark.geofence", base + ("dwellSeconds" to 120))).isEmpty())
        listOf(-1, 86_401, Double.NaN).forEach {
            assertTrue("dwell $it", "bad_geofence_dwell" in codes(spark("spark.geofence", base + ("dwellSeconds" to it))))
        }
        val ignored = issues(spark("spark.geofence", base + mapOf("transition" to "exit", "dwellSeconds" to 60)))
        assertEquals(listOf("geofence_dwell_ignored"), ignored.map { it.code })
        assertEquals(IssueSeverity.Warning, ignored.single().severity)
        assertEquals(120_000L, (GeofenceSpec.parse("f", spark("spark.geofence", base + ("dwellSeconds" to 120))) as GeofenceSpec.Parsed.Valid).spec.dwellMs)
    }
}
