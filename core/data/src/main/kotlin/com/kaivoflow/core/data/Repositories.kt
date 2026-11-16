package com.kaivoflow.core.data

import com.kaivoflow.core.engine.CredentialProvider
import com.kaivoflow.core.engine.LifecycleSnapshot
import com.kaivoflow.core.engine.OAuth2TokenManager
import com.kaivoflow.core.engine.OAuth2TokenStore
import com.kaivoflow.core.model.CredentialItem
import com.kaivoflow.core.model.CredentialRef
import com.kaivoflow.core.model.DataTable
import com.kaivoflow.core.model.DataTableColumn
import com.kaivoflow.core.model.DataTableRow
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.WorkflowGroup
import com.kaivoflow.core.model.WorkflowVariable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow as FlowStream
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/** Lightweight list row — the flows screen never needs the whole document. */
data class FlowSummary(
    val id: String,
    val name: String,
    val stepCount: Int,
    val updatedAt: Long,
    val isFavorite: Boolean,
    val lastRunStatus: String?,
    val lastRunAt: Long?,
    val groupId: String? = null,
    val isActive: Boolean = false,
)

interface FlowRepository {
    fun observeSummaries(): FlowStream<List<FlowSummary>>
    fun observeFlow(id: String): FlowStream<Flow?>
    suspend fun flow(id: String): Flow?
    suspend fun all(): List<Flow>
    suspend fun save(flow: Flow)
    suspend fun delete(id: String)
    suspend fun duplicate(id: String): Flow?
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun createEmpty(name: String = "New Flow", groupId: String? = null): Flow
    suspend fun recordRun(flowId: String, status: RunStatus, at: Long)
    suspend fun updateGroup(id: String, groupId: String?)
}

class RoomFlowRepository(
    private val flowDao: FlowDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val storageDir: java.io.File? = null,
) : FlowRepository {

    override fun observeSummaries(): FlowStream<List<FlowSummary>> =
        flowDao.observeAll().map { entities ->
            entities.map {
                val active = runCatching { JsonCodec.decodeFlow(it.flowJson)?.isActive == true }.getOrDefault(false)
                FlowSummary(
                    id = it.id,
                    name = it.name,
                    stepCount = it.stepCount,
                    updatedAt = it.updatedAt,
                    isFavorite = it.isFavorite,
                    lastRunStatus = it.lastRunStatus,
                    lastRunAt = it.lastRunAt,
                    groupId = it.groupId,
                    isActive = active,
                )
            }
        }

    override fun observeFlow(id: String): FlowStream<Flow?> =
        flowDao.observe(id).map { entity -> entity?.let { JsonCodec.decodeFlow(it.flowJson) } }

    override suspend fun flow(id: String): Flow? = withContext(io) {
        flowDao.byId(id)?.let { JsonCodec.decodeFlow(it.flowJson) }
    }

    override suspend fun all(): List<Flow> = withContext(io) {
        flowDao.all().mapNotNull { JsonCodec.decodeFlow(it.flowJson) }
    }

    override suspend fun save(flow: Flow): Unit = withContext(io) {
        val now = System.currentTimeMillis()
        val stamped = if (flow.updatedAt == 0L) flow.copy(updatedAt = now, createdAt = if (flow.createdAt == 0L) now else flow.createdAt) else flow
        val existing = flowDao.byId(stamped.id)
        val json = JsonCodec.encodeFlow(stamped)
        flowDao.upsert(
            FlowEntity(
                id = stamped.id,
                name = stamped.name,
                flowJson = json,
                updatedAt = stamped.updatedAt,
                createdAt = stamped.createdAt,
                isFavorite = stamped.isFavorite,
                stepCount = stamped.steps.size,
                lastRunStatus = existing?.lastRunStatus,
                lastRunAt = existing?.lastRunAt,
                groupId = stamped.groupId,
            ).let { base ->
                // The upsert replaces the whole row, so the lifecycle columns must be carried over, kept
                // consistent with the Phase 1 isActive flag until the publish UI uses the state machine.
                val current = existing?.lifecycleSnapshot() ?: LifecycleSnapshot()
                base.withLifecycle(LifecycleLogic.syncWithLegacyActive(current, stamped.isActive, System.currentTimeMillis()))
            }
        )
        // Sovereign file mirror in app internal storage
        runCatching {
            storageDir?.let { root ->
                val dir = root.resolve("flows").apply { mkdirs() }
                dir.resolve("${stamped.id}.json").writeText(json)
            }
        }
        Unit
    }

    override suspend fun delete(id: String): Unit = withContext(io) {
        flowDao.delete(id)
        runCatching {
            storageDir?.resolve("flows")?.resolve("$id.json")?.delete()
        }
        Unit
    }

    override suspend fun duplicate(id: String): Flow? = withContext(io) {
        val original = flow(id) ?: return@withContext null
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = "${original.name} copy",
            updatedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            isFavorite = false,
        )
        save(copy)
        copy
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) = withContext(io) {
        flowDao.setFavorite(id, favorite)
    }

    override suspend fun createEmpty(name: String, groupId: String?): Flow = withContext(io) {
        val now = System.currentTimeMillis()
        Flow(id = UUID.randomUUID().toString(), name = name, groupId = groupId, createdAt = now, updatedAt = now)
    }

    override suspend fun updateGroup(id: String, groupId: String?) = withContext(io) {
        flowDao.updateGroup(id, groupId)
    }

    override suspend fun recordRun(flowId: String, status: RunStatus, at: Long) = withContext(io) {
        flowDao.updateLastRun(flowId, status.name, at)
    }
}

interface RunRepository {
    fun observeRecent(limit: Int = 100): FlowStream<List<RunRecord>>
    fun observeForFlow(flowId: String, limit: Int = 50): FlowStream<List<RunRecord>>
    suspend fun save(run: RunRecord)
    suspend fun clear()
    suspend fun pruneOlderThan(days: Int)
}

class RoomRunRepository(
    private val runDao: RunDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RunRepository {

    override fun observeRecent(limit: Int): FlowStream<List<RunRecord>> =
        runDao.observeRecent(limit).map { list -> list.mapNotNull { JsonCodec.decodeRun(it.runJson) } }

    override fun observeForFlow(flowId: String, limit: Int): FlowStream<List<RunRecord>> =
        runDao.observeForFlow(flowId, limit).map { list -> list.mapNotNull { JsonCodec.decodeRun(it.runJson) } }

    override suspend fun save(run: RunRecord) = withContext(io) {
        runDao.upsert(
            RunEntity(
                id = run.id,
                flowId = run.flowId,
                flowName = run.flowName,
                status = run.status.name,
                startedAt = run.startedAt,
                finishedAt = run.finishedAt,
                trigger = run.trigger,
                error = run.error,
                itemsOut = run.itemsOut,
                runJson = JsonCodec.encodeRun(run),
            )
        )
    }

    override suspend fun clear() = withContext(io) { runDao.clear() }

    override suspend fun pruneOlderThan(days: Int) = withContext(io) {
        runDao.deleteOlderThan(System.currentTimeMillis() - days * 86_400_000L)
    }
}

/** Credentials: encrypted at rest, exposed to the engine only through [CredentialProvider]. */
interface CredentialRepository : CredentialProvider {
    fun observeRefs(): FlowStream<List<CredentialRef>>
    fun observeCredentials(): FlowStream<List<CredentialItem>>
    suspend fun getFields(id: String): Map<String, String>?
    suspend fun save(id: String?, type: String, label: String, fields: Map<String, String>): String
    suspend fun delete(id: String)
}

class EncryptedCredentialRepository(
    private val credentialDao: CredentialDao,
    private val cryptoBox: CryptoBox = CryptoBox(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val tokenManager: OAuth2TokenManager? = null,
) : CredentialRepository {

    override suspend fun resolve(ref: String): Map<String, String> = withContext(io) {
        val entity = credentialDao.byId(ref) ?: throw IllegalStateException("That credential was deleted. Pick another one.")
        val fields = decrypt(entity.cipherText)
        tokenManager?.refreshIfNeeded(ref, entity.type, fields) ?: fields
    }

    private fun decrypt(cipherText: String): Map<String, String> {
        val json = cryptoBox.decrypt(cipherText)
            ?: throw IllegalStateException("This credential could not be unlocked on this device. Please re-enter it.")
        val map = JsonCodec.fromJsonMap(kotlinx.serialization.json.Json.parseToJsonElement(json)) ?: emptyMap()
        return map.mapValues { it.value?.toString().orEmpty() }
    }

    override suspend fun list(): List<CredentialRef> = withContext(io) {
        credentialDao.all().map { CredentialRef(it.id, it.type, it.label) }
    }

    override fun observeRefs(): FlowStream<List<CredentialRef>> =
        credentialDao.observeAll().map { list -> list.map { CredentialRef(it.id, it.type, it.label) } }

    override fun observeCredentials(): FlowStream<List<CredentialItem>> =
        credentialDao.observeAll().map { list ->
            list.map { entity ->
                CredentialItem(
                    id = entity.id,
                    type = entity.type,
                    name = entity.label,
                    createdAt = entity.createdAt,
                    updatedAt = entity.createdAt,
                    usedInFlowCount = 0,
                )
            }
        }.flowOn(io)

    override suspend fun getFields(id: String): Map<String, String>? = withContext(io) {
        val entity = credentialDao.byId(id) ?: return@withContext null
        runCatching { decrypt(entity.cipherText) }.getOrNull()
    }

    override suspend fun save(id: String?, type: String, label: String, fields: Map<String, String>): String =
        withContext(io) {
            val credentialId = id ?: UUID.randomUUID().toString()
            val payload = JsonCodec.toJson(fields)?.toString() ?: "{}"
            val cipherText = cryptoBox.encrypt(payload)
                ?: throw IllegalStateException("This device's secure storage refused to encrypt the credential.")
            credentialDao.upsert(
                CredentialEntity(
                    id = credentialId,
                    type = type,
                    label = label,
                    cipherText = cipherText,
                    createdAt = System.currentTimeMillis(),
                )
            )
            credentialId
        }

    override suspend fun delete(id: String) = withContext(io) { credentialDao.delete(id) }
}

/**
 * Sovereign Data Tables Repository.
 * Provides durable, typed tabular data storage directly on the phone.
 */
interface DataTableRepository {
    fun observeTables(): FlowStream<List<DataTable>>
    suspend fun tableById(id: String): DataTable?
    suspend fun createTable(name: String, description: String = "", columns: List<DataTableColumn>): String
    suspend fun deleteTable(id: String)

    fun observeRows(tableId: String): FlowStream<List<DataTableRow>>
    suspend fun rowsForTable(tableId: String): List<DataTableRow>
    suspend fun insertRow(tableId: String, data: Map<String, Any?>): String
    suspend fun deleteRow(rowId: String)
}

class RoomDataTableRepository(
    private val dataTableDao: DataTableDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DataTableRepository {

    override fun observeTables(): FlowStream<List<DataTable>> =
        dataTableDao.observeAll().map { list ->
            list.map { entity ->
                val columns = parseColumns(entity.columnsJson)
                val count = dataTableDao.rowCount(entity.id)
                DataTable(
                    id = entity.id,
                    name = entity.name,
                    description = entity.description,
                    columns = columns,
                    rowCount = count,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                )
            }
        }.flowOn(io)

    override suspend fun tableById(id: String): DataTable? = withContext(io) {
        val entity = dataTableDao.byId(id) ?: return@withContext null
        val columns = parseColumns(entity.columnsJson)
        val count = dataTableDao.rowCount(id)
        DataTable(
            id = entity.id,
            name = entity.name,
            description = entity.description,
            columns = columns,
            rowCount = count,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    override suspend fun createTable(name: String, description: String, columns: List<DataTableColumn>): String =
        withContext(io) {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val columnsJson = serializeColumns(columns)
            dataTableDao.upsertTable(
                DataTableEntity(
                    id = id,
                    name = name.trim(),
                    description = description.trim(),
                    columnsJson = columnsJson,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            id
        }

    override suspend fun deleteTable(id: String): Unit = withContext(io) {
        dataTableDao.deleteRowsForTable(id)
        dataTableDao.deleteTable(id)
    }

    override fun observeRows(tableId: String): FlowStream<List<DataTableRow>> =
        dataTableDao.observeRows(tableId).map { list ->
            list.map { entity ->
                DataTableRow(
                    id = entity.id,
                    tableId = entity.tableId,
                    data = parseRowData(entity.dataJson),
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                )
            }
        }.flowOn(io)

    override suspend fun rowsForTable(tableId: String): List<DataTableRow> = withContext(io) {
        dataTableDao.rowsForTable(tableId).map { entity ->
            DataTableRow(
                id = entity.id,
                tableId = entity.tableId,
                data = parseRowData(entity.dataJson),
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
            )
        }
    }

    override suspend fun insertRow(tableId: String, data: Map<String, Any?>): String = withContext(io) {
        val rowId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val dataJson = JsonCodec.toJson(data)?.toString() ?: "{}"
        dataTableDao.upsertRow(
            DataTableRowEntity(
                id = rowId,
                tableId = tableId,
                dataJson = dataJson,
                createdAt = now,
                updatedAt = now,
            )
        )
        val table = dataTableDao.byId(tableId)
        if (table != null) {
            dataTableDao.upsertTable(table.copy(updatedAt = now))
        }
        rowId
    }

    override suspend fun deleteRow(rowId: String): Unit = withContext(io) {
        dataTableDao.deleteRow(rowId)
    }

    private fun serializeColumns(columns: List<DataTableColumn>): String {
        val list = columns.map { mapOf("id" to it.id, "name" to it.name, "type" to it.type) }
        return JsonCodec.toJson(list)?.toString() ?: "[]"
    }

    private fun parseColumns(json: String): List<DataTableColumn> {
        val element = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        val list = JsonCodec.fromJson(element) as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            DataTableColumn(
                id = map["id"]?.toString() ?: UUID.randomUUID().toString(),
                name = map["name"]?.toString() ?: "",
                type = map["type"]?.toString() ?: "string",
            )
        }
    }

    private fun parseRowData(json: String): Map<String, Any?> {
        val element = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull() ?: return emptyMap()
        return (JsonCodec.fromJsonMap(element) as? Map<String, Any?>) ?: emptyMap()
    }
}

/**
 * Encrypted persistence for refreshed OAuth2 tokens.
 *
 * This writes the credential row directly (never through [CredentialRepository.resolve]) so a
 * refresh cannot recurse into itself. The access token, refresh token and client secret are all
 * inside the same Keystore-encrypted blob as before.
 */
class RoomOAuth2TokenStore(
    private val credentialDao: CredentialDao,
    private val cryptoBox: CryptoBox = CryptoBox(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : OAuth2TokenStore {

    override suspend fun load(credentialId: String): Map<String, String> = withContext(io) {
        val entity = credentialDao.byId(credentialId) ?: return@withContext emptyMap()
        val json = cryptoBox.decrypt(entity.cipherText) ?: return@withContext emptyMap()
        val map = JsonCodec.fromJsonMap(kotlinx.serialization.json.Json.parseToJsonElement(json)) ?: emptyMap()
        map.mapValues { it.value?.toString().orEmpty() }
    }

    override suspend fun save(credentialId: String, fields: Map<String, String>) = withContext(io) {
        val payload = JsonCodec.toJson(fields)?.toString() ?: "{}"
        val cipherText = cryptoBox.encrypt(payload)
            ?: throw IllegalStateException("This device's secure storage refused to encrypt the refreshed token.")
        val existing = credentialDao.byId(credentialId)
        credentialDao.upsert(
            CredentialEntity(
                id = credentialId,
                type = existing?.type ?: "oauth2",
                label = existing?.label ?: "Connected account",
                cipherText = cipherText,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
        )
    }
}

interface WorkflowGroupRepository {
    fun observeAll(): FlowStream<List<WorkflowGroup>>
    suspend fun save(group: WorkflowGroup)
    suspend fun delete(id: String)
}

class RoomWorkflowGroupRepository(
    private val dao: WorkflowGroupDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WorkflowGroupRepository {
    override fun observeAll(): FlowStream<List<WorkflowGroup>> =
        dao.observeAll().map { entities ->
            entities.map {
                WorkflowGroup(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    color = it.color,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
        }

    override suspend fun save(group: WorkflowGroup): Unit = withContext(io) {
        dao.upsert(
            WorkflowGroupEntity(
                id = group.id,
                name = group.name,
                description = group.description,
                color = group.color,
                createdAt = group.createdAt,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun delete(id: String): Unit = withContext(io) {
        dao.delete(id)
    }
}

interface WorkflowVariableRepository {
    fun observeAll(): FlowStream<List<WorkflowVariable>>
    fun observeForGroup(groupId: String): FlowStream<List<WorkflowVariable>>
    suspend fun save(variable: WorkflowVariable)
    suspend fun delete(id: String)
    suspend fun resolveVariables(groupId: String?): Map<String, String>
}

class RoomWorkflowVariableRepository(
    private val dao: WorkflowVariableDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WorkflowVariableRepository {
    override fun observeAll(): FlowStream<List<WorkflowVariable>> =
        dao.observeAll().map { entities ->
            entities.map {
                WorkflowVariable(
                    id = it.id,
                    groupId = it.groupId,
                    key = it.key,
                    value = it.value,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
        }

    override fun observeForGroup(groupId: String): FlowStream<List<WorkflowVariable>> =
        dao.observeForGroup(groupId).map { entities ->
            entities.map {
                WorkflowVariable(
                    id = it.id,
                    groupId = it.groupId,
                    key = it.key,
                    value = it.value,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
        }

    override suspend fun save(variable: WorkflowVariable): Unit = withContext(io) {
        dao.upsert(
            WorkflowVariableEntity(
                id = variable.id,
                groupId = variable.groupId,
                key = variable.key,
                value = variable.value,
                createdAt = variable.createdAt,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun delete(id: String): Unit = withContext(io) {
        dao.delete(id)
    }

    override suspend fun resolveVariables(groupId: String?): Map<String, String> = withContext(io) {
        val result = mutableMapOf<String, String>()
        val all = if (groupId != null) dao.forGroup(groupId) else dao.all()
        all.filter { it.groupId == null }.forEach { result[it.key] = it.value }
        if (groupId != null) {
            all.filter { it.groupId == groupId }.forEach { result[it.key] = it.value }
        }
        result
    }
}
