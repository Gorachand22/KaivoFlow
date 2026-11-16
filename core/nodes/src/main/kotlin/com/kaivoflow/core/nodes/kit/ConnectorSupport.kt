package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.HttpResponseSpec
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.nodes.net.web.snippet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Base64

internal fun requireHttpUrl(ctx: StepContext, url: String) {
    if (url.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add a URL")
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        throw StepExecutionException(ctx.step.id, ctx.step.name, "the URL must start with http:// or https://")
    }
}

internal suspend fun connectorHeaders(ctx: StepContext, index: Int): MutableMap<String, String> {
    val raw = ctx.step.params
    val headers = LinkedHashMap<String, String>()
    Values.asMap(raw["headers"]).forEach { (k, v) ->
        val value = ctx.resolveText(v, index)
        if (value.isNotBlank()) headers[k] = value
    }
    when (Values.asString(raw["auth"], "none")) {
        "bearer" -> {
            val token = ctx.secret("authToken")
            if (token.isNotBlank()) headers["Authorization"] = if (token.startsWith("Bearer ")) token else "Bearer $token"
        }
        "header" -> {
            val name = ctx.resolveText(raw["authHeaderName"], index, "Authorization").ifBlank { "Authorization" }
            val token = ctx.secret("authToken")
            if (token.isNotBlank()) headers[name] = token
        }
        "basic" -> {
            val user = ctx.resolveText(raw["authUser"], index)
            val password = ctx.secret("authPassword")
            val encoded = Base64.getEncoder().encodeToString("$user:$password".toByteArray())
            headers["Authorization"] = "Basic $encoded"
        }
        "credential" -> {
            val ref = ctx.secret("credential")
            if (ref.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "no credential selected")
            val credential = ctx.credentials.resolve(ref)
            val token = credential.bearerToken()
            if (token.isNotBlank()) {
                headers["Authorization"] = if (token.startsWith("Bearer ")) token else "Bearer $token"
            }
            credential["header"]?.let { name -> credential["value"]?.let { headers[name] = it } }
            credential["username"]?.let { user ->
                val encoded = Base64.getEncoder()
                    .encodeToString("$user:${credential["password"].orEmpty()}".toByteArray())
                headers["Authorization"] = "Basic $encoded"
            }
        }
    }
    return headers
}

internal suspend fun requestWithRetry(
    ctx: StepContext,
    request: HttpRequestSpec,
    retries: Int,
    delayMs: Long,
    ignoreErrors: Boolean,
): HttpResponseSpec {
    var last: Throwable? = null
    repeat(retries + 1) { attempt ->
        try {
            val response = ctx.http.request(request)
            if (response.isSuccess || ignoreErrors) return response
            last = StepExecutionException(
                ctx.step.id, ctx.step.name,
                "${request.method} ${shortUrl(request.url)} returned HTTP ${response.status}. ${snippet(response.bodyText)}",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            last = e
        }
        if (attempt < retries && delayMs > 0) delay(delayMs)
    }
    throw last ?: StepExecutionException(ctx.step.id, ctx.step.name, "the service did not respond")
}

internal fun responsePayload(response: HttpResponseSpec): Any? =
    response.body ?: MiniJson.parse(response.bodyText) ?: response.bodyText

internal fun extractEntries(payload: Any?, path: String): List<Any?> {
    val selected = if (path.isBlank()) payload else Values.deepGet(payload, path)
    return when (selected) {
        null -> emptyList()
        is List<*> -> selected
        else -> listOf(selected)
    }
}

internal fun connectorItem(
    entry: Any?,
    field: String,
    status: Int,
    durationMs: Long,
    page: Int,
): FlowData {
    val json = LinkedHashMap<String, Any?>()
    json["statusCode"] = status.toDouble()
    json["durationMs"] = durationMs.toDouble()
    json["page"] = page.toDouble()
    if (field.isNotBlank()) {
        json[field] = entry
    } else {
        when (entry) {
            is Map<*, *> -> json.putAll(Values.asMap(entry))
            else -> json["data"] = entry
        }
    }
    return FlowData(json)
}

internal fun shortUrl(url: String): String = if (url.length <= 90) url else url.take(87) + "..."
