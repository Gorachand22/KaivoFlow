package com.kaivoflow.core.nodes.net.web

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.connectorHeaders
import com.kaivoflow.core.nodes.connectorItem
import com.kaivoflow.core.nodes.extractEntries
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.requestWithRetry
import com.kaivoflow.core.nodes.requireHttpUrl
import com.kaivoflow.core.nodes.resolve
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.responsePayload
import com.kaivoflow.core.nodes.shortUrl
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import java.net.URI

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

private fun connectorBody(ctx: StepContext, index: Int): Pair<Any?, HttpRequestSpec.BodyKind> {
    val raw = ctx.step.params
    return when (Values.asString(raw["bodyKind"], "none")) {
        "json" -> ctx.resolve(raw["body"], index) to HttpRequestSpec.BodyKind.Json
        "raw" -> ctx.resolveText(raw["body"], index) to HttpRequestSpec.BodyKind.Raw
        "form" -> null to HttpRequestSpec.BodyKind.Form
        else -> null to HttpRequestSpec.BodyKind.None
    }
}

private fun nextPageUrl(payload: Any?, path: String, currentUrl: String): String? {
    if (path.isBlank()) return null
    val raw = Values.asString(Values.deepGet(payload, path))
    if (raw.isBlank() || raw == currentUrl) return null
    return runCatching { URI(currentUrl).resolve(raw).toString() }.getOrDefault(raw)
}

internal fun snippet(text: String): String =
    if (text.isBlank()) "" else "Response: " + text.take(300).replace('\n', ' ')
