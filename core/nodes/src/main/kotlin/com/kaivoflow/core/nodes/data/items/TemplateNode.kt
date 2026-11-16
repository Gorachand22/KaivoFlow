package com.kaivoflow.core.nodes.data.items

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.outputField
import kotlinx.collections.immutable.persistentListOf

/** Plain text templating — emails, messages, documents. */
class TemplateNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.template",
        title = "Write text",
        category = StepCategory.Transform,
        subtitle = "Build a message from your data",
        iconKey = "text",
        params = persistentListOf(
            ParamSpec(
                key = "template",
                label = "Text",
                type = ParamType.Multiline,
                required = true,
                placeholder = "Hi! {{ ${'$'}json.merchant }} charged {{ ${'$'}json.amount }}.",
            ),
            ParamSpec("outputField", "Put the text in", ParamType.Text, default = "text"),
        ),
        keywords = persistentListOf("template", "text", "message", "format", "string"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val field = Values.asString(params["outputField"], "text")
        val out = ctx.input.mapIndexed { index, item ->
            val value = ctx.paramsFor(index)["template"]
            item.copy(json = item.json + (field to value))
        }
        return ctx.output(out)
    }
}
