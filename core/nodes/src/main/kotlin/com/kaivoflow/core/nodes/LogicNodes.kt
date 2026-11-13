package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay

/**
 * Control flow. Every branching Step here routes items per output port, which is what makes
 * the canvas' branch rendering and the engine's per-port liveness work.
 */
class IfNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.if",
        title = "If",
        category = StepCategory.Logic,
        subtitle = "Send items down the true or false branch",
        iconKey = "if",
        outputs = 2,
        outputLabels = persistentListOf("true", "false"),
        params = LogicParams.conditionParams(),
        keywords = persistentListOf("condition", "branch", "filter", "gate"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        // Raw params: condition templates are resolved per item inside ConditionGroup.
        val group = ConditionGroup.fromParams(ctx.step.params)
        val scope = ctx.scope()
        val resolver: (String, Map<String, Any?>) -> Any? = { template, itemJson ->
            ctx.evaluator.interpolate(template, scope + ("json" to itemJson))
        }
        val (yes, no) = ctx.input.partition { item -> group.matches(item.json, resolver) }
        return StepResult(mapOf(0 to yes, 1 to no))
    }
}

class SwitchNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.switch",
        title = "Switch",
        category = StepCategory.Logic,
        subtitle = "Route items into up to four branches by value",
        iconKey = "switch",
        outputs = 5,
        outputLabels = persistentListOf("1", "2", "3", "4", "else"),
        params = persistentListOf(
            ParamSpec("field", "Value to check", ParamType.Text, required = true, placeholder = "{{ ${'$'}json.type }}"),
            ParamSpec("case1", "Branch 1 when it is", ParamType.Text, placeholder = "invoice"),
            ParamSpec("case2", "Branch 2 when it is", ParamType.Text),
            ParamSpec("case3", "Branch 3 when it is", ParamType.Text),
            ParamSpec("case4", "Branch 4 when it is", ParamType.Text),
        ),
        keywords = persistentListOf("route", "cases", "branch"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val rawValue = Values.asString(params["field"])
        val compiled = ParamResolverLite(rawValue)
        val outputs = MutableList(spec.outputs) { mutableListOf<FlowData>() }
        val cases = (1..4).map { Values.asString(params["case$it"]) }
        ctx.input.forEach { item ->
            val value = Values.asString(compiled.resolve(item.json) { template, json ->
                ctx.evaluator.interpolate(template, ctx.scope() + ("json" to json))
            })
            val index = cases.indexOfFirst { it.isNotBlank() && it.equals(value, ignoreCase = true) }
            outputs[if (index >= 0) index else 4] += item
        }
        if (rawValue.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "choose a value to switch on")
        return StepResult(outputs.mapIndexed { i, items -> i to items }.toMap())
    }
}

/** Tiny helper so the same template resolution is used in every branch Step. */
private class ParamResolverLite(private val template: String) {
    fun resolve(item: Map<String, Any?>, interpolate: (String, Map<String, Any?>) -> Any?): Any? =
        if (template.contains("{{")) interpolate(template, item) else Values.deepGet(item, template.removePrefix("${'$'}json."))
}

class MergeNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.merge",
        title = "Merge",
        category = StepCategory.Logic,
        subtitle = "Join two branches back together",
        iconKey = "merge",
        params = persistentListOf(
            ParamSpec(
                key = "mode",
                label = "How to combine",
                type = ParamType.Select,
                default = "append",
                options = persistentListOf(
                    ParamOption("append", "One after another"),
                    ParamOption("zip", "Pair them up"),
                    ParamOption("multiply", "Every combination"),
                    ParamOption("combine", "Merge fields of matching items"),
                ),
            ),
            ParamSpec("matchField", "Match items on field", ParamType.Text, showWhen = mapOf("mode" to listOf("combine"))),
        ),
        keywords = persistentListOf("join", "union", "combine", "merge"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val mode = Values.asString(params["mode"], "append")
        // The engine concatenates all incoming Streams into ctx.input in Stream order, so a real
        // merge needs the split point: recompute from the flow's incoming Streams.
        val incoming = ctx.flow.incoming(ctx.step.id).sortedBy { it.fromPort }
        val scopes = incoming.mapNotNull { stream -> ctx.stepOutputs[ctx.flow.step(stream.fromStepId)?.name ?: ""] }
        val groups = if (scopes.size >= 2) scopes else listOf(ctx.input)

        val items = when (mode) {
            "zip" -> groups[0].zip(groups.getOrElse(1) { emptyList() })
                .map { (a, b) -> FlowData(Values.deepMerge(a.json, b.json) as Map<String, Any?>) }
            "multiply" -> groups[0].flatMap { a ->
                groups.getOrElse(1) { emptyList() }.map { b ->
                    FlowData(Values.deepMerge(a.json, b.json) as Map<String, Any?>)
                }
            }
            "combine" -> {
                val matchField = Values.asString(params["matchField"])
                val right = groups.getOrElse(1) { emptyList() }
                groups[0].map { a ->
                    if (matchField.isBlank()) a
                    else {
                        val match = right.firstOrNull { Values.asString(it.json[matchField]) == Values.asString(a.json[matchField]) }
                        if (match == null) a else FlowData(Values.deepMerge(a.json, match.json) as Map<String, Any?>)
                    }
                }
            }
            else -> groups.flatten()
        }
        return ctx.output(items)
    }
}

class FilterNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.filter",
        title = "Filter",
        category = StepCategory.Logic,
        subtitle = "Keep only items that match a condition",
        iconKey = "filter",
        params = LogicParams.conditionParams(),
        keywords = persistentListOf("where", "keep", "condition"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val group = ConditionGroup.fromParams(ctx.step.params)
        val scope = ctx.scope()
        val resolver: (String, Map<String, Any?>) -> Any? = { template, json ->
            ctx.evaluator.interpolate(template, scope + ("json" to json))
        }
        val kept = ctx.input.filter { group.matches(it.json, resolver) }
        ctx.log("Kept ${kept.size} of ${ctx.input.size} items.")
        return ctx.output(kept)
    }
}

class DedupeNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.dedupe",
        title = "Remove duplicates",
        category = StepCategory.Logic,
        subtitle = "Drop repeated items, keeping the first",
        iconKey = "unique",
        params = persistentListOf(
            ParamSpec("fields", "Compare using fields", ParamType.Text, placeholder = "id, email (leave empty for the whole item)"),
            ParamSpec(
                key = "scope",
                label = "Remember",
                type = ParamType.Select,
                default = "run",
                options = persistentListOf(
                    ParamOption("run", "Within this Run"),
                    ParamOption("day", "Within today"),
                    ParamOption("always", "Across every Run (needs storage)"),
                ),
            ),
        ),
        keywords = persistentListOf("unique", "distinct", "duplicate"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val fields = Values.asString(params["fields"]).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val seen = mutableSetOf<String>()
        val kept = ctx.input.filter { item ->
            val key = if (fields.isEmpty()) Values.stringify(item.json)
            else fields.joinToString("|") { Values.asString(Values.deepGet(item.json, it)) }
            seen.add(key)
        }
        ctx.log("Removed ${ctx.input.size - kept.size} duplicate items.")
        return ctx.output(kept)
    }
}

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

class WaitNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.wait",
        title = "Wait",
        category = StepCategory.Logic,
        subtitle = "Pause before continuing (respects rate limits)",
        iconKey = "clock",
        params = persistentListOf(
            ParamSpec("seconds", "Wait for (seconds)", ParamType.Number, default = 1, required = true),
            ParamSpec("randomJitter", "Add random extra (seconds)", ParamType.Number, default = 0),
        ),
        keywords = persistentListOf("delay", "sleep", "throttle", "pause"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val base = (Values.asDouble(params["seconds"]) ?: 1.0).coerceIn(0.0, 900.0)
        val jitter = (Values.asDouble(params["randomJitter"]) ?: 0.0).coerceIn(0.0, 60.0)
        val total = base + if (jitter > 0) Math.random() * jitter else 0.0
        delay((total * 1000).toLong())
        return ctx.output(ctx.input)
    }
}

class StopNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.stop",
        title = "Stop",
        category = StepCategory.Logic,
        subtitle = "End the Run here and return these items",
        iconKey = "stop",
        params = persistentListOf(
            ParamSpec(
                key = "status",
                label = "Report the Run as",
                type = ParamType.Select,
                default = "success",
                options = persistentListOf(ParamOption("success", "Succeeded"), ParamOption("error", "Failed")),
            ),
            ParamSpec("message", "Message", ParamType.Text, placeholder = "Optional note shown in the Run log"),
        ),
        keywords = persistentListOf("end", "finish", "abort", "return"),
        producesItems = true,
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        if (Values.asString(params["status"]) == "error") {
            throw StepExecutionException(
                ctx.step.id, ctx.step.name,
                Values.asString(params["message"]).ifBlank { "stopped with an error" }
            )
        }
        Values.asString(params["message"]).takeIf { it.isNotBlank() }?.let { ctx.log(it) }
        // Downstream Steps are allowed to run: Stop simply ends *its* branch by producing items.
        return ctx.output(ctx.input)
    }
}

class ErrorHandlerNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.onError",
        title = "When a Step fails",
        category = StepCategory.Logic,
        subtitle = "Clean up or alert when something breaks",
        iconKey = "shield",
        params = persistentListOf(
            ParamSpec("message", "Alert text", ParamType.Multiline, placeholder = "{{ ${'$'}json.step }} failed: {{ ${'$'}json.error }}"),
        ),
        keywords = persistentListOf("catch", "error", "handler", "alert"),
    )

    override suspend fun execute(ctx: StepContext): StepResult = ctx.output(ctx.input)
}

internal object LogicParams {
    fun conditionParams() = persistentListOf(
        ParamSpec(
            key = "combinator",
            label = "Match",
            type = ParamType.Select,
            default = "all",
            options = persistentListOf(ParamOption("all", "All conditions"), ParamOption("any", "Any condition")),
        ),
        ParamSpec(
            key = "conditions",
            label = "Conditions",
            type = ParamType.KeyValue,
            hint = "left = expression or field, operator, right = value",
        ),
    )
}

/** Shared by both branch Step specs. */
internal fun ParamSpecList(vararg specs: ParamSpec) = persistentListOf(*specs)
