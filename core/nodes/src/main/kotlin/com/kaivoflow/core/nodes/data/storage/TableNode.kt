package com.kaivoflow.core.nodes.data.storage

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.BinarySupport
import com.kaivoflow.core.nodes.DeviceGateway
import com.kaivoflow.core.nodes.MiniJson
import com.kaivoflow.core.nodes.NoopDeviceGateway
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolve
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.selectOptions
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** A tiny, durable table stored inside the app's private files — no server and no account. */
class TableNode(private val gateway: DeviceGateway = NoopDeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.table",
        title = "Local table",
        category = StepCategory.Logic,
        subtitle = "Remember rows between Runs on this device",
        iconKey = "storage",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "put",
                options = selectOptions(
                    "put" to "Save a row", "get" to "Read a row", "delete" to "Delete a row",
                    "list" to "List all rows", "increment" to "Add to a number",
                ),
            ),
            textParam("table", "Table name", default = "memory", required = true),
            textParam("key", "Row key", default = "{{ \$json.id }}",
                showWhen = mapOf("action" to listOf("put", "get", "delete", "increment"))),
            ParamSpec("value", "Row value (JSON)", ParamType.Json,
                showWhen = mapOf("action" to listOf("put"))),
            textParam("numberField", "Number field", default = "count",
                showWhen = mapOf("action" to listOf("increment"))),
            ParamSpec("step", "Add", ParamType.Number, default = 1,
                showWhen = mapOf("action" to listOf("increment"))),
            textParam("outputField", "Save result in", default = "row"),
        ),
        keywords = persistentListOf("table", "database", "storage", "remember", "state", "key", "value", "persistent"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val table = Values.asString(params["table"], "memory").ifBlank { "memory" }
        val action = Values.asString(params["action"], "put")
        val field = ctx.outputName("row")
        val rows = read(table).toMutableMap()
        val results = mutableListOf<FlowData>()

        if (action == "list") {
            rows.forEach { (key, value) -> results += FlowData(mapOf("key" to key, field to value)) }
            if (results.isEmpty()) results += FlowData(mapOf("key" to "", field to null, "empty" to true))
            return ctx.output(results)
        }

        val sources = ctx.input.ifEmpty { listOf(FlowData()) }
        sources.forEachIndexed { index, item ->
            val key = ctx.resolveText(params["key"], index)
            if (key.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "each row needs a key")
            when (action) {
                "put" -> {
                    val value = ctx.resolve(params["value"], index) ?: item.json
                    rows[key] = value
                    results += item.copy(json = item.json + (field to mapOf("key" to key, "value" to value)))
                }
                "get" -> {
                    val value = rows[key]
                    results += item.copy(json = item.json + mapOf(field to value, "found" to (value != null)))
                }
                "delete" -> {
                    val removed = rows.remove(key)
                    results += item.copy(json = item.json + mapOf(field to removed, "deleted" to (removed != null)))
                }
                "increment" -> {
                    val numberField = Values.asString(params["numberField"], "count").ifBlank { "count" }
                    val amount = Values.asDouble(params["step"]) ?: 1.0
                    val existing = Values.asMap(rows[key])
                    val next = (Values.asDouble(Values.deepGet(existing, numberField)) ?: 0.0) + amount
                    val updated = existing + (numberField to next)
                    rows[key] = updated
                    results += item.copy(json = item.json + (field to updated))
                }
                else -> results += item
            }
        }
        if (rows.size > MAX_ROWS) throw StepExecutionException(ctx.step.id, ctx.step.name, "a local table holds at most $MAX_ROWS rows")
        write(table, rows)
        return ctx.output(results)
    }

    private suspend fun read(table: String): Map<String, Any?> {
        val text = gateway.readFile(fileName(table)) ?: return emptyMap()
        return MiniJson.parse(text)?.let { Values.asMap(it) } ?: emptyMap()
    }

    private suspend fun write(table: String, rows: Map<String, Any?>) {
        gateway.writeFile(fileName(table), Values.stringify(rows))
    }

    private fun fileName(table: String): String =
        "kaivo_table_" + BinarySupport.safeFileName(table).removeSuffix(".json") + ".json"

    private companion object { const val MAX_ROWS = 5_000 }
}
