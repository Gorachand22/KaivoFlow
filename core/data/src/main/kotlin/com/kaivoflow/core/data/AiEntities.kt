package com.kaivoflow.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Schema 7. Small key/value rows for one-time vault work (migration flags). A flag row means "this
 * migration finished"; running a migration twice is harmless, but the flag keeps it from running at all.
 */
@Entity(tableName = "vault_meta")
data class VaultMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

/**
 * Persistent agent memory, one row per (agent scope, hashed session key). [rowKey] is `scope:sha256(key)`:
 * the session key may come from an expression that data influences, so it is never used as a raw id.
 * [payload] is redacted JSON of the kept messages. [lastAccessAt] drives the 30-day idle sweep and
 * least-recently-used eviction.
 */
@Entity(tableName = "agent_memory", indices = [Index("scope"), Index("lastAccessAt")])
data class AgentMemoryEntity(
    @PrimaryKey val rowKey: String,
    val scope: String,
    val payload: String,
    val messageCount: Int,
    val bytes: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long,
)

/** One model call: who asked, which provider and model, tokens and the estimated cost (micro-USD, integer). */
@Entity(tableName = "ai_usage", indices = [Index("at"), Index("agentId", "at"), Index("runId")])
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val agentId: String,
    val flowId: String?,
    val runId: String?,
    val provider: String,
    val model: String,
    val tokensIn: Long,
    val tokensOut: Long,
    val costMicros: Long,
    val at: Long,
)

/**
 * Running totals per (scope, period). Scope is `all`, `agent:<id>` or `provider:<name>`; period is
 * `d:YYYY-MM-DD` or `m:YYYY-MM` in the device's local time zone. These rows are the budget counters.
 */
@Entity(tableName = "ai_usage_rollup", primaryKeys = ["scope", "period"], indices = [Index("period")])
data class AiUsageRollupEntity(
    val scope: String,
    val period: String,
    val calls: Long,
    val tokensIn: Long,
    val tokensOut: Long,
    val costMicros: Long,
)

/**
 * Append-only event log of the assistant and agent runs, for replay. [seq] is dense per session.
 * [payload] is redacted and cut to the cap before it is stored.
 */
@Entity(tableName = "ai_event_log", indices = [Index(value = ["sessionId", "seq"], unique = true), Index("at")])
data class AiEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val seq: Int,
    val kind: String,
    val payload: String,
    val at: Long,
)
