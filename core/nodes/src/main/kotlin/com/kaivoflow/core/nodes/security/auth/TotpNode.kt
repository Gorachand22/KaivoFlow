package com.kaivoflow.core.nodes.security.auth

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
import com.kaivoflow.core.nodes.CryptoMath
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.secret
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Time-based one-time passwords from a base32 secret (RFC 6238 shape, HMAC only). */
class TotpNode : StepExecutor {
    override val spec = NodeSpec(
        id = "security.totp",
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
