package com.kaivoflow.core.nodes.security.crypto

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.CryptoMath
import com.kaivoflow.core.nodes.MiniJson
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolve
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.secret
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

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
