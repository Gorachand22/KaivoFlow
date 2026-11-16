package com.kaivoflow.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/*
 * Step execution data (schema 5, spec P2-data-panels-params section 5.2).
 *
 * `step_executions` deliberately has no foreign key to `runs`: a Test Step execution has no Run row,
 * `RunDao.upsert` replaces rows (a REPLACE would cascade-delete the data of a Run that is merely
 * updated) and a missing parent must never make a capture fail. Deleting a Run removes its data
 * explicitly (RoomRunRepository, RunRetention). `step_port_data` does cascade from its execution.
 */

/** One execution of one Step inside a Run. [status] is the name of `StepRunStatus`. */
@Entity(
    tableName = "step_executions",
    primaryKeys = ["runId", "stepId", "runIndex"],
    indices = [Index("stepId", "startedAt"), Index("flowId", "startedAt")],
)
data class StepExecutionEntity(
    val runId: String,
    val stepId: String,
    val runIndex: Int,
    val flowId: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val attempts: Int,
    val errorJson: String?,
    val pinned: Boolean,
    /** A manual or test Run (retention treats these differently from production Runs). */
    val manual: Boolean,
    /** The capture channel overflowed: counts are kept, items are not. */
    val dataDropped: Boolean,
)

/** One port of one execution. [direction] is `in` or `out`. */
@Entity(
    tableName = "step_port_data",
    primaryKeys = ["runId", "stepId", "runIndex", "direction", "port"],
    foreignKeys = [
        ForeignKey(
            entity = StepExecutionEntity::class,
            parentColumns = ["runId", "stepId", "runIndex"],
            childColumns = ["runId", "stepId", "runIndex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
class StepPortDataEntity(
    val runId: String,
    val stepId: String,
    val runIndex: Int,
    val direction: String,
    val port: Int,
    val label: String?,
    val itemCount: Int,
    val storedCount: Int,
    val bytes: Long,
    val truncationJson: String?,
    val schemaJson: String?,
    val parentsJson: String,
    /** Concatenated gzip blocks of newline-delimited item JSON, 64 items per block. */
    val itemsBlob: ByteArray,
    /** Varints: item count, then the compressed length of each block. */
    val offsetsBlob: ByteArray,
)

/** Everything about a port except its blobs (read without loading the data). */
data class StepPortMeta(
    val runId: String,
    val stepId: String,
    val runIndex: Int,
    val direction: String,
    val port: Int,
    val label: String?,
    val itemCount: Int,
    val storedCount: Int,
    val bytes: Long,
    val truncationJson: String?,
    val schemaJson: String?,
    val parentsJson: String,
)

/** Size of one stored execution (global budget input). */
data class StoredExecutionRow(
    val runId: String,
    val stepId: String,
    val flowId: String,
    val startedAt: Long,
    val pinned: Boolean,
    val bytes: Long,
)

/** One Run that has stored data (count-based retention input). */
data class RunMetaRow(
    val runId: String,
    val flowId: String,
    val startedAt: Long,
    val manual: Boolean,
    val failed: Boolean,
)

/** Pinned data of one Step (schema 5). [portsJson] is a JSON array (one entry per output port) of item arrays. */
@Entity(tableName = "step_pins", primaryKeys = ["flowId", "stepId"])
data class StepPinEntity(
    val flowId: String,
    val stepId: String,
    val portsJson: String,
    val bytes: Long,
    val pinnedAt: Long,
    /** `Execution` or `Json`. */
    val source: String,
)
