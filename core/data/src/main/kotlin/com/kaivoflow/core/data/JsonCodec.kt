package com.kaivoflow.core.data

import com.kaivoflow.core.model.BinaryDataRef
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.FlowGroup
import com.kaivoflow.core.model.FlowSettings
import com.kaivoflow.core.model.OnErrorPolicy
import com.kaivoflow.core.model.RetryPolicy
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StepRun
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.model.StickyNote
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hand-written JSON mapping between the domain model and storage.
 *
 * Deliberately not `@Serializable` on the domain model: the model stays free of serialization
 * plugins (so the engine module has no serialization dependency, and R8 has no generated
 * `$$serializer` classes for it to keep), and unknown fields from a newer app version are
 * preserved instead of throwing.
 */
object JsonCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }

    fun encodeFlow(flow: Flow): String = buildJsonObject {
        put("id", JsonPrimitive(flow.id))
        put("name", JsonPrimitive(flow.name))
        put("createdAt", JsonPrimitive(flow.createdAt))
        put("updatedAt", JsonPrimitive(flow.updatedAt))
        put("isFavorite", JsonPrimitive(flow.isFavorite))
        put("groupId", flow.groupId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("vars", toJson(flow.vars) ?: JsonObject(emptyMap()))
        put("settings", buildJsonObject {
            put("timezone", JsonPrimitive(flow.settings.timezone))
            put("errorStepId", flow.settings.errorStepId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("executionTimeoutMs", JsonPrimitive(flow.settings.executionTimeoutMs))
            put("saveSuccessfulRuns", JsonPrimitive(flow.settings.saveSuccessfulRuns))
            put("saveFailedRuns", JsonPrimitive(flow.settings.saveFailedRuns))
            put("maxItemsPerStep", JsonPrimitive(flow.settings.maxItemsPerStep))
        })
        put("tags", buildJsonArray { flow.tags.forEach { add(JsonPrimitive(it)) } })
        put("steps", buildJsonArray {
            flow.steps.forEach { step ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(step.id))
                        put("type", JsonPrimitive(step.type))
                        put("name", JsonPrimitive(step.name))
                        put("x", JsonPrimitive(step.x))
                        put("y", JsonPrimitive(step.y))
                        put("disabled", JsonPrimitive(step.disabled))
                        put("notes", JsonPrimitive(step.notes))
                        put("onError", JsonPrimitive(step.onError.name))
                        put("timeoutMs", step.timeoutMs?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("params", toJson(step.params) ?: JsonObject(emptyMap()))
                        put("retry", buildJsonObject {
                            put("enabled", JsonPrimitive(step.retry.enabled))
                            put("maxAttempts", JsonPrimitive(step.retry.maxAttempts))
                            put("initialDelayMs", JsonPrimitive(step.retry.initialDelayMs))
                            put("backoffMultiplier", JsonPrimitive(step.retry.backoffMultiplier))
                            put("maxDelayMs", JsonPrimitive(step.retry.maxDelayMs))
                        })
                        step.frozenOutput?.let { frozen ->
                            put("frozenOutput", buildJsonArray { frozen.forEach { add(encodeFlowData(it)) } })
                        }
                    }
                )
            }
        })
        put("streams", buildJsonArray {
            flow.streams.forEach { stream ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(stream.id))
                        put("from", JsonPrimitive(stream.fromStepId))
                        put("to", JsonPrimitive(stream.toStepId))
                        put("fromPort", JsonPrimitive(stream.fromPort))
                        put("toPort", JsonPrimitive(stream.toPort))
                        put("label", JsonPrimitive(stream.label))
                    }
                )
            }
        })
        put("isActive", JsonPrimitive(flow.isActive))
        put("groups", buildJsonArray {
            flow.groups.forEach { group ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(group.id))
                        put("name", JsonPrimitive(group.name))
                        put("color", JsonPrimitive(group.color))
                        put("description", JsonPrimitive(group.description))
                        put("stepIds", buildJsonArray { group.stepIds.forEach { add(JsonPrimitive(it)) } })
                    }
                )
            }
        })
        put("notes", buildJsonArray {
            flow.notes.forEach { note ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(note.id))
                        put("title", JsonPrimitive(note.title))
                        put("content", JsonPrimitive(note.content))
                        put("x", JsonPrimitive(note.x))
                        put("y", JsonPrimitive(note.y))
                        put("width", JsonPrimitive(note.width))
                        put("height", JsonPrimitive(note.height))
                        put("color", JsonPrimitive(note.color))
                    }
                )
            }
        })
    }.toString()

    fun decodeFlow(text: String): Flow? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        Flow(
            id = root.str("id"),
            name = root.str("name"),
            createdAt = root.long("createdAt"),
            updatedAt = root.long("updatedAt"),
            isFavorite = root.bool("isFavorite"),
            groupId = root.str("groupId").ifBlank { null },
            vars = root["vars"]?.let { fromJsonMap(it) }?.toImmutableMap() ?: kotlinx.collections.immutable.persistentMapOf(),
            tags = (root["tags"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toImmutableList()
                ?: kotlinx.collections.immutable.persistentListOf(),
            settings = (root["settings"] as? JsonObject)?.let { settings ->
                FlowSettings(
                    timezone = settings.str("timezone").ifBlank { "Asia/Kolkata" },
                    errorStepId = settings.str("errorStepId").ifBlank { null },
                    executionTimeoutMs = settings.long("executionTimeoutMs").takeIf { it > 0 } ?: 900_000L,
                    saveSuccessfulRuns = settings.bool("saveSuccessfulRuns", true),
                    saveFailedRuns = settings.bool("saveFailedRuns", true),
                    maxItemsPerStep = settings.long("maxItemsPerStep").toInt().takeIf { it > 0 } ?: 5_000,
                )
            } ?: FlowSettings(),
            steps = (root["steps"] as? JsonArray)?.mapNotNull { element ->
                val step = element as? JsonObject ?: return@mapNotNull null
                StepNode(
                    id = step.str("id"),
                    type = step.str("type"),
                    name = step.str("name"),
                    x = step.double("x").toFloat(),
                    y = step.double("y").toFloat(),
                    disabled = step.bool("disabled"),
                    notes = step.str("notes"),
                    onError = runCatching { OnErrorPolicy.valueOf(step.str("onError")) }.getOrDefault(OnErrorPolicy.Stop),
                    timeoutMs = step.long("timeoutMs").takeIf { it > 0 },
                    params = step["params"]?.let { fromJsonMap(it) }?.toImmutableMap()
                        ?: kotlinx.collections.immutable.persistentMapOf(),
                    retry = (step["retry"] as? JsonObject)?.let { retry ->
                        RetryPolicy(
                            enabled = retry.bool("enabled"),
                            maxAttempts = retry.long("maxAttempts").toInt().coerceAtLeast(1),
                            initialDelayMs = retry.long("initialDelayMs"),
                            backoffMultiplier = retry.double("backoffMultiplier").takeIf { it > 0 } ?: 2.0,
                            maxDelayMs = retry.long("maxDelayMs").takeIf { it > 0 } ?: 30_000,
                        )
                    } ?: RetryPolicy(),
                    frozenOutput = (step["frozenOutput"] as? JsonArray)?.map { decodeFlowData(it) }
                        ?.toImmutableList(),
                )
            }?.toImmutableList() ?: kotlinx.collections.immutable.persistentListOf(),
            streams = (root["streams"] as? JsonArray)?.mapNotNull { element ->
                val stream = element as? JsonObject ?: return@mapNotNull null
                StreamEdge(
                    id = stream.str("id"),
                    fromStepId = stream.str("from"),
                    toStepId = stream.str("to"),
                    fromPort = stream.long("fromPort").toInt(),
                    toPort = stream.long("toPort").toInt(),
                    label = stream.str("label"),
                )
            }?.toImmutableList() ?: kotlinx.collections.immutable.persistentListOf(),
            isActive = root.bool("isActive"),
            groups = (root["groups"] as? JsonArray)?.mapNotNull { element ->
                val group = element as? JsonObject ?: return@mapNotNull null
                FlowGroup(
                    id = group.str("id"),
                    name = group.str("name").ifBlank { "Group" },
                    color = group.str("color").ifBlank { "amber" },
                    description = group.str("description"),
                    stepIds = (group["stepIds"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toImmutableList()
                        ?: kotlinx.collections.immutable.persistentListOf(),
                )
            }?.toImmutableList() ?: kotlinx.collections.immutable.persistentListOf(),
            notes = (root["notes"] as? JsonArray)?.mapNotNull { element ->
                val note = element as? JsonObject ?: return@mapNotNull null
                StickyNote(
                    id = note.str("id"),
                    title = note.str("title").ifBlank { "Note" },
                    content = note.str("content"),
                    x = note.double("x").toFloat(),
                    y = note.double("y").toFloat(),
                    width = note.double("width").toFloat().takeIf { it > 0 } ?: 240f,
                    height = note.double("height").toFloat().takeIf { it > 0 } ?: 140f,
                    color = note.str("color").ifBlank { "gold" },
                )
            }?.toImmutableList() ?: kotlinx.collections.immutable.persistentListOf(),
        )
    }.getOrNull()

    fun encodeRun(run: RunRecord): String = buildJsonObject {
        put("id", JsonPrimitive(run.id))
        put("flowId", JsonPrimitive(run.flowId))
        put("flowName", JsonPrimitive(run.flowName))
        put("status", JsonPrimitive(run.status.name))
        put("startedAt", JsonPrimitive(run.startedAt))
        put("finishedAt", run.finishedAt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("trigger", JsonPrimitive(run.trigger))
        put("error", run.error?.let { JsonPrimitive(it) } ?: JsonNull)
        put("steps", buildJsonArray {
            run.stepRuns.forEach { stepRun ->
                add(
                    buildJsonObject {
                        put("stepId", JsonPrimitive(stepRun.stepId))
                        put("name", JsonPrimitive(stepRun.stepName))
                        put("status", JsonPrimitive(stepRun.status.name))
                        put("startedAt", JsonPrimitive(stepRun.startedAt))
                        put("finishedAt", stepRun.finishedAt?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("itemsIn", JsonPrimitive(stepRun.itemsIn))
                        put("itemsOut", JsonPrimitive(stepRun.itemsOut))
                        put("error", stepRun.error?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("attempts", JsonPrimitive(stepRun.attempts))
                        put("output", buildJsonArray {
                            stepRun.output.take(20).forEach { data ->
                                add(encodeFlowData(data))
                            }
                        })
                    }
                )
            }
        })
    }.toString()

    fun decodeRun(text: String): RunRecord? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        RunRecord(
            id = root.str("id"),
            flowId = root.str("flowId"),
            flowName = root.str("flowName"),
            status = runCatching { RunStatus.valueOf(root.str("status")) }.getOrDefault(RunStatus.Failed),
            startedAt = root.long("startedAt"),
            finishedAt = root.long("finishedAt").takeIf { it > 0 },
            trigger = root.str("trigger"),
            error = root.str("error").ifBlank { null },
            stepRuns = (root["steps"] as? JsonArray)?.mapNotNull { element ->
                val step = element as? JsonObject ?: return@mapNotNull null
                StepRun(
                    stepId = step.str("stepId"),
                    stepName = step.str("name"),
                    status = runCatching { StepRunStatus.valueOf(step.str("status")) }.getOrDefault(StepRunStatus.Skipped),
                    startedAt = step.long("startedAt"),
                    finishedAt = step.long("finishedAt").takeIf { it > 0 },
                    itemsIn = step.long("itemsIn").toInt(),
                    itemsOut = step.long("itemsOut").toInt(),
                    error = step.str("error").ifBlank { null },
                    attempts = step.long("attempts").toInt().coerceAtLeast(1),
                    output = (step["output"] as? JsonArray)?.map { decodeFlowData(it) }
                        ?.toImmutableList() ?: kotlinx.collections.immutable.persistentListOf(),
                )
            }?.toImmutableList() ?: kotlinx.collections.immutable.persistentListOf(),
        )
    }.getOrNull()

    // ---- FlowData (json + binary attachments) ------------------------------------------

    /**
     * Encodes one data item. Older documents stored the bare json object; current documents store
     * a wrapper so binary attachments, item pairing and error flags all survive a round trip.
     */
    fun encodeFlowData(data: FlowData): JsonObject = buildJsonObject {
        put("json", toJson(data.json) ?: JsonObject(emptyMap()))
        if (data.binary.isNotEmpty()) {
            put("binary", buildJsonObject {
                data.binary.forEach { (key, ref) -> put(key, toJson(ref.asJson()) ?: JsonNull) }
            })
        }
        data.pairedIndex?.let { put("pairedIndex", JsonPrimitive(it)) }
        data.error?.let { put("error", JsonPrimitive(it)) }
    }

    /** Reads both the current wrapped format and the legacy bare-json format. */
    fun decodeFlowData(element: JsonElement): FlowData {
        val obj = element as? JsonObject ?: return FlowData()
        if (obj["json"] == null) return FlowData(fromJsonMap(element) ?: emptyMap())
        val json = (obj["json"] as? JsonObject)?.let { fromJsonMap(it) } ?: emptyMap()
        val binary = (obj["binary"] as? JsonObject)?.entries?.mapNotNull { (key, value) ->
            BinaryDataRef.fromJson(fromJson(value))?.let { key to it }
        }?.toMap() ?: emptyMap()
        return FlowData(
            json = json,
            pairedIndex = (obj["pairedIndex"] as? JsonPrimitive)?.doubleOrNull?.toInt(),
            error = (obj["error"] as? JsonPrimitive)?.content?.takeUnless { it == "null" },
            binary = binary,
        )
    }

    // ---- JSON-ish value <-> JsonElement -------------------------------------------------

    fun toJson(value: Any?): JsonElement? = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value.toDouble())
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to (toJson(it.value) ?: JsonNull) })
        is Iterable<*> -> JsonArray(value.map { toJson(it) ?: JsonNull })
        is Array<*> -> JsonArray(value.map { toJson(it) ?: JsonNull })
        else -> JsonPrimitive(value.toString())
    }

    fun fromJsonMap(element: JsonElement): Map<String, Any?>? = when (element) {
        is JsonObject -> element.mapValues { fromJson(it.value) }
        else -> null
    }

    fun fromJson(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.mapValues { fromJson(it.value) }
        is JsonArray -> element.map { fromJson(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" -> true
            element.content == "false" -> false
            element.content == "null" -> null
            element.booleanOrNull != null -> element.booleanOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            else -> element.content
        }
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.takeUnless { it == "null" } ?: ""

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L

    private fun JsonObject.double(key: String): Double =
        (this[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0

    private fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean =
        (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content == "true" else it.booleanOrNull } ?: fallback
}
