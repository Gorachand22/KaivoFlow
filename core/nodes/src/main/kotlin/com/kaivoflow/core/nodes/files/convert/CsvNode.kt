package com.kaivoflow.core.nodes.files.convert

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
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** CSV in, items out — and items back into CSV. Quoted fields and embedded newlines are handled. */
class CsvNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.csv",
        title = "CSV",
        category = StepCategory.Transform,
        subtitle = "Read a table into items, or write one from items",
        iconKey = "table",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "parse",
                options = selectOptions("parse" to "Read CSV into items", "build" to "Build CSV from items"),
            ),
            textParam("source", "Text field", default = "{{ \$json.csv }}",
                showWhen = mapOf("action" to listOf("parse"))),
            textParam("delimiter", "Column separator", default = ","),
            ParamSpec("hasHeader", "First row holds the column names", ParamType.Boolean, default = true,
                showWhen = mapOf("action" to listOf("parse"))),
            textParam("columns", "Columns in order", placeholder = "date, merchant, amount",
                showWhen = mapOf("action" to listOf("build"))),
            textParam("outputField", "Save CSV text in", default = "csv",
                showWhen = mapOf("action" to listOf("build"))),
        ),
        keywords = persistentListOf("csv", "spreadsheet", "table", "columns", "delimiter", "rows", "export"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val action = Values.asString(params["action"], "parse")
        val delimiter = ctx.resolveText(params["delimiter"], 0, ",").firstOrNull() ?: ','
        if (action == "build") {
            val columns = ctx.resolveText(params["columns"], 0)
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val csv = CsvLite.build(ctx.input.map { it.json }, columns, delimiter)
            val field = ctx.outputName("csv")
            return ctx.output(listOf(FlowData(mapOf(field to csv, "rows" to ctx.input.size))))
        }
        val source = ctx.resolveText(params["source"], 0)
        val hasHeader = Values.asBoolean(params["hasHeader"] ?: true)
        val rows = CsvLite.parse(source, delimiter, hasHeader)
        ctx.log("Read ${rows.size} CSV rows.")
        return ctx.output(rows.map { FlowData(it) })
    }
}
