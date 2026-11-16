package com.kaivoflow.core.nodes.net.web

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.NoopDeviceGateway
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** A narrow HTTP Step that sends an attachment as multipart/form-data. */
class BinaryUploadNode(private val gateway: DeviceGateway = NoopDeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.binary_upload",
        title = "Upload a file",
        category = StepCategory.Transform,
        subtitle = "Send one attachment as multipart/form-data",
        iconKey = "upload",
        params = persistentListOf(
            textParam("url", "Endpoint", default = "{{ \$json.url }}", required = true),
            ParamSpec(
                key = "method",
                label = "Method",
                type = ParamType.Select,
                default = "POST",
                options = selectOptions("POST" to "POST", "PUT" to "PUT", "PATCH" to "PATCH"),
            ),
            textParam("binaryField", "Attachment field", default = "data"),
            textParam("formField", "Form field name", default = "file"),
            ParamSpec("extraFields", "Extra form fields", ParamType.KeyValue),
            ParamSpec("headers", "Headers", ParamType.KeyValue),
            ParamSpec("timeoutSeconds", "Timeout (seconds)", ParamType.Number, default = 30),
            textParam("outputField", "Save response in", default = "response"),
        ),
        keywords = persistentListOf("upload", "multipart", "http", "file", "attachment", "post"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val field = ctx.outputName("response")
        val out = ctx.input.mapIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val url = Values.asString(row["url"])
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw StepExecutionException(ctx.step.id, ctx.step.name, "the endpoint must start with http:// or https://")
            }
            val binaryKey = Values.asString(row["binaryField"], "data")
            val ref = item.binaryRef(binaryKey) ?: item.binary?.values?.firstOrNull()
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "this item has no attachment to upload")
            val headers = Values.asMap(row["headers"]).entries.associate { (key, raw) ->
                key to Values.asString(raw)
            }
            val formFields = Values.asMap(row["extraFields"]).entries.associate { (key, raw) ->
                key to Values.asString(raw)
            }
            val base64 = gateway.loadBinary(ref.uri)
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the attachment could not be opened on this device")
            val timeout = (Values.asDouble(row["timeoutSeconds"]) ?: 30.0)
                .coerceIn(1.0, 120.0).toLong() * 1000L
            val response = ctx.http.request(
                HttpRequestSpec(
                    method = Values.asString(row["method"], "POST").uppercase(),
                    url = url,
                    headers = headers,
                    bodyKind = HttpRequestSpec.BodyKind.Multipart,
                    binaryUpload = com.kaivoflow.core.engine.BinaryUploadSpec(
                        field = Values.asString(row["formField"], "file"),
                        fileName = ref.fileName,
                        mimeType = ref.mimeType,
                        base64 = base64,
                        extraFields = formFields,
                    ),
                    timeoutMs = timeout,
                )
            )
            if (!response.isSuccess) {
                throw StepExecutionException(ctx.step.id, ctx.step.name, "the server answered ${response.status}")
            }
            item.copy(json = item.json + (field to mapOf(
                "status" to response.status.toDouble(),
                "body" to (response.body ?: response.bodyText),
                "fileName" to ref.fileName,
            )))
        }
        return ctx.output(out)
    }
}
