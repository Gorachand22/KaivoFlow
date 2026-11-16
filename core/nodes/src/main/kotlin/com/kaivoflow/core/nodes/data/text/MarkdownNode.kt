package com.kaivoflow.core.nodes.data.text

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** Markdown to HTML (and back to plain text) for messages, notes and notifications. */
class MarkdownNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.markdown",
        title = "Markdown",
        category = StepCategory.Transform,
        subtitle = "Turn Markdown notes into HTML or plain text",
        iconKey = "markdown",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "to_html",
                options = selectOptions("to_html" to "Markdown → HTML", "to_text" to "HTML/Markdown → plain text"),
            ),
            textParam("source", "Text field", default = "{{ \$json.markdown }}"),
            textParam("outputField", "Save result in", default = "html"),
        ),
        keywords = persistentListOf("markdown", "html", "format", "notes", "rich text", "headings"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val action = Values.asString(params["action"], "to_html")
        val field = ctx.outputName("html")
        val out = ctx.input.mapIndexed { index, item ->
            val source = ctx.resolveText(params["source"], index)
            val result = if (action == "to_text") MarkdownLite.toText(source) else MarkdownLite.toHtml(source)
            item.copy(json = item.json + (field to result))
        }
        return ctx.output(out)
    }
}
