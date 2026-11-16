package com.kaivoflow.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Always-On Engine tables (schema 4, docs/specs/P2-always-on-engine.md sections 3.9, 4.5, 6).
 * Enum-like columns are Strings with the constant sets below so a future value never crashes a read.
 * No foreign keys: rows for a deleted Flow are removed by the repositories, and a missing parent must
 * never make an Inbox or log write fail.
 */

object InboxState { const val PENDING = "PENDING"; const val CLAIMED = "CLAIMED"; const val DONE = "DONE"; const val DROPPED = "DROPPED" }

object RegistrationState {
    const val ARMED = "ARMED"; const val PENDING = "PENDING"
    const val FAILED_TRANSIENT = "FAILED_TRANSIENT"; const val FAILED_PERMANENT = "FAILED_PERMANENT"
}

object ExpectedRunState {
    const val PENDING = "PENDING"; const val FIRED = "FIRED"; const val LATE = "LATE"; const val MISSED = "MISSED"
    const val SKIPPED = "SKIPPED"; const val PAUSED = "PAUSED"; const val SUPERSEDED = "SUPERSEDED"
}

/** A published revision of a Flow. Immutable once written; also the Flow history. */
@Entity(tableName = "flow_versions", primaryKeys = ["flowId", "revision"])
data class FlowVersionEntity(
    val flowId: String,
    val revision: Int,
    val savedAt: Long,
    val label: String,
    val flowJson: String,
)

/** Per-Spark truth about whether it is armed. [sparkId] is the Spark Step id. */
@Entity(tableName = "trigger_registration", primaryKeys = ["flowId", "sparkId"], indices = [Index("state")])
data class TriggerRegistrationEntity(
    val flowId: String,
    val sparkId: String,
    val kind: String,
    val state: String,
    val detail: String?,
    val since: Long,
    val retryCount: Int,
    val nextRetryAt: Long?,
)

/** Why a Flow changed state. Columns are named fromState/toState because FROM and TO are SQL keywords. */
@Entity(tableName = "flow_lifecycle_log", indices = [Index("flowId", "at")])
data class FlowLifecycleLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val flowId: String,
    val at: Long,
    val fromState: String,
    val toState: String,
    val reason: String,
    /** user, engine, reconciler or import. */
    val actor: String,
)

/**
 * Durable intake of every Spark event and schedule slot. The auto-increment id gives arrival order.
 * [dedupeKey] is unique: a duplicate insert is a no-op that returns -1 (for schedules the key is
 * `schedule:<flowId>:<slotEpochMs>`). Large payloads go to a file and only [fileRef] is stored.
 */
@Entity(
    tableName = "spark_inbox",
    indices = [Index("dedupeKey", unique = true), Index("state", "receivedAt"), Index("flowId")],
)
data class SparkInboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val flowId: String,
    val sparkId: String,
    val kind: String,
    val payload: String?,
    val fileRef: String?,
    val receivedAt: Long,
    val state: String,
    val dedupeKey: String,
    val runId: String?,
    /** A CLAIMED row whose lease passed is offered again (a claimer died). */
    val leaseUntil: Long?,
    val dropReason: String?,
)

/** Schedule arming state (section 3.9). */
@Entity(tableName = "schedule_state", primaryKeys = ["flowId", "sparkId"], indices = [Index("nextDueAt")])
data class ScheduleStateEntity(
    val flowId: String,
    val sparkId: String,
    val configHash: String,
    val precision: String,
    val nextDueAt: Long?,
    val lastFiredAt: Long?,
    val lastSlotKey: String?,
    val armedAt: Long?,
    val armedMode: String?,
    val missedPolicy: String,
    val lastStatus: String?,
)

/** Append-only evidence of what should have run (section 6.3). */
@Entity(
    tableName = "expected_run",
    indices = [Index("flowId", "sparkId", "slotKey", unique = true), Index("state", "dueAt"), Index("dueAt")],
)
data class ExpectedRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val flowId: String,
    val sparkId: String,
    val slotKey: String,
    val dueAt: Long,
    val armedAt: Long,
    val state: String,
    val firedAt: Long?,
    val runId: String?,
    val latenessMs: Long?,
    val reason: String?,
)

@Entity(tableName = "engine_session", indices = [Index("startedAt")])
data class EngineSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val startReason: String,
    val bootCount: Int,
    /** Set on a deliberate stop. A session without it ended by process death. */
    val cleanStopAt: Long?,
    val endReason: String?,
)

/** One row, updated in place (section 6.1). The id is always [SINGLE_ROW_ID]. */
@Entity(tableName = "engine_heartbeat")
data class EngineHeartbeatEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val sessionId: Long,
    val lastBeatWall: Long,
    val lastBeatElapsed: Long,
    val bootCount: Int,
    val processStart: Long,
    val engineState: String,
    val pendingInbox: Int,
    val armedAlarms: Int,
    val listenerLastConnected: Long?,
    val listenerLastDisconnected: Long?,
    val lastAlarmFiredAt: Long?,
    val wakeLockMillisToday: Long,
    val memoryClass: Int,
    val appVersion: String,
) {
    companion object { const val SINGLE_ROW_ID = 1 }
}

@Entity(tableName = "reliability_event", indices = [Index("at"), Index("kind", "at")])
data class ReliabilityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: String,
    val severity: String,
    val flowId: String?,
    val detail: String?,
    val sessionId: Long?,
    /** Acknowledging clears the badge on the Reliability tab (spec 5.5). Added in schema 6. */
    @ColumnInfo(defaultValue = "0") val acknowledged: Boolean = false,
)

/** Daily aggregate of `expected_run` rows older than the 30 day window, kept 180 days (spec 6.3). Added in schema 6. */
@Entity(tableName = "expected_run_daily", primaryKeys = ["flowId", "dayStart"], indices = [Index("dayStart")])
data class ExpectedRunDailyEntity(
    val flowId: String,
    val dayStart: Long,
    val fired: Int,
    val late: Int,
    val missed: Int,
    val skipped: Int,
    val paused: Int,
    val p50Ms: Long,
    val p95Ms: Long,
    val maxMs: Long,
)
