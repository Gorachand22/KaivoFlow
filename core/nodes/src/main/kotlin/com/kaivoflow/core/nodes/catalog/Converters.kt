package com.kaivoflow.core.nodes

import com.kaivoflow.core.model.ConvertedStep
import com.kaivoflow.core.model.StepConverter

/**
 * Pure converters from retired Step ids to their replacements (taxonomy spec 6.3). Each takes the
 * old params and returns the params of the target. Keys it does not map go to `legacyParams` so
 * nothing is lost. Converters are deterministic, idempotent and free of IO; bump [version] to fix one.
 */
internal class FnConverter(
    override val id: String,
    override val from: String,
    override val to: String,
    override val version: Int = 1,
    /** Keys the converter consumed; null means every key moved into the result. */
    private val mapped: Set<String>?,
    private val fn: (Map<String, Any?>) -> Map<String, Any?>,
    private val warn: (Map<String, Any?>) -> List<String> = { emptyList() },
) : StepConverter {
    override fun convert(params: Map<String, Any?>): ConvertedStep = ConvertedStep(
        params = fn(params),
        legacyParams = if (mapped == null) emptyMap() else params.filterKeys { it !in mapped },
        warnings = warn(params),
    )
}

object StepConverters {
    private fun s(v: Any?): String? = (v as? String)?.takeIf { it.isNotBlank() }

    private val POLL_KEYS = setOf(
        "url", "method", "headers", "auth", "authHeaderName", "authToken", "authUser", "authPassword", "credential",
        "responsePath", "idPath", "stateKey", "reset", "maxItems", "maxStateEntries", "retryCount", "retryDelayMs",
        "timeoutSeconds", "ignoreHttpErrors", "outputField",
    )

    private val CITIES = mapOf(
        "bhubaneswar" to (20.2961 to 85.8245), "delhi" to (28.6139 to 77.2090), "mumbai" to (19.0760 to 72.8777),
        "bengaluru" to (12.9716 to 77.5946), "hyderabad" to (17.3850 to 78.4867), "kolkata" to (22.5726 to 88.3639),
        "chennai" to (13.0827 to 80.2707), "pune" to (18.5204 to 73.8567), "london" to (51.5074 to -0.1278),
        "new york" to (40.7128 to -74.0060), "singapore" to (1.3521 to 103.8198), "dubai" to (25.2048 to 55.2708),
    )

    internal val all: List<StepConverter> = listOf(
        FnConverter("clipboard_read_to_clipboard", "device.clipboard_read", "device.clipboard", mapped = setOf("outputField"),
            fn = { p -> mapOf("operation" to "read", "outputField" to (s(p["outputField"]) ?: "clipboard")) }),
        FnConverter("toast_to_notify", "device.toast", "device.notify", mapped = setOf("text"),
            fn = { p -> mapOf("mode" to "toast", "text" to (p["text"] ?: "")) }),
        FnConverter("wifi_info_to_network_status", "device.wifi_info", "device.network_status", mapped = setOf("outputField"),
            fn = { p -> mapOf("detail" to "wifi", "outputField" to (s(p["outputField"]) ?: "wifi")) }),
        // The seen set keeps living in the old state file, so nothing already seen is delivered twice after the upgrade.
        FnConverter("poll_to_dedupe", "logic.poll", "data.dedupe", mapped = POLL_KEYS,
            fn = { p ->
                p.filterKeys { it in POLL_KEYS } +
                    mapOf("source" to "url", "scope" to "always", "stateKey" to (s(p["stateKey"]) ?: "poll-state.txt"))
            }),
        // Edit fields hub (data.set)
        FnConverter("fields_to_set", "transform.fields", "data.set", mapped = setOf("rename", "remove", "keepOnly"),
            fn = { p -> p.filterKeys { it in setOf("rename", "remove", "keepOnly") } }),
        FnConverter("template_to_set", "transform.template", "data.set", mapped = setOf("template", "outputField"),
            fn = { p ->
                mapOf("assignments" to listOf(mapOf("name" to (s(p["outputField"]) ?: "text"), "type" to "any", "value" to (p["template"] ?: ""))))
            }),
        helperConverter("json", "transform.json"),
        helperConverter("math", "transform.math"),
        helperConverter("text", "transform.text"),
        helperConverter("url", "transform.url"),
        // The old Step replaced the items with one row; the hub adds the fields to each item (spec 3.2: data belongs in `$run`).
        FnConverter("run_info_to_set", "logic.run_info", "data.set", mapped = setOf("outputField"),
            fn = { p ->
                val ex = { key: String -> "{{ \$run.$key }}" }
                mapOf("assignments" to listOf(mapOf(
                    "name" to (s(p["outputField"]) ?: "run"), "type" to "object",
                    "value" to linkedMapOf(
                        "runId" to ex("id"), "flowId" to ex("flowId"), "flowName" to ex("flowName"), "stepId" to ex("stepId"),
                        "stepName" to ex("step"), "mode" to ex("mode"), "attempt" to ex("attempt"), "now" to ex("timestamp"),
                        "timezone" to ex("timezone"), "inputItems" to ex("inputItems"),
                    ),
                )))
            },
            warn = { listOf("Run details no longer replaces the items with one row: the details are added to every item.") }),
        // net.http presets
        FnConverter("connector_to_http", "saas.connector", "net.http", mapped = null,
            fn = { p -> mapOf("preset" to "paged") + p }),
        FnConverter("graphql_to_http", "saas.graphql", "net.http",
            mapped = setOf("endpoint", "query", "variables", "operationName", "headers", "authToken", "timeoutSeconds", "failOnErrors", "outputField"),
            fn = { p ->
                val token = s(p["authToken"])
                listOfNotNull(
                    "preset" to "graphql", "method" to "POST",
                    p["endpoint"]?.let { "url" to it }, p["query"]?.let { "graphqlQuery" to it },
                    p["variables"]?.let { "graphqlVariables" to it }, p["operationName"]?.let { "graphqlOperation" to it },
                    p["headers"]?.let { "headers" to it },
                    token?.let { "auth" to "bearer" }, token?.let { "authToken" to it },
                    p["timeoutSeconds"]?.let { "timeoutSeconds" to it }, p["failOnErrors"]?.let { "graphqlFailOnErrors" to it },
                    "outputField" to (s(p["outputField"]) ?: "data"),
                ).toMap()
            }),
        FnConverter("webdav_to_http", "saas.webdav", "net.http",
            mapped = setOf("method", "url", "body", "headers", "authToken", "downloadAsBinary", "fileName", "timeoutSeconds", "outputField"),
            fn = { p ->
                val token = s(p["authToken"])
                listOfNotNull(
                    "preset" to "webdav", "method" to (s(p["method"]) ?: "GET"),
                    p["url"]?.let { "url" to it }, p["body"]?.let { "body" to it }, p["body"]?.let { "bodyKind" to "raw" },
                    p["headers"]?.let { "headers" to it },
                    token?.let { "auth" to "bearer" }, token?.let { "authToken" to it },
                    p["downloadAsBinary"]?.let { "downloadAsBinary" to it }, p["fileName"]?.let { "fileName" to it },
                    p["timeoutSeconds"]?.let { "timeoutSeconds" to it },
                    "outputField" to (s(p["outputField"]) ?: "response"),
                ).toMap()
            }),
        FnConverter("binary_upload_to_http", "transform.binary_upload", "net.http",
            mapped = setOf("url", "method", "binaryField", "formField", "extraFields", "headers", "timeoutSeconds", "outputField"),
            fn = { p ->
                listOfNotNull(
                    "preset" to "upload", "method" to (s(p["method"]) ?: "POST"),
                    p["url"]?.let { "url" to it }, p["binaryField"]?.let { "binaryField" to it }, p["formField"]?.let { "formField" to it },
                    p["extraFields"]?.let { "extraFields" to it }, p["headers"]?.let { "headers" to it },
                    p["timeoutSeconds"]?.let { "timeoutSeconds" to it },
                    "outputField" to (s(p["outputField"]) ?: "response"),
                ).toMap()
            }),
        FnConverter("token_to_crypto", "transform.token", "security.crypto",
            mapped = setOf("action", "token", "algorithm", "secret", "payload", "ttlSeconds", "outputField"),
            fn = { p ->
                listOfNotNull(
                    "action" to "token_${s(p["action"]) ?: "decode"}",
                    p["token"]?.let { "token" to it }, p["algorithm"]?.let { "tokenAlgorithm" to it },
                    p["secret"]?.let { "tokenSecret" to it }, p["payload"]?.let { "tokenClaims" to it },
                    p["ttlSeconds"]?.let { "tokenTtlSeconds" to it },
                    "outputField" to (s(p["outputField"]) ?: "token"),
                ).toMap()
            }),
        object : StepConverter {
            override val id = "weather_to_http"
            override val from = "saas.weather"
            override val to = "net.http"
            override val version = 1
            override fun convert(params: Map<String, Any?>): ConvertedStep {
                val city = s(params["city"]) ?: "Bhubaneswar"
                val known = CITIES[city.trim().lowercase()]
                val lat = (params["latitude"] as? Number)?.toDouble() ?: known?.first ?: CITIES.getValue("bhubaneswar").first
                val lon = (params["longitude"] as? Number)?.toDouble() ?: known?.second ?: CITIES.getValue("bhubaneswar").second
                val warnings = buildList {
                    add("The weather forecast is now a plain HTTP request: the answer is the raw forecast (current, daily) instead of the short city, condition and temperature fields. Update Steps that read them.")
                    if (known == null && params["latitude"] == null) add("\"$city\" was not a known city, so the coordinates were set to Bhubaneswar. Edit latitude and longitude in the query.")
                }
                return ConvertedStep(
                    params = mapOf(
                        "preset" to "request", "method" to "GET", "url" to "https://api.open-meteo.com/v1/forecast",
                        "query" to linkedMapOf(
                            "latitude" to lat.toString(), "longitude" to lon.toString(),
                            "current" to "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m",
                            "daily" to "temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                            "forecast_days" to "1", "timezone" to "auto",
                            "temperature_unit" to (s(params["units"]) ?: "celsius"),
                        ),
                    ),
                    legacyParams = mapOf("city" to city),
                    warnings = warnings,
                )
            }
        },
        // Random value: uuid and string map to Crypto, shuffle to Sort; number and pick have no Step equivalent.
        object : StepConverter {
            override val id = "random_to_crypto"
            override val from = "transform.random"
            override val to = "security.crypto"
            override val version = 1
            override fun convert(params: Map<String, Any?>): ConvertedStep {
                val field = s(params["outputField"]) ?: "value"
                return when (s(params["kind"]) ?: "uuid") {
                    "uuid" -> ConvertedStep(mapOf("action" to "uuid", "outputField" to field))
                    "string" -> ConvertedStep(mapOf("action" to "random_string", "length" to (params["length"] ?: 24), "outputField" to field))
                    "shuffle" -> ConvertedStep(mapOf("mode" to "random"), type = "data.sort")
                    else -> ConvertedStep(
                        params = emptyMap(),
                        unconvertible = "Random number and Random pick were removed and have no direct replacement. Delete this Step or rebuild it with Code.",
                    )
                }
            }
        },
    )

    /** A helper Step (JSON, Math, Text, URL) becomes one row in the hub's `operations`; every old param moves into the row. */
    private fun helperConverter(op: String, from: String) = FnConverter(
        "${op}_to_set", from, "data.set", mapped = null,
        fn = { p -> mapOf("operations" to listOf(linkedMapOf<String, Any?>("op" to op) + p)) },
    )

    /** Old id to (new id, converter id); every entry converts at load (no public release exists yet, spec 5.7). */
    internal val merges: Map<String, Pair<String, String>> = all.associate { it.from to (it.to to it.id) }
}
