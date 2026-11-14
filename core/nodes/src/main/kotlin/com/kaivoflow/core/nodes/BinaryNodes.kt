package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.ParamResolver
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
import kotlinx.collections.immutable.persistentListOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Binary-aware Steps. All conversions run on the JVM with bounded buffers; every output carries a
 * [BinaryDataRef] so the UI can show a real attachment card instead of a wall of encoded text.
 */
class BinaryToolsNode(private val gateway: DeviceGateway = NoopDeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.binary",
        title = "Binary tools",
        category = StepCategory.Transform,
        subtitle = "Encode, decode, hash, zip and unzip attachments",
        iconKey = "archive",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "from_base64",
                options = selectOptions(
                    "from_base64" to "Base64 → attachment", "to_base64" to "Attachment → Base64 text",
                    "from_text" to "Text → attachment", "to_text" to "Attachment → text",
                    "info" to "Describe an attachment", "gzip" to "Compress (.gz)",
                    "gunzip" to "Decompress (.gz)", "zip" to "Bundle attachments (.zip)",
                    "unzip" to "Open a ZIP bundle", "hash" to "Fingerprint (SHA-256)",
                ),
            ),
            textParam("source", "Source field", default = "{{ \$json.data }}",
                hint = "A Base64 string or plain text to turn into an attachment."),
            textParam("fileName", "File name", default = "attachment.bin"),
            textParam("mimeType", "Content type", default = "application/octet-stream"),
            textParam("binaryField", "Binary field", default = "data",
                hint = "The field name of an incoming attachment."),
            textParam("outputField", "Save text in", default = "data",
                showWhen = mapOf("action" to listOf("to_base64", "to_text", "hash", "info"))),
            textParam("outputBinaryField", "Save attachment in", default = "data"),
        ),
        keywords = persistentListOf("binary", "file", "base64", "zip", "gzip", "compress", "hash", "attachment"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val action = Values.asString(params["action"], "from_base64")
        val binaryField = Values.asString(params["binaryField"], "data").ifBlank { "data" }
        val outputBinary = Values.asString(params["outputBinaryField"], "data").ifBlank { "data" }
        val textField = ctx.outputName("data")
        val fileName = Values.asString(params["fileName"], "attachment.bin")
        val mimeType = Values.asString(params["mimeType"], "application/octet-stream")
        val out = ctx.input.mapIndexed { index, item ->
            when (action) {
                "from_base64" -> {
                    val encoded = ctx.resolveText(params["source"], index)
                    val bytes = Base64.getMimeDecoder().let { decoder ->
                        runCatching { decoder.decode(encoded) }.getOrElse {
                            throw StepExecutionException(ctx.step.id, ctx.step.name, "the source is not valid Base64")
                        }
                    }
                    attach(item, outputBinary, ctx, bytes, fileName, mimeType)
                }
                "from_text" -> attach(
                    item, outputBinary, ctx,
                    ctx.resolveText(params["source"], index).toByteArray(Charsets.UTF_8),
                    fileName, "text/plain; charset=utf-8",
                )
                "to_base64", "to_text", "hash", "info" -> {
                    val ref = findBinary(item, binaryField, ctx)
                        ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "there is no attachment in \"$binaryField\"")
                    val bytes = bytesOf(ref, ctx)
                    val value: Any? = when (action) {
                        "to_base64" -> BinarySupport.encode(bytes)
                        "to_text" -> String(bytes, Charsets.UTF_8)
                        "hash" -> CryptoMath.sha256Hex(bytes)
                        else -> mapOf(
                            "fileName" to ref.fileName,
                            "mimeType" to ref.mimeType,
                            "byteSize" to ref.sizeBytes.toDouble(),
                        )
                    }
                    item.copy(json = item.json + (textField to value))
                }
                "gzip", "zip" -> {
                    val refs: List<BinaryDataRef> = if (action == "zip") {
                        val present = item.binary?.values.orEmpty().toList()
                        if (present.isEmpty()) {
                            throw StepExecutionException(ctx.step.id, ctx.step.name, "this item has no attachments to bundle")
                        }
                        present
                    } else {
                        listOf(findBinary(item, binaryField, ctx)
                            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "there is no attachment in \"$binaryField\""))
                    }
                    val bytes = if (action == "gzip") gzip(bytesOf(refs.first(), ctx)) else zip(refs, ctx)
                    val name = if (action == "zip") "bundle.zip" else fileName + ".gz"
                    val type = if (action == "zip") "application/zip" else "application/gzip"
                    attach(item, outputBinary, ctx, bytes, name, type)
                }
                "gunzip", "unzip" -> {
                    val ref = findBinary(item, binaryField, ctx)
                        ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "there is no attachment in \"$binaryField\"")
                    val bytes = bytesOf(ref, ctx)
                    if (action == "gunzip") {
                        val plain = runCatching { gunzip(bytes) }.getOrElse {
                            throw StepExecutionException(ctx.step.id, ctx.step.name, "this is not a valid gzip attachment")
                        }
                        attach(item, outputBinary, ctx, plain, fileName.removeSuffix(".gz"), mimeType)
                    } else {
                        val extracted = runCatching { unzip(bytes) }.getOrElse {
                            throw StepExecutionException(ctx.step.id, ctx.step.name, "this is not a valid ZIP attachment")
                        }
                        if (extracted.isEmpty()) {
                            throw StepExecutionException(ctx.step.id, ctx.step.name, "the ZIP bundle is empty")
                        }
                        val binary = LinkedHashMap(item.binary.orEmpty())
                        extracted.take(30).forEach { (name, content) ->
                            val stored = store(ctx, content, name, BinarySupport.mimeTypeFor(name))
                            binary[safeKey(name)] = stored
                        }
                        item.copy(
                            json = item.json + ("files" to extracted.map { it.first }),
                            binary = binary,
                        )
                    }
                }
                else -> item
            }
        }
        return ctx.output(out)
    }

    private fun findBinary(item: FlowData, field: String, ctx: StepContext): BinaryDataRef? =
        item.binaryRef(field) ?: item.binary?.values?.firstOrNull()?.also {
            if (!item.binary.orEmpty().containsKey(field)) ctx.log("Using the first attachment on this item.")
        }

    private suspend fun bytesOf(ref: BinaryDataRef, ctx: StepContext): ByteArray {
        val base64 = gateway.loadBinary(ref.uri)
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the attachment could not be opened on this device")
        return BinarySupport.decode(base64)
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the attachment is unreadable or too large")
    }

    private suspend fun store(ctx: StepContext, bytes: ByteArray, name: String, mimeType: String): BinaryDataRef {
        val map = gateway.storeBinary(BinarySupport.safeFileName(name), mimeType, BinarySupport.encode(bytes))
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the attachment could not be saved")
        return BinarySupport.ref(map)
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the attachment reference is incomplete")
    }

    private suspend fun attach(
        item: FlowData,
        field: String,
        ctx: StepContext,
        bytes: ByteArray,
        name: String,
        mimeType: String,
    ): FlowData {
        if (bytes.size.toLong() > BinarySupport.MAX_BYTES) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "the attachment is larger than the 25 MB limit")
        }
        val ref = store(ctx, bytes, name, mimeType)
        return item.withBinary(field, ref)
    }

    private fun safeKey(name: String): String {
        val base = name.substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "file" }
        return if (base.first().isDigit()) "file_$base" else base
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { readBounded(it) }

    private suspend fun zip(refs: Collection<BinaryDataRef>, ctx: StepContext): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            refs.take(50).forEach { ref ->
                val name = BinarySupport.safeFileName(ref.fileName).take(120)
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytesOf(ref, ctx))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && result.size < 30) {
                if (!entry.isDirectory) {
                    val name = entry.name.replace(Regex("""^\.\./|^/"""), "").take(120)
                    result += name to readBounded(zip)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size().toLong() + read > BinarySupport.MAX_BYTES) {
                throw IllegalStateException("decompressed data exceeds the 25 MB limit")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

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
        val resolver = ParamResolver(ctx.evaluator)
        val out = ctx.input.mapIndexed { index, item ->
            val scope = ctx.scope(index)
            val url = Values.asString(resolver.resolveValue(params["url"], scope))
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw StepExecutionException(ctx.step.id, ctx.step.name, "the endpoint must start with http:// or https://")
            }
            val binaryKey = Values.asString(resolver.resolveValue(params["binaryField"], scope), "data")
            val ref = item.binaryRef(binaryKey) ?: item.binary?.values?.firstOrNull()
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "this item has no attachment to upload")
            val headers = Values.asMap(params["headers"]).entries.associate { (key, raw) ->
                key to Values.asString(resolver.resolveValue(raw, scope))
            }
            val formFields = Values.asMap(params["extraFields"]).entries.associate { (key, raw) ->
                key to Values.asString(resolver.resolveValue(raw, scope))
            }
            val base64 = gateway.loadBinary(ref.uri)
                ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the attachment could not be opened on this device")
            val timeout = (Values.asDouble(params["timeoutSeconds"]) ?: 30.0)
                .coerceIn(1.0, 120.0).toLong() * 1000L
            val response = ctx.http.request(
                HttpRequestSpec(
                    method = Values.asString(params["method"], "POST").uppercase(),
                    url = url,
                    headers = headers,
                    bodyKind = HttpRequestSpec.BodyKind.Multipart,
                    binaryUpload = com.kaivoflow.core.engine.BinaryUploadSpec(
                        field = Values.asString(resolver.resolveValue(params["formField"], scope), "file"),
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
