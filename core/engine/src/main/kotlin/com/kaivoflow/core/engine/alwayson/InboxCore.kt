package com.kaivoflow.core.engine.alwayson

import kotlin.math.abs

enum class InboxRowState {
    Pending, Claimed, Done, Dropped;

    val db: String get() = name.uppercase()

    companion object {
        fun fromDb(v: String?): InboxRowState = entries.firstOrNull { it.db == v } ?: Dropped
    }
}

data class InboxRecord(
    val id: Long,
    val flowId: String,
    val sparkId: String,
    val kind: String,
    val payload: String?,
    val fileRef: String?,
    val receivedAt: Long,
    val state: InboxRowState,
    val dedupeKey: String,
    val runId: String?,
    val leaseUntil: Long?,
    val dropReason: String?,
)

data class NewInboxRow(
    val flowId: String,
    val sparkId: String,
    val kind: String,
    val payload: String?,
    val fileRef: String?,
    val receivedAt: Long,
    val dedupeKey: String,
)

/**
 * Durable queue behind [SparkInbox] and [RunDispatcher]. The Room adapter lives in core:data; tests use
 * an in-memory store with the same semantics as the SQL (see LeasePolicy for the shared rule).
 */
interface InboxStore {
    /** Returns the new id, or null when [NewInboxRow.dedupeKey] already exists. */
    suspend fun insert(row: NewInboxRow): Long?
    suspend fun byDedupeKey(key: String): InboxRecord?
    suspend fun get(id: Long): InboxRecord?

    /** Offerable rows in arrival order (see [LeasePolicy.isOffered]). */
    suspend fun claimable(now: Long, limit: Int): List<InboxRecord>

    /** Flows that currently hold a live (unexpired) claim, so a later row of the same Flow must wait. */
    suspend fun liveClaimedFlowIds(now: Long): Set<String>

    suspend fun claim(id: Long, now: Long, leaseUntil: Long): Boolean
    suspend fun renewLease(id: Long, leaseUntil: Long): Boolean

    /** Records that a Run began for this claim; from now on an expired lease means Crashed, never a replay. */
    suspend fun markStarted(id: Long, runId: String, leaseUntil: Long): Boolean
    suspend fun complete(id: Long, runId: String?): Boolean
    suspend fun release(id: Long): Boolean
    suspend fun drop(id: Long, reason: String): Boolean

    /** Started claims whose lease expired with no process (see [LeasePolicy.isCrashed]). */
    suspend fun crashedClaims(now: Long): List<InboxRecord>

    /** Drops one crashed claim with reason [RunCrash.DROP_REASON]. False when it was not (or no longer) crashed. */
    suspend fun markCrashed(id: Long, now: Long): Boolean
    suspend fun openCount(): Int
}

/** The single rule for who may take a row. The SQL in `SparkInboxDao` mirrors it. */
object LeasePolicy {
    const val DEFAULT_LEASE_MS = 60_000L
    const val RENEW_EVERY_MS = 20_000L

    fun isOffered(state: InboxRowState, leaseUntil: Long?, runId: String?, now: Long): Boolean = when (state) {
        InboxRowState.Pending -> true
        InboxRowState.Claimed -> runId == null && (leaseUntil == null || leaseUntil < now)
        else -> false
    }

    /** A Run started under this claim and nobody renewed the lease: the process died mid-Run. */
    fun isCrashed(state: InboxRowState, leaseUntil: Long?, runId: String?, now: Long): Boolean =
        state == InboxRowState.Claimed && runId != null && (leaseUntil == null || leaseUntil < now)
}

object DedupeKeys {
    /** Schedule slot row: a duplicate fire (alarm plus watchdog catch-up) is a no-op. */
    fun schedule(flowId: String, slotEpochMs: Long) = "schedule:$flowId:$slotEpochMs"

    /** [sourceId] identifies the occurrence at the source (message timestamp and hash, notification key and post time). */
    fun event(kind: String, flowId: String, sparkId: String, sourceId: String) = "event:$kind:$flowId:$sparkId:$sourceId"

    fun boot(bootCount: Int, flowId: String, sparkId: String) = "boot:$bootCount:$flowId:$sparkId"

    /** Inverse of [schedule]: the Flow id and slot of a schedule key, or null for any other key. Flow ids may contain ':'. */
    fun parseSchedule(key: String): Pair<String, Long>? {
        if (!key.startsWith("schedule:")) return null
        val rest = key.removePrefix("schedule:")
        val cut = rest.lastIndexOf(':')
        if (cut <= 0) return null
        val slot = rest.substring(cut + 1).toLongOrNull() ?: return null
        return rest.substring(0, cut) to slot
    }
}

/** Keeps the 10 KB WorkManager Data limit out of the picture: big payloads are spilled to a file. */
interface PayloadFiles {
    /** Stores [text] and returns a reference that [read] and [delete] accept. */
    fun write(name: String, text: String): String
    fun read(ref: String): String?
    fun delete(ref: String)
}

data class NewSpark(
    val flowId: String,
    val sparkId: String,
    val kind: String,
    val payloadJson: String?,
    val dedupeKey: String,
)

sealed interface EnqueueResult {
    data class Stored(val id: Long, val spilledToFile: Boolean) : EnqueueResult
    data class Duplicate(val dedupeKey: String) : EnqueueResult
}

/**
 * Persist first, execute second (spec 3.1). Every Spark event and schedule slot goes through here.
 * [onDuplicate] is how a dropped duplicate leaves a record (a reliability event in the Android wiring).
 */
class SparkInbox(
    private val store: InboxStore,
    private val files: PayloadFiles,
    private val clock: Clock,
    private val inlineLimitBytes: Int = DEFAULT_INLINE_LIMIT_BYTES,
    private val onDuplicate: suspend (NewSpark) -> Unit = {},
) {
    private var fileCounter = 0L

    suspend fun enqueue(spark: NewSpark): EnqueueResult {
        if (store.byDedupeKey(spark.dedupeKey) != null) return duplicate(spark)
        val payload = spark.payloadJson
        var inline: String? = payload
        var ref: String? = null
        if (payload != null && payload.length * 3 > inlineLimitBytes && payload.toByteArray(Charsets.UTF_8).size > inlineLimitBytes) {
            ref = files.write(fileName(spark.dedupeKey), payload)
            inline = null
        }
        val id = store.insert(
            NewInboxRow(spark.flowId, spark.sparkId, spark.kind, inline, ref, clock.now(), spark.dedupeKey),
        )
        if (id == null) {
            ref?.let { files.delete(it) } // lost a race with a concurrent insert of the same key
            return duplicate(spark)
        }
        return EnqueueResult.Stored(id, ref != null)
    }

    suspend fun payloadOf(record: InboxRecord): String? = record.payload ?: record.fileRef?.let { files.read(it) }

    /** Call when a row is finished (Done or Dropped) so a spilled payload file does not linger. */
    fun discardPayload(record: InboxRecord) { record.fileRef?.let { files.delete(it) } }

    private suspend fun duplicate(spark: NewSpark): EnqueueResult {
        onDuplicate(spark)
        return EnqueueResult.Duplicate(spark.dedupeKey)
    }

    @Synchronized
    private fun fileName(key: String): String = "${abs(key.hashCode())}-${clock.now()}-${fileCounter++}"

    companion object { const val DEFAULT_INLINE_LIMIT_BYTES = 32 * 1024 }
}
