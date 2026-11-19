package com.kaivoflow.core.engine

import com.kaivoflow.core.model.StepNode

/**
 * A realistic sample event per Spark type. Used by Test Step (the Spark is tested with this sample, nothing is armed
 * or published) and by manual Runs that start without a payload. Pure: the caller supplies the clock and the Run id.
 */
object SparkSamples {
    fun payload(step: StepNode, now: Long, runId: String, triggerKind: String = "test"): Map<String, Any?> {
        return when (step.type) {
            "spark.webhook" -> mapOf(
                "headers" to mapOf(
                    "host" to "kaivoflow.local",
                    "user-agent" to "KaivoFlow/1.0",
                    "content-type" to "application/json",
                ),
                "params" to emptyMap<String, Any>(),
                "query" to emptyMap<String, Any>(),
                "body" to mapOf("message" to "Webhook trigger payload", "timestamp" to now),
                "path" to (step.params["path"] as? String ?: "my-flow"),
                "method" to (step.params["method"] as? String ?: "POST"),
                "timestamp" to now,
                "executionId" to runId,
            )
            "spark.notification" -> mapOf(
                "app" to "com.whatsapp",
                "appName" to "WhatsApp",
                "title" to (step.params["titleContains"] as? String ?: "Notification"),
                "text" to (step.params["textContains"] as? String ?: "Sample notification text"),
                "isOngoing" to false,
                "timestamp" to now,
                "executionId" to runId,
            )
            "spark.sms" -> mapOf(
                "from" to (step.params["senderContains"] as? String ?: "+919876543210"),
                "body" to (step.params["bodyContains"] as? String ?: "Sample SMS message body"),
                "timestamp" to now,
                "executionId" to runId,
            )
            "spark.battery" -> mapOf(
                "level" to (step.params["threshold"] as? Number ?: 15),
                "isCharging" to false,
                "temperature" to 31.5,
                "timestamp" to now,
                "executionId" to runId,
            )
            "spark.wifi" -> mapOf(
                "ssid" to (step.params["ssidContains"] as? String ?: "MyHome_WiFi"),
                "event" to (step.params["event"] as? String ?: "connected"),
                "ipAddress" to "192.168.1.100",
                "timestamp" to now,
                "executionId" to runId,
            )
            "spark.bluetooth" -> mapOf(
                "name" to (step.params["deviceNameContains"] as? String ?: "Wireless Headphones"),
                "event" to (step.params["event"] as? String ?: "connected"),
                "address" to "12:34:56:78:9A:BC",
                "timestamp" to now,
                "executionId" to runId,
            )
            "spark.geofence" -> mapOf(
                "transition" to (step.params["transition"] as? String ?: "enter"),
                "latitude" to (step.params["latitude"] as? Number ?: 20.2961),
                "longitude" to (step.params["longitude"] as? Number ?: 85.8245),
                "timestamp" to now,
                "executionId" to runId,
            )
            "spark.headset" -> mapOf(
                "event" to (step.params["event"] as? String ?: "plugged"),
                "hasMic" to true,
                "timestamp" to now,
                "executionId" to runId,
            )
            "spark.boot" -> mapOf(
                "event" to "boot_completed",
                "reason" to "reboot",
                "boot" to true,
                "uptimeMs" to 60_000L,
                "timestamp" to now,
                "executionId" to runId,
            )
            "spark.share" -> mapOf(
                "text" to "https://kaivoflow.app",
                "type" to "text",
                "timestamp" to now,
                "executionId" to runId,
            )
            else -> mapOf(
                "timestamp" to now,
                "executionId" to runId,
                "trigger" to triggerKind,
                "stepId" to step.id,
                "stepName" to step.name,
            )
        }
    }
}
