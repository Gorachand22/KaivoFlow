package com.kaivoflow.core.engine.alwayson

import com.kaivoflow.core.engine.FailureCode
import com.kaivoflow.core.engine.SparkTypes
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.SocketException

class WebhookCoreTest {

    // ---- Policy (moved from core:work) ----

    @Test fun defaultsAreSafe() {
        assertTrue(WebhookDefaults.REQUIRE_AUTH)
        assertEquals(1024 * 1024, WebhookDefaults.MAX_BODY_BYTES)
        assertEquals(ReachMode.Loopback, WebhookReach.plan(lanEnabled = false, port = 8787).mode)
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

    @Test fun idempotencyKeyIsOptionalAndBounded() {
        assertEquals("k1", WebhookPolicy.idempotencyKey(mapOf("idempotency-key" to " k1 ")))
        assertNull(WebhookPolicy.idempotencyKey(emptyMap()))
        assertNull(WebhookPolicy.idempotencyKey(mapOf("idempotency-key" to "x".repeat(201))))
    }

    // ---- Reach modes and tokens ----

    @Test fun loopbackIsDefaultAndLanBindsEveryInterface() {
        val lo = WebhookReach.plan(false, 8787)
        assertEquals("127.0.0.1", lo.host)
        val lan = WebhookReach.plan(true, 8787)
        assertNull("all interfaces", lan.host)
        assertEquals(ReachMode.Lan, lan.mode)
    }

    @Test fun portRangeExcludesPrivilegedPorts() {
        assertFalse(WebhookReach.validPort(80)); assertFalse(WebhookReach.validPort(1023)); assertFalse(WebhookReach.validPort(70_000))
        assertTrue(WebhookReach.validPort(1024)); assertTrue(WebhookReach.validPort(8787)); assertTrue(WebhookReach.validPort(65_535))
    }

    @Test fun flowTokenIsDeterministicPerFlowAndSecret() {
        val a = FlowTokens.derive("secret", "flow-1")
        assertEquals(a, FlowTokens.derive("secret", "flow-1"))
        assertNotEquals(a, FlowTokens.derive("secret", "flow-2"))
        assertNotEquals(a, FlowTokens.derive("other", "flow-1"))
        assertEquals(64, a.length)
        assertNotEquals("the derived token is not the secret", "secret", a)
    }

    @Test fun loopbackAcceptsAppTokenOrFlowToken() {
        val app = "app-secret"
        val flowTok = FlowTokens.derive(app, "f1")
        assertTrue(WebhookAuth.authorize(ReachMode.Loopback, app, "f1", mapOf("authorization" to "Bearer $app")))
        assertTrue(WebhookAuth.authorize(ReachMode.Loopback, app, "f1", mapOf("x-kaivo-token" to flowTok)))
        assertFalse("flow token of another flow", WebhookAuth.authorize(ReachMode.Loopback, app, "f2", mapOf("x-kaivo-token" to flowTok)))
        assertFalse(WebhookAuth.authorize(ReachMode.Loopback, app, "f1", emptyMap()))
    }

    @Test fun lanAcceptsOnlyTheFlowTokenOfTheRoutedFlow() {
        val app = "app-secret"
        val flowTok = FlowTokens.derive(app, "f1")
        assertTrue(WebhookAuth.authorize(ReachMode.Lan, app, "f1", mapOf("authorization" to "Bearer $flowTok")))
        assertFalse("the app token is too powerful for the LAN", WebhookAuth.authorize(ReachMode.Lan, app, "f1", mapOf("authorization" to "Bearer $app")))
        assertFalse("another flow's token", WebhookAuth.authorize(ReachMode.Lan, app, "f2", mapOf("authorization" to "Bearer $flowTok")))
        assertFalse("unknown route has no token that can match", WebhookAuth.authorize(ReachMode.Lan, app, null, mapOf("authorization" to "Bearer $flowTok")))
    }

    @Test fun signatureVerification() {
        val sig = hmacHex("shh", "{\"a\":1}")
        assertTrue(WebhookSignature.verify("shh", "{\"a\":1}", sig))
        assertFalse(WebhookSignature.verify("shh", "{\"a\":2}", sig))
        assertFalse(WebhookSignature.verify("shh", "{\"a\":1}", ""))
    }

    // ---- Response plan and gate ----

    @Test fun responsePlanParsing() {
        assertEquals(ResponsePlan.IMMEDIATE, ResponsePlan.parse(null, null))
        assertEquals(ResponsePlan.IMMEDIATE, ResponsePlan.parse("garbage", 30.0))
        assertEquals(ResponsePlan(ResponseMode.WaitForRun, 10_000), ResponsePlan.parse("wait", null))
        assertEquals(ResponsePlan(ResponseMode.WaitForRun, 55_000), ResponsePlan.parse("WaitForRun", 600.0))
        assertEquals(ResponsePlan(ResponseMode.WaitForRun, 1_000), ResponsePlan.parse("wait", 0.0))
        assertEquals(ResponsePlan(ResponseMode.WaitForRun, 10_000), ResponsePlan.parse("wait", Double.NaN))
        assertEquals(ResponsePlan(ResponseMode.WaitForRun, 2_500), ResponsePlan.parse("wait", 2.5))
    }

    @Test fun gateDeliversWhenResultArrivesBeforeOrAfterAwait() = runBlocking {
        val gate = ResponseGate()
        gate.complete(1, GateResult.Dropped("early"))
        assertEquals(GateResult.Dropped("early"), gate.await(1, 50))
        val later = async { gate.await(2, 2_000) }
        while (gate.waitingCount() == 0) delay(5)
        gate.complete(2, GateResult.Crashed("r"))
        assertEquals(GateResult.Crashed("r"), later.await())
        assertEquals(0, gate.waitingCount())
    }

    @Test fun gateTimeoutReturnsNullAndLeavesNoWaiter() = runBlocking {
        val gate = ResponseGate()
        assertNull(gate.await(9, 30))
        assertEquals(0, gate.waitingCount())
        gate.complete(9, GateResult.Dropped("late result is kept, not lost"))
        assertEquals(GateResult.Dropped("late result is kept, not lost"), gate.await(9, 30))
    }

    @Test fun gateKeepsOnlyABoundedNumberOfUnwaitedResults() = runBlocking {
        val gate = ResponseGate(keep = 3)
        (1L..10L).forEach { gate.complete(it, GateResult.Dropped("x")) }
        assertNull("oldest evicted", gate.await(1, 10))
        assertEquals(GateResult.Dropped("x"), gate.await(10, 10))
    }

    @Test fun responseMapperCoversEveryOutcome() {
        assertEquals(202, ResponseMapper.forResult(5, null).status)
        assertTrue(ResponseMapper.forResult(5, null).body.contains("\"status\":\"running\""))
        val ok = ResponseMapper.forResult(5, GateResult.Finished("Succeeded", "r", "{\"answer\":42}"))
        assertEquals(200, ok.status); assertEquals("{\"answer\":42}", ok.body)
        assertEquals(200, ResponseMapper.forResult(5, GateResult.Finished("PartialSuccess", "r", null)).status)
        assertEquals(500, ResponseMapper.forResult(5, GateResult.Finished("Failed", "r", null)).status)
        assertEquals(500, ResponseMapper.forResult(5, GateResult.Finished("Canceled", "r", null)).status)
        assertEquals(409, ResponseMapper.forResult(5, GateResult.Dropped("FlowNotLive")).status)
        assertEquals(500, ResponseMapper.forResult(5, GateResult.Crashed("r")).status)
        assertTrue(ResponseMapper.accepted(1, duplicate = true).body.contains("duplicate"))
    }

    // ---- Route table ----

    private fun hook(id: String, path: String, method: String = "POST", extra: Map<String, Any?> = emptyMap(), disabled: Boolean = false) =
        StepNode(id, SparkTypes.WEBHOOK, id, params = (mapOf<String, Any?>("path" to path, "method" to method) + extra).toImmutableMap(), disabled = disabled)

    private fun flow(id: String, vararg steps: StepNode) = Flow(id = id, name = id, steps = persistentListOf(*steps))

    @Test fun routeTableBuildsFromLiveFlowsAndSkipsDisabledSparks() {
        val t = RouteTable.fromFlows(listOf(
            flow("a", hook("a1", "/hook/orders/"), hook("a2", "off", disabled = true)),
            flow("b", hook("b1", "ping", method = "get", extra = mapOf("secret" to "s3"))),
        ))
        assertEquals(setOf("orders", "ping"), t.keys)
        assertEquals("GET", t.lookup("ping")!!.method)
        assertEquals("s3", t.lookup("ping")!!.secret)
        assertNull(t.lookup("off"))
        assertTrue(t.conflicts.isEmpty())
    }

    @Test fun duplicatePathIsAConflictAndTheFirstFlowWinsDeterministically() {
        val t = RouteTable.fromFlows(listOf(flow("z", hook("z1", "same")), flow("a", hook("a1", "same"))))
        assertEquals("a", t.lookup("same")!!.flowId)
        val c = t.conflicts.single()
        assertEquals("same", c.key); assertEquals("z", c.loserFlowId); assertEquals("a1", c.winnerSparkId)
        val loser = t.lookup("same")!!.copy(flowId = "z", sparkId = "z1")
        val state = WebhookStatusMapper.registration(loser, t, WebhookListenerStatus.Listening(8787, ReachMode.Loopback), engineRunning = true)
        assertEquals(SparkRegState.Kind.FailedPermanent, state.kind)
        assertEquals(FailureCode.InvalidConfig, state.failure)
    }

    @Test fun responseParamsFlowIntoTheRoute() {
        val t = RouteTable.fromFlows(listOf(flow("a", hook("a1", "p", extra = mapOf("responseMode" to "wait", "responseTimeoutSec" to 7)))))
        assertEquals(ResponsePlan(ResponseMode.WaitForRun, 7_000), t.lookup("p")!!.response)
    }

    @Test fun routeDiffReportsPublishPauseAndEdit() {
        val before = RouteTable.fromFlows(listOf(flow("a", hook("a1", "one")), flow("b", hook("b1", "two"))))
        val after = RouteTable.fromFlows(listOf(flow("a", hook("a1", "one", method = "ANY")), flow("c", hook("c1", "three"))))
        val d = after.diff(before)
        assertEquals(setOf("three"), d.added); assertEquals(setOf("two"), d.removed); assertEquals(setOf("one"), d.changed)
        assertTrue(after.diff(after).isEmpty)
        assertTrue(RouteTable.EMPTY.diff(RouteTable.EMPTY).isEmpty)
    }

    // ---- Listener status mapping ----

    @Test fun bindFailureBecomesPortInUseOnlyWhenTheSystemSaysSo() {
        assertEquals(WebhookListenerStatus.PortInUse(8787), WebhookStatusMapper.fromBindFailure(8787, BindException("Address already in use")))
        assertEquals(WebhookListenerStatus.PortInUse(8787), WebhookStatusMapper.fromBindFailure(8787, BindException("bind failed: EADDRINUSE (Address already in use)")))
        assertEquals(WebhookListenerStatus.Failed(8787, "SocketException"), WebhookStatusMapper.fromBindFailure(8787, SocketException("boom")))
        assertEquals(WebhookListenerStatus.Failed(8787, "BindException"), WebhookStatusMapper.fromBindFailure(8787, BindException("Permission denied")))
    }

    @Test fun registrationStatusFromListenerState() {
        val spec = RouteSpec("f", "s", "k", "POST", null, ResponsePlan.IMMEDIATE)
        val table = RouteTable.build(listOf(spec))
        fun st(status: WebhookListenerStatus, running: Boolean = true) = WebhookStatusMapper.registration(spec, table, status, running)
        assertTrue(st(WebhookListenerStatus.Listening(8787, ReachMode.Lan)).isArmed)
        assertEquals("port 8787 lan", st(WebhookListenerStatus.Listening(8787, ReachMode.Lan)).detail)
        assertEquals(SparkRegState.Kind.Pending, st(WebhookListenerStatus.Stopped, running = false).kind)
        val busy = st(WebhookListenerStatus.PortInUse(8787))
        assertEquals(SparkRegState.Kind.FailedTransient, busy.kind); assertEquals(FailureCode.PortInUse, busy.failure); assertEquals("PortInUse:8787", busy.detail)
        assertEquals(SparkRegState.Kind.FailedPermanent, st(WebhookListenerStatus.InvalidPort(80)).kind)
        assertEquals(FailureCode.Unknown, st(WebhookListenerStatus.Failed(8787, "X")).failure)
    }

    @Test fun registrationFailuresFeedPublishValidation() {
        val m = mapOf(
            "s2" to SparkRegState.failed(FailureCode.PortInUse, "PortInUse:8787"),
            "s1" to SparkRegState.armed(),
            "s3" to SparkRegState.pending("WaitingForEngine"),
            "s0" to SparkRegState.failed(FailureCode.PermissionMissing, "ACCESS_FINE_LOCATION"),
        )
        val f = RegistrationStatuses.failures(m)
        assertEquals(listOf("s0", "s2"), f.map { it.sparkId })
        assertEquals(FailureCode.PermissionMissing, f[0].code)
        assertFalse(RegistrationStatuses.allHealthy(m))
        assertTrue(RegistrationStatuses.allHealthy(m - "s0" - "s2"))
    }

    // ---- Processor: order and replies ----

    private class Harness(
        mode: ReachMode = ReachMode.Loopback,
        response: ResponsePlan = ResponsePlan.IMMEDIATE,
        secret: String? = null,
        val sinkResult: SinkResult = SinkResult.Stored(7),
        val gate: ResponseGate = ResponseGate(),
    ) {
        val events = ArrayList<String>()
        val route = RouteSpec("f1", "s1", "orders", "POST", secret, response)
        val table = RouteTable.build(listOf(route))
        val app = "app-secret"
        val processor = WebhookProcessor(
            table = { table }, mode = { mode }, appToken = { app },
            sink = WebhookSink { r, m, p, k -> events += "store:${r.flowId}:$m:${p["a"]}:${k ?: "-"}"; sinkResult },
            gate = gate,
            parseBody = { body, _ -> if (body.isBlank()) emptyMap() else mapOf("a" to body) },
        )
        fun head(method: String = "POST", target: String = "/hook/orders", token: String? = app, extra: Map<String, String> = emptyMap()) =
            WebhookRequestHead(method, target, buildMap {
                if (token != null) put("authorization", "Bearer $token"); putAll(extra)
            })
        suspend fun call(h: WebhookRequestHead, body: String = "x"): WebhookReply = when (val p = processor.preflight(h)) {
            is Preflight.Reject -> p.reply
            is Preflight.Proceed -> processor.handle(h, p.route, body)
        }
    }

    @Test fun storesBeforeRepliesAndNeverStoresARejectedRequest() = runBlocking {
        val h = Harness()
        val ok = h.call(h.head())
        assertEquals(200, ok.status)
        assertEquals(listOf("store:f1:POST:x:-"), h.events)
        assertEquals(401, h.call(h.head(token = null)).status)
        assertEquals(401, h.call(h.head(token = "wrong")).status)
        assertEquals(404, h.call(h.head(target = "/hook/nope")).status)
        assertEquals(405, h.call(h.head(method = "GET")).status)
        assertEquals("only the first request reached the Inbox", 1, h.events.size)
    }

    @Test fun anUnauthenticatedCallerCannotTellRoutesApart() = runBlocking {
        val h = Harness(mode = ReachMode.Lan)
        val known = h.call(h.head(token = "bad"))
        val unknown = h.call(h.head(token = "bad", target = "/hook/nope"))
        assertEquals(401, known.status); assertEquals(known.status, unknown.status); assertEquals(known.body, unknown.body)
        assertEquals(401, h.call(h.head(token = h.app)).status) // the app token never works on the LAN
        val flowToken = FlowTokens.derive(h.app, "f1")
        assertEquals(200, h.call(h.head(token = flowToken)).status)
        assertEquals("no route means no token can match", 401, h.call(h.head(token = flowToken, target = "/hook/nope")).status)
    }

    @Test fun storageFailureIsAn503NotA200() = runBlocking {
        val h = Harness(sinkResult = SinkResult.Failed)
        val r = h.call(h.head())
        assertEquals(503, r.status)
        assertTrue(r.body.contains("try again"))
    }

    @Test fun duplicateDeliveryIsAcceptedWithoutASecondRun() = runBlocking {
        val h = Harness(sinkResult = SinkResult.Duplicate)
        val r = h.call(h.head(extra = mapOf("idempotency-key" to "abc")))
        assertEquals(200, r.status); assertTrue(r.body.contains("duplicate"))
        assertEquals(listOf("store:f1:POST:x:abc"), h.events)
    }

    @Test fun badSignatureIsRejectedBeforeStoring() = runBlocking {
        val h = Harness(secret = "shh")
        assertEquals(401, h.call(h.head(), body = "{}").status)
        assertTrue(h.events.isEmpty())
        val good = h.call(h.head(extra = mapOf("x-kaivo-signature" to hmacHex("shh", "{}"))), body = "{}")
        assertEquals(200, good.status)
        assertEquals(1, h.events.size)
    }

    @Test fun lengthRulesComeAfterAuthButBeforeRouting() {
        val h = Harness()
        fun pre(vararg hdr: Pair<String, String>) =
            h.processor.preflight(WebhookRequestHead("POST", "/hook/orders", mapOf("authorization" to "Bearer ${h.app}") + hdr))
        assertEquals(413, (pre("content-length" to "99999999") as Preflight.Reject).reply.status)
        assertEquals(411, (pre("transfer-encoding" to "chunked") as Preflight.Reject).reply.status)
        assertEquals(400, (pre("content-length" to "x") as Preflight.Reject).reply.status)
        assertEquals(3, (pre("content-length" to "3") as Preflight.Proceed).bodyLength)
    }

    @Test fun waitForRunAnswersWithTheRunResult() = runBlocking {
        val gate = ResponseGate()
        val h = Harness(response = ResponsePlan(ResponseMode.WaitForRun, 5_000), gate = gate)
        val call = async { h.call(h.head()) }
        while (gate.waitingCount() == 0) delay(5)
        assertEquals("the row was stored before the caller started waiting", listOf("store:f1:POST:x:-"), h.events)
        gate.complete(7, GateResult.Finished("Succeeded", "run-1", "{\"ok\":true}"))
        val r = call.await()
        assertEquals(200, r.status); assertEquals("{\"ok\":true}", r.body)
    }

    @Test fun waitForRunTimeoutIs202AndTheEventStaysStored() = runBlocking {
        val h = Harness(response = ResponsePlan(ResponseMode.WaitForRun, 40))
        val r = h.call(h.head())
        assertEquals(202, r.status)
        assertEquals(1, h.events.size)
    }

    @Test fun waitForRunMapsAFailedRunTo500AndADroppedRowTo409() = runBlocking {
        val gate = ResponseGate()
        gate.complete(7, GateResult.Finished("Failed", "r", null))
        assertEquals(500, Harness(response = ResponsePlan(ResponseMode.WaitForRun, 500), gate = gate).let { it.call(it.head()) }.status)
        val gate2 = ResponseGate()
        gate2.complete(7, GateResult.Dropped("FlowNotLive"))
        assertEquals(409, Harness(response = ResponsePlan(ResponseMode.WaitForRun, 500), gate = gate2).let { it.call(it.head()) }.status)
    }
}
