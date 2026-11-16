package com.kaivoflow.core.nodes.data.items

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
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Parse a URL into fields, or build a URL from a base and query parameters. */
class UrlNode : StepExecutor {
    override val spec = NodeSpec(
        id = "transform.url",
        title = "URL tools",
        category = StepCategory.Transform,
        subtitle = "Read a link apart or build one with query parameters",
        iconKey = "external",
        params = persistentListOf(
            ParamSpec(
                key = "action",
                label = "Do",
                type = ParamType.Select,
                default = "parse",
                options = selectOptions("parse" to "Take a link apart", "build" to "Build a link"),
            ),
            textParam("url", "Link", default = "{{ \$json.url }}", showWhen = mapOf("action" to listOf("parse"))),
            textParam("base", "Base address", default = "https://example.com/search",
                showWhen = mapOf("action" to listOf("build"))),
            ParamSpec("query", "Query parameters", ParamType.KeyValue,
                showWhen = mapOf("action" to listOf("build"))),
            textParam("outputField", "Save result in", default = "url"),
        ),
        keywords = persistentListOf("url", "link", "query", "parse", "encode", "domain", "host"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val action = Values.asString(params["action"], "parse")
        val field = ctx.outputName("url")
        val resolver = com.kaivoflow.core.engine.ParamResolver(ctx.evaluator)
        val out = ctx.input.mapIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            if (action == "build") {
                val base = Values.asString(row["base"])
                if (base.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add a base address")
                val query = Values.asMap(params["query"]).entries.mapNotNull { (key, raw) ->
                    val value = Values.asString(resolver.resolveValue(raw, scope))
                    if (key.isBlank() || value.isBlank()) null
                    else "${encode(key)}=${encode(value)}"
                }
                val separator = if (base.contains('?')) "&" else "?"
                val url = base + (if (query.isEmpty()) "" else separator + query.joinToString("&"))
                item.copy(json = item.json + (field to url))
            } else {
                val text = Values.asString(row["url"])
                val parsed = runCatching { URI(text) }.getOrElse {
                    throw StepExecutionException(ctx.step.id, ctx.step.name, "\"$text\" is not a valid link")
                }
                val queryMap = LinkedHashMap<String, Any?>()
                parsed.rawQuery?.split('&')?.forEach { pair ->
                    if (pair.isBlank()) return@forEach
                    val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    queryMap[decode(k)] = decode(v)
                }
                item.copy(
                    json = item.json + (field to mapOf(
                        "scheme" to (parsed.scheme ?: ""),
                        "host" to (parsed.host ?: ""),
                        "port" to parsed.port.toDouble(),
                        "path" to (parsed.rawPath ?: ""),
                        "query" to queryMap,
                        "fragment" to (parsed.fragment ?: ""),
                    ))
                )
            }
        }
        return ctx.output(out)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
