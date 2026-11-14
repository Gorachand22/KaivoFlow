package com.kaivoflow.core.data

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowSettings
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * A deliberately limited, versioned Flow *structure* transfer format, not a backup.
 * No Step params, notes, variables, credentials or frozen outputs are retained. Names, Step types
 * and Stream labels remain and may themselves be sensitive. Imported Steps are all disabled.
 * No code, triggers or network are executed by parsing or preparing an import.
 */
object FlowStructureBundle {
    const val FORMAT = "kaivoflow.structure"
    const val VERSION = 1
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_STEPS = 500
    const val MAX_STREAMS = 2000
    private const val MAX_DEPTH = 48
    private const val MAX_NAME = 160

    class InvalidBundle(message: String) : IllegalArgumentException(message)
    data class Preview(val flow: Flow, val unsupportedTypes: List<String>)

    /** Returns a structure-only document. Inspect names/types/labels before sharing. */
    fun export(flow: Flow): ByteArray {
        validateGraph(flow)
        val safe = structureOnly(flow)
        val encoded = buildJsonObject {
            put("format", JsonPrimitive(FORMAT))
            put("version", JsonPrimitive(VERSION))
            put("flow", Json.parseToJsonElement(JsonCodec.encodeFlow(safe)))
        }.toString().toByteArray(StandardCharsets.UTF_8)
        if (encoded.size > MAX_BYTES) throw InvalidBundle("This Flow is too large to export.")
        return encoded
    }

    /** Parse and prepare a new copy for review. Does not write to the database. */
    fun preview(bytes: ByteArray, knownTypes: Set<String>): Preview {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw InvalidBundle("The file is empty or exceeds the size limit.")
        val text = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw InvalidBundle("The file is not valid UTF-8.") }
        checkDepth(text)
        val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            ?: throw InvalidBundle("This file is not a valid KaivoFlow structure.")
        if ((root["format"] as? JsonPrimitive)?.content != FORMAT) throw InvalidBundle("This is not a valid KaivoFlow structure file.")
        if ((root["version"] as? JsonPrimitive)?.content != VERSION.toString()) throw InvalidBundle("This structure file version is not supported.")
        val document = root["flow"] as? JsonObject ?: throw InvalidBundle("The Flow is missing.")
        val rawSteps = document["steps"] as? JsonArray ?: throw InvalidBundle("The Steps list is missing.")
        val rawStreams = document["streams"] as? JsonArray ?: throw InvalidBundle("The Streams list is missing.")
        if (rawSteps.size > MAX_STEPS || rawStreams.size > MAX_STREAMS) throw InvalidBundle("This Flow exceeds the Step or Stream limit.")
        fun requiredText(obj: JsonObject, key: String) {
            val p = obj[key] as? JsonPrimitive
            if (p == null || !p.isString || p.content.isBlank() || p.content.length > MAX_NAME) throw InvalidBundle("A required Flow field is invalid.")
        }
        requiredText(document, "id")
        requiredText(document, "name")
        rawSteps.forEach {
            val obj = it as? JsonObject ?: throw InvalidBundle("A Step is malformed.")
            requiredText(obj, "id"); requiredText(obj, "type"); requiredText(obj, "name")
        }
        rawStreams.forEach {
            val obj = it as? JsonObject ?: throw InvalidBundle("A Stream is malformed.")
            requiredText(obj, "id"); requiredText(obj, "from"); requiredText(obj, "to")
        }
        val decoded = JsonCodec.decodeFlow(document.toString()) ?: throw InvalidBundle("The Flow could not be read.")
        if (decoded.steps.size != rawSteps.size || decoded.streams.size != rawStreams.size) throw InvalidBundle("Some Steps or Streams could not be read.")
        validateGraph(decoded)
        val copy = freshIds(structureOnly(decoded))
        return Preview(copy, copy.steps.map { it.type }.filterNot { it in knownTypes }.distinct().sorted())
    }

    private fun structureOnly(flow: Flow): Flow = flow.copy(
        vars = persistentMapOf(), tags = kotlinx.collections.immutable.persistentListOf(),
        createdAt = 0, updatedAt = 0, isFavorite = false,
        settings = FlowSettings(errorStepId = flow.settings.errorStepId),
        steps = flow.steps.map { it.copy(params = persistentMapOf(), notes = "", frozenOutput = null,
            disabled = true, onError = com.kaivoflow.core.model.OnErrorPolicy.Stop,
            retry = com.kaivoflow.core.model.RetryPolicy(), timeoutMs = null) }.toImmutableList(),
    )

    private fun freshIds(flow: Flow): Flow {
        val stepIds = flow.steps.associate { it.id to UUID.randomUUID().toString() }
        return flow.copy(
            id = UUID.randomUUID().toString(),
            steps = flow.steps.map { it.copy(id = stepIds.getValue(it.id)) }.toImmutableList(),
            streams = flow.streams.map { it.copy(id = UUID.randomUUID().toString(),
                fromStepId = stepIds.getValue(it.fromStepId), toStepId = stepIds.getValue(it.toStepId)) }.toImmutableList(),
            settings = flow.settings.copy(errorStepId = flow.settings.errorStepId?.let { stepIds.getValue(it) }),
        )
    }

    private fun validateGraph(flow: Flow) {
        if (flow.id.isBlank() || flow.id.length > MAX_NAME || flow.name.isBlank() || flow.name.length > MAX_NAME) throw InvalidBundle("Flow name or ID is invalid.")
        if (flow.steps.size > MAX_STEPS || flow.streams.size > MAX_STREAMS) throw InvalidBundle("This Flow exceeds the Step or Stream limit.")
        if (flow.steps.any { it.id.isBlank() || it.id.length > MAX_NAME || it.type.isBlank() || it.type.length > MAX_NAME || it.name.isBlank() || it.name.length > MAX_NAME || !it.x.isFinite() || !it.y.isFinite() }) throw InvalidBundle("A Step has invalid fields.")
        if (flow.steps.map { it.id }.toSet().size != flow.steps.size) throw InvalidBundle("Step IDs must be unique.")
        if (flow.streams.any { it.id.isBlank() || it.id.length > MAX_NAME || it.label.length > MAX_NAME || it.fromPort !in 0..255 || it.toPort !in 0..255 }) throw InvalidBundle("A Stream has invalid fields.")
        if (flow.streams.map { it.id }.toSet().size != flow.streams.size) throw InvalidBundle("Stream IDs must be unique.")
        val ids = flow.steps.map { it.id }.toSet()
        if (flow.settings.errorStepId != null && flow.settings.errorStepId !in ids) throw InvalidBundle("The error Step is missing.")
        if (flow.streams.any { it.fromStepId !in ids || it.toStepId !in ids || it.fromStepId == it.toStepId }) throw InvalidBundle("A Stream has a missing or invalid endpoint.")
        val degree = ids.associateWith { 0 }.toMutableMap()
        val downstream = mutableMapOf<String, MutableList<String>>()
        flow.streams.forEach { edge ->
            degree[edge.toStepId] = degree.getValue(edge.toStepId) + 1
            downstream.getOrPut(edge.fromStepId) { mutableListOf() }.add(edge.toStepId)
        }
        val queue = ArrayDeque(degree.filterValues { it == 0 }.keys)
        var visited = 0
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst(); visited++
            downstream[id].orEmpty().forEach { child ->
                val n = degree.getValue(child) - 1; degree[child] = n
                if (n == 0) queue.addLast(child)
            }
        }
        if (visited != ids.size) throw InvalidBundle("This Flow contains a cycle.")
    }

    /** Bound nesting before parsing; quoted brackets and escapes do not affect depth. */
    private fun checkDepth(text: String) {
        var depth = 0; var inString = false; var escaped = false
        for (c in text) {
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> { depth++; if (depth > MAX_DEPTH) throw InvalidBundle("The file is nested too deeply.") }
                '}', ']' -> { depth--; if (depth < 0) throw InvalidBundle("The file is malformed.") }
            }
        }
        if (inString || depth != 0) throw InvalidBundle("The file is malformed.")
    }
}
