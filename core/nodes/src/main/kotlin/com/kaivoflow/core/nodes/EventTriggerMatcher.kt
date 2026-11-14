package com.kaivoflow.core.nodes

import com.kaivoflow.core.model.StepNode

/**
 * Pure, fail-closed matching for device events before they enter a Flow.
 *
 * Every branch requires the payload fields it needs to be present and correctly typed: a broadcast
 * with a missing address, SSID or transition can never accidentally start a Flow. Regex matching is
 * deliberately disabled until a bounded-time regex engine is available — arbitrary user patterns
 * running on the notification listener can otherwise hang the process. A non-empty pattern
 * rejects the event rather than widening the filter.
 */
object EventTriggerMatcher {

    fun matches(step: StepNode, kind: String, payload: Map<String, Any?>, ownPackage: String): Boolean {
        if (step.disabled || step.type != kind) return false
        return when (kind) {
            TriggerSpecs.NOTIFICATION -> {
                val app = payload["app"] as? String ?: return false
                if (app.isBlank()) return false
                if (step.paramBoolean("ignoreOwnApp", true) && app == ownPackage) return false
                if (step.paramBoolean("ignoreOngoing", true) && payload["isOngoing"] == true) return false
                val packages = step.paramString("packageNames").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (packages.isNotEmpty() && packages.none { it == app }) return false
                if (step.paramString("textMatches").isNotBlank()) return false
                contains(payload["title"], step.paramString("titleContains")) &&
                    contains(payload["text"], step.paramString("textContains"))
            }
            TriggerSpecs.SMS -> payload["from"] is String && payload["body"] is String &&
                contains(payload["from"], step.paramString("senderContains")) &&
                contains(payload["body"], step.paramString("bodyContains"))
            TriggerSpecs.SHARE -> step.paramString("types", "text") in setOf("text", "any") && payload["text"] is String
            TriggerSpecs.BATTERY_LOW -> {
                val level = (payload["level"] as? Number)?.toDouble() ?: return false
                val threshold = step.paramNumber("threshold", 20.0)
                val isCharging = payload["isCharging"] as? Boolean ?: false
                val alsoWhenCharging = step.paramBoolean("alsoWhenCharging", false)
                (level <= threshold) || (alsoWhenCharging && isCharging)
            }
            TriggerSpecs.GEOFENCE -> {
                val transition = payload["transition"] as? String ?: return false
                if (transition != "enter" && transition != "exit") return false
                val wanted = step.paramString("transition", "enter").ifBlank { "enter" }
                if (wanted != "both" && wanted != transition) return false
                val lat = (payload["latitude"] as? Number)?.toDouble()
                val lon = (payload["longitude"] as? Number)?.toDouble()
                if (lat == null || lon == null) return false
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
                val centerLat = step.paramNumber("latitude", 0.0)
                val centerLon = step.paramNumber("longitude", 0.0)
                val radius = step.paramNumber("radiusMeters", 150.0).coerceIn(25.0, 100_000.0)
                val distance = MobileTriggerMath.distanceMeters(centerLat, centerLon, lat, lon)
                when (transition) {
                    "enter" -> distance <= radius * 1.15
                    else -> distance >= radius * 0.85
                }
            }
            TriggerSpecs.BLUETOOTH -> {
                val event = payload["event"] as? String ?: return false
                val wanted = step.paramString("event", "connected").ifBlank { "connected" }
                if (wanted != "any" && wanted != event) return false
                val address = payload["address"] as? String ?: return false
                if (address.isBlank()) return false
                val exact = step.paramString("deviceAddress")
                if (exact.isNotBlank() && !exact.equals(address, ignoreCase = true)) return false
                contains(payload["name"], step.paramString("deviceNameContains"))
            }
            TriggerSpecs.WIFI -> {
                val event = payload["event"] as? String ?: return false
                val wanted = step.paramString("event", "connected").ifBlank { "connected" }
                if (wanted != "any" && wanted != event) return false
                val ssid = payload["ssid"] as? String ?: return false
                if (ssid.isBlank() || ssid.equals("<unknown ssid>", ignoreCase = true)) return false
                contains(ssid, step.paramString("ssidContains"))
            }
            TriggerSpecs.HEADSET -> {
                val event = payload["event"] as? String ?: return false
                if (event != "plugged" && event != "unplugged") return false
                val wanted = step.paramString("event", "plugged").ifBlank { "plugged" }
                wanted == "any" || wanted == event
            }
            TriggerSpecs.BOOT -> payload["reason"] is String || payload["boot"] == true
            else -> false // Unknown events never start a Flow by accident.
        }
    }

    private fun contains(value: Any?, filter: String): Boolean {
        if (filter.isBlank()) return true
        return (value as? String)?.contains(filter.trim(), ignoreCase = true) == true
    }
}
