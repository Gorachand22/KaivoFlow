package com.kaivoflow.core.nodes

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
import kotlinx.collections.immutable.persistentListOf

/**
 * SaaS presets. Each is a thin, guided wrapper over HTTP with the awkward parts (auth shape,
 * required fields, response unwrapping) already handled — but every one still exposes the URL
 * error message a user can act on, and all of them can be replaced by the raw HTTP Step.
 */

class TelegramNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.telegram",
        title = "Send to Telegram",
        category = StepCategory.Saas,
        subtitle = "Post a message to a chat or channel",
        iconKey = "telegram",
        params = persistentListOf(
            ParamSpec("credential", "Bot credential", ParamType.Secret, required = true, hint = "Stores the bot token from @BotFather."),
            ParamSpec("chatId", "Chat ID or @channel", ParamType.Text, required = true, placeholder = "@my_channel"),
            ParamSpec("text", "Message", ParamType.Multiline, required = true, placeholder = "Today you spent {{ ${'$'}json.total }} INR"),
            ParamSpec("parseMode", "Formatting", ParamType.Select, default = "HTML",
                options = persistentListOf(ParamOption("HTML", "HTML"), ParamOption("MarkdownV2", "Markdown"), ParamOption("", "Plain text"))),
            ParamSpec("silent", "Send silently", ParamType.Boolean, default = false),
        ),
        keywords = persistentListOf("telegram", "message", "chat", "bot", "notify"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val credential = ctx.credential()
        val token = credential.bearerToken()
        if (token.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "the Telegram credential has no bot token")

        val sent = ctx.input.mapIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val text = Values.asString(row["text"])
            if (text.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "the message is empty")
            val response = ctx.http.request(
                HttpRequestSpec(
                    method = "POST",
                    url = "https://api.telegram.org/bot$token/sendMessage",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = buildMap<String, Any?> {
                        put("chat_id", Values.asString(row["chatId"]))
                        put("text", text.take(4096))
                        Values.asString(row["parseMode"]).takeIf { it.isNotBlank() }?.let { put("parse_mode", it) }
                        if (Values.asBoolean(row["silent"] ?: false)) put("disable_notification", true)
                    },
                    timeoutMs = 30_000,
                )
            )
            if (!response.isSuccess) {
                throw StepExecutionException(
                    ctx.step.id, ctx.step.name,
                    "Telegram rejected the message (HTTP ${response.status}). ${Values.asString(Values.deepGet(Values.asMap(response.body), "description"))}"
                )
            }
            item.copy(json = item.json + ("telegramMessageId" to Values.deepGet(Values.asMap(response.body), "result.message_id")))
        }
        return ctx.output(sent)
    }
}

class SlackNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.slack",
        title = "Send to Slack",
        category = StepCategory.Saas,
        subtitle = "Post to a channel with an incoming webhook",
        iconKey = "slack",
        params = persistentListOf(
            ParamSpec("webhookUrl", "Webhook URL", ParamType.Secret, required = true, hint = "Slack → Apps → Incoming Webhooks."),
            ParamSpec("text", "Message", ParamType.Multiline, required = true),
            ParamSpec("channel", "Override channel", ParamType.Text, advanced = true),
        ),
        keywords = persistentListOf("slack", "channel", "message", "notify", "webhook"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val webhook = Values.asString(params["webhookUrl"]).ifBlank {
            ctx.credential().bearerToken()
        }
        if (webhook.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add the Slack webhook URL")

        ctx.input.forEachIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val response = ctx.http.request(
                HttpRequestSpec(
                    method = "POST",
                    url = webhook,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = buildMap<String, Any?> {
                        put("text", Values.asString(row["text"]))
                        Values.asString(row["channel"]).takeIf { it.isNotBlank() }
                            ?.let { put("channel", it) }
                    },
                    timeoutMs = 30_000,
                )
            )
            if (!response.isSuccess) {
                throw StepExecutionException(ctx.step.id, ctx.step.name, "Slack rejected the message (HTTP ${response.status}): ${response.bodyText.take(200)}")
            }
        }
        return ctx.output(ctx.input)
    }
}

class DiscordNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.discord",
        title = "Send to Discord",
        category = StepCategory.Saas,
        subtitle = "Post to a channel webhook",
        iconKey = "discord",
        params = persistentListOf(
            ParamSpec("webhookUrl", "Webhook URL", ParamType.Secret, required = true),
            ParamSpec("content", "Message", ParamType.Multiline, required = true),
            ParamSpec("username", "Show as", ParamType.Text, advanced = true),
        ),
        keywords = persistentListOf("discord", "webhook", "message"),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val webhook = Values.asString(params["webhookUrl"]).ifBlank { ctx.credential().bearerToken() }
        if (webhook.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add the Discord webhook URL")
        ctx.input.forEachIndexed { index, item ->
            val row = ctx.paramsFor(index)
            val scope = ctx.scope(index)
            val response = ctx.http.request(
                HttpRequestSpec(
                    method = "POST",
                    url = webhook,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = buildMap<String, Any?> {
                        put("content", Values.asString(row["content"]).take(2000))
                        Values.asString(row["username"]).takeIf { it.isNotBlank() }?.let { put("username", it) }
                    },
                    timeoutMs = 30_000,
                )
            )
            if (!response.isSuccess) {
                throw StepExecutionException(ctx.step.id, ctx.step.name, "Discord rejected the message (HTTP ${response.status})")
            }
        }
        return ctx.output(ctx.input)
    }
}

class GoogleSheetsNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.sheets",
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

class NotionNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.notion",
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

/** Weather without an API key (Open-Meteo), used by the morning-digest starter. */
class WeatherNode : StepExecutor {
    override val spec = NodeSpec(
        id = "saas.weather",
        title = "Weather",
        category = StepCategory.Saas,
        subtitle = "Today's forecast for a city (no API key needed)",
        iconKey = "cloud",
        params = persistentListOf(
            ParamSpec("city", "City", ParamType.Text, default = "Bhubaneswar"),
            ParamSpec("latitude", "Latitude", ParamType.Number, advanced = true),
            ParamSpec("longitude", "Longitude", ParamType.Number, advanced = true),
            ParamSpec("units", "Units", ParamType.Select, default = "celsius",
                options = persistentListOf(ParamOption("celsius", "Celsius"), ParamOption("fahrenheit", "Fahrenheit"))),
        ),
        keywords = persistentListOf("weather", "forecast", "rain", "temperature", "open-meteo"),
    )

    override val requiresNetwork: Boolean get() = true

    private val knownCities = mapOf(
        "bhubaneswar" to (20.2961 to 85.8245),
        "delhi" to (28.6139 to 77.2090),
        "mumbai" to (19.0760 to 72.8777),
        "bengaluru" to (12.9716 to 77.5946),
        "hyderabad" to (17.3850 to 78.4867),
        "kolkata" to (22.5726 to 88.3639),
        "chennai" to (13.0827 to 80.2707),
        "pune" to (18.5204 to 73.8567),
        "london" to (51.5074 to -0.1278),
        "new york" to (40.7128 to -74.0060),
        "singapore" to (1.3521 to 103.8198),
        "dubai" to (25.2048 to 55.2708),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val city = Values.asString(params["city"], "Bhubaneswar")
        val explicitLat = Values.asDouble(params["latitude"])
        val explicitLon = Values.asDouble(params["longitude"])
        val fallback = knownCities[city.trim().lowercase()] ?: knownCities.getValue("bhubaneswar")
        val lat = explicitLat ?: fallback.first
        val lon = explicitLon ?: fallback.second

        // Geocoding keeps arbitrary city names working without shipping a city database.
        var resolvedName = city
        if (explicitLat == null && knownCities[city.trim().lowercase()] == null) {
            val geo = ctx.http.request(
                HttpRequestSpec(
                    method = "GET",
                    url = "https://geocoding-api.open-meteo.com/v1/search",
                    query = mapOf("name" to city, "count" to "1"),
                    failOnErrorStatus = false,
                )
            )
            val first = Values.asList(Values.deepGet(Values.asMap(geo.body), "results")).firstOrNull()
            if (first != null) {
                resolvedName = Values.asString(Values.deepGet(first, "name"))
                val resolvedLat = Values.asDouble(Values.deepGet(first, "latitude")) ?: fallback.first
                val resolvedLon = Values.asDouble(Values.deepGet(first, "longitude")) ?: fallback.second
                return fetch(ctx, params, resolvedLat, resolvedLon, resolvedName)
            }
        }
        return fetch(ctx, params, lat, lon, resolvedName)
    }

    private suspend fun fetch(ctx: StepContext, params: Map<String, Any?>, lat: Double, lon: Double, name: String): StepResult {
        val units = Values.asString(params["units"], "celsius")
        val response = ctx.http.request(
            HttpRequestSpec(
                method = "GET",
                url = "https://api.open-meteo.com/v1/forecast",
                query = mapOf(
                    "latitude" to lat.toString(),
                    "longitude" to lon.toString(),
                    "current" to "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m",
                    "daily" to "temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                    "forecast_days" to "1",
                    "timezone" to "auto",
                    "temperature_unit" to units,
                ),
            )
        )
        if (!response.isSuccess) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "the weather service returned HTTP ${response.status}")
        }
        val body = Values.asMap(response.body)
        val current = Values.asMap(body["current"])
        val daily = Values.asMap(body["daily"])
        val code = (Values.asDouble(current["weather_code"]) ?: 0.0).toInt()
        val json = mapOf(
            "city" to name,
            "temperature" to current["temperature_2m"],
            "humidity" to current["relative_humidity_2m"],
            "wind" to current["wind_speed_10m"],
            "condition" to weatherCodeLabel(code),
            "max" to Values.asList(daily["temperature_2m_max"]).firstOrNull(),
            "min" to Values.asList(daily["temperature_2m_min"]).firstOrNull(),
            "rainChance" to Values.asList(daily["precipitation_probability_max"]).firstOrNull(),
            "units" to if (units == "fahrenheit") "°F" else "°C",
        )
        return ctx.output(listOf(com.kaivoflow.core.model.FlowData(json)))
    }

    private fun weatherCodeLabel(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Unsettled"
    }
}
