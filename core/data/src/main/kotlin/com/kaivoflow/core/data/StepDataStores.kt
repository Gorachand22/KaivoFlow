package com.kaivoflow.core.data

import com.kaivoflow.core.engine.stepdata.CapturedExecution
import com.kaivoflow.core.engine.stepdata.CapturedPort
import com.kaivoflow.core.engine.stepdata.Direction
import com.kaivoflow.core.engine.stepdata.ItemFilter
import com.kaivoflow.core.engine.stepdata.ItemPage
import com.kaivoflow.core.engine.stepdata.PinPersistence
import com.kaivoflow.core.engine.stepdata.PinSource
import com.kaivoflow.core.engine.stepdata.PinnedData
import com.kaivoflow.core.engine.stepdata.PortSnapshot
import com.kaivoflow.core.engine.stepdata.RunDataMeta
import com.kaivoflow.core.engine.stepdata.SearchResult
import com.kaivoflow.core.engine.stepdata.StepDataStore
import com.kaivoflow.core.engine.stepdata.StepExecutionKey
import com.kaivoflow.core.engine.stepdata.StepExecutionRecord
import com.kaivoflow.core.engine.stepdata.StoredExecutionInfo
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.SchemaInference
import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.core.model.StepRunStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * What [StepDataStoreImpl] needs from a database. Room backs it in the app ([RoomStepDataAccess]); tests
 * use [InMemoryStepDataAccess], which follows the same rules (delete cascades from execution to ports).
 */
interface StepDataAccess {
    suspend fun write(execution: StepExecutionEntity, ports: List<StepPortDataEntity>)
    fun observeExecutions(flowId: String, stepId: String, limit: Int): Flow<List<StepExecutionEntity>>
    suspend fun execution(runId: String, stepId: String, runIndex: Int): StepExecutionEntity?
    suspend fun latest(flowId: String, stepId: String): StepExecutionEntity?
    suspend fun portMetas(runId: String, stepId: String, runIndex: Int): List<StepPortMeta>
    suspend fun port(runId: String, stepId: String, runIndex: Int, direction: String, port: Int): StepPortDataEntity?
    suspend fun deleteRun(runId: String)
    suspend fun usage(): List<StoredExecutionRow>
    suspend fun runMetas(): List<RunMetaRow>
}

/** Map-backed [StepDataAccess] with the same semantics as the Room tables. Thread-safe by a single lock. */
class InMemoryStepDataAccess : StepDataAccess {
    private val lock = Any()
    private val executions = LinkedHashMap<Triple<String, String, Int>, StepExecutionEntity>()
    private val ports = LinkedHashMap<List<Any>, StepPortDataEntity>()
    private val version = MutableStateFlow(0)

    override suspend fun write(execution: StepExecutionEntity, ports: List<StepPortDataEntity>) {
        synchronized(lock) {
            val key = Triple(execution.runId, execution.stepId, execution.runIndex)
            removePorts(key)
            executions[key] = execution // REPLACE
            ports.forEach { this.ports[listOf(it.runId, it.stepId, it.runIndex, it.direction, it.port)] = it }
        }
        version.value++
    }

    private fun removePorts(key: Triple<String, String, Int>) =
        ports.keys.removeAll { it[0] == key.first && it[1] == key.second && it[2] == key.third }

    private fun sortedFor(flowId: String, stepId: String) = synchronized(lock) {
        executions.values.filter { it.flowId == flowId && it.stepId == stepId }
            .sortedWith(compareByDescending<StepExecutionEntity> { it.startedAt }.thenByDescending { it.runIndex })
    }

    override fun observeExecutions(flowId: String, stepId: String, limit: Int): Flow<List<StepExecutionEntity>> =
        version.map { sortedFor(flowId, stepId).take(limit) }

    override suspend fun execution(runId: String, stepId: String, runIndex: Int) =
        synchronized(lock) { executions[Triple(runId, stepId, runIndex)] }

    override suspend fun latest(flowId: String, stepId: String) = sortedFor(flowId, stepId).firstOrNull()

    override suspend fun portMetas(runId: String, stepId: String, runIndex: Int): List<StepPortMeta> = synchronized(lock) {
        ports.values.filter { it.runId == runId && it.stepId == stepId && it.runIndex == runIndex }
            .sortedWith(compareBy({ it.direction }, { it.port }))
            .map {
                StepPortMeta(it.runId, it.stepId, it.runIndex, it.direction, it.port, it.label, it.itemCount, it.storedCount, it.bytes,
                    it.truncationJson, it.schemaJson, it.parentsJson)
            }
    }

    override suspend fun port(runId: String, stepId: String, runIndex: Int, direction: String, port: Int) =
        synchronized(lock) { ports[listOf(runId, stepId, runIndex, direction, port)] }

    override suspend fun deleteRun(runId: String) {
        synchronized(lock) {
            executions.keys.removeAll { it.first == runId }
            ports.keys.removeAll { it[0] == runId }
        }
        version.value++
    }

    override suspend fun usage(): List<StoredExecutionRow> = synchronized(lock) {
        executions.values.map { e ->
            val bytes = ports.values.filter { it.runId == e.runId && it.stepId == e.stepId && it.runIndex == e.runIndex }.sumOf { it.bytes }
            StoredExecutionRow(e.runId, e.stepId, e.flowId, e.startedAt, e.pinned, bytes)
        }
    }

    override suspend fun runMetas(): List<RunMetaRow> = synchronized(lock) {
        executions.values.groupBy { it.runId }.map { (id, list) ->
            RunMetaRow(id, list.first().flowId, list.minOf { it.startedAt }, list.any { it.manual }, list.any { it.status == "Failed" })
        }
    }

    /** Number of stored port rows (tests). */
    val portRowCount: Int get() = synchronized(lock) { ports.size }
    val executionCount: Int get() = synchronized(lock) { executions.size }

    /** Every stored blob concatenated (tests grep it for secrets). */
    fun allBlobBytes(): ByteArray = synchronized(lock) { ports.values.flatMap { it.itemsBlob.asList() + it.offsetsBlob.asList() }.toByteArray() }
}

/** The [StepDataStore] on top of any [StepDataAccess]: encoding, paging, schema, search. */
class StepDataStoreImpl(
    private val access: StepDataAccess,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StepDataStore {

    override suspend fun write(execution: CapturedExecution) = withContext(io) {
        val r = execution.record
        val key = r.key
        val entity = StepExecutionEntity(
            runId = key.runId, stepId = key.stepId, runIndex = key.runIndex, flowId = r.flowId, status = r.status.name,
            startedAt = r.startedAt, finishedAt = r.finishedAt, attempts = r.attempts, errorJson = StepDataCodec.errorToJson(r.error),
            pinned = r.pinned, manual = r.manual, dataDropped = r.dataDropped,
        )
        val rows = ArrayList<StepPortDataEntity>()
        execution.inputs.forEach { rows += portRow(key, Direction.In, it) }
        execution.outputs.forEach { rows += portRow(key, Direction.Out, it) }
        access.write(entity, rows)
    }

    private fun portRow(key: StepExecutionKey, dir: Direction, p: CapturedPort): StepPortDataEntity {
        val blob = StepDataCodec.encodeItems(p.items, p.originalIndices)
        val s = p.snapshot
        return StepPortDataEntity(
            runId = key.runId, stepId = key.stepId, runIndex = key.runIndex, direction = dir.db, port = s.port, label = s.label,
            itemCount = s.itemCount, storedCount = s.storedCount, bytes = s.bytes,
            truncationJson = StepDataCodec.truncationToJson(s.truncation), schemaJson = StepDataCodec.schemaToJson(s.schema),
            parentsJson = StepDataCodec.parentsToJson(s.parents), itemsBlob = blob.items, offsetsBlob = blob.offsets,
        )
    }

    private suspend fun record(e: StepExecutionEntity): StepExecutionRecord {
        val metas = access.portMetas(e.runId, e.stepId, e.runIndex)
        fun snaps(dir: Direction) = metas.filter { it.direction == dir.db }.map {
            PortSnapshot(
                port = it.port, label = it.label, itemCount = it.itemCount, storedCount = it.storedCount, bytes = it.bytes,
                truncation = StepDataCodec.truncationFromJson(it.truncationJson), schema = StepDataCodec.schemaFromJson(it.schemaJson),
                parents = StepDataCodec.parentsFromJson(it.parentsJson),
            )
        }
        return StepExecutionRecord(
            key = StepExecutionKey(e.runId, e.stepId, e.runIndex), flowId = e.flowId,
            status = runCatching { StepRunStatus.valueOf(e.status) }.getOrDefault(StepRunStatus.Failed),
            startedAt = e.startedAt, finishedAt = e.finishedAt, attempts = e.attempts, inputs = snaps(Direction.In), outputs = snaps(Direction.Out),
            error = StepDataCodec.errorFromJson(e.errorJson), pinned = e.pinned, manual = e.manual, dataDropped = e.dataDropped,
        )
    }

    override fun observeExecutions(flowId: String, stepId: String, limit: Int): Flow<List<StepExecutionRecord>> =
        access.observeExecutions(flowId, stepId, limit).map { list -> list.map { record(it) } }

    override suspend fun summary(key: StepExecutionKey): StepExecutionRecord? = withContext(io) {
        access.execution(key.runId, key.stepId, key.runIndex)?.let { record(it) }
    }

    override suspend fun latestKey(flowId: String, stepId: String): StepExecutionKey? = withContext(io) {
        access.latest(flowId, stepId)?.let { StepExecutionKey(it.runId, it.stepId, it.runIndex) }
    }

    private suspend fun load(key: StepExecutionKey, dir: Direction, port: Int): Pair<StepPortDataEntity, PortBlob>? {
        val row = access.port(key.runId, key.stepId, key.runIndex, dir.db, port) ?: return null
        return row to PortBlob(row.itemsBlob, row.offsetsBlob)
    }

    override suspend fun page(
        key: StepExecutionKey, direction: Direction, port: Int, offset: Int, limit: Int, filter: ItemFilter?,
    ): ItemPage = withContext(io) {
        val (row, blob) = load(key, direction, port) ?: return@withContext ItemPage.Empty
        val trunc = StepDataCodec.truncationFromJson(row.truncationJson)
        val from = offset.coerceAtLeast(0)
        if (filter == null || filter.isEmpty) {
            val items = blob.range(from, limit)
            ItemPage(items.map { it.item }, items.map { it.original }, from, blob.itemCount, blob.itemCount, row.itemCount, trunc)
        } else {
            val all = blob.matching(filter)
            val slice = all.drop(from).take(limit.coerceAtLeast(0))
            ItemPage(slice.map { it.item }, slice.map { it.original }, from, blob.itemCount, all.size, row.itemCount, trunc)
        }
    }

    override suspend fun schema(key: StepExecutionKey, direction: Direction, port: Int): SchemaNode = withContext(io) {
        val meta = access.portMetas(key.runId, key.stepId, key.runIndex).firstOrNull { it.direction == direction.db && it.port == port }
        StepDataCodec.schemaFromJson(meta?.schemaJson) ?: SchemaInference.infer(emptySequence())
    }

    override suspend fun search(key: StepExecutionKey, direction: Direction, port: Int, query: String, limit: Int): SearchResult =
        withContext(io) {
            val (_, blob) = load(key, direction, port) ?: return@withContext SearchResult(IntArray(0), 0)
            val all = blob.matching(ItemFilter(query = query))
            SearchResult(all.take(limit.coerceAtLeast(0)).map { it.index }.toIntArray(), all.size)
        }

    override suspend fun deleteRun(runId: String) = withContext(io) { access.deleteRun(runId) }

    override suspend fun usage(): List<StoredExecutionInfo> = withContext(io) {
        access.usage().map { StoredExecutionInfo(it.runId, it.stepId, it.flowId, it.startedAt, it.bytes, it.pinned) }
    }

    override suspend fun runMetas(): List<RunDataMeta> = withContext(io) {
        access.runMetas().map { RunDataMeta(it.runId, it.flowId, it.startedAt, it.manual, it.failed) }
    }
}

/** Entity and bundle encoding of [PinnedData]. A pin that cannot be read decodes to null (ignored, never a crash). */
object PinCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun portsToJson(ports: List<List<Item>>): JsonArray = buildJsonArray {
        ports.forEach { items -> add(JsonArray(items.map { JsonCodec.encodeFlowData(it.withoutBinary()) })) }
    }

    fun portsFromJson(element: kotlinx.serialization.json.JsonElement?): List<List<Item>>? =
        (element as? JsonArray)?.map { port -> (port as? JsonArray ?: return null).map { JsonCodec.decodeFlowData(it) } }

    fun toEntity(flowId: String, stepId: String, d: PinnedData) =
        StepPinEntity(flowId, stepId, portsToJson(d.ports).toString(), d.bytes, d.pinnedAt, d.source.name)

    fun fromEntity(e: StepPinEntity): PinnedData? {
        val ports = runCatching { portsFromJson(json.parseToJsonElement(e.portsJson)) }.getOrNull() ?: return null
        return PinnedData(ports, e.bytes, e.pinnedAt, runCatching { PinSource.valueOf(e.source) }.getOrDefault(PinSource.Json))
    }

    fun toBundleJson(d: PinnedData): JsonObject = JsonObject(
        mapOf(
            "ports" to portsToJson(d.ports), "bytes" to JsonPrimitive(d.bytes),
            "pinnedAt" to JsonPrimitive(d.pinnedAt), "source" to JsonPrimitive(d.source.name),
        ),
    )

    fun fromBundleJson(o: JsonObject): PinnedData? {
        val ports = portsFromJson(o["ports"]) ?: return null
        return PinnedData(
            ports = ports, bytes = (o["bytes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            pinnedAt = (o["pinnedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            source = runCatching { PinSource.valueOf((o["source"] as? JsonPrimitive)?.content.orEmpty()) }.getOrDefault(PinSource.Json),
        )
    }
}

/** Map-backed [PinPersistence] for tests and previews. */
class InMemoryPinPersistence : PinPersistence {
    private val map = LinkedHashMap<Pair<String, String>, PinnedData>()
    private val version = MutableStateFlow(0)

    override suspend fun load(flowId: String, stepId: String) = synchronized(map) { map[flowId to stepId] }
    override suspend fun save(flowId: String, stepId: String, data: PinnedData) { synchronized(map) { map[flowId to stepId] = data }; version.value++ }
    override suspend fun delete(flowId: String, stepId: String) { synchronized(map) { map.remove(flowId to stepId) }; version.value++ }
    override suspend fun all(flowId: String): Map<String, PinnedData> =
        synchronized(map) { map.filterKeys { it.first == flowId }.mapKeys { it.key.second } }
    override fun observe(flowId: String, stepId: String): Flow<PinnedData?> = version.map { synchronized(map) { map[flowId to stepId] } }
}
