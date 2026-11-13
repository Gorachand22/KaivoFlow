package com.kaivoflow.core.nodes

import com.kaivoflow.core.model.StepNode

/** Pure, fail-closed matching for device events before they enter a Flow.
 *
 * Regex matching is deliberately disabled until a bounded-time regex engine is available: arbitrary
 * user patterns running on the notification listener can otherwise hang the process. A non-empty
 * pattern rejects the event, never widens the filter.
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
            else -> false // Unknown events never start a Flow by accident.
        }
    }

    private fun contains(value: Any?, filter: String): Boolean {
        if (filter.isBlank()) return true
        return (value as? String)?.contains(filter.trim(), ignoreCase = true) == true
    }
}
