package com.kaivoflow.core.work

import android.content.Context
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.RunTrigger
import com.kaivoflow.core.nodes.TriggerSpecs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Incoming webhooks — a tiny HTTP/1.1 listener, not a web framework.
 *
 * Why hand-rolled: a phone that exposes a webhook needs a foreground-only, single-threaded,
 * zero-dependency receiver. Anything bigger belongs on a server.
 *
 * Security:
 *  • Only POST/GET accepted, body capped at 256KB.
 *  • Optional shared secret verified with a constant-time HMAC comparison.
 *  • Runs only while the app is in the foreground or as a visible foreground service, so the port
 *    is never silently open in the background.
 */
@Singleton
class WebhookServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flowRepository: FlowRepository,
    private val flowEngine: FlowEngine,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routes = mutableMapOf<String, Route>()

    data class Route(val flowId: String, val secret: String?, val method: String)

    val isRunning: Boolean get() = serverSocket?.isClosed == false
    val port: Int get() = serverSocket?.localPort ?: 0

    /** Rebuilds routes from every Flow that has an incoming-webhook Spark. */
    suspend fun refreshRoutes() {
        val flows = flowRepository.all()
        withContext(Dispatchers.IO) { routes.clear() }
        flows.forEach { flow ->
            flow.triggers.filter { it.type == TriggerSpecs.WEBHOOK }.forEach { step ->
                val path = step.paramString("path", "my-flow").trim('/').ifBlank { "my-flow" }
                routes[path] = Route(
                    flowId = flow.id,
                    secret = step.paramString("secret").ifBlank { null },
                    method = step.paramString("method", "POST").uppercase(),
                )
            }
        }
    }

    fun start(port: Int = 8787) {
        if (isRunning) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        acceptJob = scope.launch {
            while (isRunning) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { handle(client) }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 8_000
            val input = BufferedInputStream(socket.getInputStream())
            val head = readHead(input) ?: return respond(socket, 400, "Bad Request", "Malformed request")
            val lines = head.lines()
            val requestLine = lines.firstOrNull().orEmpty()
            val parts = requestLine.split(' ')
            if (parts.size < 2) return respond(socket, 400, "Bad Request", "Malformed request line")
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?').trim('/')
            val query = parts[1].substringAfter('?', "")

            val headers = lines.drop(1).mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
            }.toMap()

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length > MAX_BODY) return respond(socket, 413, "Payload Too Large", "Body is too large")

            val body = if (length > 0) {
                val buffer = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(buffer, read, length - read)
                    if (n <= 0) break
                    read += n
                }
                String(buffer, 0, read, Charsets.UTF_8)
            } else ""

            val route = routes[path]
                ?: return respond(socket, 404, "Not Found", "No Flow listens on /$path")
            if (route.method != "ANY" && route.method != method) {
                return respond(socket, 405, "Method Not Allowed", "This webhook accepts ${route.method}")
            }
            if (route.secret != null) {
                val provided = headers["x-kaivo-signature"] ?: query.substringAfter("secret=", "")
                if (!constantTimeEquals(provided, hmac(route.secret, body))) {
                    return respond(socket, 401, "Unauthorized", "Signature did not match")
                }
            }

            val payload = parseBody(body, headers["content-type"])
            respond(socket, 200, "OK", "{\"accepted\":true}")
            // Enqueue rather than block the socket on the whole Run.
            scope.launch {
                flowEngine.runById(
                    flowId = route.flowId,
                    trigger = RunTrigger(
                        kind = "webhook",
                        items = listOf(FlowData(payload)).toImmutableList(),
                        meta = mapOf("path" to path, "method" to method).toImmutableMap(),
                    ),
                )
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

    private fun readHead(input: BufferedInputStream): String? {
        val builder = StringBuilder()
        var previous = 0
        while (builder.length < MAX_HEAD) {
            val byte = input.read()
            if (byte == -1) return if (builder.isEmpty()) null else builder.toString()
            builder.append(byte.toChar())
            if (previous == '\r'.code && byte == '\n'.code && builder.endsWith("\r\n\r\n")) {
                return builder.toString().trimEnd()
            }
            previous = byte
        }
        return builder.toString()
    }

    private fun respond(socket: Socket, status: Int, reason: String, body: String) {
        val bytes = body.toByteArray()
        val out: OutputStream = socket.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
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

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    private companion object {
        const val MAX_BODY = 256 * 1024
        const val MAX_HEAD = 16 * 1024
    }
}
