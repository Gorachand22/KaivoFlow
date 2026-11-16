package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.selectOptions
import kotlinx.collections.immutable.persistentListOf
import java.security.SecureRandom

/** Keep only the first, last or a slice of the incoming list. */
class LimitNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.limit",
        title = "Limit items",
        category = StepCategory.Logic,
        subtitle = "Take just the items you need",
        iconKey = "filterList",
        params = persistentListOf(
            ParamSpec(
                key = "mode",
                label = "Keep",
                type = ParamType.Select,
                default = "first",
                options = selectOptions(
                    "first" to "First N", "last" to "Last N", "skip" to "Skip the first N",
                    "slice" to "A slice", "sample" to "A random sample",
                ),
            ),
            ParamSpec("count", "How many", ParamType.Number, default = 10),
            ParamSpec("offset", "Start after", ParamType.Number, default = 0,
                showWhen = mapOf("mode" to listOf("slice"))),
        ),
        keywords = persistentListOf("limit", "take", "first", "last", "skip", "slice", "sample", "truncate"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val mode = Values.asString(params["mode"], "first")
        val count = (Values.asDouble(params["count"]) ?: 10.0).toInt().coerceAtLeast(0)
        val offset = (Values.asDouble(params["offset"]) ?: 0.0).toInt().coerceAtLeast(0)
        val out = when (mode) {
            "last" -> ctx.input.takeLast(count)
            "skip" -> ctx.input.drop(count)
            "slice" -> ctx.input.drop(offset).take(count)
            "sample" -> {
                val random = SecureRandom()
                ctx.input.shuffled(random).take(count)
            }
            else -> ctx.input.take(count)
        }
        ctx.log("Kept ${out.size} of ${ctx.input.size} items.")
        return ctx.output(out)
    }
}
