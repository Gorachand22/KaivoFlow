package com.kaivoflow.core.engine.alwayson

class FakeClock(var t: Long = 1_000_000_000L) : Clock {
    override fun now(): Long = t
    fun advance(ms: Long) { t += ms }
}

class MemFiles : PayloadFiles {
    val files = HashMap<String, String>()
    override fun write(name: String, text: String): String { files["mem:$name"] = text; return "mem:$name" }
    override fun read(ref: String): String? = files[ref]
    override fun delete(ref: String) { files.remove(ref) }
}

/** In-memory twin of the Room store. It uses LeasePolicy, the same rule the SQL in SparkInboxDao spells out. */
class MemInboxStore(private val clock: Clock) : InboxStore {
    private val rows = ArrayList<InboxRecord>()
    private var nextId = 1L
    private inline fun <T> sync(f: () -> T): T = synchronized(rows) { f() }

    val snapshot: List<InboxRecord> get() = sync { rows.toList() }

    private fun replace(id: Long, f: (InboxRecord) -> InboxRecord): Boolean {
        val i = rows.indexOfFirst { it.id == id }
        if (i < 0) return false
        rows[i] = f(rows[i]); return true
    }
    private fun find(id: Long) = rows.firstOrNull { it.id == id }

    override suspend fun insert(row: NewInboxRow): Long? = sync {
        if (rows.any { it.dedupeKey == row.dedupeKey }) return@sync null
        val id = nextId++
        rows += InboxRecord(id, row.flowId, row.sparkId, row.kind, row.payload, row.fileRef, row.receivedAt, InboxRowState.Pending, row.dedupeKey, null, null, null)
        id
    }
    override suspend fun byDedupeKey(key: String) = sync { rows.firstOrNull { it.dedupeKey == key } }
    override suspend fun get(id: Long) = sync { find(id) }
    override suspend fun claimable(now: Long, limit: Int) = sync {
        rows.filter { LeasePolicy.isOffered(it.state, it.leaseUntil, it.runId, now) }.sortedBy { it.id }.take(limit)
    }
    override suspend fun liveClaimedFlowIds(now: Long) = sync {
        rows.filter { it.state == InboxRowState.Claimed && it.leaseUntil != null && it.leaseUntil >= now }.map { it.flowId }.toSet()
    }
    override suspend fun claim(id: Long, now: Long, leaseUntil: Long): Boolean = sync {
        val r = find(id) ?: return@sync false
        if (!LeasePolicy.isOffered(r.state, r.leaseUntil, r.runId, now)) return@sync false
        replace(id) { it.copy(state = InboxRowState.Claimed, leaseUntil = leaseUntil) }
    }
    override suspend fun renewLease(id: Long, leaseUntil: Long) = sync {
        find(id)?.takeIf { it.state == InboxRowState.Claimed }?.let { replace(id) { r -> r.copy(leaseUntil = leaseUntil) } } ?: false
    }
    override suspend fun markStarted(id: Long, runId: String, leaseUntil: Long) = sync {
        find(id)?.takeIf { it.state == InboxRowState.Claimed }?.let { replace(id) { r -> r.copy(runId = runId, leaseUntil = leaseUntil) } } ?: false
    }
    override suspend fun complete(id: Long, runId: String?) = sync {
        find(id)?.takeIf { it.state == InboxRowState.Claimed }?.let { replace(id) { r -> r.copy(state = InboxRowState.Done, runId = runId ?: r.runId, leaseUntil = null) } } ?: false
    }
    override suspend fun release(id: Long) = sync {
        find(id)?.takeIf { it.state == InboxRowState.Claimed }?.let { replace(id) { r -> r.copy(state = InboxRowState.Pending, leaseUntil = null) } } ?: false
    }
    override suspend fun drop(id: Long, reason: String) = sync {
        find(id)?.takeIf { it.state == InboxRowState.Pending || it.state == InboxRowState.Claimed }
            ?.let { replace(id) { r -> r.copy(state = InboxRowState.Dropped, dropReason = reason, leaseUntil = null) } } ?: false
    }
    override suspend fun crashedClaims(now: Long) = sync {
        rows.filter { LeasePolicy.isCrashed(it.state, it.leaseUntil, it.runId, now) }.sortedBy { it.id }
    }
    override suspend fun markCrashed(id: Long, now: Long): Boolean = sync {
        val r = find(id) ?: return@sync false
        if (!LeasePolicy.isCrashed(r.state, r.leaseUntil, r.runId, now)) return@sync false
        replace(id) { it.copy(state = InboxRowState.Dropped, dropReason = RunCrash.DROP_REASON, leaseUntil = null) }
    }
    override suspend fun openCount() = sync { rows.count { it.state == InboxRowState.Pending || it.state == InboxRowState.Claimed } }
}
