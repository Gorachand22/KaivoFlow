package com.kaivoflow.core.nodes.files.document

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.BinaryDataRef
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.BinarySupport
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.NoopDeviceGateway
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** Best-effort text extraction from an attachment's PDF text layer. */
class PdfNode(private val gateway: DeviceGateway = NoopDeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.pdf",
        title = "PDF text",
        category = StepCategory.Transform,
        subtitle = "Read the text layer, page count and file size from a PDF",
        iconKey = "text",
        params = persistentListOf(
            textParam("binaryField", "Attachment field", default = "data"),
            textParam("outputField", "Save text in", default = "text"),
        ),
        keywords = persistentListOf("pdf", "document", "extract", "text", "pages", "attachment", "read"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val binaryField = Values.asString(params["binaryField"], "data").ifBlank { "data" }
        val field = ctx.outputName("text")
        val out = ctx.input.mapIndexed { _, item ->
            val ref: BinaryDataRef = item.binaryRef(binaryField) ?: item.binary?.values?.firstOrNull()
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "this item has no PDF attachment")
            val base64 = gateway.loadBinary(ref.uri)
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the PDF could not be opened on this device")
            val bytes = BinarySupport.decode(base64)
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the PDF is unreadable or too large")
            val text = PdfLite.extractText(bytes)
            item.copy(json = item.json + mapOf(field to text, "pdf" to mapOf(
                "fileName" to ref.fileName,
                "byteSize" to ref.sizeBytes.toDouble(),
                "pageCount" to PdfLite.pageCount(bytes).toDouble(),
                "characters" to text.length.toDouble(),
            )))
        }
        return ctx.output(out)
    }
}
