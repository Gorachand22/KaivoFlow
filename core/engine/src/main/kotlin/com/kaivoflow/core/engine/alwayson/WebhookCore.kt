package com.kaivoflow.core.engine.alwayson

import com.kaivoflow.core.engine.FailureCode
import com.kaivoflow.core.engine.SparkTypes
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.Flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.BindException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * The webhook server's decisions, without a socket (spec 3.7, AO-11). The Android class in core:work only reads bytes, calls
 * [WebhookProcessor] and writes the reply it returns, so the order "check, store, reply" is tested here on the JVM.
 */

/** Explicit webhook security settings. Every default is the safe one. */
object WebhookDefaults {
    const val REQUIRE_AUTH: Boolean = true
    const val MAX_BODY_BYTES: Int = 1024 * 1024
    const val MAX_HEADER_BYTES: Int = 16 * 1024
    const val READ_TIMEOUT_MS: Int = 8_000

    /** Wall-clock budget for receiving a whole request, so a slow sender cannot hold a socket. */
    const val REQUEST_DEADLINE_MS: Long = 15_000
    const val MAX_CONNECTIONS: Int = 16
    const val DEFAULT_PORT: Int = 8787
    const val MIN_PORT: Int = 1024
    const val MAX_PORT: Int = 65_535
}

/** Pure request-checking helpers; no Android or socket types. */
object WebhookPolicy {
    const val PATH_PREFIX = "hook/"

    /** Maps a request target such as `/hook/my-flow?x=1` to the route key `my-flow`, or null for anything else. */
    fun routeKey(target: String): String? {
        val path = target.substringBefore('?').substringBefore('#').trim('/')
        if (!path.startsWith(PATH_PREFIX)) return null
        val key = path.removePrefix(PATH_PREFIX).trim('/')
        if (key.isEmpty() || key.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return key
    }

    /** Normalises the user-entered Spark `path` param into a route key. */
    fun normalizeSparkPath(raw: String): String {
        val trimmed = raw.trim().trim('/').removePrefix(PATH_PREFIX).trim('/')
        return trimmed.ifBlank { "my-flow" }
    }

    /** Token from `Authorization: Bearer <t>` or `X-Kaivo-Token: <t>`; headers are lower-cased keys. */
    fun presentedToken(headers: Map<String, String>): String? {
        val auth = headers["authorization"]
        if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) return auth.substring(7).trim().ifEmpty { null }
        return headers["x-kaivo-token"]?.trim()?.ifEmpty { null }
    }

    /** Constant-time token check. A blank expected token never authenticates. */
    fun tokenMatches(expected: String, presented: String?): Boolean {
        if (expected.isBlank() || presented == null) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))
    }

    fun authorized(requireAuth: Boolean, expectedToken: String, headers: Map<String, String>): Boolean =
        !requireAuth || tokenMatches(expectedToken, presentedToken(headers))

    sealed interface Length {
        data class Ok(val bytes: Int) : Length
        data object Invalid : Length
        data object TooLarge : Length
        data object Unsupported : Length
    }

    /** Validates Content-Length / Transfer-Encoding against [maxBytes]. */
    fun bodyLength(headers: Map<String, String>, maxBytes: Int): Length {
        if (headers.containsKey("transfer-encoding")) return Length.Unsupported
        val raw = headers["content-length"] ?: return Length.Ok(0)
        val n = raw.trim().toLongOrNull() ?: return Length.Invalid
        if (n < 0) return Length.Invalid
        if (n > maxBytes) return Length.TooLarge
        return Length.Ok(n.toInt())
    }

    /** `Idempotency-Key` makes a retried delivery of the same request a duplicate instead of a second Run. */
    fun idempotencyKey(headers: Map<String, String>): String? =
        headers["idempotency-key"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
}

// ---- Reach modes and tokens ----------------------------------------------------------------------

enum class ReachMode { Loopback, Lan }

/** Where the listener binds. [host] null means every interface. */
data class BindPlan(val mode: ReachMode, val host: String?, val port: Int)

object WebhookReach {
    /** Loopback is the default. LAN is an explicit opt-in switch and is the only way to bind all interfaces. */
    fun plan(lanEnabled: Boolean, port: Int): BindPlan =
        BindPlan(if (lanEnabled) ReachMode.Lan else ReachMode.Loopback, if (lanEnabled) null else "127.0.0.1", port)

    fun validPort(port: Int): Boolean = port in WebhookDefaults.MIN_PORT..WebhookDefaults.MAX_PORT
}

/**
 * Per-Flow token: HMAC-SHA256 of the Flow id under the app secret. Nothing extra is stored, and regenerating the app secret
 * revokes every Flow token at once. Over the LAN only this token is accepted, so a leaked URL exposes one Flow, not all.
 */
object FlowTokens {
    fun derive(appSecret: String, flowId: String): String = hmacHex(appSecret, "kaivo-flow-token:v1:$flowId")
}

object WebhookAuth {
    /**
     * [routeFlowId] is the Flow that owns the requested route, or null when there is no such route. The answer never depends on
     * whether the route exists: an unknown route simply has no token that can match, so unauthenticated callers cannot probe paths.
     * Loopback accepts the app token or the Flow token; LAN accepts the Flow token only.
     */
    fun authorize(mode: ReachMode, appToken: String, routeFlowId: String?, headers: Map<String, String>): Boolean {
        val presented = WebhookPolicy.presentedToken(headers) ?: return false
        val flowOk = routeFlowId != null && WebhookPolicy.tokenMatches(FlowTokens.derive(appToken, routeFlowId), presented)
        return when (mode) {
            ReachMode.Loopback -> WebhookPolicy.tokenMatches(appToken, presented) || flowOk
            ReachMode.Lan -> flowOk
        }
    }
}

object WebhookSignature {
    /** `X-Kaivo-Signature`: hex HMAC-SHA256 of the raw body under the Spark's shared secret. */
    fun verify(secret: String, body: String, provided: String): Boolean {
        val expected = hmacHex(secret, body)
        return MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }
}

internal fun hmacHex(secret: String, text: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

// ---- Response modes ------------------------------------------------------------------------------

enum class ResponseMode { Immediate, WaitForRun }

/** How a Spark wants the HTTP call answered. Read from the Spark's `responseMode` and `responseTimeoutSec` params. */
data class ResponsePlan(val mode: ResponseMode, val timeoutMs: Long) {
    companion object {
        const val DEFAULT_TIMEOUT_SEC = 10.0
        const val MIN_TIMEOUT_SEC = 1.0
        const val MAX_TIMEOUT_SEC = 55.0
        val IMMEDIATE = ResponsePlan(ResponseMode.Immediate, 0)

        /** Unknown text means Immediate: a typo must never make callers wait. */
        fun parse(mode: String?, timeoutSec: Double?): ResponsePlan {
            val m = when (mode?.trim()?.lowercase()) {
                "wait", "waitforrun", "wait_for_run", "wait-for-run", "sync" -> ResponseMode.WaitForRun
                else -> ResponseMode.Immediate
            }
            if (m == ResponseMode.Immediate) return IMMEDIATE
            val sec = (timeoutSec ?: DEFAULT_TIMEOUT_SEC).takeIf { it.isFinite() }
                ?.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC) ?: DEFAULT_TIMEOUT_SEC
            return ResponsePlan(m, (sec * 1000).toLong())
        }
    }
}

/** What the dispatcher learned about a row, as far as a waiting HTTP caller is concerned. */
sealed interface GateResult {
    /** The Run ended. [status] is the RunStatus name; [responseJson] is the last Step's output as JSON, when there was one. */
    data class Finished(val status: String, val runId: String?, val responseJson: String?) : GateResult
    data class Dropped(val reason: String) : GateResult
    data class Crashed(val runId: String?) : GateResult
}

/**
 * Hands a row's final outcome from the dispatcher to the HTTP handler that is waiting for it. The outcome may arrive before the
 * handler starts waiting (a fast Run), so recent results are kept in a bounded map. Thread-safe.
 */
class ResponseGate(private val keep: Int = 256) {
    private val lock = Any()
    private val waiters = HashMap<Long, CompletableDeferred<GateResult>>()
    private val recent = LinkedHashMap<Long, GateResult>()

    fun complete(rowId: Long, result: GateResult) {
        val waiter = synchronized(lock) {
            val w = waiters.remove(rowId)
            if (w == null) {
                recent[rowId] = result
                while (recent.size > keep) recent.remove(recent.keys.first())
            }
            w
        }
        waiter?.complete(result)
    }

    /** The outcome, or null when [timeoutMs] passes first. Does not cancel the Run: it stays in the Inbox and finishes. */
    suspend fun await(rowId: Long, timeoutMs: Long): GateResult? {
        val deferred = synchronized(lock) {
            recent.remove(rowId)?.let { return it }
            waiters.getOrPut(rowId) { CompletableDeferred() }
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result == null) synchronized(lock) { waiters.remove(rowId) }
        return result
    }

    fun waitingCount(): Int = synchronized(lock) { waiters.size }
}

// ---- Route table ---------------------------------------------------------------------------------

data class RouteSpec(
    val flowId: String,
    val sparkId: String,
    /** Normalised path without the `hook/` prefix. */
    val key: String,
    /** POST, GET or ANY. */
    val method: String,
    val secret: String?,
    val response: ResponsePlan,
)

data class RouteConflict(val key: String, val winnerSparkId: String, val loserFlowId: String, val loserSparkId: String)

data class RouteDiff(val added: Set<String>, val removed: Set<String>, val changed: Set<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

/** Immutable. Replaced as a whole on publish, pause and unpublish, so a request never sees a half-built table. */
class RouteTable private constructor(private val byKey: Map<String, RouteSpec>, val conflicts: List<RouteConflict>) {
    val size: Int get() = byKey.size
    val keys: Set<String> get() = byKey.keys

    fun lookup(key: String): RouteSpec? = byKey[key]
    fun forSpark(flowId: String, sparkId: String): RouteSpec? = byKey.values.firstOrNull { it.flowId == flowId && it.sparkId == sparkId }

    fun diff(previous: RouteTable): RouteDiff = RouteDiff(
        added = byKey.keys - previous.byKey.keys,
        removed = previous.byKey.keys - byKey.keys,
        changed = byKey.keys.intersect(previous.byKey.keys).filter { byKey[it] != previous.byKey[it] }.toSet(),
    )

    companion object {
        val EMPTY = RouteTable(emptyMap(), emptyList())

        /** Deterministic: specs are ordered by Flow and Spark id, the first claimant of a path wins, later ones are conflicts. */
        fun build(specs: List<RouteSpec>): RouteTable {
            val byKey = LinkedHashMap<String, RouteSpec>()
            val conflicts = ArrayList<RouteConflict>()
            specs.sortedWith(compareBy({ it.flowId }, { it.sparkId })).forEach { s ->
                val winner = byKey[s.key]
                if (winner == null) byKey[s.key] = s
                else conflicts += RouteConflict(s.key, winner.sparkId, s.flowId, s.sparkId)
            }
            return RouteTable(byKey, conflicts)
        }

        /** The enabled webhook Sparks of the given live Flows. Callers pass only Published and Error Flows. */
        fun fromFlows(liveFlows: List<Flow>): RouteTable = build(
            liveFlows.flatMap { flow ->
                flow.triggers.filter { it.type == SparkTypes.WEBHOOK && !it.disabled }.map { step ->
                    RouteSpec(
                        flowId = flow.id,
                        sparkId = step.id,
                        key = WebhookPolicy.normalizeSparkPath(step.paramString("path", "my-flow")),
                        method = step.paramString("method", "POST").trim().uppercase().ifEmpty { "POST" },
                        secret = step.paramString("secret").ifBlank { null },
                        response = ResponsePlan.parse(step.paramString("responseMode", "immediate"), step.paramNumber("responseTimeoutSec", ResponsePlan.DEFAULT_TIMEOUT_SEC)),
                    )
                }
            },
        )
    }
}

// ---- Listener status -----------------------------------------------------------------------------

/** Visible status of the listener itself (the Reliability Center and the Flow's Spark row read it). */
sealed interface WebhookListenerStatus {
    data object Stopped : WebhookListenerStatus
    data class Listening(val port: Int, val mode: ReachMode) : WebhookListenerStatus
    data class PortInUse(val port: Int) : WebhookListenerStatus
    data class InvalidPort(val port: Int) : WebhookListenerStatus
    data class Failed(val port: Int, val detail: String) : WebhookListenerStatus
}

object WebhookStatusMapper {
    /** A bind exception becomes PortInUse when the system says so (message text varies by platform), otherwise Failed. */
    fun fromBindFailure(port: Int, t: Throwable): WebhookListenerStatus {
        val msg = t.message.orEmpty()
        val inUse = t is BindException && (msg.contains("in use", true) || msg.contains("EADDRINUSE", true))
        return if (inUse) WebhookListenerStatus.PortInUse(port) else WebhookListenerStatus.Failed(port, t.javaClass.simpleName)
    }

    /**
     * The registration state of one webhook Spark. [engineRunning] false means the listener cannot exist yet: Pending, not failed.
     * A lost path conflict is permanent for the loser, because only editing a Flow can fix it.
     */
    fun registration(spec: RouteSpec, table: RouteTable, status: WebhookListenerStatus, engineRunning: Boolean): SparkRegState {
        val lost = table.conflicts.firstOrNull { it.loserFlowId == spec.flowId && it.loserSparkId == spec.sparkId }
        if (lost != null) return SparkRegState.failed(FailureCode.InvalidConfig, "PathTaken:${lost.key}")
        if (!engineRunning) return SparkRegState.pending("WaitingForEngine")
        return when (status) {
            is WebhookListenerStatus.Listening -> SparkRegState.armed("port ${status.port} ${status.mode.name.lowercase()}")
            is WebhookListenerStatus.PortInUse -> SparkRegState.failed(FailureCode.PortInUse, "PortInUse:${status.port}")
            is WebhookListenerStatus.InvalidPort -> SparkRegState.failed(FailureCode.InvalidConfig, "InvalidPort:${status.port}")
            is WebhookListenerStatus.Failed -> SparkRegState.failed(FailureCode.Unknown, "BindFailed:${status.detail}")
            WebhookListenerStatus.Stopped -> SparkRegState.pending("ListenerStopped")
        }
    }
}

// ---- Request processing --------------------------------------------------------------------------

class WebhookReply(val status: Int, val body: String)

class WebhookRequestHead(val method: String, val target: String, val headers: Map<String, String>)

sealed interface Preflight {
    data class Reject(val reply: WebhookReply) : Preflight
    /** Authenticated and routable: read [bodyLength] bytes and call [WebhookProcessor.handle]. */
    data class Proceed(val route: RouteSpec, val bodyLength: Int) : Preflight
}

sealed interface SinkResult {
    data class Stored(val rowId: Long) : SinkResult
    data object Duplicate : SinkResult
    /** The Inbox write failed: the caller is told so and may retry. Nothing was accepted. */
    data object Failed : SinkResult
}

fun interface WebhookSink {
    /** Must return only after the Inbox row is committed. */
    suspend fun accept(route: RouteSpec, method: String, payload: Map<String, Any?>, idempotencyKey: String?): SinkResult
}

object WebhookReplies {
    fun reply(status: Int, body: Map<String, Any?>) = WebhookReply(status, Values.stringify(body))
    fun error(status: Int, text: String) = reply(status, mapOf("error" to text))

    val BAD_REQUEST = error(400, "bad request")
    val UNAUTHORIZED = error(401, "unauthorized")
    val NOT_FOUND = error(404, "not found")
    val METHOD_NOT_ALLOWED = error(405, "method not allowed")
    val TIMEOUT = error(408, "timeout")
    val LENGTH_REQUIRED = error(411, "length required")
    val TOO_LARGE = error(413, "payload too large")
    val HEADERS_TOO_LARGE = error(431, "headers too large")
    val UNAVAILABLE = WebhookReply(503, Values.stringify(mapOf("error" to "could not store the event, try again")))

    fun reason(status: Int): String = when (status) {
        200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 408 -> "Request Timeout"; 409 -> "Conflict"; 411 -> "Length Required"
        413 -> "Payload Too Large"; 431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"
        503 -> "Service Unavailable"; else -> "Bad Request"
    }
}

/** Turns a final row outcome into the HTTP answer of a wait-for-run call. */
object ResponseMapper {
    fun accepted(rowId: Long, duplicate: Boolean = false): WebhookReply =
        WebhookReplies.reply(200, if (duplicate) mapOf("accepted" to true, "duplicate" to true) else mapOf("accepted" to true, "id" to rowId))

    fun forResult(rowId: Long, result: GateResult?): WebhookReply = when (result) {
        // Timeout is not a failure: the event is stored and the Run goes on. 202 says "accepted, not finished".
        null -> WebhookReplies.reply(202, mapOf("accepted" to true, "id" to rowId, "status" to "running"))
        is GateResult.Finished -> when (result.status) {
            "Succeeded", "PartialSuccess" -> WebhookReply(200, result.responseJson ?: Values.stringify(mapOf("status" to result.status, "runId" to result.runId)))
            else -> WebhookReplies.reply(500, mapOf("error" to "run did not succeed", "status" to result.status, "runId" to result.runId))
        }
        is GateResult.Dropped -> WebhookReplies.reply(409, mapOf("error" to "event was not run", "reason" to result.reason))
        is GateResult.Crashed -> WebhookReplies.reply(500, mapOf("error" to "run crashed", "runId" to result.runId))
    }
}

/**
 * The webhook pipeline. Order is fixed and tested: authenticate (before anything about routes is revealed), check length,
 * route and method, read the body, verify the signature, **store in the Inbox, then reply**. A reply of 200 therefore means the event
 * survives a crash of this process; a storage failure is a 503 and the caller can retry safely.
 */
class WebhookProcessor(
    private val table: () -> RouteTable,
    private val mode: () -> ReachMode,
    private val appToken: () -> String,
    private val sink: WebhookSink,
    private val gate: ResponseGate,
    private val parseBody: (body: String, contentType: String?) -> Map<String, Any?>,
    private val maxBodyBytes: () -> Int = { WebhookDefaults.MAX_BODY_BYTES },
) {
    fun preflight(head: WebhookRequestHead): Preflight {
        val key = WebhookPolicy.routeKey(head.target)
        val route = key?.let { table().lookup(it) }
        if (!WebhookAuth.authorize(mode(), appToken(), route?.flowId, head.headers)) return Preflight.Reject(WebhookReplies.UNAUTHORIZED)
        val length = when (val l = WebhookPolicy.bodyLength(head.headers, maxBodyBytes())) {
            is WebhookPolicy.Length.Ok -> l.bytes
            WebhookPolicy.Length.Invalid -> return Preflight.Reject(WebhookReplies.BAD_REQUEST)
            WebhookPolicy.Length.TooLarge -> return Preflight.Reject(WebhookReplies.TOO_LARGE)
            WebhookPolicy.Length.Unsupported -> return Preflight.Reject(WebhookReplies.LENGTH_REQUIRED)
        }
        if (key == null || route == null) return Preflight.Reject(WebhookReplies.NOT_FOUND)
        val method = head.method.uppercase()
        if (route.method != "ANY" && route.method != method) return Preflight.Reject(WebhookReplies.METHOD_NOT_ALLOWED)
        return Preflight.Proceed(route, length)
    }

    suspend fun handle(head: WebhookRequestHead, route: RouteSpec, body: String): WebhookReply {
        if (route.secret != null && !WebhookSignature.verify(route.secret, body, head.headers["x-kaivo-signature"].orEmpty())) {
            return WebhookReplies.UNAUTHORIZED
        }
        val payload = parseBody(body, head.headers["content-type"])
        val stored = sink.accept(route, head.method.uppercase(), payload, WebhookPolicy.idempotencyKey(head.headers))
        return when (stored) {
            SinkResult.Failed -> WebhookReplies.UNAVAILABLE
            SinkResult.Duplicate -> ResponseMapper.accepted(0, duplicate = true)
            is SinkResult.Stored -> when (route.response.mode) {
                ResponseMode.Immediate -> ResponseMapper.accepted(stored.rowId)
                ResponseMode.WaitForRun -> ResponseMapper.forResult(stored.rowId, gate.await(stored.rowId, route.response.timeoutMs))
            }
        }
    }
}
