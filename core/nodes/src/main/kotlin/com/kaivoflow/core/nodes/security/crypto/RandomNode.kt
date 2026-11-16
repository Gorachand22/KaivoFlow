package com.kaivoflow.core.nodes.security.crypto

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
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolve
import com.kaivoflow.core.nodes.resolveNumber
import com.kaivoflow.core.nodes.secret
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf
import java.security.SecureRandom
import java.util.UUID
import kotlin.math.max

/** Cryptographically useful randomness, entirely on-device. */
class RandomNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.random",
        title = "Random value",
        category = StepCategory.Transform,
        subtitle = "Create IDs, numbers, strings or a random pick",
        iconKey = "shuffle",
        params = persistentListOf(
            ParamSpec(
                key = "kind",
                label = "Create",
                type = ParamType.Select,
                default = "uuid",
                options = selectOptions(
                    "uuid" to "Unique ID", "number" to "A number", "string" to "A secret-like string",
                    "pick" to "Pick one of a list", "shuffle" to "Shuffle the incoming items",
                ),
            ),
            textParam("minimum", "Smallest number", default = "0", showWhen = mapOf("kind" to listOf("number"))),
            textParam("maximum", "Largest number", default = "100", showWhen = mapOf("kind" to listOf("number"))),
            ParamSpec("length", "String length", ParamType.Number, default = 24,
                showWhen = mapOf("kind" to listOf("string"))),
            textParam("list", "Pick from (JSON list)", default = "[]",
                showWhen = mapOf("kind" to listOf("pick"))),
            textParam("outputField", "Save value in", default = "value"),
        ),
        keywords = persistentListOf("random", "uuid", "id", "token", "string", "number", "pick", "shuffle", "sample"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val kind = Values.asString(params["kind"], "uuid")
        val field = ctx.outputName("value")
        val random = SecureRandom()
        if (kind == "shuffle") return ctx.output(ctx.input.shuffled(random))
        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        return ctx.output(sources.mapIndexed { index, item ->
            val value: Any? = when (kind) {
                "number" -> {
                    val lo = ctx.resolveNumber(params["minimum"], index, 0.0)
                    val hi = ctx.resolveNumber(params["maximum"], index, 100.0)
                    val min = kotlin.math.min(lo, hi)
                    val max = kotlin.math.max(lo, hi)
                    min + random.nextDouble() * (max - min)
                }
                "string" -> {
                    val length = (Values.asDouble(params["length"]) ?: 24.0).toInt().coerceIn(1, 256)
                    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
                    buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
                }
                "pick" -> {
                    val choices = Values.asList(ctx.resolve(params["list"], index))
                    if (choices.isEmpty()) throw StepExecutionException(ctx.step.id, ctx.step.name, "the list to pick from is empty")
                    choices[random.nextInt(choices.size)]
                }
                else -> UUID.randomUUID().toString()
            }
            item.copy(json = item.json + (field to value))
        })
    }
}
