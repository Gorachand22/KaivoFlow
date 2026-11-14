package com.kaivoflow.core.data

import com.kaivoflow.core.engine.CredentialProvider
import com.kaivoflow.core.model.CredentialRef
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow as FlowStream
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
    suspend fun createEmpty(name: String = "New Flow"): Flow
    suspend fun recordRun(flowId: String, status: RunStatus, at: Long)
}

class RoomFlowRepository(
    private val flowDao: FlowDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FlowRepository {

    override fun observeSummaries(): FlowStream<List<FlowSummary>> =
        flowDao.observeAll().map { entities ->
            entities.map {
                FlowSummary(
                    id = it.id,
                    name = it.name,
                    stepCount = it.stepCount,
                    updatedAt = it.updatedAt,
                    isFavorite = it.isFavorite,
                    lastRunStatus = it.lastRunStatus,
                    lastRunAt = it.lastRunAt,
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

    override suspend fun save(flow: Flow) = withContext(io) {
        val now = System.currentTimeMillis()
        val stamped = if (flow.updatedAt == 0L) flow.copy(updatedAt = now, createdAt = if (flow.createdAt == 0L) now else flow.createdAt) else flow
        val existing = flowDao.byId(stamped.id)
        flowDao.upsert(
            FlowEntity(
                id = stamped.id,
                name = stamped.name,
                flowJson = JsonCodec.encodeFlow(stamped),
                updatedAt = stamped.updatedAt,
                createdAt = stamped.createdAt,
                isFavorite = stamped.isFavorite,
                stepCount = stamped.steps.size,
                lastRunStatus = existing?.lastRunStatus,
                lastRunAt = existing?.lastRunAt,
            )
        )
    }

    override suspend fun delete(id: String) = withContext(io) { flowDao.delete(id) }

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

    override suspend fun createEmpty(name: String): Flow = withContext(io) {
        val now = System.currentTimeMillis()
        Flow(id = UUID.randomUUID().toString(), name = name, createdAt = now, updatedAt = now)
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
    suspend fun save(id: String?, type: String, label: String, fields: Map<String, String>): String
    suspend fun delete(id: String)
}

class EncryptedCredentialRepository(
    private val credentialDao: CredentialDao,
    private val cryptoBox: CryptoBox = CryptoBox(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CredentialRepository {

    override suspend fun resolve(ref: String): Map<String, String> = withContext(io) {
        val entity = credentialDao.byId(ref) ?: throw IllegalStateException("That credential was deleted. Pick another one.")
        val json = cryptoBox.decrypt(entity.cipherText)
            ?: throw IllegalStateException("This credential could not be unlocked on this device. Please re-enter it.")
        val map = JsonCodec.fromJsonMap(kotlinx.serialization.json.Json.parseToJsonElement(json)) ?: emptyMap()
        map.mapValues { it.value?.toString().orEmpty() }
    }

    override suspend fun list(): List<CredentialRef> = withContext(io) {
        credentialDao.all().map { CredentialRef(it.id, it.type, it.label) }
    }

    override fun observeRefs(): FlowStream<List<CredentialRef>> =
        credentialDao.observeAll().map { list -> list.map { CredentialRef(it.id, it.type, it.label) } }

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
