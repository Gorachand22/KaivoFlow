package com.kaivoflow.core.nodes.ai.chains

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.nodes.ai.shared.AiClient
import com.kaivoflow.core.nodes.ai.shared.aiSpec
import com.kaivoflow.core.nodes.outputField

/** Route items into branches by an AI-chosen label — classification without code. */
class AiClassifyNode : StepExecutor {
    override val spec = aiSpec(
        id = "ai.classify",
        title = "Sort with AI",
        subtitle = "Send items into branches by meaning",
        iconKey = "sort",
        outputs = 4,
        outputLabels = listOf("label 1", "label 2", "label 3", "anything else"),
        extraParams = listOf(
            ParamSpec("text", "Text to classify", ParamType.Multiline, required = true, placeholder = "{{ ${'$'}json.text }}"),
            ParamSpec("labels", "Labels (comma separated)", ParamType.Text, required = true, placeholder = "urgent, invoice, personal"),
            ParamSpec("instructions", "How to decide", ParamType.Multiline, advanced = true),
            ParamSpec("outputField", "Put the label in", ParamType.Text, default = "label"),
        ),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val labels = Values.asString(params["labels"]).split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (labels.isEmpty()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add at least one label")
        val outputs = MutableList(spec.outputs) { mutableListOf<FlowData>() }
        val field = Values.asString(params["outputField"], "label")

        ctx.input.forEachIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val text = Values.asString(row["text"])
            val instructions = Values.asString(row["instructions"])
            val prompt = buildString {
                appendLine("Classify the text into exactly one of these labels: ${labels.joinToString(", ")}")
                appendLine("If none fits, answer \"other\".")
                if (instructions.isNotBlank()) appendLine(instructions)
                appendLine()
                appendLine("TEXT:")
                append(text.take(8_000))
            }
            val result = AiClient.chat(
                ctx, params, prompt,
                "Answer with a single label and nothing else.", jsonMode = false
            )
            val answer = result.text.trim().trim('"', '.', '\n').lowercase()
            val index2 = labels.indexOfFirst { answer.contains(it.lowercase()) }
            val target = if (index2 in 0..2) index2 else 3
            outputs[target] += item.copy(json = item.json + (field to (if (index2 >= 0) labels[index2] else "other")))
        }
        return StepResult(outputs.mapIndexed { i, items -> i to items }.toMap())
    }
}
