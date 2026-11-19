package com.kaivoflow.core.ai.flowedit

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap

/**
 * Ready-made Flows for the offline helper (no model configured, or the model call failed). Every id and setting
 * name here is checked against the real catalog by a golden test, so the fallback can never propose a Step the app
 * cannot run. They are proposals like any other: nothing is saved until the person applies.
 */
object OfflineFlows {
    private fun notify(id: String, name: String, title: String, body: String, y: Float) = StepNode(
        id, "device.notify", name, mapOf<String, Any?>("mode" to "notification", "title" to title, "body" to body).toImmutableMap(), 100f, y,
    )

    fun batteryAlert(newId: String, now: Long): Flow = Flow(
        id = newId, name = "Battery Alert",
        steps = persistentListOf(
            StepNode("s1", "spark.battery", "Battery Low", persistentMapOf("threshold" to 20), 100f, 100f),
            notify("s2", "Low Battery Warning", "Battery low", "Please connect your charger soon.", 260f),
        ),
        streams = persistentListOf(StreamEdge("e1", "s1", "s2")),
        createdAt = now, updatedAt = now,
    )

    fun webhookToNotification(newId: String, now: Long): Flow = Flow(
        id = newId, name = "Webhook to Notification",
        steps = persistentListOf(
            StepNode("s1", "spark.webhook", "Webhook", persistentMapOf("path" to "notify", "method" to "POST"), 100f, 100f),
            notify("s2", "Show Alert", "New webhook event", "{{ \$json.body.message }}", 260f),
        ),
        streams = persistentListOf(StreamEdge("e1", "s1", "s2")),
        createdAt = now, updatedAt = now,
    )

    /** A notification Step appended after the last Step of [flow]; null when the Flow is empty. */
    fun withNotification(flow: Flow, stepId: String, streamId: String, now: Long): Flow {
        val last = flow.steps.lastOrNull { !it.isTrigger } ?: flow.steps.lastOrNull()
        val y = (flow.steps.maxOfOrNull { it.y } ?: 0f) + 160f
        val step = notify(stepId, "Send Notification", "KaivoFlow Alert", "Trigger fired successfully", y)
        val withStep = flow.plusStep(step)
        return (if (last != null) withStep.plusStream(StreamEdge(streamId, last.id, stepId)) else withStep).copy(updatedAt = now)
    }
}
