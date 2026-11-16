package com.kaivoflow.core.nodes.code.script

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
import com.kaivoflow.core.nodes.net.web.snippet
import kotlinx.collections.immutable.persistentListOf

/**
 * Run JavaScript for the cases the visual Steps do not cover.
 *
 * The script runs inside the same sandbox as expressions (`:core:expressions`), so:
 *  • there is no file system, network or Android API reachable from the script;
 *  • it is time-boxed by the evaluator, so a runaway loop cannot hang the Run;
 *  • it returns data, never side effects — side effects go through Device Steps.
 *
 * Contract: the script must return an item, an array of items, or `undefined` to pass the
 * incoming items through unchanged.
 */
class CodeNode : StepExecutor {
    override val spec = NodeSpec(
        id = "code.javascript",
        title = "Code",
        category = StepCategory.Transform,
        subtitle = "Run a small JavaScript snippet on your items",
        iconKey = "code",
        params = persistentListOf(
            ParamSpec(
                key = "mode",
                label = "Run",
                type = ParamType.Select,
                default = "all",
                options = persistentListOf(
                    ParamOption("all", "Once for all items (use ${'$'}items)"),
                    ParamOption("each", "Once per item (use ${'$'}json)"),
                ),
            ),
            ParamSpec(
                key = "code",
                label = "Script",
                type = ParamType.Code,
                required = true,
                default = "return ${'$'}items.map(item => ({ ...item, doubled: item.amount * 2 }));",
                supportsExpression = false,
            ),
        ),
        keywords = persistentListOf("code", "javascript", "script", "function", "custom"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val raw = Values.asString(params["code"])
        if (raw.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "the script is empty")
        val perItem = Values.asString(params["mode"], "all") == "each"

        val script = "(function(){\n$raw\n})()"

        fun toItems(value: Any?, fallback: List<FlowData>): List<FlowData> = when (value) {
            null -> fallback
            is List<*> -> value.mapNotNull { entry ->
                when (entry) {
                    null -> null
                    is Map<*, *> -> FlowData(Values.asMap(entry))
                    else -> FlowData(mapOf("value" to entry))
                }
            }
            is Map<*, *> -> listOf(FlowData(Values.asMap(value)))
            else -> listOf(FlowData(mapOf("value" to value)))
        }

        return if (perItem) {
            val out = ctx.input.mapIndexed { index, item ->
                val result = ctx.evaluator.evaluate(script, ctx.scope(index))
                toItems(result, listOf(item)).firstOrNull() ?: item
            }
            ctx.output(out)
        } else {
            ctx.output(toItems(ctx.evaluator.evaluate(script, ctx.scope(0)), ctx.input))
        }
    }
}
