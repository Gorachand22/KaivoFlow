package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.ParamResolver
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
        keywords = persistentListOf("http", "rest", "api", "request", "get", "post", "fetch", "webhook", "n8n http request"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val resolver = ParamResolver(ctx.evaluator)
        val url = Values.asString(params["url"])
        if (url.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add a URL")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "the URL must start with http:// or https://")
        }

        val headers = LinkedHashMap<String, String>()
        Values.asMap(params["headers"]).forEach { (k, v) -> if (Values.asString(v).isNotBlank()) headers[k] = Values.asString(v) }

        when (Values.asString(params["auth"], "none")) {
            "bearer" -> headers["Authorization"] = "Bearer ${Values.asString(params["authToken"])}"
            "header" -> headers[Values.asString(params["authHeaderName"], "Authorization")] = Values.asString(params["authToken"])
            "basic" -> {
                val raw = "${Values.asString(params["authUser"])}:${Values.asString(params["authPassword"])}"
                headers["Authorization"] = "Basic " + java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
            }
            "credential" -> {
                val credential = ctx.credential()
                credential["apiKey"]?.let { headers["Authorization"] = if (it.startsWith("Bearer ")) it else "Bearer $it" }
                credential["header"]?.let { name -> credential["value"]?.let { headers[name] = it } }
                credential["username"]?.let { user ->
                    val raw = "$user:${credential["password"].orEmpty()}"
                    headers["Authorization"] = "Basic " + java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
                }
            }
        }

        val bodyKind = Values.asString(params["bodyKind"], "none")
        val (body, kind) = when (bodyKind) {
            "json" -> params["body"] to HttpRequestSpec.BodyKind.Json
            "raw" -> Values.asString(params["body"]) to HttpRequestSpec.BodyKind.Raw
            "form" -> Values.asMap(params["form"]) to HttpRequestSpec.BodyKind.Form
            else -> null to HttpRequestSpec.BodyKind.None
        }

        // One request per input item, so a Flow can loop naturally over a list of URLs.
        val outputs = mutableListOf<FlowData>()
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        sources.forEachIndexed { index, item ->
            val scope = ctx.scope(index)
            val itemUrl = Values.asString(resolver.resolveValue(params["url"], scope))
            val itemHeaders = headers.mapValues { (_, v) -> Values.asString(resolver.resolveValue(v, scope)) }
            val bodyValue = ParamResolver(ctx.evaluator).resolveValue(body, scope)

            val request = HttpRequestSpec(
                method = Values.asString(params["method"], "GET").uppercase(),
                url = itemUrl,
                headers = itemHeaders,
                query = Values.asMap(params["query"]).mapValues { (_, v) -> Values.asString(resolver.resolveValue(v, scope)) }
                    .filterValues { it.isNotBlank() },
                body = bodyValue,
                bodyKind = kind,
                timeoutMs = ((Values.asDouble(params["timeoutSeconds"]) ?: 30.0).coerceIn(1.0, 300.0) * 1000).toLong(),
                failOnErrorStatus = !Values.asBoolean(params["ignoreHttpErrors"] ?: false),
                formFields = Values.asMap(params["form"]).mapValues { (_, v) -> Values.asString(resolver.resolveValue(v, scope)) },
            )

            val response = ctx.http.request(request)
            if (!response.isSuccess && request.failOnErrorStatus) {
                throw StepExecutionException(
                    ctx.step.id, ctx.step.name,
                    "${request.method} ${shortUrl(request.url)} returned HTTP ${response.status}. ${snippet(response.bodyText)}"
                )
            }

            val splitItems = Values.asBoolean(params["splitItems"] ?: true)
            val outputField = Values.asString(params["outputField"])
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
