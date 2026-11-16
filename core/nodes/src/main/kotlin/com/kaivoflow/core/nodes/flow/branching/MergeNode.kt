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

    /** An input item together with its identity (null when the Step runs outside the runner). */
    private class Entry(val item: FlowData, val ref: ItemRef?)

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val mode = Values.asString(params["mode"], "append")
        val groups = inputGroups(ctx)

        fun join(a: Entry, b: Entry) = FlowData(
            json = Values.deepMerge(a.item.json, b.item.json).asObject(),
            binary = a.item.binary + b.item.binary,
            error = a.item.error ?: b.item.error,
            sources = listOfNotNull(a.ref, b.ref),
        )

        fun keep(e: Entry) = e.item.copy(sources = listOfNotNull(e.ref))

        val items = when (mode) {
            "zip" -> groups[0].zip(groups.getOrElse(1) { emptyList() }).map { (a, b) -> join(a, b) }
            "multiply" -> groups[0].flatMap { a -> groups.getOrElse(1) { emptyList() }.map { b -> join(a, b) } }
            "combine" -> {
                val matchField = Values.asString(params["matchField"])
                val right = groups.getOrElse(1) { emptyList() }
                groups[0].map { a ->
                    if (matchField.isBlank()) keep(a)
                    else {
                        val match = right.firstOrNull { Values.asString(it.item.json[matchField]) == Values.asString(a.item.json[matchField]) }
                        if (match == null) keep(a) else join(a, match)
                    }
                }
            }
            else -> groups.flatten().map { keep(it) }
        }
        return ctx.output(items)
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
