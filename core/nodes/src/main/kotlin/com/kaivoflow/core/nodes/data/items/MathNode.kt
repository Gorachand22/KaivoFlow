package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolveNumber
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Numeric work without a scripting language: the maths Step the backlog asked for. */
class MathNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.math",
        title = "Math",
        category = StepCategory.Transform,
        subtitle = "Calculate with numbers from your items",
        iconKey = "calculate",
        params = persistentListOf(
            ParamSpec(
                key = "operation",
                label = "Operation",
                type = ParamType.Select,
                default = "add",
                options = selectOptions(
                    "add" to "Add", "subtract" to "Subtract", "multiply" to "Multiply",
                    "divide" to "Divide", "modulo" to "Remainder", "power" to "Power of",
                    "percent" to "Percentage of", "round" to "Round", "ceil" to "Round up",
                    "floor" to "Round down", "abs" to "Absolute value", "negate" to "Negate",
                    "sqrt" to "Square root", "log" to "Logarithm", "min" to "Smaller of",
                    "max" to "Larger of", "average" to "Average", "clamp" to "Keep between",
                    "random" to "Random number", "sum_fields" to "Total listed fields",
                ),
            ),
            textParam("a", "First value", default = "{{ \$json.value }}", placeholder = "{{ \$json.amount }}"),
            textParam("b", "Second value", default = "0", showWhen = mapOf("operation" to listOf(
                "add", "subtract", "multiply", "divide", "modulo", "power", "percent",
                "min", "max", "average", "clamp", "random",
            ))),
            textParam("fields", "Fields to total", default = "amount", placeholder = "amount, tax, shipping",
                showWhen = mapOf("operation" to listOf("sum_fields"))),
            ParamSpec("precision", "Decimal places", ParamType.Number, default = 2,
                showWhen = mapOf("operation" to listOf("round"))),
            textParam("maximum", "Upper bound", default = "100", showWhen = mapOf("operation" to listOf("clamp"))),
            textParam("outputField", "Save result in", default = "result"),
        ),
        keywords = persistentListOf("math", "calculate", "sum", "round", "min", "max", "percent", "number"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val operation = Values.asString(params["operation"], "add")
        val field = ctx.outputName("result")
        val precision = (Values.asDouble(params["precision"]) ?: 2.0).toInt().coerceIn(0, 12)
        val out = ctx.input.mapIndexed { index, item ->
            val a = ctx.resolveNumber(params["a"], index, 0.0)
            val b = ctx.resolveNumber(params["b"], index, 0.0)
            val result: Double = when (operation) {
                "add" -> a + b
                "subtract" -> a - b
                "multiply" -> a * b
                "divide" -> divide(ctx, a, b)
                "modulo" -> if (b == 0.0) throw zeroDivision(ctx) else a % b
                "power" -> a.pow(b)
                "percent" -> if (b == 0.0) throw zeroDivision(ctx) else a / b * 100.0
                "round" -> {
                    val factor = 10.0.pow(precision)
                    Math.round(a * factor).toDouble() / factor
                }
                "ceil" -> ceil(a)
                "floor" -> floor(a)
                "abs" -> abs(a)
                "negate" -> -a
                "sqrt" -> if (a < 0) throw badNumber(ctx, "square root of a negative number") else sqrt(a)
                "log" -> if (a <= 0) throw badNumber(ctx, "logarithm of zero or a negative number") else log10(a)
                "ln" -> if (a <= 0) throw badNumber(ctx, "logarithm of zero or a negative number") else ln(a)
                "min" -> min(a, b)
                "max" -> max(a, b)
                "average" -> (a + b) / 2.0
                "clamp" -> {
                    val upper = ctx.resolveNumber(params["maximum"], index, 100.0)
                    a.coerceIn(min(b, upper), max(b, upper))
                }
                "random" -> {
                    val lo = min(a, b)
                    val hi = max(a, b)
                    lo + Math.random() * (hi - lo)
                }
                "sum_fields" -> {
                    val fields = ctx.resolveText(params["fields"], index)
                        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (fields.isEmpty()) throw badNumber(ctx, "list at least one field to total")
                    fields.sumOf { name -> Values.asDouble(Values.deepGet(item.json, name)) ?: 0.0 }
                }
                else -> a
            }
            if (!result.isFinite()) throw badNumber(ctx, "the result is not a finite number")
            item.copy(json = item.json + (field to result))
        }
        return ctx.output(out)
    }

    private fun divide(ctx: StepContext, a: Double, b: Double): Double =
        if (b == 0.0) throw zeroDivision(ctx) else a / b

    private fun zeroDivision(ctx: StepContext) =
        StepExecutionException(ctx.step.id, ctx.step.name, "the second value is zero, so division is not possible")

    private fun badNumber(ctx: StepContext, detail: String) =
        StepExecutionException(ctx.step.id, ctx.step.name, "cannot compute $detail")
}
