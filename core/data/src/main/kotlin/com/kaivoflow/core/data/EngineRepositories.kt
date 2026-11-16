package com.kaivoflow.core.data

import com.kaivoflow.core.engine.LifecycleLogEntry
import com.kaivoflow.core.engine.LifecycleSnapshot
import kotlinx.coroutines.flow.Flow as FlowStream

/*
 * Thin repositories over the Always-On Engine tables. They hold no policy: the decisions live in
 * core:engine (state machine, validator) and in the Engine milestones that come later.
 */

interface FlowLifecycleRepository {
    suspend fun snapshot(flowId: String): LifecycleSnapshot?

    /** Snapshots of every stored Flow keyed by id. */
    suspend fun allSnapshots(): Map<String, LifecycleSnapshot>

    /** Ids of Flows in Published or Error. */
    suspend fun liveFlowIds(): List<String>

    /**
     * Writes the new columns, the optional log row and the optional revision snapshot in one
     * transaction. Returns false when the Flow row does not exist.
     */
    suspend fun applyTransition(
        flowId: String,
        snapshot: LifecycleSnapshot,
        log: LifecycleLogEntry? = null,
        version: FlowVersionEntity? = null,
    ): Boolean

    suspend fun version(flowId: String, revision: Int): FlowVersionEntity?
    suspend fun latestRevision(flowId: String): Int
    suspend fun versions(flowId: String): List<FlowVersionEntity>
    suspend fun log(flowId: String, limit: Int = 50): List<FlowLifecycleLogEntity>

    /** Removes versions and log rows of a deleted Flow. */
    suspend fun purge(flowId: String)
}

class RoomFlowLifecycleRepository(private val dao: FlowLifecycleDao) : FlowLifecycleRepository {
    override suspend fun snapshot(flowId: String) = dao.flowRow(flowId)?.lifecycleSnapshot()

    override suspend fun allSnapshots() = dao.allFlowRows().associate { it.id to it.lifecycleSnapshot() }

    override suspend fun liveFlowIds() = dao.liveFlows().map { it.id }

    override suspend fun applyTransition(flowId: String, snapshot: LifecycleSnapshot, log: LifecycleLogEntry?, version: FlowVersionEntity?) =
        dao.applyTransition(
            id = flowId,
            lifecycle = snapshot.state.db,
            publishedRevision = snapshot.publishedRevision,
            reason = snapshot.reason.db,
            since = snapshot.since,
            pausedUntil = snapshot.pausedUntil,
            errorCode = snapshot.errorCode,
            errorSince = snapshot.errorSince,
            retryCount = snapshot.retryCount,
            nextRetryAt = snapshot.nextRetryAt,
            healthySince = snapshot.healthySince,
            log = log?.toEntity(flowId),
            version = version,
        )

    override suspend fun version(flowId: String, revision: Int) = dao.version(flowId, revision)
    override suspend fun latestRevision(flowId: String) = dao.latestRevision(flowId)
    override suspend fun versions(flowId: String) = dao.versions(flowId)
    override suspend fun log(flowId: String, limit: Int) = dao.log(flowId, limit)

    override suspend fun purge(flowId: String) {
        dao.deleteVersions(flowId)
        dao.deleteLog(flowId)
    }
}

interface TriggerRegistrationRepository {
    suspend fun upsert(row: TriggerRegistrationEntity)
    suspend fun forFlow(flowId: String): List<TriggerRegistrationEntity>
    fun observeForFlow(flowId: String): FlowStream<List<TriggerRegistrationEntity>>
    suspend fun all(): List<TriggerRegistrationEntity>
    suspend fun remove(flowId: String, sparkId: String)
    suspend fun removeAll(flowId: String)
}

class RoomTriggerRegistrationRepository(private val dao: TriggerRegistrationDao) : TriggerRegistrationRepository {
    override suspend fun upsert(row: TriggerRegistrationEntity) = dao.upsert(row)
    override suspend fun forFlow(flowId: String) = dao.forFlow(flowId)
    override fun observeForFlow(flowId: String) = dao.observeForFlow(flowId)
    override suspend fun all() = dao.all()
    override suspend fun remove(flowId: String, sparkId: String) = dao.delete(flowId, sparkId)
    override suspend fun removeAll(flowId: String) = dao.deleteForFlow(flowId)
}

interface SparkInboxRepository {
    /** Persists an event. Returns the row id, or null when the dedupe key already exists. */
    suspend fun enqueue(row: SparkInboxEntity): Long?
    suspend fun get(id: Long): SparkInboxEntity?
    suspend fun claimable(now: Long, limit: Int = 50): List<SparkInboxEntity>

    /** Takes a lease on the row. False when another holder has a live lease or it is already finished. */
    suspend fun claim(id: Long, now: Long, leaseMillis: Long): Boolean
    suspend fun complete(id: Long, runId: String?): Boolean
    suspend fun release(id: Long): Boolean
    suspend fun dropOpenForFlow(flowId: String, reason: String): Int
    suspend fun openCount(flowId: String? = null): Int
    suspend fun deleteFinishedOlderThan(before: Long): Int
    suspend fun purge(flowId: String)
}

class RoomSparkInboxRepository(private val dao: SparkInboxDao) : SparkInboxRepository {
    override suspend fun enqueue(row: SparkInboxEntity): Long? = dao.insert(row).takeIf { it != -1L }
    override suspend fun get(id: Long) = dao.byId(id)
    override suspend fun claimable(now: Long, limit: Int) = dao.claimable(now, limit)
    override suspend fun claim(id: Long, now: Long, leaseMillis: Long) = dao.claim(id, now, now + leaseMillis) == 1
    override suspend fun complete(id: Long, runId: String?) = dao.complete(id, runId) == 1
    override suspend fun release(id: Long) = dao.release(id) == 1
    override suspend fun dropOpenForFlow(flowId: String, reason: String) = dao.dropOpenForFlow(flowId, reason)
    override suspend fun openCount(flowId: String?) = if (flowId == null) dao.openCount() else dao.openCountForFlow(flowId)
    override suspend fun deleteFinishedOlderThan(before: Long) = dao.deleteFinishedOlderThan(before)
    override suspend fun purge(flowId: String) = dao.deleteForFlow(flowId)
}

interface ScheduleRepository {
    suspend fun saveState(row: ScheduleStateEntity)
    suspend fun state(flowId: String, sparkId: String): ScheduleStateEntity?
    suspend fun statesForFlow(flowId: String): List<ScheduleStateEntity>
    suspend fun allStates(): List<ScheduleStateEntity>

    /** Records an expected slot. False when the slot already exists (arming twice is a no-op). */
    suspend fun expect(row: ExpectedRunEntity): Boolean
    suspend fun expected(flowId: String, sparkId: String, slotKey: String): ExpectedRunEntity?
    suspend fun pendingDueBefore(cutoff: Long): List<ExpectedRunEntity>
    suspend fun markExpected(id: Long, state: String, firedAt: Long?, runId: String?, latenessMs: Long?, reason: String?): Boolean
    suspend fun markPendingInSpan(flowId: String, from: Long, to: Long, state: String, reason: String?): Int
    suspend fun supersedePending(flowId: String, reason: String): Int
    suspend fun recentForFlow(flowId: String, since: Long, limit: Int = 200): List<ExpectedRunEntity>
    suspend fun deleteExpectedOlderThan(before: Long): Int
    suspend fun purge(flowId: String)
}

class RoomScheduleRepository(private val dao: ScheduleDao) : ScheduleRepository {
    override suspend fun saveState(row: ScheduleStateEntity) = dao.upsertState(row)
    override suspend fun state(flowId: String, sparkId: String) = dao.state(flowId, sparkId)
    override suspend fun statesForFlow(flowId: String) = dao.statesForFlow(flowId)
    override suspend fun allStates() = dao.allStates()
    override suspend fun expect(row: ExpectedRunEntity) = dao.insertExpected(row) != -1L
    override suspend fun expected(flowId: String, sparkId: String, slotKey: String) = dao.expected(flowId, sparkId, slotKey)
    override suspend fun pendingDueBefore(cutoff: Long) = dao.pendingDueBefore(cutoff)
    override suspend fun markExpected(id: Long, state: String, firedAt: Long?, runId: String?, latenessMs: Long?, reason: String?) =
        dao.updateExpected(id, state, firedAt, runId, latenessMs, reason) == 1
    override suspend fun markPendingInSpan(flowId: String, from: Long, to: Long, state: String, reason: String?) =
        dao.markPendingInSpan(flowId, from, to, state, reason)
    override suspend fun supersedePending(flowId: String, reason: String) = dao.supersedePending(flowId, reason)
    override suspend fun recentForFlow(flowId: String, since: Long, limit: Int) = dao.recentForFlow(flowId, since, limit)
    override suspend fun deleteExpectedOlderThan(before: Long) = dao.deleteExpectedOlderThan(before)
    override suspend fun purge(flowId: String) {
        dao.deleteStatesForFlow(flowId)
        dao.deleteExpectedForFlow(flowId)
    }
}

interface EngineTelemetryRepository {
    suspend fun startSession(row: EngineSessionEntity): Long
    suspend fun stopSession(id: Long, at: Long, reason: String)
    suspend fun markSessionDied(id: Long, reason: String)
    suspend fun latestSession(): EngineSessionEntity?
    suspend fun recentSessions(limit: Int = 20): List<EngineSessionEntity>
    suspend fun writeHeartbeat(row: EngineHeartbeatEntity)
    suspend fun heartbeat(): EngineHeartbeatEntity?
    fun observeHeartbeat(): FlowStream<EngineHeartbeatEntity?>
    suspend fun record(event: ReliabilityEventEntity): Long
    suspend fun recentEvents(limit: Int = 100): List<ReliabilityEventEntity>
    fun observeRecentEvents(limit: Int = 100): FlowStream<List<ReliabilityEventEntity>>
    suspend fun countSince(kind: String, since: Long): Int
    suspend fun lastOfKind(kind: String): ReliabilityEventEntity?
    suspend fun deleteEventsOlderThan(before: Long): Int
}

class RoomEngineTelemetryRepository(private val dao: EngineTelemetryDao) : EngineTelemetryRepository {
    override suspend fun startSession(row: EngineSessionEntity) = dao.insertSession(row)
    override suspend fun stopSession(id: Long, at: Long, reason: String) { dao.cleanStop(id, at, reason) }
    override suspend fun markSessionDied(id: Long, reason: String) { dao.markDied(id, reason) }
    override suspend fun latestSession() = dao.latestSession()
    override suspend fun recentSessions(limit: Int) = dao.recentSessions(limit)
    override suspend fun writeHeartbeat(row: EngineHeartbeatEntity) = dao.upsertHeartbeat(row.copy(id = EngineHeartbeatEntity.SINGLE_ROW_ID))
    override suspend fun heartbeat() = dao.heartbeat()
    override fun observeHeartbeat() = dao.observeHeartbeat()
    override suspend fun record(event: ReliabilityEventEntity) = dao.insertEvent(event)
    override suspend fun recentEvents(limit: Int) = dao.recentEvents(limit)
    override fun observeRecentEvents(limit: Int) = dao.observeRecentEvents(limit)
    override suspend fun countSince(kind: String, since: Long) = dao.countSince(kind, since)
    override suspend fun lastOfKind(kind: String) = dao.lastOfKind(kind)
    override suspend fun deleteEventsOlderThan(before: Long) = dao.deleteEventsOlderThan(before)
}
