package com.kaivoflow.core.nodes.net.protocols

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolve
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A bounded raw TCP conversation for on-premise devices and simple services. */
class TcpNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.tcp",
        title = "Socket message",
        category = StepCategory.Saas,
        subtitle = "Send a short message to a TCP service and read its reply",
        iconKey = "api",
        params = persistentListOf(
            textParam("host", "Host", default = "127.0.0.1", required = true),
            ParamSpec("port", "Port", ParamType.Number, default = 9000, required = true),
            textParam("payload", "Message", default = "{{ \$json.message }}"),
            ParamSpec("useTls", "Use TLS", ParamType.Boolean, default = false),
            ParamSpec("appendNewline", "Add a line break", ParamType.Boolean, default = true),
            ParamSpec("timeoutSeconds", "Timeout (seconds)", ParamType.Number, default = 5),
            ParamSpec("readReply", "Wait for a reply", ParamType.Boolean, default = true),
            ParamSpec("maxBytes", "Maximum reply size", ParamType.Number, default = 65536),
            textParam("outputField", "Save reply in", default = "reply"),
        ),
        keywords = persistentListOf("tcp", "socket", "tls", "network", "message", "iot", "device", "on premise"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val host = Values.asString(params["host"])
        val port = (Values.asDouble(params["port"]) ?: 0.0).toInt()
        if (host.isBlank() || port !in 1..65535) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "add a host and a port between 1 and 65535")
        }
        val timeoutMs = (Values.asDouble(params["timeoutSeconds"]) ?: 5.0).coerceIn(1.0, 60.0).toInt() * 1000
        val maxBytes = (Values.asDouble(params["maxBytes"]) ?: 65_536.0).toInt().coerceIn(256, 1_000_000)
        val useTls = Values.asBoolean(params["useTls"] ?: false)
        val readReply = Values.asBoolean(params["readReply"] ?: true)
        val appendNewline = Values.asBoolean(params["appendNewline"] ?: true)
        val field = ctx.outputName("reply")
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        return ctx.output(sources.mapIndexed { index, item ->
            val payload = Values.asString(ctx.resolve(params["payload"], index)) +
                if (appendNewline) "\n" else ""
            val reply = withContext(Dispatchers.IO) {
                talk(host, port, payload, timeoutMs, maxBytes, useTls, readReply, ctx)
            }
            item.copy(json = item.json + mapOf(field to reply.text, "socket" to mapOf(
                "host" to host,
                "port" to port.toDouble(),
                "tls" to useTls,
                "bytesRead" to reply.bytes.toDouble(),
                "timedOut" to reply.timedOut,
            )))
        })
    }

    private fun talk(
        host: String,
        port: Int,
        payload: String,
        timeoutMs: Int,
        maxBytes: Int,
        useTls: Boolean,
        readReply: Boolean,
        ctx: StepContext,
    ): SocketReply = runCatching {
        val socket: Socket = if (useTls) {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(host, port), timeoutMs)
                plain.soTimeout = timeoutMs
                // Layered over the connected socket so SNI and hostname verification use `host`.
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
                tls
            } catch (e: Throwable) {
                runCatching { plain.close() }
                throw e
            }
        } else {
            Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
        }
        socket.soTimeout = timeoutMs
        socket.use { s ->
            s.getOutputStream().apply {
                write(payload.toByteArray(Charsets.UTF_8))
                flush()
            }
            if (!readReply) {
                SocketReply("", 0, false)
            } else {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var timedOut = false
                try {
                    while (out.size() < maxBytes) {
                        val read = s.getInputStream().read(buffer)
                        if (read < 0) break
                        val allowed = minOf(read, maxBytes - out.size())
                        out.write(buffer, 0, allowed)
                    }
                } catch (_: SocketTimeoutException) {
                    timedOut = true
                }
                SocketReply(String(out.toByteArray(), Charsets.UTF_8).take(200_000), out.size(), timedOut)
            }
        }
    }.getOrElse { error ->
        throw StepExecutionException(
            ctx.step.id, ctx.step.name,
            "the socket could not complete: ${error.message?.take(160) ?: "connection failed"}",
        )
    }

    private data class SocketReply(val text: String, val bytes: Int, val timedOut: Boolean)
}
