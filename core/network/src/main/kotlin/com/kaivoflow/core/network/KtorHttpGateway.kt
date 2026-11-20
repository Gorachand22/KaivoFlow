package com.kaivoflow.core.network

import com.kaivoflow.core.ai.HttpStreamGateway
import com.kaivoflow.core.ai.StreamingHttpResponse
import com.kaivoflow.core.engine.BinaryUploadSpec
import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.HttpResponseSpec
import com.kaivoflow.core.engine.KaivoException
import com.kaivoflow.core.engine.Values
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only place the app talks to the network.
 *
 * Everything interesting (auth headers, retries, error text) is decided by the Step executor; this
 * class only turns an [HttpRequestSpec] into bytes on the wire and back, and translates transport
 * failures into messages a non-technical user can act on.
 *
 * Binary support is deliberately transport-level: a download is returned as base64 in
 * [HttpResponseSpec.bodyBase64] and an upload is accepted as a [BinaryUploadSpec], so no Step ever
 * needs to hold an OkHttp or Android type.
 */
@Singleton
class KtorHttpGateway @Inject constructor(
    private val client: HttpClient,
    private val allowList: HostAllowList,
) : HttpGateway, HttpStreamGateway {

    /** Owns the readers of open streams; a reader ends when its collector stops, fails or the body ends. */
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun request(request: HttpRequestSpec): HttpResponseSpec = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val response = sendGuarded(request)

            val responseHeaders = response.headers.entries()
                .associate { it.key.lowercase() to it.value.joinToString(", ") }

            if (request.responseAsBinary) {
                val declared = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
                if (declared > MAX_BINARY_BYTES) {
                    throw KaivoException("The downloaded file is larger than the ${MAX_BINARY_BYTES / (1024 * 1024)} MB limit.")
                }
                val bytes = response.readBytes()
                if (bytes.size > MAX_BINARY_BYTES) {
                    throw KaivoException("The downloaded file is larger than the ${MAX_BINARY_BYTES / (1024 * 1024)} MB limit.")
                }
                HttpResponseSpec(
                    status = response.status.value,
                    headers = responseHeaders,
                    bodyText = "",
                    body = null,
                    durationMs = System.currentTimeMillis() - started,
                    bodyBase64 = Base64.getEncoder().encodeToString(bytes),
                )
            } else {
                val text = response.bodyAsText()
                val contentType = response.headers["Content-Type"].orEmpty()
                HttpResponseSpec(
                    status = response.status.value,
                    headers = responseHeaders,
                    bodyText = text,
                    body = if (contentType.contains("json", true) || looksLikeJson(text)) parse(text) else text,
                    durationMs = System.currentTimeMillis() - started,
                )
            }
        } catch (e: KaivoException) {
            throw e
        } catch (e: UnknownHostException) {
            blockedCause(e)?.let { throw KaivoException("Blocked: ${it.message}", e) }
            throw KaivoException("No internet connection, or that address does not exist.", e)
        } catch (e: SocketTimeoutException) {
            throw KaivoException("The server took too long to answer (${request.timeoutMs}ms).", e)
        } catch (e: IOException) {
            blockedCause(e)?.let { throw KaivoException("Blocked: ${it.message}", e) }
            throw KaivoException("The request could not be completed: ${e.message ?: "network error"}", e)
        }
    }


    /**
     * Opens a streamed response (server-sent events or newline-delimited JSON). The status and headers are returned
     * as soon as they arrive; [StreamingHttpResponse.lines] then delivers each line of the body the moment it is
     * complete. The same rules as [request] apply: the address is checked before connecting and again by
     * [GuardedDns], redirects are followed by hand (only when the request allows it) with every hop checked and
     * credentials dropped when the origin changes, and nothing is retried. Keys travel in headers only and nothing
     * here is logged. Collecting [StreamingHttpResponse.lines] reads the body, and stopping the collector (or
     * cancelling the caller) closes the connection. A silent server ends the stream with an error after
     * [HttpRequestSpec.timeoutMs]; a body that is not read for that long is dropped as well.
     */
    override suspend fun open(request: HttpRequestSpec): StreamingHttpResponse {
        val head = CompletableDeferred<Pair<Int, Map<String, String>>>()
        val lines = Channel<String>(STREAM_BUFFER_LINES)
        val reader = streamScope.launch {
            try {
                withTimeout(MAX_STREAM_MS) {
                    executeStreaming(request) { response ->
                        val status = response.status.value
                        head.complete(status to response.headers.entries().associate { it.key.lowercase() to it.value.joinToString(", ") })
                        val channel = response.bodyAsChannel()
                        // An error body is only read for its message; a success body is read to its end.
                        var budget = if (status in 200..299) Long.MAX_VALUE else MAX_ERROR_BODY_BYTES
                        readStreamLines(read = { b, o, l -> channel.readAvailable(b, o, l) }) { line ->
                            budget -= line.length + 1
                            if (budget < 0) throw StreamEnd
                            val sent = withTimeoutOrNull(request.timeoutMs) { lines.send(line) }
                            if (sent == null) throw IOException("The stream was not read in time.")
                        }
                    }
                }
                lines.close()
            } catch (e: StreamEnd) {
                lines.close()
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // The overall cap (a timeout is a CancellationException, but here the caller did not cancel).
                val failure = streamFailure(e, request)
                if (!head.completeExceptionally(failure)) lines.close(failure) else lines.close()
            } catch (e: CancellationException) {
                head.cancel(e)
                lines.close(e)
                throw e
            } catch (e: Throwable) {
                val failure = streamFailure(e, request)
                if (!head.completeExceptionally(failure)) lines.close(failure) else lines.close()
            }
        }
        val (status, headers) = try {
            head.await()
        } catch (e: CancellationException) {
            reader.cancel()
            throw e
        }
        return StreamingHttpResponse(status, headers, lines.receiveAsFlow().onCompletion { reader.cancel() })
    }

    /** Ends a body read on purpose (error body cap); not an error. */
    private object StreamEnd : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Same translation as [request], for failures that happen while a stream is opened or read. */
    private fun streamFailure(e: Throwable, request: HttpRequestSpec): KaivoException = when {
        e is KaivoException -> e
        blockedCause(e) != null -> KaivoException("Blocked: ${blockedCause(e)?.message}", e)
        e is UnknownHostException -> KaivoException("No internet connection, or that address does not exist.", e)
        e is kotlinx.coroutines.TimeoutCancellationException -> KaivoException("The stream ran longer than ${MAX_STREAM_MS / 60_000} minutes and was stopped.", e)
        e is SocketTimeoutException ->
            KaivoException("The server stopped sending data (${request.timeoutMs}ms without anything).", e)
        e is IOException -> KaivoException("The stream could not be completed: ${e.message ?: "network error"}", e)
        else -> KaivoException("The stream failed: ${e::class.java.simpleName}", e)
    }

    /**
     * The redirect loop of [sendGuarded] for a body that is read while it is open: the final response is handed to
     * [onFinal] and the connection is closed when it returns.
     */
    private suspend fun executeStreaming(request: HttpRequestSpec, onFinal: suspend (HttpResponse) -> Unit) {
        var url = request.url
        var method = request.method.uppercase()
        var headers: Map<String, String> = request.headers
        var withBody = true
        var withQuery = true
        var hop = 0
        while (true) {
            when (val v = NetworkGuard.checkUrl(url, allowList)) {
                is GuardVerdict.Blocked -> throw KaivoException("Blocked: ${v.reason}.")
                is GuardVerdict.Allowed -> Unit
            }
            var next: RedirectDecision.Follow? = null
            val sendBody = withBody
            val sendQuery = withQuery
            val m = method
            val h = headers
            client.prepareRequest(url) { configure(request, m, h, sendQuery, sendBody, streaming = true) }.execute { response ->
                if (!request.followRedirects || !RedirectPolicy.isRedirect(response.status.value)) {
                    onFinal(response)
                } else {
                    when (val d = RedirectPolicy.decide(url, response.status.value, response.headers[HttpHeaders.Location], m, h, hop, allowList)) {
                        is RedirectDecision.Refuse -> throw KaivoException("The server redirected the request but ${d.reason}.")
                        is RedirectDecision.Follow -> next = d
                    }
                }
            }
            val d = next ?: return
            url = d.url; method = d.method; headers = d.headers
            withBody = withBody && d.keepBody
            withQuery = false
            hop++
        }
    }

    private fun blockedCause(e: Throwable): BlockedAddressException? {
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth++ < 8) { if (c is BlockedAddressException) return c; c = c.cause }
        return null
    }

    /**
     * Sends [request] with the network rules applied: the address is checked before connecting (and again at
     * connect time by [GuardedDns]), redirects are followed by hand only when the request allows them, at most
     * [RedirectPolicy.DEFAULT_MAX_HOPS] times, and every hop is checked again. Credentials are dropped when a
     * redirect leaves the origin. With `followRedirects = false` the 3xx answer is returned as it is.
     */
    private suspend fun sendGuarded(request: HttpRequestSpec): HttpResponse {
        var url = request.url
        var method = request.method.uppercase()
        var headers: Map<String, String> = request.headers
        var withBody = true
        var withQuery = true
        var hop = 0
        while (true) {
            when (val v = NetworkGuard.checkUrl(url, allowList)) {
                is GuardVerdict.Blocked -> throw KaivoException("Blocked: ${v.reason}.")
                is GuardVerdict.Allowed -> Unit
            }
            val response = client.request(url) { configure(request, method, headers, withQuery, withBody) }
            if (!request.followRedirects || !RedirectPolicy.isRedirect(response.status.value)) return response
            when (val d = RedirectPolicy.decide(url, response.status.value, response.headers[HttpHeaders.Location], method, headers, hop, allowList)) {
                is RedirectDecision.Refuse -> throw KaivoException("The server redirected the request but ${d.reason}.")
                is RedirectDecision.Follow -> {
                    url = d.url; method = d.method; headers = d.headers
                    withBody = withBody && d.keepBody
                    withQuery = false // the Location address already carries its own query
                    hop++
                }
            }
        }
    }

    private fun HttpRequestBuilder.configure(
        request: HttpRequestSpec, methodName: String, hdrs: Map<String, String>, withQuery: Boolean, withBody: Boolean,
        streaming: Boolean = false,
    ) {
        method = HttpMethod.parse(methodName)
        if (withQuery) request.query.forEach { (key, value) -> parameter(key, value) }
        hdrs.forEach { (key, value) -> header(key, value) }
        if (withBody) when (methodName) {
            "GET", "HEAD" -> Unit
            else -> when (request.bodyKind) {
                HttpRequestSpec.BodyKind.Json -> {
                    contentType(ContentType.Application.Json)
                    setBody(serialize(request.body))
                }
                HttpRequestSpec.BodyKind.Form -> {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(request.formFields.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" })
                }
                HttpRequestSpec.BodyKind.Raw -> {
                    contentType(ContentType.Text.Plain)
                    setBody(Values.asString(request.body))
                }
                HttpRequestSpec.BodyKind.Multipart -> {
                    val upload = request.binaryUpload
                    if (upload != null) {
                        val boundary = "kaivo" + java.util.UUID.randomUUID().toString().filter { it.isLetterOrDigit() }
                        header(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
                        setBody(buildMultipart(boundary, upload))
                    }
                }
                HttpRequestSpec.BodyKind.None -> Unit
            }
        }
        if (streaming) {
            // A stream may last longer than any fixed request limit; what must not happen is silence. The request's
            // timeout is therefore the longest the server may send nothing (connect and read), and the total is
            // capped by MAX_STREAM_MS in open().
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                connectTimeoutMillis = request.timeoutMs
                socketTimeoutMillis = request.timeoutMs
            }
        } else {
            timeout { requestTimeoutMillis = request.timeoutMs }
        }
    }

    /**
     * Builds a multipart/form-data body by hand. This keeps the binary path independent of any
     * optional Ktor form artifact and makes the exact bytes testable.
     */
    private fun buildMultipart(boundary: String, upload: BinaryUploadSpec): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(upload.base64)
        } catch (e: IllegalArgumentException) {
            throw KaivoException("The attachment is not valid base64 data.", e)
        }
        val out = ByteArrayOutputStream(bytes.size + 512)
        fun line(text: String) {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.write('\r'.code)
            out.write('\n'.code)
        }
        upload.extraFields.forEach { (key, value) ->
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"${safePart(key)}\"")
            line("")
            line(value)
        }
        line("--$boundary")
        line(
            "Content-Disposition: form-data; name=\"${safePart(upload.field)}\"; " +
                "filename=\"${safePart(upload.fileName)}\""
        )
        line("Content-Type: ${safePart(upload.mimeType.ifBlank { "application/octet-stream" })}")
        line("Content-Transfer-Encoding: binary")
        line("")
        out.write(bytes)
        out.write('\r'.code)
        out.write('\n'.code)
        line("--$boundary--")
        return out.toByteArray()
    }

    /** Header injection guard: part names must never be able to add new header lines. */
    private fun safePart(value: String): String = value
        .replace("\"", "")
        .replace("\r", "")
        .replace("\n", "")

    private fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    private fun parse(text: String): Any? = runCatching {
        fromJson(Json.parseToJsonElement(text))
    }.getOrNull() ?: text

    private fun fromJson(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.mapValues { fromJson(it.value) }
        is JsonArray -> element.map { fromJson(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            else -> element.content
        }
    }

    private fun serialize(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        else -> Values.stringify(value)
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        /** A phone should never buffer an unbounded download into memory. */
        const val MAX_BINARY_BYTES = 25L * 1024 * 1024

        /** No model stream may run longer than this, whatever it keeps sending. */
        const val MAX_STREAM_MS = 15L * 60 * 1000

        /** An error body is read only for its message. */
        const val MAX_ERROR_BODY_BYTES = 64L * 1024

        /** Lines read ahead of the collector; beyond this the reader waits, so memory stays bounded. */
        const val STREAM_BUFFER_LINES = 256

        /**
         * One shared client: connection pooling matters on mobile data, and a Flow that runs 50
         * HTTP Steps should not open 50 TLS sessions.
         */
        fun createClient(allowList: HostAllowList): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false
            // Redirects are followed by hand in sendGuarded, so every hop passes the network rules.
            followRedirects = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 120_000
                requestTimeoutMillis = 120_000
            }
            engine {
                config {
                    // No automatic retry: a POST must not be sent twice behind the Step's back.
                    retryOnConnectionFailure(false)
                    followRedirects(false)
                    followSslRedirects(false)
                    dns(GuardedDns(allowList))
                }
            }
        }
    }
}
