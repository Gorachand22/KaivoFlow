package com.kaivoflow.core.nodes.data.text

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
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** Convert between a compact JSON-ish representation and XML. */
class XmlNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.xml",
        title = "XML",
        category = StepCategory.Transform,
        subtitle = "Turn XML into fields, or fields into XML",
        iconKey = "dataObject",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "to_json",
                options = selectOptions("to_json" to "Read XML into fields", "to_xml" to "Build XML from fields"),
            ),
            textParam("source", "XML or data field", default = "{{ \$json.xml }}",
                showWhen = mapOf("action" to listOf("to_json"))),
            textParam("rootTag", "Root element name", default = "root",
                showWhen = mapOf("action" to listOf("to_xml"))),
            textParam("outputField", "Save result in", default = "result"),
        ),
        keywords = persistentListOf("xml", "soap", "parse", "serialize", "tags", "attributes"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val action = Values.asString(params["action"], "to_json")
        val field = ctx.outputName("result")
        val out = ctx.input.mapIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val value: Any? = if (action == "to_xml") {
                val root = Values.asString(row["rootTag"], "root")
                    .replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "root" }
                XmlLite.build(item.json, root)
            } else {
                val text = Values.asString(row["source"])
                XmlLite.parse(text) ?: throw StepExecutionException(
                    ctx.step.id, ctx.step.name, "the XML could not be read (check for unbalanced tags)"
                )
            }
            item.copy(json = item.json + (field to value))
        }
        return ctx.output(out)
    }
}
