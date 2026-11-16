package com.kaivoflow.core.nodes.flow.looping

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf

class LoopNode : StepExecutor {
    override val spec = NodeSpec(
        id = "flow.loop",
        title = "Loop over items",
        category = StepCategory.Logic,
        subtitle = "Work through the items in batches",
        iconKey = "loop",
        params = persistentListOf(
            ParamSpec(
                key = "batchSize",
                label = "Items per batch",
                type = ParamType.Number,
                default = 1,
                hint = "Higher is faster; keep at 1 when the next Step is rate-limited.",
            ),
            ParamSpec("maxIterations", "Safety limit", ParamType.Number, default = 500,
                hint = "The most batches one Run may go through."),
            ParamSpec(
                key = "emit",
                label = "Send on",
                type = ParamType.Select,
                default = "items",
                options = persistentListOf(
                    ParamOption("items", "The items, tagged with their batch"),
                    ParamOption("batches", "One item per batch (holds its items)"),
                ),
                hint = "\"One item per batch\" lets the next Step make one call per batch, for example a bulk API.",
            ),
        ),
        keywords = persistentListOf("each", "iterate", "batch", "repeat", "chunk", "loop over items", "split in batches"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val batchSize = (Values.asDouble(params["batchSize"]) ?: 1.0).toInt().coerceAtLeast(1)
        val limit = (Values.asDouble(params["maxIterations"]) ?: 500.0).toInt().coerceAtLeast(1)
        val batches = ctx.input.chunked(batchSize)
        if (batches.size > limit) {
            throw StepExecutionException(
                ctx.step.id, ctx.step.name,
                "this Loop would run ${batches.size} times but its safety limit is $limit. Raise the limit, use bigger batches or filter the items first."
            )
        }
        val total = ctx.input.size
        fun tag(batchNo: Int, size: Int) = mapOf(
            "batch" to batchNo, "batches" to batches.size, "size" to size, "total" to total,
            "first" to (batchNo == 0), "last" to (batchNo == batches.lastIndex),
        )
        val out = if (Values.asString(params["emit"], "items") == "batches") {
            batches.mapIndexed { n, batch ->
                FlowData(
                    json = tag(n, batch.size) + ("items" to batch.map { it.json }),
                    sources = batch.indices.mapNotNull { ctx.refOf(n * batchSize + it) },
                )
            }
        } else {
            batches.flatMapIndexed { n, batch ->
                batch.mapIndexed { i, item ->
                    // `index` is the position inside the batch, as before; `batch` and `last` are new.
                    item.copy(json = item.json + ("_loop" to (tag(n, batch.size) + ("index" to i))))
                }
            }
        }
        return ctx.output(out)
    }
}
