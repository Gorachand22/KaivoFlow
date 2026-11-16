package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
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
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** Turn one item into many: split an array field, split text, or chunk the whole list. */
class SplitNode : StepExecutor {
    override val spec = NodeSpec(
        id = "data.split_out",
        title = "Split items",
        category = StepCategory.Transform,
        subtitle = "Turn a list or a line of text into separate items",
        iconKey = "unfoldMore",
        params = persistentListOf(
            ParamSpec(
                key = "mode",
                label = "Split",
                type = ParamType.Select,
                default = "array",
                options = selectOptions(
                    "array" to "A list field", "text" to "Text by a separator", "chunks" to "Items into batches",
                ),
            ),
            textParam("source", "From field", default = "{{ \$json.items }}",
                showWhen = mapOf("mode" to listOf("array", "text"))),
            textParam("delimiter", "Separator", default = ",", showWhen = mapOf("mode" to listOf("text"))),
            ParamSpec("chunkSize", "Items per batch", ParamType.Number, default = 10,
                showWhen = mapOf("mode" to listOf("chunks"))),
            textParam("outputField", "Put each value in", default = "value"),
            ParamSpec("keepOtherFields", "Keep the other fields", ParamType.Boolean, default = true),
        ),
        keywords = persistentListOf("split", "explode", "flatten", "batch", "chunk", "array", "items"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val mode = Values.asString(params["mode"], "array")
        val field = ctx.outputName("value")
        val keep = Values.asBoolean(params["keepOtherFields"] ?: true)
        if (mode == "chunks") {
            val size = (Values.asDouble(params["chunkSize"]) ?: 10.0).toInt().coerceAtLeast(1)
            val batches = ctx.input.chunked(size).mapIndexed { i, batch ->
                FlowData(
                    mapOf(
                        "batchIndex" to i,
                        "batchSize" to batch.size,
                        field to batch.map { it.json },
                    )
                )
            }
            return ctx.output(batches)
        }
        val out = ctx.input.flatMapIndexed { index, item ->
            val raw = ctx.resolve(params["source"], index)
            val values = when (mode) {
                "text" -> {
                    val separator = ctx.resolveText(params["delimiter"], index, ",")
                    Values.asString(raw).split(separator).map { it.trim() }
                }
                else -> Values.asList(raw)
            }
            values.mapIndexed { i, value ->
                val base = if (keep) LinkedHashMap(item.json) else LinkedHashMap()
                base[field] = value
                base["splitIndex"] = i
                item.copy(json = base)
            }
        }
        return ctx.output(out)
    }
}
