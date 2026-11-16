package com.kaivoflow.core.nodes.net.web

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
import com.kaivoflow.core.nodes.BinarySupport
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.NoopDeviceGateway
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolve
import com.kaivoflow.core.nodes.secret
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

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
