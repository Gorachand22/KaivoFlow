package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.BinaryDataRef
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocketFactory

/** Read an RSS or Atom feed into ordinary items. */
class RssNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.rss",
        title = "Feed reader",
        category = StepCategory.Saas,
        subtitle = "Read new posts from an RSS or Atom feed",
        iconKey = "rssFeed",
        params = persistentListOf(
            textParam("url", "Feed address", default = "{{ \$json.url }}", required = true),
            ParamSpec("limit", "Maximum posts", ParamType.Number, default = 25),
            ParamSpec("timeoutSeconds", "Timeout (seconds)", ParamType.Number, default = 20),
        ),
        keywords = persistentListOf("rss", "atom", "feed", "news", "posts", "blog", "subscribe", "xml"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val url = ctx.resolveText(params["url"], 0)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "the feed address must start with http:// or https://")
        }
        val timeout = (Values.asDouble(params["timeoutSeconds"]) ?: 20.0).coerceIn(1.0, 120.0).toLong() * 1000L
        val response = ctx.http.request(
            HttpRequestSpec(
                method = "GET",
                url = url,
                bodyKind = HttpRequestSpec.BodyKind.None,
                headers = mapOf("Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml"),
                timeoutMs = timeout,
            )
        )
        if (!response.isSuccess) throw StepExecutionException(ctx.step.id, ctx.step.name, "the feed answered ${response.status}")
        val root = Values.asMap(XmlLite.parse(response.bodyText) ?: emptyMap<String, Any?>())
        val channel = Values.asMap(Values.deepGet(root, "rss.channel"))
            .ifEmpty { Values.asMap(root["feed"]) }
        val rawItems = channel["item"] ?: channel["entry"] ?: root["item"] ?: root["entry"] ?: emptyList<Any?>()
        val items = Values.asList(rawItems).mapNotNull { entry ->
            val row = Values.asMap(entry)
            if (row.isEmpty()) null else mapOf(
                "title" to text(row["title"]),
                "link" to link(row["link"]),
                "summary" to text(row["description"] ?: row["summary"] ?: row["content"]),
                "published" to text(row["pubDate"] ?: row["published"] ?: row["updated"]),
                "id" to text(row["guid"] ?: row["id"]),
            )
        }
        val limit = (Values.asDouble(params["limit"]) ?: 25.0).toInt().coerceIn(1, 200)
        ctx.log("Read ${items.size} feed entries.")
        return ctx.output(items.take(limit).map { FlowData(it) })
    }

    private fun text(value: Any?): String = when (value) {
        is Map<*, *> -> HtmlLite.text(Values.asString(Values.asMap(value)["#text"] ?: Values.asMap(value)["text"]))
        else -> HtmlLite.text(Values.asString(value))
    }

    private fun link(value: Any?): String = when (value) {
        is Map<*, *> -> Values.asString(Values.asMap(value)["@href"] ?: Values.asMap(value)["#text"])
        is List<*> -> Values.asList(value).firstNotNullOfOrNull { link(it) }.orEmpty()
        else -> Values.asString(value)
    }
}

/** Send a GraphQL query, with variables and a clear error message when the API says no. */
class GraphQlNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.graphql",
        title = "GraphQL",
        category = StepCategory.Saas,
        subtitle = "Ask an API for exactly the fields you need",
        iconKey = "api",
        params = persistentListOf(
            textParam("endpoint", "Endpoint", default = "https://api.example.com/graphql", required = true),
            ParamSpec("query", "Query", ParamType.Multiline, required = true, default = "query { viewer { id } }",
                supportsExpression = false),
            ParamSpec("variables", "Variables (JSON)", ParamType.Json, default = emptyMap<String, Any?>()),
            textParam("operationName", "Operation name", placeholder = "optional"),
            ParamSpec("headers", "Headers", ParamType.KeyValue, advanced = true),
            ParamSpec("authToken", "Bearer token", ParamType.Secret, advanced = true),
            ParamSpec("timeoutSeconds", "Timeout (seconds)", ParamType.Number, default = 30),
            ParamSpec("failOnErrors", "Stop when the API returns errors", ParamType.Boolean, default = true),
            textParam("outputField", "Save response in", default = "data"),
        ),
        keywords = persistentListOf("graphql", "query", "mutation", "api", "variables", "gql"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val endpoint = ctx.resolveText(params["endpoint"], 0)
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "the endpoint must start with http:// or https://")
        }
        val query = Values.asString(params["query"])
        if (query.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add a query")
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        Values.asMap(params["headers"]).forEach { (key, raw) ->
            val value = Values.asString(ctx.resolve(raw, 0))
            if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
        }
        ctx.secret("authToken").takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer $it" }
        val body = LinkedHashMap<String, Any?>()
        body["query"] = query
        body["variables"] = ctx.resolve(params["variables"], 0) ?: emptyMap<String, Any?>()
        ctx.resolveText(params["operationName"], 0).takeIf { it.isNotBlank() }?.let { body["operationName"] = it }
        val timeout = (Values.asDouble(params["timeoutSeconds"]) ?: 30.0).coerceIn(1.0, 120.0).toLong() * 1000L
        val response = ctx.http.request(
            HttpRequestSpec(
                method = "POST",
                url = endpoint,
                headers = headers,
                body = body,
                bodyKind = HttpRequestSpec.BodyKind.Json,
                timeoutMs = timeout,
            )
        )
        val parsed = response.body ?: MiniJson.parse(response.bodyText) ?: response.bodyText
        val errors = Values.asList(Values.deepGet(parsed, "errors")).filterNotNull()
        if (errors.isNotEmpty() && Values.asBoolean(params["failOnErrors"] ?: true)) {
            val first = Values.asString(Values.deepGet(errors.first(), "message"), "the API reported an error")
            throw StepExecutionException(ctx.step.id, ctx.step.name, first.take(240))
        }
        if (!response.isSuccess) throw StepExecutionException(ctx.step.id, ctx.step.name, "the server answered ${response.status}")
        val field = ctx.outputName("data")
        return ctx.output(listOf(FlowData(mapOf(
            field to (Values.deepGet(parsed, "data") ?: parsed),
            "errors" to errors,
            "status" to response.status.toDouble(),
        ))))
    }
}

/** A generic file-store request Step for WebDAV-style endpoints. */
class WebDavNode(private val gateway: DeviceGateway = NoopDeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.webdav",
        title = "File store request",
        category = StepCategory.Saas,
        subtitle = "Read, write or list files on a WebDAV-style endpoint",
        iconKey = "cloud",
        params = persistentListOf(
            ParamSpec(
                key = "method",
                label = "Method",
                type = ParamType.Select,
                default = "GET",
                options = selectOptions(
                    "GET" to "Download (GET)", "PUT" to "Upload (PUT)", "PROPFIND" to "List (PROPFIND)",
                    "MKCOL" to "Make folder (MKCOL)", "DELETE" to "Delete", "MOVE" to "Move", "COPY" to "Copy",
                ),
            ),
            textParam("url", "File or folder address", default = "{{ \$json.url }}", required = true),
            textParam("body", "Text to send", showWhen = mapOf("method" to listOf("PUT", "PROPFIND"))),
            ParamSpec("headers", "Headers", ParamType.KeyValue, advanced = true),
            ParamSpec("authToken", "Bearer token", ParamType.Secret, advanced = true),
            ParamSpec("downloadAsBinary", "Keep downloads as an attachment", ParamType.Boolean, default = true,
                showWhen = mapOf("method" to listOf("GET"))),
            textParam("fileName", "Attachment name", default = "download.bin",
                showWhen = mapOf("method" to listOf("GET"))),
            ParamSpec("timeoutSeconds", "Timeout (seconds)", ParamType.Number, default = 30),
            textParam("outputField", "Save response in", default = "response"),
        ),
        keywords = persistentListOf("webdav", "file", "storage", "download", "upload", "propfind", "cloud", "http"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val method = Values.asString(params["method"], "GET").uppercase()
        val field = ctx.outputName("response")
        val headers = LinkedHashMap<String, String>()
        Values.asMap(params["headers"]).forEach { (key, raw) ->
            val value = Values.asString(ctx.resolve(raw, 0))
            if (key.isNotBlank() && value.isNotBlank()) headers[key] = value
        }
        ctx.secret("authToken").takeIf { it.isNotBlank() }?.let { headers["Authorization"] = "Bearer $it" }
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        return ctx.output(sources.mapIndexed { index, item ->
            val url = Values.asString(ctx.resolve(params["url"], index))
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw StepExecutionException(ctx.step.id, ctx.step.name, "the address must start with http:// or https://")
            }
            val bodyText = Values.asString(ctx.resolve(params["body"], index))
            val asBinary = method == "GET" && Values.asBoolean(params["downloadAsBinary"] ?: true)
            val response = ctx.http.request(
                HttpRequestSpec(
                    method = method,
                    url = url,
                    headers = headers,
                    body = bodyText,
                    bodyKind = if (bodyText.isBlank()) HttpRequestSpec.BodyKind.None else HttpRequestSpec.BodyKind.Raw,
                    timeoutMs = (Values.asDouble(params["timeoutSeconds"]) ?: 30.0).coerceIn(1.0, 120.0).toLong() * 1000L,
                    responseAsBinary = asBinary,
                )
            )
            if (!response.isSuccess) {
                throw StepExecutionException(ctx.step.id, ctx.step.name, "the server answered ${response.status}")
            }
            var result: FlowData = item.copy(json = item.json + (field to mapOf(
                "status" to response.status.toDouble(),
                "headers" to response.headers,
                "body" to if (asBinary) "" else (response.body ?: response.bodyText),
            )))
            if (asBinary) {
                val name = Values.asString(ctx.resolve(params["fileName"], index), "download.bin")
                val mime = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }
                    ?.value?.substringBefore(';') ?: BinarySupport.mimeTypeFor(name)
                val ref = store(ctx, response.bodyBase64, name, mime)
                result = result.withBinary("download", ref)
            }
            result
        })
    }

    private suspend fun store(ctx: StepContext, base64: String, name: String, mime: String): BinaryDataRef {
        if (base64.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "the downloaded file was empty")
        val map = gateway.storeBinary(name, mime, base64)
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the downloaded file could not be saved")
        return BinarySupport.ref(map)
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the downloaded file reference is incomplete")
    }
}

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
            item.copy(json = item.json + (field to reply.text, "socket" to mapOf(
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
            SSLSocketFactory.getDefault().createSocket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
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
