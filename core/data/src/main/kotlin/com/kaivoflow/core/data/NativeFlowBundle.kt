package com.kaivoflow.core.data

import com.kaivoflow.core.engine.stepdata.PinnedData
import com.kaivoflow.core.engine.stepdata.StepDataPolicy
import com.kaivoflow.core.model.Flow
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The real `.kaivoflow` Flow backup format.
 *
 * Unlike the deliberately limited structure bundle, this keeps names, positions, notes, tags,
 * variables, Step parameters, retry/timeout policies and (JSON-only) frozen outputs. It is a
 * backup you can restore on another phone — with three hard safety rules:
 *
 *  1. Every credential-shaped value is removed on export and reported as a "rebind" item.
 *  2. Binary attachments are never exported: only their metadata is meaningful on the source device.
 *  3. Import re-maps every id, disables every Spark, and never schedules or runs a Flow. The
 *     caller must save the returned copy after the user explicitly confirms.
 *
 * Parsing and preparing an import is pure Kotlin: no database writes, no network, no code execution.
 */
object NativeFlowBundle {
    const val FORMAT = "kaivoflow.flow"
    const val VERSION = 1
    const val MAX_BYTES = 4 * 1024 * 1024

    /** A backup that carries pins may be larger: the pin cap of a Flow is 8 MB at most. */
    const val MAX_BYTES_WITH_PINS = 16 * 1024 * 1024
    const val MAX_STEPS = 500
    const val MAX_STREAMS = 2000
    private const val MAX_DEPTH = 48
    private const val MAX_NAME = 160
    private const val MAX_TEXT = 200_000

    class InvalidBundle(message: String) : IllegalArgumentException(message)

    data class Preview(
        val flow: Flow,
        val unsupportedTypes: List<String>,
        /** Human-readable "Step → field" hints for credentials the user must re-enter. */
        val rebind: List<String>,
        val redactedCount: Int,
        /**
         * Pinned data of the backup, keyed by the NEW Step ids, already checked against the pin caps and the
         * Flow's Steps. Empty for backups written before pins existed. The caller stores them with `PinStore.restore`.
         */
        val pins: Map<String, PinnedData> = emptyMap(),
        /** Pins in the file that were left out (unknown Step, unreadable or over a cap). */
        val droppedPins: Int = 0,
    )

    /**
     * @param secretKeysForType optional catalog lookup; when supplied, the exact `ParamType.Secret`
     * keys of a Step type are redacted in addition to the generic key hints.
     */
    fun export(
        flow: Flow,
        /** Pinned data by Step id; written as the optional `pins` field. Old readers ignore it. */
        pins: Map<String, PinnedData> = emptyMap(),
        secretKeysForType: (String) -> Set<String> = { emptySet() },
    ): ByteArray {
        validateGraph(flow)
        val rebind = mutableListOf<Map<String, String>>()
        var redactedCount = 0

        val safeSteps = flow.steps.map { step ->
            val declared = secretKeysForType(step.type)
            val params = LinkedHashMap<String, Any?>()
            step.params.forEach { (key, value) ->
                if (isSecretKey(key, declared)) {
                    if (!isBlank(value)) {
                        redactedCount++
                        rebind += mapOf("stepId" to step.id, "stepName" to step.name, "key" to key)
                    }
                    params[key] = ""
                } else {
                    params[key] = redactNested(value) { redactedCount++ }
                }
            }
            step.copy(
                params = params.toImmutableMap(),
                // A frozen photo is a local URI; only the JSON part of a pinned fixture travels.
                frozenOutput = step.frozenOutput?.map { it.withoutBinary() }?.toImmutableList(),
            )
        }.toImmutableList()

        val safe = flow.copy(
            vars = (redactNested(flow.vars) { redactedCount++ } as Map<String, Any?>).toImmutableMap(),
            steps = safeSteps,
        )

        val encoded = buildJsonObject {
            put("format", JsonPrimitive(FORMAT))
            put("version", JsonPrimitive(VERSION))
            put("exportedAt", JsonPrimitive(System.currentTimeMillis()))
            put("redacted", JsonPrimitive(redactedCount))
            put("credentialRefs", buildJsonArray {
                rebind.forEach { ref ->
                    add(buildJsonObject {
                        put("stepId", JsonPrimitive(ref.getValue("stepId")))
                        put("stepName", JsonPrimitive(ref.getValue("stepName")))
                        put("key", JsonPrimitive(ref.getValue("key")))
                    })
                }
            })
            put("flow", Json.parseToJsonElement(JsonCodec.encodeFlow(safe)))
            val stepIds = flow.steps.map { it.id }.toSet()
            val exportable = pins.filterKeys { it in stepIds }
            if (exportable.isNotEmpty()) {
                put("pins", JsonObject(exportable.mapValues { (_, d) -> PinCodec.toBundleJson(redactPin(d) { redactedCount++ }) }))
            }
        }.toString().toByteArray(StandardCharsets.UTF_8)

        if (encoded.size > (if (pins.isEmpty()) MAX_BYTES else MAX_BYTES_WITH_PINS)) throw InvalidBundle("This Flow is too large to back up.")
        return encoded
    }

    /** Cheap format sniff so the UI can accept both backup and structure files in one picker. */
    fun isNative(bytes: ByteArray): Boolean = runCatching {
        val text = decodeUtf8(bytes)
        val root = Json.parseToJsonElement(text) as? JsonObject
        (root?.get("format") as? JsonPrimitive)?.content == FORMAT
    }.getOrDefault(false)

    /** Parse and prepare a fresh copy for review. Does not write anything. */
    fun preview(bytes: ByteArray, knownTypes: Set<String>): Preview {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES_WITH_PINS) throw InvalidBundle("The file is empty or exceeds the size limit.")
        val text = decodeUtf8(bytes)
        checkDepth(text)
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw InvalidBundle("This file is not a valid KaivoFlow backup.")
        if ((root["format"] as? JsonPrimitive)?.content != FORMAT) {
            throw InvalidBundle("This is not a KaivoFlow backup file.")
        }
        if ((root["version"] as? JsonPrimitive)?.content != VERSION.toString()) {
            throw InvalidBundle("This backup version is not supported.")
        }
        if (bytes.size > MAX_BYTES && root["pins"] == null) throw InvalidBundle("The file is empty or exceeds the size limit.")
        val document = root["flow"] as? JsonObject ?: throw InvalidBundle("The Flow is missing.")
        val rawSteps = document["steps"] as? JsonArray ?: throw InvalidBundle("The Steps list is missing.")
        val rawStreams = document["streams"] as? JsonArray ?: throw InvalidBundle("The Streams list is missing.")
        if (rawSteps.size > MAX_STEPS || rawStreams.size > MAX_STREAMS) {
            throw InvalidBundle("This Flow exceeds the Step or Stream limit.")
        }
        fun requiredText(obj: JsonObject, key: String) {
            val p = obj[key] as? JsonPrimitive
            if (p == null || !p.isString || p.content.isBlank() || p.content.length > MAX_NAME) {
                throw InvalidBundle("A required Flow field is invalid.")
            }
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

        val decoded = JsonCodec.decodeFlow(document.toString())
            ?: throw InvalidBundle("The Flow could not be read.")
        if (decoded.steps.size != rawSteps.size || decoded.streams.size != rawStreams.size) {
            throw InvalidBundle("Some Steps or Streams could not be read.")
        }
        validateGraph(decoded)

        val refEntries = (root["credentialRefs"] as? JsonArray)?.toList().orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = (obj["stepName"] as? JsonPrimitive)?.content.orEmpty()
            val key = (obj["key"] as? JsonPrimitive)?.content.orEmpty()
            if (name.isBlank() || key.isBlank()) null else Triple(name, key, "${name.take(60)} → ${humanKey(key).take(60)}")
        }
        val refs = refEntries.map { it.third }.distinct().sorted()
        val rawSecretKeys = refEntries.map { it.second }.toSet()

        val redactedCount = (root["redacted"] as? JsonPrimitive)?.content?.toIntOrNull() ?: refs.size
        val idMap = HashMap<String, String>()
        val copy = freshIds(decoded, rawSecretKeys, idMap)
        val (pins, droppedPins) = readPins(root["pins"], idMap)
        return Preview(
            flow = copy,
            unsupportedTypes = copy.steps.map { it.type }.filterNot { it in knownTypes }.distinct().sorted(),
            rebind = refs,
            redactedCount = redactedCount,
            pins = pins,
            droppedPins = droppedPins,
        )
    }

    // ---- internals ----------------------------------------------------------------------

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw InvalidBundle("The file is not valid UTF-8.")
    }

    /** Secret-looking keys inside pinned items are blanked on export, like Step params. Binary never travels. */
    private fun redactPin(d: PinnedData, onRedact: () -> Unit): PinnedData = d.copy(
        ports = d.ports.map { port ->
            port.map { item ->
                @Suppress("UNCHECKED_CAST")
                val json = redactNested(item.json) { onRedact() } as Map<String, Any?>
                item.withoutBinary().copy(json = json)
            }
        },
    )

    /** Reads the optional `pins` field; anything unknown, unreadable or over the pin caps is dropped and counted. */
    private fun readPins(element: kotlinx.serialization.json.JsonElement?, idMap: Map<String, String>): Pair<Map<String, PinnedData>, Int> {
        val obj = element as? JsonObject ?: return emptyMap<String, PinnedData>() to 0
        val policy = StepDataPolicy()
        val out = LinkedHashMap<String, PinnedData>()
        var dropped = 0
        var total = 0L
        for ((oldId, value) in obj) {
            val newId = idMap[oldId]
            val data = (value as? JsonObject)?.let { PinCodec.fromBundleJson(it) }
            if (newId == null || data == null) { dropped++; continue }
            val clean = data.copy(ports = data.ports.map { p -> p.map { it.withoutBinary() } })
            val bytes = clean.ports.sumOf { p -> p.sumOf { policy.sizeOf(it) } }
            if (policy.pinVerdict(bytes, total) != com.kaivoflow.core.engine.stepdata.PinVerdict.Ok) { dropped++; continue }
            total += bytes
            out[newId] = clean.copy(bytes = bytes)
        }
        return out to dropped
    }

    private fun freshIds(flow: Flow, secretKeys: Set<String>, idMap: MutableMap<String, String> = HashMap()): Flow {
        val stepIds = flow.steps.associate { it.id to UUID.randomUUID().toString() }
        idMap.putAll(stepIds)
        val steps = flow.steps.map { step ->
            val params = LinkedHashMap<String, Any?>()
            step.params.forEach { (key, value) ->
                params[key] = if (isSecretKey(key, secretKeys)) "" else value
            }
            step.copy(
                id = stepIds.getValue(step.id),
                params = params.toImmutableMap(),
                // Sparks never auto-start on import; the user switches one on after reviewing.
                disabled = step.disabled || step.isTrigger,
                frozenOutput = step.frozenOutput?.map { it.withoutBinary() }?.toImmutableList(),
            )
        }.toImmutableList()
        return flow.copy(
            id = UUID.randomUUID().toString(),
            steps = steps,
            streams = flow.streams.map {
                it.copy(
                    id = UUID.randomUUID().toString(),
                    fromStepId = stepIds[it.fromStepId] ?: throw InvalidBundle("A Stream has a missing endpoint."),
                    toStepId = stepIds[it.toStepId] ?: throw InvalidBundle("A Stream has a missing endpoint."),
                )
            }.toImmutableList(),
            settings = flow.settings.copy(
                errorStepId = flow.settings.errorStepId?.let { stepIds[it] },
            ),
            vars = (redactNested(flow.vars) as Map<String, Any?>).toImmutableMap(),
            createdAt = 0,
            updatedAt = 0,
            isFavorite = false,
        )
    }

    private val SECRET_HINTS = listOf(
        "password", "secret", "token", "apikey", "authorization", "credential",
        "private", "otp", "cvv", "webhookurl", "accesskey", "xkey", "xauth", "bearer",
    )

    private fun isSecretKey(key: String, declared: Set<String>): Boolean {
        if (declared.any { it.equals(key, ignoreCase = true) }) return true
        val normalized = key.lowercase().replace("_", "").replace("-", "").replace(" ", "")
        return SECRET_HINTS.any { normalized.contains(it) }
    }

    private fun isBlank(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isBlank()
        is Collection<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        else -> false
    }

    private fun redactNested(value: Any?, onRedact: (String) -> Unit = {}): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) ->
            val key = k.toString()
            if (isSecretKey(key, emptySet())) {
                if (!isBlank(v)) onRedact(key)
                key to ""
            } else {
                key to redactNested(v, onRedact)
            }
        }
        is List<*> -> value.map { redactNested(it, onRedact) }
        is Array<*> -> value.map { redactNested(it, onRedact) }
        is String -> value.take(MAX_TEXT)
        else -> value
    }

    private fun humanKey(key: String): String = key
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }

    private fun validateGraph(flow: Flow) {
        if (flow.id.isBlank() || flow.id.length > MAX_NAME || flow.name.isBlank() || flow.name.length > MAX_NAME) {
            throw InvalidBundle("Flow name or ID is invalid.")
        }
        if (flow.steps.size > MAX_STEPS || flow.streams.size > MAX_STREAMS) {
            throw InvalidBundle("This Flow exceeds the Step or Stream limit.")
        }
        if (flow.steps.any {
                it.id.isBlank() || it.id.length > MAX_NAME || it.type.isBlank() ||
                    it.type.length > MAX_NAME || it.name.isBlank() || it.name.length > MAX_NAME ||
                    !it.x.isFinite() || !it.y.isFinite() || it.notes.length > MAX_TEXT
            }
        ) throw InvalidBundle("A Step has invalid fields.")
        if (flow.steps.any { step -> step.params.keys.any { it.length > MAX_NAME } }) {
            throw InvalidBundle("A Step parameter name is invalid.")
        }
        if (flow.steps.map { it.id }.toSet().size != flow.steps.size) throw InvalidBundle("Step IDs must be unique.")
        if (flow.streams.any {
                it.id.isBlank() || it.id.length > MAX_NAME || it.label.length > MAX_NAME ||
                    it.fromPort !in 0..255 || it.toPort !in 0..255
            }
        ) throw InvalidBundle("A Stream has invalid fields.")
        if (flow.streams.map { it.id }.toSet().size != flow.streams.size) throw InvalidBundle("Stream IDs must be unique.")
        val ids = flow.steps.map { it.id }.toSet()
        if (flow.settings.errorStepId != null && flow.settings.errorStepId !in ids) {
            throw InvalidBundle("The error Step is missing.")
        }
        if (flow.streams.any { it.fromStepId !in ids || it.toStepId !in ids || it.fromStepId == it.toStepId }) {
            throw InvalidBundle("A Stream has a missing or invalid endpoint.")
        }
        val degree = ids.associateWith { 0 }.toMutableMap()
        val downstream = mutableMapOf<String, MutableList<String>>()
        flow.streams.forEach { edge ->
            degree[edge.toStepId] = degree.getValue(edge.toStepId) + 1
            downstream.getOrPut(edge.fromStepId) { mutableListOf() }.add(edge.toStepId)
        }
        val queue = ArrayDeque(degree.filterValues { it == 0 }.keys)
        var visited = 0
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            visited++
            downstream[id].orEmpty().forEach { child ->
                val n = degree.getValue(child) - 1
                degree[child] = n
                if (n == 0) queue.addLast(child)
            }
        }
        if (visited != ids.size) throw InvalidBundle("This Flow contains a cycle.")
    }

    /** Bound nesting before parsing; quoted brackets and escapes do not affect depth. */
    private fun checkDepth(text: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > MAX_DEPTH) throw InvalidBundle("The file is nested too deeply.")
                }
                '}', ']' -> {
                    depth--
                    if (depth < 0) throw InvalidBundle("The file is malformed.")
                }
            }
        }
        if (inString || depth != 0) throw InvalidBundle("The file is malformed.")
    }
}
