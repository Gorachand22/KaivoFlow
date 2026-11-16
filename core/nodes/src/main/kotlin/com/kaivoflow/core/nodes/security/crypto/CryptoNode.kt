package com.kaivoflow.core.nodes.security.crypto

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.outputField
import kotlinx.collections.immutable.persistentListOf

/** Native Cryptographic and hashing operations for developers. */
class CryptoNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.crypto",
        title = "Crypto & hash",
        category = StepCategory.Transform,
        subtitle = "Hash, HMAC, encode Base64, or generate UUIDs",
        iconKey = "lock",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Action",
                type = ParamType.Select,
                default = "sha256",
                options = persistentListOf(
                    ParamOption("sha256", "SHA-256 Hash"),
                    ParamOption("md5", "MD5 Hash"),
                    ParamOption("sha1", "SHA-1 Hash"),
                    ParamOption("hmac256", "HMAC-SHA256"),
                    ParamOption("base64_encode", "Base64 Encode"),
                    ParamOption("base64_decode", "Base64 Decode"),
                    ParamOption("uuid", "Generate UUID"),
                ),
            ),
            ParamSpec("input", "Input text", ParamType.Text, default = "{{ ${'$'}json.text }}",
                showWhen = mapOf("action" to listOf("sha256", "md5", "sha1", "hmac256", "base64_encode", "base64_decode"))),
            ParamSpec("secretKey", "Secret key", ParamType.Secret,
                showWhen = mapOf("action" to listOf("hmac256"))),
            ParamSpec("outputField", "Save result in", ParamType.Text, default = "hash"),
        ),
        keywords = persistentListOf("hash", "sha256", "md5", "hmac", "base64", "uuid", "crypto", "security"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val action = Values.asString(params["action"], "sha256")
        val field = Values.asString(params["outputField"], "hash")

        val out = ctx.input.mapIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val inputStr = Values.asString(row["input"])
            val result: String = when (action) {
                "uuid" -> java.util.UUID.randomUUID().toString()
                "md5" -> hashString("MD5", inputStr)
                "sha1" -> hashString("SHA-1", inputStr)
                "sha256" -> hashString("SHA-256", inputStr)
                "hmac256" -> {
                    val key = Values.asString(row["secretKey"])
                    hmacSha256(key, inputStr)
                }
                "base64_encode" -> java.util.Base64.getEncoder().encodeToString(inputStr.toByteArray(Charsets.UTF_8))
                "base64_decode" -> runCatching {
                    String(java.util.Base64.getDecoder().decode(inputStr), Charsets.UTF_8)
                }.getOrDefault("")
                else -> inputStr
            }
            item.copy(json = item.json + (field to result))
        }
        return ctx.output(out)
    }

    private fun hashString(algorithm: String, input: String): String {
        val bytes = java.security.MessageDigest.getInstance(algorithm).digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: String, data: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretKey = javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
