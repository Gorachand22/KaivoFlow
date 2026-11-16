package com.kaivoflow.core.work

import android.content.Context
import com.kaivoflow.core.engine.alwayson.BindPlan
import com.kaivoflow.core.engine.alwayson.Preflight
import com.kaivoflow.core.engine.alwayson.ReachMode
import com.kaivoflow.core.engine.alwayson.ResponseGate
import com.kaivoflow.core.engine.alwayson.RouteTable
import com.kaivoflow.core.engine.alwayson.WebhookDefaults
import com.kaivoflow.core.engine.alwayson.WebhookListenerStatus
import com.kaivoflow.core.engine.alwayson.WebhookProcessor
import com.kaivoflow.core.engine.alwayson.WebhookReach
import com.kaivoflow.core.engine.alwayson.WebhookReplies
import com.kaivoflow.core.engine.alwayson.WebhookReply
import com.kaivoflow.core.engine.alwayson.WebhookRequestHead
import com.kaivoflow.core.engine.alwayson.WebhookStatusMapper
import com.kaivoflow.core.work.alwayson.InboxPayloads
import com.kaivoflow.core.work.alwayson.InboxWebhookSink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** One random secret for the whole app, created on first use. Per-Flow tokens are derived from it (`FlowTokens`). */
@Singleton
class WebhookTokenStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("kaivo.webhook", Context.MODE_PRIVATE)

    @Synchronized
    fun token(): String {
        prefs.getString(KEY, null)?.takeIf { it.length >= 32 }?.let { return it }
        return regenerate()
    }

    /** Replaces the secret. Every Flow token derived from the old one stops working at once. */
    @Synchronized
    fun regenerate(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY, token).apply()
        return token
    }

    private companion object { const val KEY = "token" }
}

/**
 * The webhook listener's sockets: a tiny HTTP/1.1 server, not a web framework. All decisions (auth, routing, storing before replying,
 * response modes) are in [WebhookProcessor] in core:engine and are tested there; this class reads bytes and writes the reply.
 * It lives inside the Always-On Engine: started and stopped by `WebhookRegistrar`, which the service and the Reconciler call.
 *
 * Security: loopback unless the LAN switch is on (then the per-Flow token is the only credential), header, body, per-read and
 * whole-request limits, a connection cap, fixed generic error bodies, auth before any route is revealed.
 */
@Singleton
class WebhookServer @Inject constructor(
    private val tokenStore: WebhookTokenStore,
    sink: InboxWebhookSink,
    gate: ResponseGate,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var boundPlan: BindPlan? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger(0)

    @Volatile private var routeTable: RouteTable = RouteTable.EMPTY
    @Volatile private var mode: ReachMode = ReachMode.Loopback

    private val _status = MutableStateFlow<WebhookListenerStatus>(WebhookListenerStatus.Stopped)
    /** Visible state of the listener: Listening, PortInUse, Failed, Stopped. */
    val status: StateFlow<WebhookListenerStatus> = _status.asStateFlow()

    private val processor = WebhookProcessor(
        table = { routeTable },
        mode = { mode },
        appToken = { tokenStore.token() },
        sink = sink,
        gate = gate,
        parseBody = ::parseBody,
    )

    val routes: RouteTable get() = routeTable
    val isRunning: Boolean get() = serverSocket?.isClosed == false
    val port: Int get() = serverSocket?.localPort ?: 0

    /** Atomic swap: a request sees the old table or the new one, never a half-built one. */
    fun updateRoutes(table: RouteTable) { routeTable = table }

    /**
     * Binds [plan]. Idempotent when the same plan is already bound; a different plan (LAN switched, port changed) rebinds. A port
     * that is taken is an expected, reported state ([WebhookListenerStatus.PortInUse]), not a crash.
     */
    @Synchronized
    fun bind(plan: BindPlan): WebhookListenerStatus {
        if (isRunning && boundPlan == plan) return _status.value
        closeSocket()
        if (!WebhookReach.validPort(plan.port)) return publish(WebhookListenerStatus.InvalidPort(plan.port))
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            val address = if (plan.host == null) InetSocketAddress(plan.port) else InetSocketAddress(InetAddress.getByName(plan.host), plan.port)
            socket.bind(address, BACKLOG)
        } catch (error: Exception) {
            runCatching { socket.close() }
            return publish(WebhookStatusMapper.fromBindFailure(plan.port, error))
        }
        serverSocket = socket
        boundPlan = plan
        mode = plan.mode
        acceptJob = scope.launch { acceptLoop(socket) }
        return publish(WebhookListenerStatus.Listening(socket.localPort, plan.mode))
    }

    @Synchronized
    fun stop() {
        closeSocket()
        publish(WebhookListenerStatus.Stopped)
    }

    private fun closeSocket() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob = null
        boundPlan = null
    }

    private fun publish(s: WebhookListenerStatus): WebhookListenerStatus { _status.value = s; return s }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            if (active.incrementAndGet() > WebhookDefaults.MAX_CONNECTIONS) {
                active.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            scope.launch {
                try { handle(client) } finally { active.decrementAndGet() }
            }
        }
    }

    private class Reject(val reply: WebhookReply) : Exception()

    private suspend fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = WebhookDefaults.READ_TIMEOUT_MS
                val deadline = System.nanoTime() + WebhookDefaults.REQUEST_DEADLINE_MS * 1_000_000L
                val input = socket.getInputStream()
                val lines = readHead(input, deadline).split("\r\n")
                val parts = lines.first().split(' ')
                if (parts.size != 3 || !parts[2].startsWith("HTTP/")) throw Reject(WebhookReplies.BAD_REQUEST)
                val headers = HashMap<String, String>()
                for (line in lines.drop(1)) {
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val head = WebhookRequestHead(parts[0], parts[1], headers)
                val reply = when (val p = processor.preflight(head)) {
                    is Preflight.Reject -> p.reply
                    is Preflight.Proceed -> processor.handle(head, p.route, readBody(input, p.bodyLength, deadline))
                }
                respond(socket, reply)
            } catch (reject: Reject) {
                respond(socket, reject.reply)
            } catch (_: SocketTimeoutException) {
                respond(socket, WebhookReplies.TIMEOUT)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                respond(socket, WebhookReplies.BAD_REQUEST)
            }
        }
    }

    private fun parseBody(body: String, contentType: String?): Map<String, Any?> {
        if (body.isBlank()) return mapOf("body" to "")
        if (contentType?.contains("json", true) == true || body.trimStart().startsWith("{")) {
            return when (val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull()) {
                is JsonObject -> parsed.mapValues { InboxPayloads.fromJson(it.value) }
                null -> mapOf("body" to body)
                else -> mapOf("body" to InboxPayloads.fromJson(parsed))
            }
        }
        if (contentType?.contains("form", true) == true) {
            return body.split('&').mapNotNull { pair ->
                if (!pair.contains('=')) null
                else URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            }.toMap()
        }
        return mapOf("body" to body)
    }

    private fun checkDeadline(deadline: Long) {
        if (System.nanoTime() > deadline) throw SocketTimeoutException()
    }

    private fun readHead(input: InputStream, deadline: Long): String {
        val out = ByteArrayOutputStream()
        var tail = 0
        while (true) {
            checkDeadline(deadline)
            val b = input.read()
            if (b == -1) throw Reject(WebhookReplies.BAD_REQUEST)
            out.write(b)
            if (out.size() > WebhookDefaults.MAX_HEADER_BYTES) throw Reject(WebhookReplies.HEADERS_TOO_LARGE)
            tail = ((tail shl 8) or b) and 0x7fffffff
            if (tail == 0x0d0a0d0a) break // CR LF CR LF
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1).trimEnd('\r', '\n')
    }

    private fun readBody(input: InputStream, length: Int, deadline: Long): String {
        if (length == 0) return ""
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            checkDeadline(deadline)
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw Reject(WebhookReplies.BAD_REQUEST)
            read += n
        }
        return String(buffer, Charsets.UTF_8)
    }

    private fun respond(socket: Socket, reply: WebhookReply) {
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        val retry = if (reply.status == 503) "Retry-After: 5\r\n" else ""
        val header = "HTTP/1.1 ${reply.status} ${WebhookReplies.reason(reply.status)}\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n$retry" + "Connection: close\r\n\r\n"
        runCatching {
            val out = socket.getOutputStream()
            out.write(header.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    private companion object { const val BACKLOG = 16 }
}
