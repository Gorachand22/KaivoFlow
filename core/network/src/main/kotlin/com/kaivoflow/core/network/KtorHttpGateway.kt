package com.kaivoflow.core.network

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
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) : HttpGateway {

    override suspend fun request(request: HttpRequestSpec): HttpResponseSpec = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val response = client.request(request.url) {
                method = HttpMethod.parse(request.method.uppercase())
                request.query.forEach { (key, value) -> parameter(key, value) }
                request.headers.forEach { (key, value) -> header(key, value) }

                when (request.method.uppercase()) {
                    "GET", "HEAD" -> Unit
                    else -> when (request.bodyKind) {
                        HttpRequestSpec.BodyKind.Json -> {
                            contentType(ContentType.Application.Json)
                            setBody(serialize(request.body))
                        }
                        HttpRequestSpec.BodyKind.Form -> {
                            contentType(ContentType.Application.FormUrlEncoded)
                            setBody(
                                request.formFields.entries.joinToString("&") { (k, v) ->
                                    "${encode(k)}=${encode(v)}"
                                }
                            )
                        }
                        HttpRequestSpec.BodyKind.Raw -> {
                            contentType(ContentType.Text.Plain)
                            setBody(Values.asString(request.body))
                        }
                        HttpRequestSpec.BodyKind.Multipart -> {
                            val upload = request.binaryUpload
                            if (upload != null) {
                                val boundary = "kaivo" + java.util.UUID.randomUUID().toString()
                                    .filter { it.isLetterOrDigit() }
                                header(HttpHeaders.ContentType, "multipart/form-data; boundary=$boundary")
                                setBody(buildMultipart(boundary, upload))
                            }
                        }
                        HttpRequestSpec.BodyKind.None -> Unit
                    }
                }
                timeout { requestTimeoutMillis = request.timeoutMs }
            }

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
        } catch (e: UnknownHostException) {
            throw KaivoException("No internet connection, or that address does not exist.", e)
        } catch (e: SocketTimeoutException) {
            throw KaivoException("The server took too long to answer (${request.timeoutMs}ms).", e)
        } catch (e: IOException) {
            throw KaivoException("The request could not be completed: ${e.message ?: "network error"}", e)
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

        /**
         * One shared client: connection pooling matters on mobile data, and a Flow that runs 50
         * HTTP Steps should not open 50 TLS sessions.
         */
        fun createClient(): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = true
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
                    retryOnConnectionFailure(true)
                }
            }
        }
    }
}
