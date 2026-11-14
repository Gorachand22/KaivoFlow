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
    val groups: ImmutableList<FlowGroup> = persistentListOf(),
    val notes: ImmutableList<StickyNote> = persistentListOf(),
    val isActive: Boolean = false,
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
        groups = groups.map { g -> g.copy(stepIds = g.stepIds.filterNot { it == stepId }.toImmutableList()) }.toImmutableList(),
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

    fun withGroup(group: FlowGroup): Flow = copy(
        groups = groups.map { if (it.id == group.id) group else it }.toImmutableList(),
        updatedAt = System.currentTimeMillis(),
    )

    fun plusGroup(group: FlowGroup): Flow = copy(
        groups = (groups + group).toImmutableList(),
        updatedAt = System.currentTimeMillis(),
    )

    fun minusGroup(groupId: String): Flow = copy(
        groups = groups.filterNot { it.id == groupId }.toImmutableList(),
        updatedAt = System.currentTimeMillis(),
    )

    fun withNote(note: StickyNote): Flow = copy(
        notes = notes.map { if (it.id == note.id) note else it }.toImmutableList(),
        updatedAt = System.currentTimeMillis(),
    )

    fun plusNote(note: StickyNote): Flow = copy(
        notes = (notes + note).toImmutableList(),
        updatedAt = System.currentTimeMillis(),
    )

    fun minusNote(noteId: String): Flow = copy(
        notes = notes.filterNot { it.id == noteId }.toImmutableList(),
        updatedAt = System.currentTimeMillis(),
    )
}

/**
 * Visual bounding container grouping multiple related Steps on Canvas.
 * [color] is a jewel tone name: "amber", "amethyst", "emerald", "sapphire", "rose", "gold".
 */
data class FlowGroup(
    val id: String,
    val name: String,
    val stepIds: ImmutableList<String> = persistentListOf(),
    val color: String = "amber",
    val description: String = "",
)

/**
 * Freeform canvas documentation note placed at world coordinates [x, y].
 * [color] is a jewel tone name: "amber", "amethyst", "emerald", "sapphire", "rose", "gold".
 */
data class StickyNote(
    val id: String,
    val title: String = "Note",
    val content: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 240f,
    val height: Float = 140f,
    val color: String = "gold",
)

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

/**
 * A reference to a file travelling through a Flow.
 *
 * The bytes never live inside [FlowData.json]. They are stored once — in app-internal storage or
 * behind an Android Storage Access Framework `content://` URI — and this small immutable handle is
 * what crosses ports. That keeps a 20 MB photo from being copied into every downstream item, keeps
 * Run records tiny, and keeps JSON logs free of base64 blobs.
 *
 * [uri] may be `content://…` (SAF document), `kaivo://files/<name>` (app-internal file),
 * `file://…` (an absolute internal path) or a bounded `data:` URI used by the pure-JVM test path.
 */
data class BinaryDataRef(
    val uri: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long,
    /** Optional hex digest (SHA-256) so a Run can prove two refs point at the same bytes. */
    val checksum: String? = null,
) {
    /** Handoff docs use this shorter name; both describe the same value. */
    val byteSize: Long get() = sizeBytes

    init {
        require(uri.isNotBlank()) { "A binary reference needs a URI or path" }
        require(fileName.isNotBlank()) { "A binary reference needs a file name" }
        require(sizeBytes >= 0) { "A binary reference cannot have a negative size" }
    }

    fun asJson(): Map<String, Any?> = mapOf(
        "uri" to uri,
        "mimeType" to mimeType,
        "fileName" to fileName,
        "sizeBytes" to sizeBytes,
        "checksum" to checksum,
    )

    companion object {
        fun fromJson(value: Any?): BinaryDataRef? {
            @Suppress("UNCHECKED_CAST")
            val map = value as? Map<String, Any?> ?: return null
            val uri = map["uri"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val name = map["fileName"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val size = (map["sizeBytes"] as? Number)?.toLong() ?: 0L
            return BinaryDataRef(
                uri = uri,
                mimeType = map["mimeType"]?.toString().orEmpty().ifBlank { "application/octet-stream" },
                fileName = name,
                sizeBytes = size.coerceAtLeast(0),
                checksum = map["checksum"]?.toString(),
            )
        }
    }
}

/** Backwards-friendly alias for the name used in the engineering handoff notes. */
typealias BinaryItem = BinaryDataRef

/** A single item of data flowing through a Flow execution graph. */
data class FlowData(
    val json: Map<String, Any?> = emptyMap(),
    /** Index of the input item that produced this item, used for item linking. */
    val pairedIndex: Int? = null,
    /** Set when a Step failed but was allowed to continue (onError = Continue). */
    val error: String? = null,
    /** Named binary attachments (photos, PDFs, audio, downloads) travelling with this item. */
    val binary: Map<String, BinaryDataRef> = emptyMap(),
) {
    operator fun get(key: String): Any? = json[key]
    val isError: Boolean get() = error != null
    val hasBinary: Boolean get() = binary.isNotEmpty()

    fun binaryRef(key: String): BinaryDataRef? = binary[key]

    /** Returns a copy with one attachment added or replaced. */
    fun withBinary(key: String, ref: BinaryDataRef): FlowData = copy(binary = binary + (key to ref))

    /** Returns a copy with several attachments merged in. */
    fun plusBinary(refs: Map<String, BinaryDataRef>): FlowData = copy(binary = binary + refs)

    /** Returns a copy with one attachment removed. */
    fun withoutBinary(key: String): FlowData = copy(binary = binary - key)

    /** Drops every attachment (used when a Run record or export must be byte-free). */
    fun withoutBinary(): FlowData = if (binary.isEmpty()) this else copy(binary = emptyMap())

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
