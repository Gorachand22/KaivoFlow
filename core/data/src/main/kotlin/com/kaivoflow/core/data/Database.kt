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
@Entity(tableName = "flows", indices = [Index("updatedAt"), Index("isFavorite")])
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

@Database(
    entities = [FlowEntity::class, RunEntity::class, CredentialEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class KaivoDatabase : RoomDatabase() {
    abstract fun flowDao(): FlowDao
    abstract fun runDao(): RunDao
    abstract fun credentialDao(): CredentialDao

    companion object {
        const val NAME = "kaivoflow.db"
    }
}
