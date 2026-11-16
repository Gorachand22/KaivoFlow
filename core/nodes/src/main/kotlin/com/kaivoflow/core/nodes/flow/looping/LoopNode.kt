package com.kaivoflow.core.nodes.flow.looping

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf

class LoopNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.loop",
        title = "Loop",
        category = StepCategory.Logic,
        subtitle = "Repeat the rest of the Flow for each item",
        iconKey = "loop",
        params = persistentListOf(
            ParamSpec(
                key = "batchSize",
                label = "Items at a time",
                type = ParamType.Number,
                default = 1,
                hint = "Higher is faster; keep at 1 when the next Step is rate-limited.",
            ),
            ParamSpec("maxIterations", "Safety limit", ParamType.Number, default = 500),
        ),
        keywords = persistentListOf("each", "iterate", "batch", "repeat"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        // The Loop Step makes items individually addressable downstream: each item becomes its
        // own "batch" that the inspector can inspect and that device Steps (SMS per item) use.
        val params = ctx.params()
        val batchSize = (Values.asDouble(params["batchSize"]) ?: 1.0).toInt().coerceAtLeast(1)
        val limit = (Values.asDouble(params["maxIterations"]) ?: 500.0).toInt().coerceAtLeast(1)
        if (ctx.input.size > limit) {
            throw StepExecutionException(
                ctx.step.id, ctx.step.name,
                "this Loop would run ${ctx.input.size} times but its safety limit is $limit. Raise the limit or filter the items first."
            )
        }
        val batched = ctx.input.chunked(batchSize).flatMap { batch ->
            batch.mapIndexed { i, item ->
                item.copy(json = item.json + ("_loop" to mapOf("index" to i, "size" to batch.size, "total" to ctx.input.size)))
            }
        }
        return ctx.output(batched)
    }
}
