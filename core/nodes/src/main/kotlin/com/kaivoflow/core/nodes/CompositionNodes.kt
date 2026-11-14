package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.HttpResponseSpec
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.URI
import java.util.Base64

/**
 * Original composition tools inspired by capability gaps, not by copied integrations.
 *
 * These three Steps deliberately cover the patterns that hundreds of service-specific presets
 * repeat: call another Flow, call an arbitrary API and shape/paginate its response, and remember
 * which items have already been seen between Runs.
 */

class SubFlowNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.subflow",
        title = "Run another Flow",
        category = StepCategory.Logic,
        subtitle = "Reuse a Flow and bring its items back",
        iconKey = "flow",
        params = persistentListOf(
            ParamSpec("flowId", "Flow ID", ParamType.Text, required = true,
                hint = "Paste the ID of another Flow saved on this phone."),
            ParamSpec("includeInput", "Send incoming items", ParamType.Boolean, default = true),
            ParamSpec("maxItems", "Max items to send", ParamType.Number, default = 100, advanced = true),
            ParamSpec("outputField", "Wrap result in field", ParamType.Text, advanced = true),
        ),
        keywords = persistentListOf("subflow", "reuse", "call", "compose", "flow", "child"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val invoker = ctx.flowInvoker
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "Sub-Flows are not available in this Run")
        val raw = ctx.step.params
        val flowId = ctx.resolveText(raw["flowId"], 0)
        if (flowId.isBlank()) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "choose the Flow this Step should run")
        }
        val sendItems = if (Values.asBoolean(raw["includeInput"] ?: true)) ctx.input else emptyList()
        val limit = (Values.asDouble(raw["maxItems"]) ?: 100.0).toInt().coerceIn(1, 5_000)
        val result = invoker.invoke(flowId, sendItems.take(limit), ctx.subFlowDepth + 1)
        val field = ctx.resolveText(raw["outputField"], 0)
        val out = if (field.isBlank()) result else result.map { item ->
            FlowData(
                json = mapOf(field to item.json),
                pairedIndex = item.pairedIndex,
                error = item.error,
                binary = item.binary,
            )
        }
        ctx.log("Sub-Flow $flowId returned ${out.size} item(s)")
        return ctx.output(out)
    }
}

class ServiceConnectorNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.connector",
        title = "Service connector",
        category = StepCategory.Saas,
        subtitle = "Call any API, pick a response path and follow pages",
        iconKey = "plug",
        params = persistentListOf(
            ParamSpec("method", "Method", ParamType.Select, default = "GET",
                options = persistentListOf(
                    ParamOption("GET", "GET"), ParamOption("POST", "POST"), ParamOption("PUT", "PUT"),
                    ParamOption("PATCH", "PATCH"), ParamOption("DELETE", "DELETE"),
                )),
            ParamSpec("url", "URL", ParamType.Url, required = true, placeholder = "https://api.example.com/v1/things"),
            ParamSpec("query", "Query parameters", ParamType.KeyValue, advanced = true),
            ParamSpec("headers", "Headers", ParamType.KeyValue, advanced = true),
            ParamSpec("auth", "Authentication", ParamType.Select, default = "none",
                options = persistentListOf(
                    ParamOption("none", "None"), ParamOption("bearer", "Bearer token"),
                    ParamOption("header", "API key in a header"), ParamOption("basic", "Username & password"),
                    ParamOption("credential", "Saved credential"),
                )),
            ParamSpec("authHeaderName", "Header name", ParamType.Text, default = "Authorization",
                showWhen = mapOf("auth" to listOf("header"))),
            ParamSpec("authToken", "Token", ParamType.Secret, showWhen = mapOf("auth" to listOf("bearer", "header"))),
            ParamSpec("authUser", "Username", ParamType.Text, showWhen = mapOf("auth" to listOf("basic"))),
            ParamSpec("authPassword", "Password", ParamType.Secret, showWhen = mapOf("auth" to listOf("basic"))),
            ParamSpec("credential", "Credential", ParamType.Secret, showWhen = mapOf("auth" to listOf("credential"))),
            ParamSpec("bodyKind", "Body", ParamType.Select, default = "none",
                options = persistentListOf(
                    ParamOption("none", "No body"), ParamOption("json", "JSON"),
                    ParamOption("form", "Form fields"), ParamOption("raw", "Raw text"),
                ),
                showWhen = mapOf("method" to listOf("POST", "PUT", "PATCH", "DELETE"))),
            ParamSpec("body", "Body", ParamType.Json, showWhen = mapOf("bodyKind" to listOf("json", "raw"))),
            ParamSpec("form", "Form fields", ParamType.KeyValue, showWhen = mapOf("bodyKind" to listOf("form"))),
            ParamSpec("responsePath", "Items path", ParamType.Text,
                hint = "Dot path to the list, e.g. data.items. Leave blank for the whole response."),
            ParamSpec("nextUrlPath", "Next page path", ParamType.Text, advanced = true,
                hint = "Dot path to a next-page URL, e.g. links.next."),
            ParamSpec("maxPages", "Max pages", ParamType.Number, default = 1, advanced = true),
            ParamSpec("retryCount", "Retries", ParamType.Number, default = 0, advanced = true),
            ParamSpec("retryDelayMs", "Retry delay (ms)", ParamType.Number, default = 500, advanced = true),
            ParamSpec("timeoutSeconds", "Timeout (seconds)", ParamType.Number, default = 30, advanced = true),
            ParamSpec("ignoreHttpErrors", "Keep going on 4xx/5xx", ParamType.Boolean, default = false, advanced = true),
            ParamSpec("outputField", "Wrap each item in field", ParamType.Text, advanced = true),
        ),
        keywords = persistentListOf("connector", "api", "rest", "integration", "pagination", "json", "http"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val raw = ctx.step.params
        val method = Values.asString(raw["method"], "GET").uppercase()
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        val outputs = mutableListOf<FlowData>()
        val maxPages = (Values.asDouble(raw["maxPages"]) ?: 1.0).toInt().coerceIn(1, 20)
        val retries = (Values.asDouble(raw["retryCount"]) ?: 0.0).toInt().coerceIn(0, 5)
        val retryDelay = (Values.asDouble(raw["retryDelayMs"]) ?: 500.0).toLong().coerceIn(0L, 5_000L)
        val timeoutMs = ((Values.asDouble(raw["timeoutSeconds"]) ?: 30.0).coerceIn(1.0, 300.0) * 1000).toLong()
        val ignoreErrors = Values.asBoolean(raw["ignoreHttpErrors"] ?: false)

        sources.forEachIndexed { index, _ ->
            val url0 = ctx.resolveText(raw["url"], index)
            requireHttpUrl(ctx, url0)
            val headers = connectorHeaders(ctx, index)
            val query = Values.asMap(raw["query"]).mapValues { (_, v) -> ctx.resolveText(v, index) }
                .filterValues { it.isNotBlank() }
            val (body, kind) = connectorBody(ctx, index)
            val form = Values.asMap(raw["form"]).mapValues { (_, v) -> ctx.resolveText(v, index) }
            val responsePath = ctx.resolveText(raw["responsePath"], index)
            val nextPath = ctx.resolveText(raw["nextUrlPath"], index)
            val field = ctx.resolveText(raw["outputField"], index)

            var url = url0
            var page = 1
            while (page <= maxPages) {
                val request = HttpRequestSpec(
                    method = method,
                    url = url,
                    headers = headers,
                    query = if (page == 1) query else emptyMap(),
                    body = body,
                    bodyKind = kind,
                    formFields = form,
                    timeoutMs = timeoutMs,
                    failOnErrorStatus = false,
                )
                val response = requestWithRetry(ctx, request, retries, retryDelay, ignoreErrors)
                val payload = responsePayload(response)
                val entries = extractEntries(payload, responsePath)
                entries.forEach { entry ->
                    outputs += connectorItem(entry, field, response.status, response.durationMs, page)
                }
                val next = nextPageUrl(payload, nextPath, url)
                if (next == null || next == url) break
                url = next
                page++
            }
            ctx.log("$method ${shortUrl(url0)} → ${outputs.size} item(s) over $page page(s)")
        }
        return ctx.output(outputs)
    }
}

class PollChangesNode(private val devices: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.poll",
        title = "Only new items",
        category = StepCategory.Logic,
        subtitle = "Poll an API and emit just what changed since last Run",
        iconKey = "refresh",
        params = persistentListOf(
            ParamSpec("url", "URL", ParamType.Url, required = true, placeholder = "https://api.example.com/feed"),
            ParamSpec("method", "Method", ParamType.Select, default = "GET",
                options = persistentListOf(ParamOption("GET", "GET"), ParamOption("POST", "POST"))),
            ParamSpec("headers", "Headers", ParamType.KeyValue, advanced = true),
            ParamSpec("auth", "Authentication", ParamType.Select, default = "none",
                options = persistentListOf(
                    ParamOption("none", "None"), ParamOption("bearer", "Bearer token"),
                    ParamOption("header", "API key in a header"), ParamOption("basic", "Username & password"),
                    ParamOption("credential", "Saved credential"),
                )),
            ParamSpec("authHeaderName", "Header name", ParamType.Text, default = "Authorization",
                showWhen = mapOf("auth" to listOf("header"))),
            ParamSpec("authToken", "Token", ParamType.Secret, showWhen = mapOf("auth" to listOf("bearer", "header"))),
            ParamSpec("authUser", "Username", ParamType.Text, showWhen = mapOf("auth" to listOf("basic"))),
            ParamSpec("authPassword", "Password", ParamType.Secret, showWhen = mapOf("auth" to listOf("basic"))),
            ParamSpec("credential", "Credential", ParamType.Secret, showWhen = mapOf("auth" to listOf("credential"))),
            ParamSpec("responsePath", "Items path", ParamType.Text, hint = "e.g. data.items"),
            ParamSpec("idPath", "ID path", ParamType.Text, default = "id",
                hint = "Field that uniquely identifies an item. Blank hashes the whole item."),
            ParamSpec("stateKey", "State file", ParamType.Text, default = "poll-state.txt", advanced = true),
            ParamSpec("reset", "Forget seen items", ParamType.Boolean, default = false, advanced = true),
            ParamSpec("maxItems", "Max items per poll", ParamType.Number, default = 200, advanced = true),
            ParamSpec("maxStateEntries", "Remember at most", ParamType.Number, default = 2_000, advanced = true),
            ParamSpec("retryCount", "Retries", ParamType.Number, default = 0, advanced = true),
            ParamSpec("retryDelayMs", "Retry delay (ms)", ParamType.Number, default = 500, advanced = true),
            ParamSpec("timeoutSeconds", "Timeout (seconds)", ParamType.Number, default = 30, advanced = true),
            ParamSpec("ignoreHttpErrors", "Keep going on 4xx/5xx", ParamType.Boolean, default = false, advanced = true),
            ParamSpec("outputField", "Wrap each item in field", ParamType.Text, advanced = true),
        ),
        keywords = persistentListOf("poll", "dedupe", "change", "new", "state", "feed", "trigger"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val raw = ctx.step.params
        val stateKey = ctx.resolveText(raw["stateKey"], 0, "poll-state.txt").ifBlank { "poll-state.txt" }
        if (Values.asBoolean(raw["reset"] ?: false)) devices.writeFile(stateKey, "")
        val seen = (devices.readFile(stateKey).orEmpty())
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        val seenSet = seen.toHashSet()

        val url = ctx.resolveText(raw["url"], 0)
        requireHttpUrl(ctx, url)
        val request = HttpRequestSpec(
            method = Values.asString(raw["method"], "GET").uppercase(),
            url = url,
            headers = connectorHeaders(ctx, 0),
            timeoutMs = ((Values.asDouble(raw["timeoutSeconds"]) ?: 30.0).coerceIn(1.0, 300.0) * 1000).toLong(),
            failOnErrorStatus = false,
        )
        val retries = (Values.asDouble(raw["retryCount"]) ?: 0.0).toInt().coerceIn(0, 5)
        val retryDelay = (Values.asDouble(raw["retryDelayMs"]) ?: 500.0).toLong().coerceIn(0L, 5_000L)
        val ignoreErrors = Values.asBoolean(raw["ignoreHttpErrors"] ?: false)
        val response = requestWithRetry(ctx, request, retries, retryDelay, ignoreErrors)
        val payload = responsePayload(response)
        val responsePath = ctx.resolveText(raw["responsePath"], 0)
        val idPath = ctx.resolveText(raw["idPath"], 0)
        val field = ctx.resolveText(raw["outputField"], 0)
        val maxItems = (Values.asDouble(raw["maxItems"]) ?: 200.0).toInt().coerceIn(1, 5_000)
        val maxState = (Values.asDouble(raw["maxStateEntries"]) ?: 2_000.0).toInt().coerceIn(10, 20_000)

        val out = mutableListOf<FlowData>()
        val newFingerprints = mutableListOf<String>()
        extractEntries(payload, responsePath).take(maxItems).forEach { entry ->
            val id = if (idPath.isBlank()) "" else Values.asString(Values.deepGet(entry, idPath))
            val fingerprint = id.ifBlank { CryptoMath.hashHex("SHA-256", Values.asString(entry)) }
            if (seenSet.add(fingerprint)) {
                newFingerprints += fingerprint
                out += connectorItem(entry, field, response.status, response.durationMs, 1)
            }
        }
        val updated = (seen + newFingerprints).takeLast(maxState)
        devices.writeFile(stateKey, updated.joinToString("\n"))
        ctx.log("Poll ${shortUrl(url)} → ${out.size} new item(s), ${updated.size} remembered")
        return ctx.output(out)
    }
}

private fun requireHttpUrl(ctx: StepContext, url: String) {
    if (url.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add a URL")
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        throw StepExecutionException(ctx.step.id, ctx.step.name, "the URL must start with http:// or https://")
    }
}

private suspend fun connectorHeaders(ctx: StepContext, index: Int): MutableMap<String, String> {
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

private fun connectorBody(ctx: StepContext, index: Int): Pair<Any?, HttpRequestSpec.BodyKind> {
    val raw = ctx.step.params
    return when (Values.asString(raw["bodyKind"], "none")) {
        "json" -> ctx.resolve(raw["body"], index) to HttpRequestSpec.BodyKind.Json
        "raw" -> ctx.resolveText(raw["body"], index) to HttpRequestSpec.BodyKind.Raw
        "form" -> null to HttpRequestSpec.BodyKind.Form
        else -> null to HttpRequestSpec.BodyKind.None
    }
}

private suspend fun requestWithRetry(
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

private fun responsePayload(response: HttpResponseSpec): Any? =
    response.body ?: MiniJson.parse(response.bodyText) ?: response.bodyText

private fun extractEntries(payload: Any?, path: String): List<Any?> {
    val selected = if (path.isBlank()) payload else Values.deepGet(payload, path)
    return when (selected) {
        null -> emptyList()
        is List<*> -> selected
        else -> listOf(selected)
    }
}

private fun nextPageUrl(payload: Any?, path: String, currentUrl: String): String? {
    if (path.isBlank()) return null
    val raw = Values.asString(Values.deepGet(payload, path))
    if (raw.isBlank() || raw == currentUrl) return null
    return runCatching { URI(currentUrl).resolve(raw).toString() }.getOrDefault(raw)
}

private fun connectorItem(
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

private fun shortUrl(url: String): String = if (url.length <= 90) url else url.take(87) + "..."
private fun snippet(text: String): String =
    if (text.isBlank()) "" else "Response: " + text.take(300).replace('\n', ' ')
