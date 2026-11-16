package com.kaivoflow.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow as FlowStream

@Dao
abstract class StepDataDao {
    /** REPLACE on the execution cascades away old port rows of the same key, then the new ports are inserted. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertExecution(row: StepExecutionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPorts(rows: List<StepPortDataEntity>)

    @Transaction
    open suspend fun write(execution: StepExecutionEntity, ports: List<StepPortDataEntity>) {
        insertExecution(execution)
        if (ports.isNotEmpty()) insertPorts(ports)
    }

    @Query("SELECT * FROM step_executions WHERE flowId = :flowId AND stepId = :stepId ORDER BY startedAt DESC, runIndex DESC LIMIT :limit")
    abstract fun observeExecutions(flowId: String, stepId: String, limit: Int): FlowStream<List<StepExecutionEntity>>

    @Query("SELECT * FROM step_executions WHERE runId = :runId AND stepId = :stepId AND runIndex = :runIndex")
    abstract suspend fun execution(runId: String, stepId: String, runIndex: Int): StepExecutionEntity?

    @Query("SELECT * FROM step_executions WHERE flowId = :flowId AND stepId = :stepId ORDER BY startedAt DESC, runIndex DESC LIMIT 1")
    abstract suspend fun latest(flowId: String, stepId: String): StepExecutionEntity?

    @Query(
        "SELECT runId, stepId, runIndex, direction, port, label, itemCount, storedCount, bytes, truncationJson, schemaJson, parentsJson " +
            "FROM step_port_data WHERE runId = :runId AND stepId = :stepId AND runIndex = :runIndex ORDER BY direction, port",
    )
    abstract suspend fun portMetas(runId: String, stepId: String, runIndex: Int): List<StepPortMeta>

    @Query(
        "SELECT * FROM step_port_data WHERE runId = :runId AND stepId = :stepId AND runIndex = :runIndex " +
            "AND direction = :direction AND port = :port",
    )
    abstract suspend fun port(runId: String, stepId: String, runIndex: Int, direction: String, port: Int): StepPortDataEntity?

    @Query("DELETE FROM step_executions WHERE runId = :runId")
    abstract suspend fun deleteRun(runId: String)

    /** For Runs pruned by age: removes their data before the `runs` rows go. */
    @Query("DELETE FROM step_executions WHERE runId IN (SELECT id FROM runs WHERE startedAt < :before)")
    abstract suspend fun deleteForRunsOlderThan(before: Long)

    @Query("DELETE FROM step_executions")
    abstract suspend fun deleteAll()

    @Query("DELETE FROM step_executions WHERE flowId = :flowId")
    abstract suspend fun deleteForFlow(flowId: String)

    @Query(
        "SELECT e.runId AS runId, e.stepId AS stepId, e.flowId AS flowId, e.startedAt AS startedAt, e.pinned AS pinned, " +
            "COALESCE(SUM(p.bytes), 0) AS bytes FROM step_executions e " +
            "LEFT JOIN step_port_data p ON p.runId = e.runId AND p.stepId = e.stepId AND p.runIndex = e.runIndex " +
            "GROUP BY e.runId, e.stepId, e.runIndex",
    )
    abstract suspend fun usage(): List<StoredExecutionRow>

    @Query(
        "SELECT runId AS runId, MIN(flowId) AS flowId, MIN(startedAt) AS startedAt, MAX(manual) AS manual, " +
            "MAX(CASE WHEN status = 'Failed' THEN 1 ELSE 0 END) AS failed FROM step_executions GROUP BY runId",
    )
    abstract suspend fun runMetas(): List<RunMetaRow>
}

@Dao
abstract class StepPinDao {
    @Query("SELECT * FROM step_pins WHERE flowId = :flowId AND stepId = :stepId")
    abstract suspend fun byStep(flowId: String, stepId: String): StepPinEntity?

    @Query("SELECT * FROM step_pins WHERE flowId = :flowId AND stepId = :stepId")
    abstract fun observe(flowId: String, stepId: String): FlowStream<StepPinEntity?>

    @Query("SELECT * FROM step_pins WHERE flowId = :flowId")
    abstract suspend fun forFlow(flowId: String): List<StepPinEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(row: StepPinEntity)

    @Query("DELETE FROM step_pins WHERE flowId = :flowId AND stepId = :stepId")
    abstract suspend fun delete(flowId: String, stepId: String)

    @Query("DELETE FROM step_pins WHERE flowId = :flowId")
    abstract suspend fun deleteForFlow(flowId: String)
}
