package com.kaivoflow.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Row access for [VaultMetaStore]. Room backs it in the app; tests use a map. */
interface VaultMetaAccess {
    suspend fun get(key: String): String?
    suspend fun put(entity: VaultMetaEntity)
}

@Dao
abstract class VaultMetaDao : VaultMetaAccess {
    @Query("SELECT value FROM vault_meta WHERE `key` = :key")
    abstract override suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract override suspend fun put(entity: VaultMetaEntity)
}

/** Row access for [RoomMemoryStore]. */
interface MemoryAccess {
    suspend fun get(rowKey: String): AgentMemoryEntity?
    suspend fun put(row: AgentMemoryEntity)
    suspend fun touch(rowKey: String, at: Long)
    suspend fun delete(rowKey: String)
    suspend fun deleteScope(scope: String)
    suspend fun deleteIdleBefore(before: Long): Int
    suspend fun scopeRows(scope: String): List<MemoryRowInfo>
    suspend fun totalBytes(): Long
    suspend fun allRows(): List<MemoryRowInfo>
}

/** A memory row without its payload, for size and age decisions. */
data class MemoryRowInfo(val rowKey: String, val scope: String, val bytes: Int, val lastAccessAt: Long)

@Dao
abstract class AgentMemoryDao : MemoryAccess {
    @Query("SELECT * FROM agent_memory WHERE rowKey = :rowKey")
    abstract override suspend fun get(rowKey: String): AgentMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract override suspend fun put(row: AgentMemoryEntity)

    @Query("UPDATE agent_memory SET lastAccessAt = :at WHERE rowKey = :rowKey")
    abstract override suspend fun touch(rowKey: String, at: Long)

    @Query("DELETE FROM agent_memory WHERE rowKey = :rowKey")
    abstract override suspend fun delete(rowKey: String)

    @Query("DELETE FROM agent_memory WHERE scope = :scope")
    abstract override suspend fun deleteScope(scope: String)

    @Query("DELETE FROM agent_memory WHERE lastAccessAt < :before")
    abstract override suspend fun deleteIdleBefore(before: Long): Int

    @Query("SELECT rowKey AS rowKey, scope AS scope, bytes AS bytes, lastAccessAt AS lastAccessAt FROM agent_memory WHERE scope = :scope ORDER BY lastAccessAt ASC")
    abstract override suspend fun scopeRows(scope: String): List<MemoryRowInfo>

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM agent_memory")
    abstract override suspend fun totalBytes(): Long

    @Query("SELECT rowKey AS rowKey, scope AS scope, bytes AS bytes, lastAccessAt AS lastAccessAt FROM agent_memory ORDER BY lastAccessAt ASC")
    abstract override suspend fun allRows(): List<MemoryRowInfo>
}

/** Row access for [RoomUsageLedger]. [record] writes the call and bumps every rollup in one transaction. */
interface UsageAccess {
    suspend fun record(row: AiUsageEntity, rollups: List<Pair<String, String>>)
    suspend fun rollup(scope: String, period: String): AiUsageRollupEntity?
    suspend fun recent(limit: Int): List<AiUsageEntity>
    suspend fun forRun(runId: String): List<AiUsageEntity>
    suspend fun deleteCallsBefore(before: Long): Int
    suspend fun deleteRollupsBefore(dayPeriod: String, monthPeriod: String): Int
}

@Dao
abstract class AiUsageDao : UsageAccess {
    @Insert
    protected abstract suspend fun insertCall(row: AiUsageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun ensureRollup(row: AiUsageRollupEntity): Long

    @Query("UPDATE ai_usage_rollup SET calls = calls + 1, tokensIn = tokensIn + :tin, tokensOut = tokensOut + :tout, costMicros = costMicros + :cost WHERE scope = :scope AND period = :period")
    protected abstract suspend fun bump(scope: String, period: String, tin: Long, tout: Long, cost: Long)

    /** [rollups] is a list of (scope, period) pairs to bump. Insert-or-ignore then add keeps this portable below API 30. */
    @Transaction
    override suspend fun record(row: AiUsageEntity, rollups: List<Pair<String, String>>) {
        insertCall(row)
        for ((scope, period) in rollups) {
            ensureRollup(AiUsageRollupEntity(scope, period, 0, 0, 0, 0))
            bump(scope, period, row.tokensIn, row.tokensOut, row.costMicros)
        }
    }

    @Query("SELECT * FROM ai_usage_rollup WHERE scope = :scope AND period = :period")
    abstract override suspend fun rollup(scope: String, period: String): AiUsageRollupEntity?

    @Query("SELECT * FROM ai_usage ORDER BY at DESC, id DESC LIMIT :limit")
    abstract override suspend fun recent(limit: Int): List<AiUsageEntity>

    @Query("SELECT * FROM ai_usage WHERE runId = :runId ORDER BY at ASC, id ASC")
    abstract override suspend fun forRun(runId: String): List<AiUsageEntity>

    @Query("DELETE FROM ai_usage WHERE at < :before")
    abstract override suspend fun deleteCallsBefore(before: Long): Int

    @Query("DELETE FROM ai_usage_rollup WHERE (period LIKE 'd:%' AND period < :dayPeriod) OR (period LIKE 'm:%' AND period < :monthPeriod)")
    abstract override suspend fun deleteRollupsBefore(dayPeriod: String, monthPeriod: String): Int
}

/** Row access for [RoomAiEventLog]. */
interface EventAccess {
    suspend fun append(row: AiEventEntity)
    suspend fun maxSeq(sessionId: String): Int?
    suspend fun forSession(sessionId: String, afterSeq: Int, limit: Int): List<AiEventEntity>
    suspend fun countForSession(sessionId: String): Int
    suspend fun deleteSession(sessionId: String)
    suspend fun deleteBefore(before: Long): Int
    suspend fun trimSession(sessionId: String, keepFromSeq: Int)
    suspend fun total(): Int
    suspend fun trimOldest(count: Int)
    suspend fun clear()
}

@Dao
abstract class AiEventDao : EventAccess {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract override suspend fun append(row: AiEventEntity)

    @Query("SELECT MAX(seq) FROM ai_event_log WHERE sessionId = :sessionId")
    abstract override suspend fun maxSeq(sessionId: String): Int?

    @Query("SELECT * FROM ai_event_log WHERE sessionId = :sessionId AND seq > :afterSeq ORDER BY seq ASC LIMIT :limit")
    abstract override suspend fun forSession(sessionId: String, afterSeq: Int, limit: Int): List<AiEventEntity>

    @Query("SELECT COUNT(*) FROM ai_event_log WHERE sessionId = :sessionId")
    abstract override suspend fun countForSession(sessionId: String): Int

    @Query("DELETE FROM ai_event_log WHERE sessionId = :sessionId")
    abstract override suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM ai_event_log WHERE at < :before")
    abstract override suspend fun deleteBefore(before: Long): Int

    @Query("DELETE FROM ai_event_log WHERE sessionId = :sessionId AND seq < :keepFromSeq")
    abstract override suspend fun trimSession(sessionId: String, keepFromSeq: Int)

    @Query("SELECT COUNT(*) FROM ai_event_log")
    abstract override suspend fun total(): Int

    @Query("DELETE FROM ai_event_log WHERE id IN (SELECT id FROM ai_event_log ORDER BY id ASC LIMIT :count)")
    abstract override suspend fun trimOldest(count: Int)

    @Query("DELETE FROM ai_event_log")
    abstract override suspend fun clear()
}
