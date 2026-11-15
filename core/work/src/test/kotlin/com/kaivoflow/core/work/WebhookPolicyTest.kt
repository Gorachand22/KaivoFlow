package com.kaivoflow.core.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookPolicyTest {

    @Test fun defaultsAreSafe() {
        assertFalse(WebhookDefaults.BIND_ALL_INTERFACES)
        assertTrue(WebhookDefaults.REQUIRE_AUTH)
        assertEquals(1024 * 1024, WebhookDefaults.MAX_BODY_BYTES)
    }

    @Test fun routeKeyStripsHookPrefixAndQuery() {
        assertEquals("my-flow", WebhookPolicy.routeKey("/hook/my-flow?a=1"))
        assertEquals("a/b", WebhookPolicy.routeKey("/hook/a/b/"))
    }

    @Test fun routeKeyRejectsOtherPathsAndTraversal() {
        assertNull(WebhookPolicy.routeKey("/my-flow"))
        assertNull(WebhookPolicy.routeKey("/hook/"))
        assertNull(WebhookPolicy.routeKey("/hook/../x"))
        assertNull(WebhookPolicy.routeKey("/hook/a//b"))
    }

    @Test fun sparkPathMatchesRouteKey() {
        assertEquals("my-flow", WebhookPolicy.normalizeSparkPath(" /hook/my-flow/ "))
        assertEquals("my-flow", WebhookPolicy.normalizeSparkPath(""))
        assertEquals(WebhookPolicy.normalizeSparkPath("orders"), WebhookPolicy.routeKey("/hook/orders"))
    }

    @Test fun authRequiresMatchingToken() {
        val t = "abc123"
        assertTrue(WebhookPolicy.authorized(true, t, mapOf("authorization" to "Bearer abc123")))
        assertTrue(WebhookPolicy.authorized(true, t, mapOf("x-kaivo-token" to "abc123")))
        assertFalse(WebhookPolicy.authorized(true, t, emptyMap()))
        assertFalse(WebhookPolicy.authorized(true, t, mapOf("authorization" to "Bearer nope")))
        assertFalse(WebhookPolicy.authorized(true, t, mapOf("authorization" to "Bearer ")))
        assertFalse(WebhookPolicy.authorized(true, "", mapOf("x-kaivo-token" to "")))
        assertTrue(WebhookPolicy.authorized(false, t, emptyMap()))
    }

    @Test fun bodyLengthLimits() {
        val max = 100
        assertEquals(WebhookPolicy.Length.Ok(0), WebhookPolicy.bodyLength(emptyMap(), max))
        assertEquals(WebhookPolicy.Length.Ok(100), WebhookPolicy.bodyLength(mapOf("content-length" to "100"), max))
        assertEquals(WebhookPolicy.Length.TooLarge, WebhookPolicy.bodyLength(mapOf("content-length" to "101"), max))
        assertEquals(WebhookPolicy.Length.TooLarge, WebhookPolicy.bodyLength(mapOf("content-length" to "99999999999"), max))
        assertEquals(WebhookPolicy.Length.Invalid, WebhookPolicy.bodyLength(mapOf("content-length" to "-1"), max))
        assertEquals(WebhookPolicy.Length.Invalid, WebhookPolicy.bodyLength(mapOf("content-length" to "x"), max))
        assertEquals(WebhookPolicy.Length.Unsupported, WebhookPolicy.bodyLength(mapOf("transfer-encoding" to "chunked"), max))
    }

    @Test fun batteryFiresOncePerFallingCrossing() {
        val th = listOf(20)
        assertTrue(BatteryEdges.shouldDispatch(21, 20, false, false, th))
        assertFalse(BatteryEdges.shouldDispatch(20, 19, false, false, th))
        assertFalse(BatteryEdges.shouldDispatch(50, 49, false, false, th))
        assertFalse(BatteryEdges.shouldDispatch(15, 16, true, true, th))
        assertTrue(BatteryEdges.shouldDispatch(15, 15, false, true, th)) // charging started
        assertFalse(BatteryEdges.shouldDispatch(null, 10, null, false, th))
    }
}
