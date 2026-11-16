package com.kaivoflow.core.nodes.flow.branching

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.ItemRef
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf

class MergeNode : StepExecutor {
    override val spec = NodeSpec(
        id = "flow.merge",
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
                    ParamOption("zip", "Pair them up by position"),
                    ParamOption("multiply", "Every combination"),
                    ParamOption("combine", "Match items on a field"),
                    ParamOption("choose", "Pass through one input"),
                ),
            ),
            ParamSpec("matchField", "Match on field (input 1)", ParamType.Text, showWhen = mapOf("mode" to listOf("combine")),
                hint = "Several fields separated by commas must all match."),
            ParamSpec("matchField2", "Match on field (input 2)", ParamType.Text, advanced = true, showWhen = mapOf("mode" to listOf("combine")),
                hint = "Leave empty when input 2 uses the same field names."),
            ParamSpec(
                key = "joinKind",
                label = "Keep",
                type = ParamType.Select,
                default = "left",
                showWhen = mapOf("mode" to listOf("combine")),
                options = persistentListOf(
                    ParamOption("left", "Everything from input 1 (matches joined)"),
                    ParamOption("inner", "Only items that match"),
                    ParamOption("outer", "Everything from both inputs"),
                    ParamOption("unmatched", "Only input 1 items with no match"),
                ),
            ),
            ParamSpec(
                key = "unequalLength",
                label = "When the inputs differ in length",
                type = ParamType.Select,
                default = "shortest",
                advanced = true,
                showWhen = mapOf("mode" to listOf("zip")),
                options = persistentListOf(
                    ParamOption("shortest", "Stop at the shorter input"),
                    ParamOption("longest", "Keep the unpaired items too"),
                ),
            ),
            ParamSpec(
                key = "inputNumber",
                label = "Input to pass through",
                type = ParamType.Number,
                default = 1,
                showWhen = mapOf("mode" to listOf("choose")),
            ),
            ParamSpec(
                key = "clash",
                label = "When both inputs have the same field",
                type = ParamType.Select,
                default = "deep",
                advanced = true,
                showWhen = mapOf("mode" to listOf("zip", "multiply", "combine")),
                options = persistentListOf(
                    ParamOption("deep", "Merge objects, input 2 wins on values"),
                    ParamOption("input1", "Input 1 wins"),
                    ParamOption("input2", "Input 2 wins"),
                ),
            ),
        ),
        keywords = persistentListOf("join", "union", "combine", "merge", "append", "key", "position", "inner", "outer", "left join"),
    )

    /** An input item together with its identity (null when the Step runs outside the runner). */
    private class Entry(val item: FlowData, val ref: ItemRef?)

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val mode = Values.asString(params["mode"], "append")
        val clash = Values.asString(params["clash"], "deep")
        val groups = inputGroups(ctx)
        val left = groups[0]
        val right = groups.getOrElse(1) { emptyList() }

        fun fields(a: Map<String, Any?>, b: Map<String, Any?>): Map<String, Any?> = when (clash) {
            "input1" -> b + a
            "input2" -> a + b
            else -> Values.deepMerge(a, b).asObject()
        }

        fun join(a: Entry, b: Entry) = FlowData(
            json = fields(a.item.json, b.item.json),
            binary = a.item.binary + b.item.binary,
            error = a.item.error ?: b.item.error,
            sources = listOfNotNull(a.ref, b.ref),
        )

        fun keep(e: Entry) = e.item.copy(sources = listOfNotNull(e.ref))

        val items = when (mode) {
            "zip" -> {
                val paired = left.zip(right).map { (a, b) -> join(a, b) }
                if (Values.asString(params["unequalLength"], "shortest") == "longest") {
                    paired + left.drop(right.size).map { keep(it) } + right.drop(left.size).map { keep(it) }
                } else paired
            }
            "multiply" -> left.flatMap { a -> right.map { b -> join(a, b) } }
            "combine" -> combine(params, left, right, ::join, ::keep)
            "choose" -> {
                val n = (Values.asDouble(params["inputNumber"]) ?: 1.0).toInt().coerceAtLeast(1)
                groups.getOrElse(n - 1) { emptyList() }.map { keep(it) }
            }
            else -> groups.flatten().map { keep(it) }
        }
        return ctx.output(items)
    }

    private fun combine(
        params: Map<String, Any?>,
        left: List<Entry>,
        right: List<Entry>,
        join: (Entry, Entry) -> FlowData,
        keep: (Entry) -> FlowData,
    ): List<FlowData> {
        fun names(key: String) = Values.asString(params[key]).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val leftFields = names("matchField")
        val rightFields = names("matchField2").ifEmpty { leftFields }
        if (leftFields.isEmpty()) return left.map(keep)
        fun keyOf(e: Entry, fields: List<String>) = fields.joinToString("\u0000") { Values.asString(Values.deepGet(e.item.json, it)) }
        val rightIndex = LinkedHashMap<String, MutableList<Entry>>()
        right.forEach { rightIndex.getOrPut(keyOf(it, rightFields)) { mutableListOf() } += it }
        val matchedRight = HashSet<Entry>()
        val out = mutableListOf<FlowData>()
        val joinKind = Values.asString(params["joinKind"], "left")
        left.forEach { a ->
            val match = rightIndex[keyOf(a, leftFields)]?.firstOrNull()
            if (match != null) {
                matchedRight += match
                if (joinKind != "unmatched") out += join(a, match)
            } else if (joinKind != "inner") {
                out += keep(a)
            }
        }
        if (joinKind == "outer") out += right.filter { it !in matchedRight }.map(keep)
        return out
    }

    /**
     * One list per input. A Merge with Streams on two or more different input ports uses those
     * ports. Flows saved before input ports existed have every Stream on port 0, so there each
     * incoming Stream counts as one input, in Stream order. Outside the runner (no stream data)
     * the upstream outputs are looked up by Step id, then by name.
     */
    private fun inputGroups(ctx: StepContext): List<List<Entry>> {
        if (ctx.streamInputs.isNotEmpty()) {
            fun entries(stream: com.kaivoflow.core.engine.StreamInput) =
                stream.items.mapIndexed { i, item -> Entry(item, stream.refs.getOrNull(i)) }
            val ports = ctx.streamInputs.map { it.toPort }.distinct().sorted()
            return when {
                ports.size >= 2 -> ports.map { p -> ctx.streamInputs.filter { it.toPort == p }.flatMap { entries(it) } }
                ctx.streamInputs.size >= 2 -> ctx.streamInputs.map { entries(it) }
                else -> listOf(ctx.streamInputs.flatMap { entries(it) })
            }
        }
        val incoming = ctx.flow.incoming(ctx.step.id).sortedBy { it.toPort }
        val found = incoming.mapNotNull { stream ->
            ctx.stepOutputs[stream.fromStepId] ?: ctx.stepOutputs[ctx.flow.step(stream.fromStepId)?.name ?: ""]
        }
        val lists = if (found.size >= 2) found else listOf(ctx.input)
        return lists.map { list -> list.map { Entry(it, null) } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObject(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
}
