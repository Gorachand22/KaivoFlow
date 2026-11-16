package com.kaivoflow.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow as FlowStream

/**
 * Lifecycle columns of `flows`, published revisions and the lifecycle log. The three writes of a
 * transition (columns, log row, revision snapshot) go through [applyTransition] so they are atomic.
 */
@Dao
abstract class FlowLifecycleDao {
    @Query(
        "UPDATE flows SET lifecycle = :lifecycle, publishedRevision = :publishedRevision, " +
            "lifecycleReason = :reason, lifecycleSince = :since, pausedUntil = :pausedUntil, " +
            "errorCode = :errorCode, errorSince = :errorSince, retryCount = :retryCount, " +
            "nextRetryAt = :nextRetryAt, healthySince = :healthySince WHERE id = :id",
    )
    abstract suspend fun updateColumns(
        id: String,
        lifecycle: String,
        publishedRevision: Int?,
        reason: String,
        since: Long,
        pausedUntil: Long?,
        errorCode: String?,
        errorSince: Long?,
        retryCount: Int,
        nextRetryAt: Long?,
        healthySince: Long?,
    ): Int

    @Insert abstract suspend fun insertLog(row: FlowLifecycleLogEntity): Long

    /** IGNORE: a revision is immutable, writing the same one twice keeps the first. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertVersion(row: FlowVersionEntity): Long

    @Transaction
    open suspend fun applyTransition(
        id: String,
        lifecycle: String,
        publishedRevision: Int?,
        reason: String,
        since: Long,
        pausedUntil: Long?,
        errorCode: String?,
        errorSince: Long?,
        retryCount: Int,
        nextRetryAt: Long?,
        healthySince: Long?,
        log: FlowLifecycleLogEntity?,
        version: FlowVersionEntity?,
    ): Boolean {
        if (updateColumns(id, lifecycle, publishedRevision, reason, since, pausedUntil, errorCode, errorSince, retryCount, nextRetryAt, healthySince) == 0) return false
        version?.let { insertVersion(it) }
        log?.let { insertLog(it) }
        return true
    }

    @Query("SELECT * FROM flows WHERE id = :id")
    abstract suspend fun flowRow(id: String): FlowEntity?

    @Query("SELECT * FROM flows WHERE lifecycle IN ('PUBLISHED', 'ERROR')")
    abstract suspend fun liveFlows(): List<FlowEntity>

    @Query("SELECT * FROM flows")
    abstract suspend fun allFlowRows(): List<FlowEntity>

    @Query("SELECT * FROM flow_versions WHERE flowId = :flowId AND revision = :revision")
    abstract suspend fun version(flowId: String, revision: Int): FlowVersionEntity?

    @Query("SELECT COALESCE(MAX(revision), 0) FROM flow_versions WHERE flowId = :flowId")
    abstract suspend fun latestRevision(flowId: String): Int

    @Query("SELECT * FROM flow_versions WHERE flowId = :flowId ORDER BY revision DESC")
    abstract suspend fun versions(flowId: String): List<FlowVersionEntity>

    @Query("SELECT * FROM flow_lifecycle_log WHERE flowId = :flowId ORDER BY at DESC, id DESC LIMIT :limit")
    abstract suspend fun log(flowId: String, limit: Int): List<FlowLifecycleLogEntity>

    @Query("DELETE FROM flow_lifecycle_log WHERE at < :before")
    abstract suspend fun deleteLogOlderThan(before: Long): Int

    @Query("DELETE FROM flow_versions WHERE flowId = :flowId")
    abstract suspend fun deleteVersions(flowId: String)

    @Query("DELETE FROM flow_lifecycle_log WHERE flowId = :flowId")
    abstract suspend fun deleteLog(flowId: String)
}

@Dao
interface TriggerRegistrationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TriggerRegistrationEntity)

    @Query("SELECT * FROM trigger_registration WHERE flowId = :flowId ORDER BY sparkId")
    suspend fun forFlow(flowId: String): List<TriggerRegistrationEntity>

    @Query("SELECT * FROM trigger_registration WHERE flowId = :flowId ORDER BY sparkId")
    fun observeForFlow(flowId: String): FlowStream<List<TriggerRegistrationEntity>>

    @Query("SELECT * FROM trigger_registration ORDER BY flowId, sparkId")
    suspend fun all(): List<TriggerRegistrationEntity>

    @Query("DELETE FROM trigger_registration WHERE flowId = :flowId AND sparkId = :sparkId")
    suspend fun delete(flowId: String, sparkId: String)

    @Query("DELETE FROM trigger_registration WHERE flowId = :flowId")
    suspend fun deleteForFlow(flowId: String)
}

@Dao
interface SparkInboxDao {
    /** Returns the new row id, or -1 when [SparkInboxEntity.dedupeKey] already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SparkInboxEntity): Long

    @Query("SELECT * FROM spark_inbox WHERE id = :id")
    suspend fun byId(id: Long): SparkInboxEntity?

    @Query("SELECT * FROM spark_inbox WHERE dedupeKey = :key")
    suspend fun byDedupeKey(key: String): SparkInboxEntity?

    /** Rows that can be claimed now, oldest first: Pending, or Claimed with an expired lease. */
    @Query(
        "SELECT * FROM spark_inbox WHERE state = 'PENDING' OR (state = 'CLAIMED' AND (leaseUntil IS NULL OR leaseUntil < :now)) " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun claimable(now: Long, limit: Int): List<SparkInboxEntity>

    /** Atomic claim. Returns 1 when this caller won the row, 0 when someone else holds a live lease. */
    @Query(
        "UPDATE spark_inbox SET state = 'CLAIMED', leaseUntil = :leaseUntil WHERE id = :id AND " +
            "(state = 'PENDING' OR (state = 'CLAIMED' AND (leaseUntil IS NULL OR leaseUntil < :now)))",
    )
    suspend fun claim(id: Long, now: Long, leaseUntil: Long): Int

    @Query("UPDATE spark_inbox SET state = 'DONE', runId = :runId, leaseUntil = NULL WHERE id = :id AND state = 'CLAIMED'")
    suspend fun complete(id: Long, runId: String?): Int

    @Query("UPDATE spark_inbox SET state = 'PENDING', leaseUntil = NULL WHERE id = :id AND state = 'CLAIMED'")
    suspend fun release(id: Long): Int

    @Query(
        "UPDATE spark_inbox SET state = 'DROPPED', dropReason = :reason, leaseUntil = NULL " +
            "WHERE flowId = :flowId AND state IN ('PENDING', 'CLAIMED')",
    )
    suspend fun dropOpenForFlow(flowId: String, reason: String): Int

    @Query("SELECT COUNT(*) FROM spark_inbox WHERE flowId = :flowId AND state IN ('PENDING', 'CLAIMED')")
    suspend fun openCountForFlow(flowId: String): Int

    @Query("SELECT COUNT(*) FROM spark_inbox WHERE state IN ('PENDING', 'CLAIMED')")
    suspend fun openCount(): Int

    @Query("DELETE FROM spark_inbox WHERE state IN ('DONE', 'DROPPED') AND receivedAt < :before")
    suspend fun deleteFinishedOlderThan(before: Long): Int

    @Query("DELETE FROM spark_inbox WHERE flowId = :flowId")
    suspend fun deleteForFlow(flowId: String)
}

/** `schedule_state` and the `expected_run` evidence log. */
@Dao
interface ScheduleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(row: ScheduleStateEntity)

    @Query("SELECT * FROM schedule_state WHERE flowId = :flowId AND sparkId = :sparkId")
    suspend fun state(flowId: String, sparkId: String): ScheduleStateEntity?

    @Query("SELECT * FROM schedule_state WHERE flowId = :flowId")
    suspend fun statesForFlow(flowId: String): List<ScheduleStateEntity>

    @Query("SELECT * FROM schedule_state ORDER BY nextDueAt")
    suspend fun allStates(): List<ScheduleStateEntity>

    @Query("DELETE FROM schedule_state WHERE flowId = :flowId")
    suspend fun deleteStatesForFlow(flowId: String)

    /** Returns -1 when the slot is already recorded (arming is idempotent). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExpected(row: ExpectedRunEntity): Long

    @Query("SELECT * FROM expected_run WHERE flowId = :flowId AND sparkId = :sparkId AND slotKey = :slotKey")
    suspend fun expected(flowId: String, sparkId: String, slotKey: String): ExpectedRunEntity?

    @Query("SELECT * FROM expected_run WHERE state = 'PENDING' AND dueAt <= :cutoff ORDER BY dueAt ASC")
    suspend fun pendingDueBefore(cutoff: Long): List<ExpectedRunEntity>

    @Query("UPDATE expected_run SET state = :state, firedAt = :firedAt, runId = :runId, latenessMs = :latenessMs, reason = :reason WHERE id = :id")
    suspend fun updateExpected(id: Long, state: String, firedAt: Long?, runId: String?, latenessMs: Long?, reason: String?): Int

    /** Marks still-pending slots inside a span, for example a paused interval. */
    @Query(
        "UPDATE expected_run SET state = :state, reason = :reason WHERE flowId = :flowId AND state = 'PENDING' " +
            "AND dueAt >= :from AND dueAt <= :to",
    )
    suspend fun markPendingInSpan(flowId: String, from: Long, to: Long, state: String, reason: String?): Int

    @Query("UPDATE expected_run SET state = 'SUPERSEDED', reason = :reason WHERE flowId = :flowId AND state = 'PENDING'")
    suspend fun supersedePending(flowId: String, reason: String): Int

    @Query("SELECT * FROM expected_run WHERE flowId = :flowId AND dueAt >= :since ORDER BY dueAt DESC LIMIT :limit")
    suspend fun recentForFlow(flowId: String, since: Long, limit: Int): List<ExpectedRunEntity>

    @Query("SELECT * FROM expected_run WHERE dueAt >= :since ORDER BY dueAt DESC")
    suspend fun dueSince(since: Long): List<ExpectedRunEntity>

    @Query("DELETE FROM expected_run WHERE dueAt < :before")
    suspend fun deleteExpectedOlderThan(before: Long): Int

    @Query("DELETE FROM expected_run WHERE flowId = :flowId")
    suspend fun deleteExpectedForFlow(flowId: String)
}

/** Engine sessions, the single heartbeat row and reliability events (spec section 6). */
@Dao
interface EngineTelemetryDao {
    @Insert suspend fun insertSession(row: EngineSessionEntity): Long

    @Query("UPDATE engine_session SET cleanStopAt = :at, endReason = :reason WHERE id = :id")
    suspend fun cleanStop(id: Long, at: Long, reason: String): Int

    @Query("UPDATE engine_session SET endReason = :reason WHERE id = :id AND cleanStopAt IS NULL")
    suspend fun markDied(id: Long, reason: String): Int

    @Query("SELECT * FROM engine_session ORDER BY id DESC LIMIT 1")
    suspend fun latestSession(): EngineSessionEntity?

    @Query("SELECT * FROM engine_session ORDER BY id DESC LIMIT :limit")
    suspend fun recentSessions(limit: Int): List<EngineSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHeartbeat(row: EngineHeartbeatEntity)

    @Query("SELECT * FROM engine_heartbeat WHERE id = 1")
    suspend fun heartbeat(): EngineHeartbeatEntity?

    @Query("SELECT * FROM engine_heartbeat WHERE id = 1")
    fun observeHeartbeat(): FlowStream<EngineHeartbeatEntity?>

    @Insert suspend fun insertEvent(row: ReliabilityEventEntity): Long

    @Query("SELECT * FROM reliability_event ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recentEvents(limit: Int): List<ReliabilityEventEntity>

    @Query("SELECT * FROM reliability_event ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeRecentEvents(limit: Int): FlowStream<List<ReliabilityEventEntity>>

    @Query("SELECT COUNT(*) FROM reliability_event WHERE kind = :kind AND at >= :since")
    suspend fun countSince(kind: String, since: Long): Int

    @Query("SELECT * FROM reliability_event WHERE kind = :kind ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun lastOfKind(kind: String): ReliabilityEventEntity?

    @Query("DELETE FROM reliability_event WHERE at < :before")
    suspend fun deleteEventsOlderThan(before: Long): Int
}
