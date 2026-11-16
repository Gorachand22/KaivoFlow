package com.kaivoflow.core.nodes.net.web

import com.kaivoflow.core.engine.HttpRequestSpec
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
import com.kaivoflow.core.nodes.bearerToken
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.shortUrl
import kotlinx.collections.immutable.persistentListOf

/**
 * The universal REST Step. Everything else that talks to a web service is a thin preset on top
 * of this, so a user can always "drop to HTTP" when a preset lacks an option.
 */
class HttpNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.http",
        title = "HTTP request",
        category = StepCategory.Saas,
        subtitle = "Call any web API",
        iconKey = "globe",
        params = persistentListOf(
            ParamSpec(
                key = "method",
                label = "Method",
                type = ParamType.Select,
                default = "GET",
                options = persistentListOf(
                    ParamOption("GET", "GET"), ParamOption("POST", "POST"), ParamOption("PUT", "PUT"),
                    ParamOption("PATCH", "PATCH"), ParamOption("DELETE", "DELETE"), ParamOption("HEAD", "HEAD"),
                ),
            ),
            ParamSpec("url", "URL", ParamType.Url, required = true, placeholder = "https://api.example.com/v1/things"),
            ParamSpec("query", "Query parameters", ParamType.KeyValue, advanced = true),
            ParamSpec("headers", "Headers", ParamType.KeyValue, advanced = true),
            ParamSpec(
                key = "auth",
                label = "Authentication",
                type = ParamType.Select,
                default = "none",
                options = persistentListOf(
                    ParamOption("none", "None"),
                    ParamOption("bearer", "Bearer token"),
                    ParamOption("header", "API key in a header"),
                    ParamOption("basic", "Username & password"),
                    ParamOption("credential", "Saved credential"),
                ),
            ),
            ParamSpec("authHeaderName", "Header name", ParamType.Text, default = "Authorization", showWhen = mapOf("auth" to listOf("header"))),
            ParamSpec("authToken", "Token", ParamType.Secret, showWhen = mapOf("auth" to listOf("bearer", "header"))),
            ParamSpec("authUser", "Username", ParamType.Text, showWhen = mapOf("auth" to listOf("basic"))),
            ParamSpec("authPassword", "Password", ParamType.Secret, showWhen = mapOf("auth" to listOf("basic"))),
            ParamSpec("credential", "Credential", ParamType.Secret, showWhen = mapOf("auth" to listOf("credential"))),
            ParamSpec(
                key = "bodyKind",
                label = "Body",
                type = ParamType.Select,
                default = "none",
                options = persistentListOf(
                    ParamOption("none", "No body"),
                    ParamOption("json", "JSON"),
                    ParamOption("form", "Form fields"),
                    ParamOption("raw", "Raw text"),
                ),
                showWhen = mapOf("method" to listOf("POST", "PUT", "PATCH", "DELETE")),
            ),
            ParamSpec("body", "Body", ParamType.Json, showWhen = mapOf("bodyKind" to listOf("json", "raw"))),
            ParamSpec("form", "Form fields", ParamType.KeyValue, showWhen = mapOf("bodyKind" to listOf("form"))),
            ParamSpec("timeoutSeconds", "Timeout (seconds)", ParamType.Number, default = 30, advanced = true),
            ParamSpec("ignoreHttpErrors", "Keep going on 4xx/5xx", ParamType.Boolean, default = false, advanced = true),
            ParamSpec("splitItems", "One item per array entry", ParamType.Boolean, default = true, advanced = true),
            ParamSpec("outputField", "Wrap response in field", ParamType.Text, advanced = true),
        ),
        keywords = persistentListOf("http", "rest", "api", "request", "get", "post", "fetch", "webhook", "network call"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {

        // One request per input item, so a Flow can loop naturally over a list of URLs.
        val outputs = mutableListOf<FlowData>()
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        sources.forEachIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val itemUrl = Values.asString(row["url"])
            if (itemUrl.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add a URL")
            if (!itemUrl.startsWith("http://") && !itemUrl.startsWith("https://")) {
                throw StepExecutionException(ctx.step.id, ctx.step.name, "the URL must start with http:// or https://")
            }
            val headers = LinkedHashMap<String, String>()
            Values.asMap(row["headers"]).forEach { (k, v) -> if (Values.asString(v).isNotBlank()) headers[k] = Values.asString(v) }

            when (Values.asString(row["auth"], "none")) {
                "bearer" -> headers["Authorization"] = "Bearer ${Values.asString(row["authToken"])}"
                "header" -> headers[Values.asString(row["authHeaderName"], "Authorization")] = Values.asString(row["authToken"])
                "basic" -> {
                    val raw = "${Values.asString(row["authUser"])}:${Values.asString(row["authPassword"])}"
                    headers["Authorization"] = "Basic " + java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
                }
                "credential" -> {
                    val credential = ctx.credential()
                    val token = credential.bearerToken()
                    if (token.isNotBlank()) {
                        headers["Authorization"] = if (token.startsWith("Bearer ")) token else "Bearer $token"
                    }
                    credential["header"]?.let { name -> credential["value"]?.let { headers[name] = it } }
                    credential["username"]?.let { user ->
                        val raw = "$user:${credential["password"].orEmpty()}"
                        headers["Authorization"] = "Basic " + java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
                    }
                }
            }

            val bodyKind = Values.asString(row["bodyKind"], "none")
            val (body, kind) = when (bodyKind) {
                "json" -> row["body"] to HttpRequestSpec.BodyKind.Json
                "raw" -> Values.asString(row["body"]) to HttpRequestSpec.BodyKind.Raw
                "form" -> Values.asMap(row["form"]) to HttpRequestSpec.BodyKind.Form
                else -> null to HttpRequestSpec.BodyKind.None
            }
            val itemHeaders: Map<String, String> = headers
            val bodyValue = body

            val request = HttpRequestSpec(
                method = Values.asString(row["method"], "GET").uppercase(),
                url = itemUrl,
                headers = itemHeaders,
                query = Values.asMap(row["query"]).mapValues { (_, v) -> Values.asString(v) }
                    .filterValues { it.isNotBlank() },
                body = bodyValue,
                bodyKind = kind,
                timeoutMs = ((Values.asDouble(row["timeoutSeconds"]) ?: 30.0).coerceIn(1.0, 300.0) * 1000).toLong(),
                failOnErrorStatus = !Values.asBoolean(row["ignoreHttpErrors"] ?: false),
                formFields = Values.asMap(row["form"]).mapValues { (_, v) -> Values.asString(v) },
            )

            val response = ctx.http.request(request)
            if (!response.isSuccess && request.failOnErrorStatus) {
                throw StepExecutionException(
                    ctx.step.id, ctx.step.name,
                    "${request.method} ${shortUrl(request.url)} returned HTTP ${response.status}. ${snippet(response.bodyText)}"
                )
            }

            val splitItems = Values.asBoolean(row["splitItems"] ?: true)
            val outputField = Values.asString(row["outputField"])
            val payload = response.body ?: response.bodyText
            val entries = if (splitItems && payload is List<*>) payload else listOf(payload)

            entries.forEach { entry ->
                val json = LinkedHashMap<String, Any?>()
                json["statusCode"] = response.status.toDouble()
                json["durationMs"] = response.durationMs.toDouble()
                if (outputField.isNotBlank()) {
                    json[outputField] = entry
                } else {
                    when (entry) {
                        is Map<*, *> -> json.putAll(Values.asMap(entry))
                        else -> json["data"] = entry
                    }
                }
                response.headers["content-type"]?.let { json["contentType"] = it }
                outputs += FlowData(json)
            }
            ctx.log("${request.method} ${shortUrl(request.url)} → ${response.status} in ${response.durationMs}ms")
        }
        return ctx.output(outputs)
    }

    private fun shortUrl(url: String): String = if (url.length <= 90) url else url.take(87) + "..."

    private fun snippet(text: String): String =
        if (text.isBlank()) "" else "Response: " + text.take(300).replace('\n', ' ')
}
