package com.kaivoflow.core.work

import android.content.Context
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.RunTrigger
import com.kaivoflow.core.nodes.TriggerSpecs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** One random bearer token for the whole app, created on first use. Shown in the UI later. */
@Singleton
class WebhookTokenStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("kaivo.webhook", Context.MODE_PRIVATE)

    @Synchronized
    fun token(): String {
        prefs.getString(KEY, null)?.takeIf { it.length >= 32 }?.let { return it }
        return regenerate()
    }

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
 * Incoming webhooks: a tiny HTTP/1.1 listener, not a web framework. It is owned and started or
 * stopped by [WebhookService]; this class is only the socket logic.
 *
 * Security defaults (see [WebhookDefaults]):
 *  - binds to loopback unless all-interface exposure is explicitly enabled,
 *  - every request needs the app token (Authorization: Bearer or X-Kaivo-Token),
 *  - header, body, per-read and whole-request limits, and a connection cap,
 *  - fixed generic error bodies, and auth is checked before the route is looked up.
 */
@Singleton
class WebhookServer @Inject constructor(
    private val flowRepository: FlowRepository,
    private val flowEngine: FlowEngine,
    private val tokenStore: WebhookTokenStore,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger(0)

    @Volatile private var routes: Map<String, Route> = emptyMap()
    @Volatile private var requireAuth: Boolean = WebhookDefaults.REQUIRE_AUTH
    @Volatile private var maxBody: Int = WebhookDefaults.MAX_BODY_BYTES

    data class Route(val flowId: String, val secret: String?, val method: String)

    private class Reject(val status: Int) : Exception()

    val isRunning: Boolean get() = serverSocket?.isClosed == false
    val port: Int get() = serverSocket?.localPort ?: 0
    val token: String get() = tokenStore.token()

    /** Rebuilds routes from every active Flow's enabled webhook Spark. Keys are bare paths (no "hook/"). */
    suspend fun refreshRoutes() {
        val next = HashMap<String, Route>()
        flowRepository.all().filter { it.isActive }.forEach { flow ->
            flow.triggers.filter { it.type == TriggerSpecs.WEBHOOK && !it.disabled }.forEach { step ->
                val key = WebhookPolicy.normalizeSparkPath(step.paramString("path", "my-flow"))
                if (key !in next) {
                    next[key] = Route(
                        flowId = flow.id,
                        secret = step.paramString("secret").ifBlank { null },
                        method = step.paramString("method", "POST").uppercase(),
                    )
                }
            }
        }
        routes = next
    }

    @Synchronized
    fun start(
        port: Int = WebhookDefaults.DEFAULT_PORT,
        bindAllInterfaces: Boolean = WebhookDefaults.BIND_ALL_INTERFACES,
        requireAuth: Boolean = WebhookDefaults.REQUIRE_AUTH,
        maxBodyBytes: Int = WebhookDefaults.MAX_BODY_BYTES,
    ) {
        if (isRunning) return
        this.requireAuth = requireAuth
        this.maxBody = maxBodyBytes
        val address = if (bindAllInterfaces) InetSocketAddress(port) else InetSocketAddress(InetAddress.getLoopbackAddress(), port)
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(address, 16)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
        serverSocket = socket
        acceptJob = scope.launch {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                if (active.incrementAndGet() > WebhookDefaults.MAX_CONNECTIONS) {
                    active.decrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                launch {
                    try { handle(client) } finally { active.decrementAndGet() }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob = null
    }

    private suspend fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = WebhookDefaults.READ_TIMEOUT_MS
                val deadline = System.nanoTime() + WebhookDefaults.REQUEST_DEADLINE_MS * 1_000_000L
                val input = socket.getInputStream()
                val head = readHead(input, deadline)
                val lines = head.split("\r\n")
                val parts = lines.first().split(' ')
                if (parts.size != 3 || !parts[2].startsWith("HTTP/")) throw Reject(400)
                val method = parts[0].uppercase()
                val target = parts[1]

                val headers = HashMap<String, String>()
                for (line in lines.drop(1)) {
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }

                // Auth first: unauthenticated callers learn nothing about which routes exist.
                if (!WebhookPolicy.authorized(requireAuth, tokenStore.token(), headers)) throw Reject(401)

                val length = when (val l = WebhookPolicy.bodyLength(headers, maxBody)) {
                    is WebhookPolicy.Length.Ok -> l.bytes
                    WebhookPolicy.Length.Invalid -> throw Reject(400)
                    WebhookPolicy.Length.TooLarge -> throw Reject(413)
                    WebhookPolicy.Length.Unsupported -> throw Reject(411)
                }
                val key = WebhookPolicy.routeKey(target) ?: throw Reject(404)
                val route = routes[key] ?: throw Reject(404)
                if (route.method != "ANY" && route.method != method) throw Reject(405)

                val body = readBody(input, length, deadline)
                if (route.secret != null) {
                    val provided = headers["x-kaivo-signature"].orEmpty()
                    if (!MessageDigest.isEqual(provided.toByteArray(), hmac(route.secret, body).toByteArray())) {
                        throw Reject(401)
                    }
                }

                val payload = parseBody(body, headers["content-type"])
                respond(socket, 200)
                // Enqueue rather than block the socket on the whole Run.
                scope.launch {
                    runCatching {
                        flowEngine.runById(
                            flowId = route.flowId,
                            trigger = RunTrigger(
                                kind = "webhook",
                                items = listOf(FlowData(payload)).toImmutableList(),
                                meta = mapOf("path" to key, "method" to method).toImmutableMap(),
                            ),
                        )
                    }
                }
            } catch (reject: Reject) {
                respond(socket, reject.status)
            } catch (_: SocketTimeoutException) {
                respond(socket, 408)
            } catch (_: Exception) {
                respond(socket, 400)
            }
        }
    }

    private fun parseBody(body: String, contentType: String?): Map<String, Any?> {
        if (body.isBlank()) return mapOf("body" to "")
        if (contentType?.contains("json", true) == true || body.trimStart().startsWith("{")) {
            val parsed = runCatching {
                com.kaivoflow.core.engine.Values.let { _ ->
                    kotlinx.serialization.json.Json.parseToJsonElement(body)
                }
            }.getOrNull()
            return when (parsed) {
                is kotlinx.serialization.json.JsonObject -> parsed.mapValues { decodeJson(it.value) }
                null -> mapOf("body" to body)
                else -> mapOf("body" to decodeJson(parsed))
            }
        }
        if (contentType?.contains("form", true) == true) {
            return body.split('&').mapNotNull { pair ->
                if (!pair.contains('=')) null
                else URLDecoder.decode(pair.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            }.toMap()
        }
        return mapOf("body" to body)
    }

    private fun decodeJson(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonObject -> element.mapValues { decodeJson(it.value) }
        is kotlinx.serialization.json.JsonArray -> element.map { decodeJson(it) }
        else -> {
            val primitive = element as kotlinx.serialization.json.JsonPrimitive
            when {
                primitive.isString -> primitive.content
                primitive.content == "true" -> true
                primitive.content == "false" -> false
                else -> primitive.content.toDoubleOrNull()
            }
        }
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
            if (b == -1) throw Reject(400)
            out.write(b)
            if (out.size() > WebhookDefaults.MAX_HEADER_BYTES) throw Reject(431)
            tail = ((tail shl 8) or b) and 0x7fffffff
            // CR LF CR LF
            if (tail == 0x0d0a0d0a) break
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
            if (n < 0) throw Reject(400)
            read += n
        }
        return String(buffer, Charsets.UTF_8)
    }

    private fun respond(socket: Socket, status: Int) {
        val (reason, body) = when (status) {
            200 -> "OK" to "{\"accepted\":true}"
            400 -> "Bad Request" to "{\"error\":\"bad request\"}"
            401 -> "Unauthorized" to "{\"error\":\"unauthorized\"}"
            404 -> "Not Found" to "{\"error\":\"not found\"}"
            405 -> "Method Not Allowed" to "{\"error\":\"method not allowed\"}"
            408 -> "Request Timeout" to "{\"error\":\"timeout\"}"
            411 -> "Length Required" to "{\"error\":\"length required\"}"
            413 -> "Payload Too Large" to "{\"error\":\"payload too large\"}"
            431 -> "Request Header Fields Too Large" to "{\"error\":\"headers too large\"}"
            else -> "Bad Request" to "{\"error\":\"bad request\"}"
        }
        val bytes = body.toByteArray()
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            val out = socket.getOutputStream()
            out.write(header.toByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    private fun hmac(secret: String, body: String): String = runCatching {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}
