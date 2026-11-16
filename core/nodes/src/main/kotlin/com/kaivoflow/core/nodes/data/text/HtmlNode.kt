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

/** Extract readable text, links or attributes from HTML without a browser. */
class HtmlNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.html",
        title = "HTML tools",
        category = StepCategory.Transform,
        subtitle = "Pull text, links and images out of a web page",
        iconKey = "html",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Get",
                type = ParamType.Select,
                default = "text",
                options = selectOptions(
                    "text" to "Readable text", "links" to "All links", "images" to "All image URLs",
                    "attribute" to "A tag attribute", "title" to "Page title",
                ),
            ),
            textParam("source", "HTML field", default = "{{ \$json.html }}"),
            textParam("tag", "Tag name", default = "a", placeholder = "a, img, meta",
                showWhen = mapOf("action" to listOf("attribute"))),
            textParam("attribute", "Attribute", default = "href",
                showWhen = mapOf("action" to listOf("attribute"))),
            textParam("outputField", "Save result in", default = "text"),
        ),
        keywords = persistentListOf("html", "scrape", "extract", "links", "text", "css", "web page"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val action = Values.asString(params["action"], "text")
        val field = ctx.outputName("text")
        val out = ctx.input.mapIndexed { index, item ->
            val html = ctx.resolveText(params["source"], index)
            val result: Any? = when (action) {
                "links" -> HtmlLite.links(html)
                "images" -> HtmlLite.attributes(html, "img", "src")
                "title" -> HtmlLite.title(html)
                "attribute" -> HtmlLite.attributes(
                    html,
                    ctx.resolveText(params["tag"], index, "a").ifBlank { "a" },
                    ctx.resolveText(params["attribute"], index, "href").ifBlank { "href" },
                )
                else -> HtmlLite.text(html)
            }
            item.copy(json = item.json + (field to result))
        }
        return ctx.output(out)
    }
}
