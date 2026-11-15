package com.kaivoflow.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow as FlowStream

/**
 * Schema note: a Flow is stored as one JSON document plus the columns needed for list queries and
 * sorting. Steps and Streams are part of that document — they are only ever read as a whole Flow,
 * and a relational split would cost 3 joins for zero benefit on a phone.
 */
@Entity(tableName = "flows", indices = [Index("updatedAt"), Index("isFavorite"), Index("groupId")])
data class FlowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val flowJson: String,
    val updatedAt: Long,
    val createdAt: Long,
    val isFavorite: Boolean,
    val stepCount: Int,
    val lastRunStatus: String? = null,
    val lastRunAt: Long? = null,
    val groupId: String? = null,
)

@Entity(tableName = "workflow_groups", indices = [Index("name")])
data class WorkflowGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val color: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "workflow_variables", indices = [Index("groupId"), Index("key")])
data class WorkflowVariableEntity(
    @PrimaryKey val id: String,
    val groupId: String?,
    val key: String,
    val value: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "runs", indices = [Index("flowId"), Index("startedAt")])
data class RunEntity(
    @PrimaryKey val id: String,
    val flowId: String,
    val flowName: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val trigger: String,
    val error: String?,
    val itemsOut: Int,
    val runJson: String,
)

/** Credential ciphertext. The key lives in the Android Keystore, never in the database. */
@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val id: String,
    val type: String,
    val label: String,
    val cipherText: String,
    val createdAt: Long,
)

@Dao
interface FlowDao {
    @Query("SELECT * FROM flows ORDER BY isFavorite DESC, updatedAt DESC")
    fun observeAll(): FlowStream<List<FlowEntity>>

    @Query("SELECT * FROM flows WHERE id = :id")
    fun observe(id: String): FlowStream<FlowEntity?>

    @Query("SELECT * FROM flows WHERE id = :id")
    suspend fun byId(id: String): FlowEntity?

    @Query("SELECT * FROM flows")
    suspend fun all(): List<FlowEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FlowEntity)

    @Query("DELETE FROM flows WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE flows SET lastRunStatus = :status, lastRunAt = :at WHERE id = :id")
    suspend fun updateLastRun(id: String, status: String, at: Long)

    @Query("UPDATE flows SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE flows SET groupId = :groupId WHERE id = :id")
    suspend fun updateGroup(id: String, groupId: String?)
}

@Dao
interface WorkflowGroupDao {
    @Query("SELECT * FROM workflow_groups ORDER BY name ASC")
    fun observeAll(): FlowStream<List<WorkflowGroupEntity>>

    @Query("SELECT * FROM workflow_groups WHERE id = :id")
    suspend fun byId(id: String): WorkflowGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorkflowGroupEntity)

    @Query("DELETE FROM workflow_groups WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface WorkflowVariableDao {
    @Query("SELECT * FROM workflow_variables ORDER BY `key` ASC")
    fun observeAll(): FlowStream<List<WorkflowVariableEntity>>

    @Query("SELECT * FROM workflow_variables WHERE groupId = :groupId OR groupId IS NULL ORDER BY `key` ASC")
    fun observeForGroup(groupId: String): FlowStream<List<WorkflowVariableEntity>>

    @Query("SELECT * FROM workflow_variables WHERE groupId = :groupId OR groupId IS NULL")
    suspend fun forGroup(groupId: String): List<WorkflowVariableEntity>

    @Query("SELECT * FROM workflow_variables")
    suspend fun all(): List<WorkflowVariableEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorkflowVariableEntity)

    @Query("DELETE FROM workflow_variables WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RunDao {
    @Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): FlowStream<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE flowId = :flowId ORDER BY startedAt DESC LIMIT :limit")
    fun observeForFlow(flowId: String, limit: Int = 50): FlowStream<List<RunEntity>>

    @Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<RunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RunEntity)

    @Query("DELETE FROM runs WHERE startedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM runs")
    suspend fun clear()
}

@Entity(tableName = "data_tables", indices = [Index("name")])
data class DataTableEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val columnsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "data_table_rows", indices = [Index("tableId")])
data class DataTableRowEntity(
    @PrimaryKey val id: String,
    val tableId: String,
    val dataJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials")
    fun observeAll(): FlowStream<List<CredentialEntity>>

    @Query("SELECT * FROM credentials")
    suspend fun all(): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun byId(id: String): CredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CredentialEntity)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DataTableDao {
    @Query("SELECT * FROM data_tables ORDER BY updatedAt DESC")
    fun observeAll(): FlowStream<List<DataTableEntity>>

    @Query("SELECT * FROM data_tables WHERE id = :id")
    suspend fun byId(id: String): DataTableEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTable(table: DataTableEntity)

    @Query("DELETE FROM data_tables WHERE id = :id")
    suspend fun deleteTable(id: String)

    @Query("SELECT * FROM data_table_rows WHERE tableId = :tableId ORDER BY createdAt ASC")
    fun observeRows(tableId: String): FlowStream<List<DataTableRowEntity>>

    @Query("SELECT * FROM data_table_rows WHERE tableId = :tableId ORDER BY createdAt ASC")
    suspend fun rowsForTable(tableId: String): List<DataTableRowEntity>

    @Query("SELECT COUNT(*) FROM data_table_rows WHERE tableId = :tableId")
    fun observeRowCount(tableId: String): FlowStream<Int>

    @Query("SELECT COUNT(*) FROM data_table_rows WHERE tableId = :tableId")
    suspend fun rowCount(tableId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRow(row: DataTableRowEntity)

    @Query("DELETE FROM data_table_rows WHERE id = :rowId")
    suspend fun deleteRow(rowId: String)

    @Query("DELETE FROM data_table_rows WHERE tableId = :tableId")
    suspend fun deleteRowsForTable(tableId: String)
}

@Database(
    entities = [
        FlowEntity::class,
        RunEntity::class,
        CredentialEntity::class,
        WorkflowGroupEntity::class,
        WorkflowVariableEntity::class,
        DataTableEntity::class,
        DataTableRowEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class KaivoDatabase : RoomDatabase() {
    abstract fun flowDao(): FlowDao
    abstract fun runDao(): RunDao
    abstract fun credentialDao(): CredentialDao
    abstract fun workflowGroupDao(): WorkflowGroupDao
    abstract fun workflowVariableDao(): WorkflowVariableDao
    abstract fun dataTableDao(): DataTableDao

    companion object {
        const val NAME = "kaivoflow.db"
    }
}
