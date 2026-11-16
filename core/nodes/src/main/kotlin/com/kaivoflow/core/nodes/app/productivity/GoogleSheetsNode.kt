package com.kaivoflow.core.nodes.app.productivity

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.bearerToken
import kotlinx.collections.immutable.persistentListOf

class GoogleSheetsNode : StepExecutor {
    override val spec = NodeSpec(
        id = "app.google_sheets",
        title = "Google Sheets",
        category = StepCategory.Saas,
        subtitle = "Append rows or read a range",
        iconKey = "sheets",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "append",
                options = persistentListOf(ParamOption("append", "Add rows"), ParamOption("read", "Read rows")),
            ),
            ParamSpec("credential", "Google credential", ParamType.Secret, required = true),
            ParamSpec("spreadsheetId", "Spreadsheet ID", ParamType.Text, required = true, hint = "The long id in the sheet URL."),
            ParamSpec("range", "Sheet and range", ParamType.Text, default = "Sheet1!A1"),
            ParamSpec(
                key = "columns",
                label = "Columns (in sheet order)",
                type = ParamType.Text,
                default = "date, merchant, amount",
                hint = "Field names from your items, comma separated.",
            ),
            ParamSpec("valueInputOption", "How to enter values", ParamType.Select, default = "USER_ENTERED",
                options = persistentListOf(ParamOption("USER_ENTERED", "Interpret as if typed"), ParamOption("RAW", "Store exactly")),
                advanced = true),
        ),
        keywords = persistentListOf("sheets", "google", "spreadsheet", "row", "log", "append"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val credential = ctx.credential()
        val token = credential.bearerToken()
        if (token.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "the Google credential is empty (use an OAuth access token)")
        val spreadsheetId = Values.asString(params["spreadsheetId"])
        val range = Values.asString(params["range"], "Sheet1!A1")
        val base = "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values"

        return when (Values.asString(params["action"], "append")) {
            "read" -> {
                val response = ctx.http.request(
                    HttpRequestSpec(
                        method = "GET",
                        url = "$base/$range",
                        headers = mapOf("Authorization" to "Bearer $token"),
                        query = mapOf("majorDimension" to "ROWS"),
                    )
                )
                if (!response.isSuccess) throw StepExecutionException(ctx.step.id, ctx.step.name, "Sheets returned HTTP ${response.status}: ${response.bodyText.take(200)}")
                val rows = Values.asList(Values.deepGet(Values.asMap(response.body), "values"))
                val headers = Values.asList(rows.firstOrNull()).map { Values.asString(it) }
                val items = rows.drop(1).map { row ->
                    val cells = Values.asList(row)
                    com.kaivoflow.core.model.FlowData(
                        headers.mapIndexed { i, header -> header to cells.getOrNull(i) }.toMap()
                    )
                }
                ctx.log("Read ${items.size} rows from $range")
                ctx.output(items)
            }
            else -> {
                val columns = Values.asString(params["columns"]).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (columns.isEmpty()) throw StepExecutionException(ctx.step.id, ctx.step.name, "list the columns to write")
                val values = ctx.input.map { item -> columns.map { Values.asString(Values.deepGet(item.json, it)) } }
                val response = ctx.http.request(
                    HttpRequestSpec(
                        method = "POST",
                        url = "$base/$range:append",
                        headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
                        query = mapOf(
                            "valueInputOption" to Values.asString(params["valueInputOption"], "USER_ENTERED"),
                            "insertDataOption" to "INSERT_ROWS",
                        ),
                        body = mapOf("values" to values),
                    )
                )
                if (!response.isSuccess) throw StepExecutionException(ctx.step.id, ctx.step.name, "Sheets returned HTTP ${response.status}: ${response.bodyText.take(200)}")
                ctx.log("Appended ${values.size} rows to $range")
                ctx.output(ctx.input)
            }
        }
    }
}
