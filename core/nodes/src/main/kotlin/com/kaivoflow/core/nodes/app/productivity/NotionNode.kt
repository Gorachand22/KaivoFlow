package com.kaivoflow.core.nodes.app.productivity

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.bearerToken
import kotlinx.collections.immutable.persistentListOf

class NotionNode : StepExecutor {
    override val spec = NodeSpec(
        id = "app.notion",
        title = "Save to Notion",
        category = StepCategory.Saas,
        subtitle = "Create a page in a database",
        iconKey = "notion",
        params = persistentListOf(
            ParamSpec("credential", "Notion credential", ParamType.Secret, required = true),
            ParamSpec("databaseId", "Database ID", ParamType.Text, required = true),
            ParamSpec("title", "Title", ParamType.Text, required = true, placeholder = "{{ ${'$'}json.title }}"),
            ParamSpec("content", "Page content", ParamType.Multiline, advanced = true),
            ParamSpec(
                key = "properties",
                label = "Other properties",
                type = ParamType.KeyValue,
                advanced = true,
                hint = "Property name → text value, matching your Notion database columns.",
            ),
        ),
        keywords = persistentListOf("notion", "page", "database", "notes"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val credential = ctx.credential()
        val token = credential.bearerToken()
        if (token.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add a Notion integration token")

        ctx.input.forEachIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val properties = LinkedHashMap<String, Any?>()
            properties["title"] = mapOf(
                "title" to listOf(mapOf("text" to mapOf("content" to Values.asString(row["title"]))))
            )
            Values.asMap(row["properties"]).forEach { (key, raw) ->
                val text = Values.asString(raw)
                if (text.isNotBlank()) {
                    properties[key] = mapOf("rich_text" to listOf(mapOf("text" to mapOf("content" to text))))
                }
            }
            val contentText = Values.asString(row["content"])
            val response = ctx.http.request(
                HttpRequestSpec(
                    method = "POST",
                    url = "https://api.notion.com/v1/pages",
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Content-Type" to "application/json",
                        "Notion-Version" to "2022-06-28",
                    ),
                    body = buildMap<String, Any?> {
                        put("parent", mapOf("database_id" to Values.asString(row["databaseId"])))
                        put("properties", properties)
                        if (contentText.isNotBlank()) {
                            put(
                                "children", listOf(
                                    mapOf(
                                        "object" to "block",
                                        "type" to "paragraph",
                                        "paragraph" to mapOf(
                                            "rich_text" to listOf(mapOf("type" to "text", "text" to mapOf("content" to contentText.take(1900))))
                                        ),
                                    )
                                )
                            )
                        }
                    },
                )
            )
            if (!response.isSuccess) {
                throw StepExecutionException(
                    ctx.step.id, ctx.step.name,
                    "Notion returned HTTP ${response.status}. ${Values.asString(Values.deepGet(Values.asMap(response.body), "message"))}"
                )
            }
        }
        return ctx.output(ctx.input)
    }
}
