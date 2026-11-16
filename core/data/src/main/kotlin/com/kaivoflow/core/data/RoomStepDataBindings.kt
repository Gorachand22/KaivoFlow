package com.kaivoflow.core.data

import com.kaivoflow.core.engine.stepdata.PinPersistence
import com.kaivoflow.core.engine.stepdata.PinnedData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// Room-backed bindings (need the generated DAOs; everything else lives in StepDataStores.kt and is JVM-testable).

class RoomStepDataAccess(private val dao: StepDataDao) : StepDataAccess {
    override suspend fun write(execution: StepExecutionEntity, ports: List<StepPortDataEntity>) = dao.write(execution, ports)
    override fun observeExecutions(flowId: String, stepId: String, limit: Int) = dao.observeExecutions(flowId, stepId, limit)
    override suspend fun execution(runId: String, stepId: String, runIndex: Int) = dao.execution(runId, stepId, runIndex)
    override suspend fun latest(flowId: String, stepId: String) = dao.latest(flowId, stepId)
    override suspend fun portMetas(runId: String, stepId: String, runIndex: Int) = dao.portMetas(runId, stepId, runIndex)
    override suspend fun port(runId: String, stepId: String, runIndex: Int, direction: String, port: Int) =
        dao.port(runId, stepId, runIndex, direction, port)
    override suspend fun deleteRun(runId: String) = dao.deleteRun(runId)
    override suspend fun usage() = dao.usage()
    override suspend fun runMetas() = dao.runMetas()
}

/** Pins in the `step_pins` table. Items are stored as the same JSON as a Flow document stores them. */
class RoomPinPersistence(
    private val dao: StepPinDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PinPersistence {

    override suspend fun load(flowId: String, stepId: String): PinnedData? = withContext(io) { dao.byStep(flowId, stepId)?.let(PinCodec::fromEntity) }

    override suspend fun save(flowId: String, stepId: String, data: PinnedData) = withContext(io) { dao.upsert(PinCodec.toEntity(flowId, stepId, data)) }

    override suspend fun delete(flowId: String, stepId: String) = withContext(io) { dao.delete(flowId, stepId) }

    override suspend fun all(flowId: String): Map<String, PinnedData> = withContext(io) {
        dao.forFlow(flowId).mapNotNull { e -> PinCodec.fromEntity(e)?.let { e.stepId to it } }.toMap()
    }

    override fun observe(flowId: String, stepId: String): Flow<PinnedData?> = dao.observe(flowId, stepId).map { it?.let(PinCodec::fromEntity) }
}

