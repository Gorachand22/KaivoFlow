package com.kaivoflow.core.nodes.net.web

import com.kaivoflow.core.engine.HttpRequestSpec
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
import com.kaivoflow.core.nodes.MiniJson
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolve
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.secret
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

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
