package com.kaivoflow.core.nodes.net.web

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
