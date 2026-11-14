package com.kaivoflow.core.nodes

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
import java.util.zip.InflaterInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signed tokens and one-time codes, implemented with the JDK only. Secrets stay in the encrypted
 * vault and are never written to Run logs.
 */
class TokenNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.token",
        title = "Token tools",
        category = StepCategory.Transform,
        subtitle = "Read, verify or sign compact JSON web tokens",
        iconKey = "token",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "decode",
                options = selectOptions(
                    "decode" to "Read a token (without checking it)",
                    "verify" to "Check a signed token",
                    "sign" to "Sign a new token",
                ),
            ),
            textParam("token", "Token", default = "{{ \$json.token }}",
                showWhen = mapOf("action" to listOf("decode", "verify"))),
            ParamSpec(
                key = "algorithm",
                label = "Algorithm",
                type = ParamType.Select,
                default = "HS256",
                options = selectOptions("HS256" to "HS256", "HS384" to "HS384", "HS512" to "HS512"),
                showWhen = mapOf("action" to listOf("verify", "sign")),
            ),
            ParamSpec("secret", "Signing secret", ParamType.Secret,
                showWhen = mapOf("action" to listOf("verify", "sign"))),
            ParamSpec("payload", "Claims (JSON)", ParamType.Json,
                default = mapOf("sub" to "kaivoflow"), showWhen = mapOf("action" to listOf("sign"))),
            ParamSpec("ttlSeconds", "Valid for (seconds)", ParamType.Number, default = 3600,
                showWhen = mapOf("action" to listOf("sign"))),
            textParam("outputField", "Save result in", default = "token"),
        ),
        keywords = persistentListOf("jwt", "token", "sign", "verify", "claims", "hmac", "auth", "bearer"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val action = Values.asString(params["action"], "decode")
        val algorithm = Values.asString(params["algorithm"], "HS256")
        val field = ctx.outputName("token")
        val secret = ctx.secret("secret")
        val out = ctx.input.mapIndexed { index, item ->
            when (action) {
                "decode" -> {
                    val token = ctx.resolveText(params["token"], index)
                    val parts = token.split('.')
                    if (parts.size != 3) throw bad(ctx, "a token has three parts separated by dots")
                    val header = jsonPart(ctx, parts[0], "header")
                    val payload = jsonPart(ctx, parts[1], "payload")
                    item.copy(json = item.json + (field to mapOf(
                        "header" to header,
                        "payload" to payload,
                        "signature" to parts[2],
                        "verified" to false,
                    )))
                }
                "verify" -> {
                    val token = ctx.resolveText(params["token"], index)
                    if (secret.isBlank()) throw bad(ctx, "add the signing secret first")
                    val parts = token.split('.')
                    if (parts.size != 3) throw bad(ctx, "a token has three parts separated by dots")
                    val header = jsonPart(ctx, parts[0], "header")
                    val payload = jsonPart(ctx, parts[1], "payload")
                    val declared = Values.asString(Values.deepGet(header, "alg"), algorithm)
                    if (!declared.equals(algorithm, true)) throw bad(ctx, "the token was signed with $declared, not $algorithm")
                    val expected = sign(algorithm, "${parts[0]}.${parts[1]}", secret)
                    if (!CryptoMath.constantTimeEquals(expected, parts[2])) throw bad(ctx, "the signature does not match")
                    val now = ctx.clock() / 1000.0
                    val expires = Values.asDouble(Values.deepGet(payload, "exp"))
                    val notBefore = Values.asDouble(Values.deepGet(payload, "nbf"))
                    if (expires != null && now >= expires) throw bad(ctx, "the token has expired")
                    if (notBefore != null && now < notBefore) throw bad(ctx, "the token is not valid yet")
                    item.copy(json = item.json + (field to mapOf(
                        "header" to header, "payload" to payload, "verified" to true, "algorithm" to algorithm,
                    )))
                }
                "sign" -> {
                    if (secret.isBlank()) throw bad(ctx, "add the signing secret first")
                    val claims = LinkedHashMap(Values.asMap(ctx.resolve(params["payload"], index)))
                    val ttl = (Values.asDouble(params["ttlSeconds"]) ?: 3600.0).toLong().coerceIn(1, 31_536_000)
                    val issued = ctx.clock() / 1000L
                    claims["iat"] = issued.toDouble()
                    claims["exp"] = (issued + ttl).toDouble()
                    val header = mapOf("alg" to algorithm, "typ" to "JWT")
                    val h = CryptoMath.base64Url(Values.stringify(header).toByteArray(Charsets.UTF_8))
                    val p = CryptoMath.base64Url(Values.stringify(claims).toByteArray(Charsets.UTF_8))
                    val signature = sign(algorithm, "$h.$p", secret)
                    item.copy(json = item.json + (field to "$h.$p.$signature"))
                }
                else -> item
            }
        }
        return ctx.output(out)
    }

    private fun sign(algorithm: String, data: String, secret: String): String =
        CryptoMath.base64Url(CryptoMath.hmac(macName(algorithm), secret.toByteArray(Charsets.UTF_8), data.toByteArray(Charsets.UTF_8)))

    private fun macName(algorithm: String): String = when (algorithm.uppercase()) {
        "HS384" -> "HmacSHA384"
        "HS512" -> "HmacSHA512"
        else -> "HmacSHA256"
    }

    private fun jsonPart(ctx: StepContext, part: String, name: String): Map<String, Any?> {
        val bytes = CryptoMath.base64UrlDecode(part) ?: throw bad(ctx, "the $name is not encoded correctly")
        val parsed = MiniJson.parse(String(bytes, Charsets.UTF_8))
        return Values.asMap(parsed).takeIf { it.isNotEmpty() } ?: throw bad(ctx, "the $name is not a JSON object")
    }

    private fun bad(ctx: StepContext, detail: String) =
        StepExecutionException(ctx.step.id, ctx.step.name, detail)
}

/** Time-based one-time passwords from a base32 secret (RFC 6238 shape, HMAC only). */
class TotpNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.totp",
        title = "One-time code",
        category = StepCategory.Transform,
        subtitle = "Generate a time-based six-digit code",
        iconKey = "pin",
        params = persistentListOf(
            ParamSpec("secret", "Base32 secret", ParamType.Secret, required = true),
            ParamSpec(
                key = "algorithm",
                label = "Algorithm",
                type = ParamType.Select,
                default = "SHA1",
                options = selectOptions("SHA1" to "SHA1", "SHA256" to "SHA256", "SHA512" to "SHA512"),
            ),
            ParamSpec("digits", "Digits", ParamType.Number, default = 6),
            ParamSpec("periodSeconds", "Period (seconds)", ParamType.Number, default = 30),
            textParam("outputField", "Save code in", default = "code"),
        ),
        keywords = persistentListOf("totp", "otp", "one time password", "authenticator", "2fa", "code", "hmac"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val secret = ctx.secret("secret").replace(" ", "").uppercase()
        if (secret.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add the base32 secret first")
        val key = CryptoMath.base32Decode(secret)
            ?: throw StepExecutionException(ctx.step.id, ctx.step.name, "the secret is not valid base32")
        val algorithm = "Hmac" + Values.asString(params["algorithm"], "SHA1").uppercase()
            .let { if (it.startsWith("HMAC")) it.removePrefix("HMAC") else it }
        val digits = (Values.asDouble(params["digits"]) ?: 6.0).toInt().coerceIn(6, 8)
        val period = (Values.asDouble(params["periodSeconds"]) ?: 30.0).toLong().coerceIn(1, 300)
        val counter = ctx.clock() / 1000L / period
        val mac = runCatching {
            Mac.getInstance(algorithm).apply { init(SecretKeySpec(key, algorithm)) }
        }.getOrElse {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "this device does not support $algorithm")
        }
        val message = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) { message[i] = (value and 0xff).toByte(); value = value shr 8 }
        val hash = mac.doFinal(message)
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val modulus = Math.pow(10.0, digits.toDouble()).toLong()
        val code = (binary % modulus).toString().padStart(digits, '0')
        val field = ctx.outputName("code")
        return ctx.output(listOf(FlowData(mapOf(
            field to code,
            "expiresInSeconds" to (period - ctx.clock() / 1000L % period).toDouble(),
            "algorithm" to algorithm,
            "digits" to digits.toDouble(),
        ))))
    }
}

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

internal object PdfLite {
    private val STREAM = Regex("""stream\r?\n([\s\S]*?)\r?\nendstream""")
    private val STRING_LITERAL = Regex("""\(((?:\\.|[^\\)])*)\)\s*Tj""")
    private val ARRAY_LITERAL = Regex("""\[([\s\S]*?)\]\s*TJ""")
    private val HEX_LITERAL = Regex("""<([0-9A-Fa-f\s]+)>\s*Tj""")

    fun pageCount(bytes: ByteArray): Int {
        val text = String(bytes, Charsets.ISO_8859_1)
        val count = Regex("""/Type\s*/Page(?![sA-Za-z])""").findAll(text).count()
        return count.coerceAtLeast(if (text.startsWith("%PDF")) 1 else 0)
    }

    fun extractText(bytes: ByteArray): String {
        val header = String(bytes.copyOfRange(0, minOf(8, bytes.size)), Charsets.ISO_8859_1)
        if (bytes.size > BinarySupport.MAX_BYTES || !header.startsWith("%PDF")) {
            return ""
        }
        val source = String(bytes, Charsets.ISO_8859_1)
        val chunks = STREAM.findAll(source).mapNotNull { match ->
            val prefix = source.substring(maxOf(0, match.range.first - 300), match.range.first)
            var data = match.groupValues[1].toByteArray(Charsets.ISO_8859_1)
            if (prefix.contains("/FlateDecode", true)) {
                data = runCatching { inflate(data) }.getOrElse { return@mapNotNull null }
            }
            String(data, Charsets.ISO_8859_1)
        }.toList()
        val parts = mutableListOf<String>()
        chunks.forEach { chunk ->
            STRING_LITERAL.findAll(chunk).forEach { parts += unescape(it.groupValues[1]) }
            HEX_LITERAL.findAll(chunk).forEach { parts += hexText(it.groupValues[1]) }
            ARRAY_LITERAL.findAll(chunk).forEach { match ->
                val content = match.groupValues[1]
                Regex("""\(((?:\\.|[^\\)])*)\)""").findAll(content).forEach {
                    parts += unescape(it.groupValues[1])
                }
                Regex("""<([0-9A-Fa-f\s]+)>""").findAll(content).forEach {
                    parts += hexText(it.groupValues[1])
                }
            }
        }
        return parts.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200_000)
    }

    private fun inflate(data: ByteArray): ByteArray {
        InflaterInputStream(ByteArrayInputStream(data)).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > BinarySupport.MAX_BYTES) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun hexText(hex: String): String {
        val clean = hex.replace(Regex("\\s"), "")
        if (clean.length % 2 != 0) return ""
        return runCatching {
            ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                .toString(Charsets.ISO_8859_1)
        }.getOrDefault("")
    }

    private fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c != '\\' || i >= text.length) { out.append(c); continue }
            when (text[i++]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                '(', ')', '\\' -> out.append(text[i - 1])
                in '0'..'7' -> {
                    val start = i - 1
                    var digits = 1
                    while (digits < 3 && i < text.length && text[i] in '0'..'7') { i++; digits++ }
                    out.append(text.substring(start, start + digits).toIntOrNull(8)?.toChar() ?: '?')
                }
                else -> out.append(text[i - 1])
            }
        }
        return out.toString()
    }
}
