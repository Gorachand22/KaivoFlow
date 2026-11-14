package com.kaivoflow.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList

/**
 * A Flow is the KaivoFlow unit of automation ("workflow" in legacy terms).
 *
 * Everything here is immutable data. Editing a Flow produces a new Flow value, which keeps
 * undo/redo, canvas diffing and Room persistence trivially correct.
 */
data class Flow(
    val id: String,
    val name: String,
    val steps: ImmutableList<StepNode> = persistentListOf(),
    val streams: ImmutableList<StreamEdge> = persistentListOf(),
    val vars: ImmutableMap<String, Any?> = persistentMapOf(),
    val settings: FlowSettings = FlowSettings(),
    val tags: ImmutableList<String> = persistentListOf(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val isFavorite: Boolean = false,
) {
    fun step(id: String?): StepNode? = if (id == null) null else steps.firstOrNull { it.id == id }

    fun stepByName(name: String): StepNode? = steps.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Streams arriving at [stepId], ordered deterministically by insertion order. */
    fun incoming(stepId: String): List<StreamEdge> = streams.filter { it.toStepId == stepId }

    fun outgoing(stepId: String): List<StreamEdge> = streams.filter { it.fromStepId == stepId }

    val triggers: List<StepNode> get() = steps.filter { it.isTrigger }

    fun withStep(step: StepNode): Flow = copy(
        steps = steps.map { if (it.id == step.id) step else it }.toImmutableList(),
        updatedAt = System.currentTimeMillis(),
    )

    fun plusStep(step: StepNode, at: Int = steps.size): Flow = copy(
        steps = steps.toMutableList().apply { add(at.coerceIn(0, size), step) }.toImmutableList(),
        updatedAt = System.currentTimeMillis(),
    )

    fun minusStep(stepId: String): Flow = copy(
        steps = steps.filterNot { it.id == stepId }.toImmutableList(),
        streams = streams.filterNot { it.fromStepId == stepId || it.toStepId == stepId }.toImmutableList(),
        settings = if (settings.errorStepId == stepId) settings.copy(errorStepId = null) else settings,
        updatedAt = System.currentTimeMillis(),
    )

    fun plusStream(stream: StreamEdge): Flow {
        require(stream.fromStepId != stream.toStepId) { "A Stream cannot loop back onto its own Step" }
        val duplicate = streams.any {
            it.fromStepId == stream.fromStepId && it.fromPort == stream.fromPort && it.toStepId == stream.toStepId
        }
        if (duplicate) return this
        return copy(streams = (streams + stream).toImmutableList(), updatedAt = System.currentTimeMillis())
    }

    fun minusStream(streamId: String): Flow = copy(
        streams = streams.filterNot { it.id == streamId }.toImmutableList(),
        updatedAt = System.currentTimeMillis(),
    )
}

/**
 * A Step ("node"). [type] refers to a [NodeSpec.id] in the node catalog.
 * [frozenOutput] is the Freeze Output (pinned data) feature: when present the Step is not
 * executed and these items are used instead.
 */
data class StepNode(
    val id: String,
    val type: String,
    val name: String,
    val params: ImmutableMap<String, Any?> = persistentMapOf(),
    val x: Float = 0f,
    val y: Float = 0f,
    val disabled: Boolean = false,
    val frozenOutput: ImmutableList<FlowData>? = null,
    val notes: String = "",
    val onError: OnErrorPolicy = OnErrorPolicy.Stop,
    val retry: RetryPolicy = RetryPolicy(),
    val timeoutMs: Long? = null,
) {
    val isTrigger: Boolean get() = type.startsWith("trigger.")

    fun param(key: String): Any? = params[key]

    fun paramString(key: String, fallback: String = ""): String = when (val v = params[key]) {
        null -> fallback
        is String -> v
        else -> v.toString()
    }

    fun paramBoolean(key: String, fallback: Boolean = false): Boolean = when (val v = params[key]) {
        is Boolean -> v
        is String -> v.equals("true", true)
        else -> fallback
    }

    fun paramNumber(key: String, fallback: Double = 0.0): Double = when (val v = params[key]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: fallback
        else -> fallback
    }
}

/**
 * A Stream (edge). [fromPort] is the zero-based output index of the source Step, which is how
 * branching Steps (IF true/false, Switch cases 0..n, AI classifier) route items.
 */
data class StreamEdge(
    val id: String,
    val fromStepId: String,
    val toStepId: String,
    val fromPort: Int = 0,
    val toPort: Int = 0,
    val label: String = "",
)

/** A single item of data flowing through a Flow execution graph. */
data class FlowData(
    val json: Map<String, Any?> = emptyMap(),
    /** Index of the input item that produced this item, used for item linking. */
    val pairedIndex: Int? = null,
    /** Set when a Step failed but was allowed to continue (onError = Continue). */
    val error: String? = null,
) {
    operator fun get(key: String): Any? = json[key]
    val isError: Boolean get() = error != null

    companion object {
        fun of(vararg pairs: Pair<String, Any?>): FlowData = FlowData(mapOf(*pairs))
    }
}

data class FlowSettings(
    /** IANA zone used for schedule Sparks and ${'$'}now. */
    val timezone: String = java.util.TimeZone.getDefault().id,
    /** Step that receives thrown errors when a Step has onError = Error Flow. */
    val errorStepId: String? = null,
    val executionTimeoutMs: Long = 15 * 60 * 1000L,
    val saveSuccessfulRuns: Boolean = true,
    val saveFailedRuns: Boolean = true,
    val maxItemsPerStep: Int = 5_000,
)

enum class OnErrorPolicy {
    /** Abort the Run and mark it failed. */
    Stop,

    /** Emit an error item and keep flowing downstream. */
    Continue,

    /** Retry using the Step's [RetryPolicy], then Stop. */
    Retry,

    /** Route the error item into the Flow's error Step. */
    ErrorFlow,
}

data class RetryPolicy(
    /** Retries are opt-in: a Step that charges money or sends a message must not repeat silently. */
    val enabled: Boolean = false,
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 1_000,
    val backoffMultiplier: Double = 2.0,
    val maxDelayMs: Long = 30_000,
) {
    fun delayFor(attempt: Int): Long {
        if (attempt <= 0) return 0
        val raw = initialDelayMs * Math.pow(backoffMultiplier, (attempt - 1).toDouble())
        return raw.toLong().coerceAtMost(maxDelayMs)
    }
}
