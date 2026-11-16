package com.kaivoflow.core.nodes.net.web

import com.kaivoflow.core.engine.HttpRequestSpec
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
import com.kaivoflow.core.nodes.data.text.HtmlLite
import com.kaivoflow.core.nodes.data.text.XmlLite
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** Read an RSS or Atom feed into ordinary items. */
class RssNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.rss",
        title = "Feed reader",
        category = StepCategory.Saas,
        subtitle = "Read new posts from an RSS or Atom feed",
        iconKey = "rssFeed",
        params = persistentListOf(
            textParam("url", "Feed address", default = "{{ \$json.url }}", required = true),
            ParamSpec("limit", "Maximum posts", ParamType.Number, default = 25),
            ParamSpec("timeoutSeconds", "Timeout (seconds)", ParamType.Number, default = 20),
        ),
        keywords = persistentListOf("rss", "atom", "feed", "news", "posts", "blog", "subscribe", "xml"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val url = ctx.resolveText(params["url"], 0)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "the feed address must start with http:// or https://")
        }
        val timeout = (Values.asDouble(params["timeoutSeconds"]) ?: 20.0).coerceIn(1.0, 120.0).toLong() * 1000L
        val response = ctx.http.request(
            HttpRequestSpec(
                method = "GET",
                url = url,
                bodyKind = HttpRequestSpec.BodyKind.None,
                headers = mapOf("Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml"),
                timeoutMs = timeout,
            )
        )
        if (!response.isSuccess) throw StepExecutionException(ctx.step.id, ctx.step.name, "the feed answered ${response.status}")
        val root = Values.asMap(XmlLite.parse(response.bodyText) ?: emptyMap<String, Any?>())
        val channel = Values.asMap(Values.deepGet(root, "rss.channel"))
            .ifEmpty { Values.asMap(root["feed"]) }
        val rawItems = channel["item"] ?: channel["entry"] ?: root["item"] ?: root["entry"] ?: emptyList<Any?>()
        val items = Values.asList(rawItems).mapNotNull { entry ->
            val row = Values.asMap(entry)
            if (row.isEmpty()) null else mapOf(
                "title" to text(row["title"]),
                "link" to link(row["link"]),
                "summary" to text(row["description"] ?: row["summary"] ?: row["content"]),
                "published" to text(row["pubDate"] ?: row["published"] ?: row["updated"]),
                "id" to text(row["guid"] ?: row["id"]),
            )
        }
        val limit = (Values.asDouble(params["limit"]) ?: 25.0).toInt().coerceIn(1, 200)
        ctx.log("Read ${items.size} feed entries.")
        return ctx.output(items.take(limit).map { FlowData(it) })
    }

    private fun text(value: Any?): String = when (value) {
        is Map<*, *> -> HtmlLite.text(Values.asString(Values.asMap(value)["#text"] ?: Values.asMap(value)["text"]))
        else -> HtmlLite.text(Values.asString(value))
    }

    private fun link(value: Any?): String = when (value) {
        is Map<*, *> -> Values.asString(Values.asMap(value)["@href"] ?: Values.asMap(value)["#text"])
        is List<*> -> Values.asList(value).firstNotNullOfOrNull { link(it) }.orEmpty()
        else -> Values.asString(value)
    }
}
