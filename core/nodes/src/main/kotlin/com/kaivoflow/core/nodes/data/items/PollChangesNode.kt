package com.kaivoflow.core.nodes.data.items

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
import com.kaivoflow.core.nodes.CryptoMath
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.connectorHeaders
import com.kaivoflow.core.nodes.connectorItem
import com.kaivoflow.core.nodes.extractEntries
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.requestWithRetry
import com.kaivoflow.core.nodes.requireHttpUrl
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.responsePayload
import com.kaivoflow.core.nodes.shortUrl
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay

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
