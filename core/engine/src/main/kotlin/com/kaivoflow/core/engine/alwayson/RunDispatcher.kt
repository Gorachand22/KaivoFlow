package com.kaivoflow.core.engine.alwayson

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.kaivoflow.core.engine.RetryBackoff

sealed interface RunOutcome {
    /** A Run executed (whatever its final status). The row is Done. */
    data class Completed(val runId: String?, val status: String = "") : RunOutcome
    /** The Flow is busy with something that is not a dispatcher Run (a manual Run). Retry after a back-off. */
    data object Busy : RunOutcome
    /** The row must not run (Flow deleted, paused or unpublished). */
    data class Dropped(val reason: String) : RunOutcome
}

fun interface RunExecutor {
    /** [onStarted] must be called as soon as the Run id exists, before the Flow's Steps execute. */
    suspend fun execute(record: InboxRecord, onStarted: suspend (runId: String) -> Unit): RunOutcome
}

/** Hooks for evidence keeping (expected_run linking, metrics). Failures here never affect the Run. */
interface DispatchListener {
    suspend fun onClaimed(record: InboxRecord) {}
    suspend fun onStarted(record: InboxRecord, runId: String) {}
    suspend fun onFinished(record: InboxRecord, outcome: RunOutcome?) {}
    suspend fun onDropped(record: InboxRecord, reason: String) {}
    suspend fun onInterrupted(count: Int) {}
}

data class DispatcherConfig(
    /** Global cap across all Flows (spec 3.11: default 3, protects memory). */
    val maxConcurrent: Int = 3,
    val leaseMs: Long = LeasePolicy.DEFAULT_LEASE_MS,
    val renewEveryMs: Long = LeasePolicy.RENEW_EVERY_MS,
    val scanBatch: Int = 100,
    /** The loop re-scans at least this often so expired leases and ended back-offs are picked up. */
    val pollMs: Long = 30_000L,
    val maxExecutorErrors: Int = 5,
)

/**
 * Claims rows from the [InboxStore] and runs them through a [RunExecutor].
 *
 * Rules: one Run per Flow at a time, in arrival order; at most [DispatcherConfig.maxConcurrent] Runs in
 * total; a row is never replayed once its Run started (at-most-once side effects). The lease is renewed
 * while the Run is in flight, so only a dead process lets a claim expire.
 */
class RunDispatcher(
    private val store: InboxStore,
    private val executor: RunExecutor,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val config: DispatcherConfig = DispatcherConfig(),
    private val listener: DispatchListener = object : DispatchListener {},
    private val backoffMs: (Int) -> Long = RetryBackoff::delayMs,
) {
    private val lock = Mutex()
    private val inFlight = HashMap<String, Job>()
    private val cooldownUntil = HashMap<String, Long>()
    private val busyAttempts = HashMap<String, Int>()
    private val errorAttempts = HashMap<Long, Int>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Number of Runs currently executing. */
    suspend fun activeCount(): Int = lock.withLock { inFlight.size }

    /** Tells the loop that the Inbox changed. Cheap and safe from any thread. */
    fun nudge() { wake.trySend(Unit) }

    data class PumpResult(val started: Int, val waitingForCooldownUntil: Long?, val moreQueued: Boolean)

    /** One scan: claim every row that may start now and launch it. */
    suspend fun pump(): PumpResult = lock.withLock {
        val now = clock.now()
        val interrupted = store.reapCrashed(now)
        if (interrupted > 0) runCatching { listener.onInterrupted(interrupted) }
        val rows = store.claimable(now, config.scanBatch)
        val foreignClaims = store.liveClaimedFlowIds(now)
        val blocked = HashSet<String>()
        var started = 0
        var nextCooldown: Long? = null
        var more = false
        for (row in rows) {
            if (row.flowId in blocked) { more = true; continue }
            if (inFlight.size >= config.maxConcurrent) { more = true; break }
            if (inFlight.containsKey(row.flowId) || row.flowId in foreignClaims) { blocked += row.flowId; more = true; continue }
            val cd = cooldownUntil[row.flowId]
            if (cd != null) {
                if (cd > now) {
                    blocked += row.flowId; more = true
                    nextCooldown = minOf(nextCooldown ?: cd, cd)
                    continue
                }
                cooldownUntil.remove(row.flowId)
            }
            if (!store.claim(row.id, now, now + config.leaseMs)) { blocked += row.flowId; more = true; continue }
            inFlight[row.flowId] = scope.launch { process(row) }
            started++
        }
        PumpResult(started, nextCooldown, more)
    }

    /** Runs the dispatcher until the scope is cancelled. Used by the foreground service. */
    suspend fun runLoop() {
        while (scope.isActive) {
            val result = pump()
            val waitMs = result.waitingForCooldownUntil?.let { (it - clock.now()).coerceIn(50L, config.pollMs) } ?: config.pollMs
            withTimeoutOrNull(waitMs) { wake.receive() }
        }
    }

    /**
     * Pumps until nothing is running and nothing claimable remains, or [maxWaitMs] passes. Used by the
     * fallback worker. Rows that wait for a Flow back-off count as remaining work until the deadline.
     */
    suspend fun drainUntilIdle(maxWaitMs: Long = 10 * 60_000L) {
        val deadline = clock.now() + maxWaitMs
        while (true) {
            val result = pump()
            val running = activeCount()
            if (running == 0 && result.started == 0 && !result.moreQueued) return
            if (clock.now() >= deadline) return
            withTimeoutOrNull(100L) { wake.receive() }
        }
    }

    /** Suspends until every in-flight Run has finished. Test and shutdown helper. */
    suspend fun awaitRunning() {
        while (true) {
            val jobs = lock.withLock { inFlight.values.toList() }
            if (jobs.isEmpty()) return
            jobs.forEach { it.join() }
        }
    }

    private suspend fun process(row: InboxRecord) {
        var runId: String? = null
        val renew = scope.launch {
            while (true) {
                delay(config.renewEveryMs)
                store.renewLease(row.id, clock.now() + config.leaseMs)
            }
        }
        var outcome: RunOutcome? = null
        try {
            runCatching { listener.onClaimed(row) }
            val result = executor.execute(row) { id ->
                runId = id
                store.markStarted(row.id, id, clock.now() + config.leaseMs)
                runCatching { listener.onStarted(row, id) }
            }
            outcome = result
            when (val o = result) {
                is RunOutcome.Completed -> { store.complete(row.id, o.runId ?: runId); resetBusy(row.flowId) }
                RunOutcome.Busy -> {
                    store.release(row.id)
                    val attempt = lock.withLock { (busyAttempts[row.flowId] ?: 0).also { busyAttempts[row.flowId] = it + 1 } }
                    lock.withLock { cooldownUntil[row.flowId] = clock.now() + backoffMs(attempt) }
                }
                is RunOutcome.Dropped -> { store.drop(row.id, o.reason); runCatching { listener.onDropped(row, o.reason) } }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // Stopped on purpose. A started Run keeps its record (the engine saves it as Canceled), so it is
                // finished; a row that never started goes straight back to Pending instead of waiting for the lease.
                if (runId != null) store.complete(row.id, runId) else store.release(row.id)
            }
            throw e
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                val started = runId
                if (started != null) {
                    store.complete(row.id, started)
                } else {
                    val n = lock.withLock { (errorAttempts[row.id] ?: 0) + 1 }
                    if (n >= config.maxExecutorErrors) {
                        lock.withLock { errorAttempts.remove(row.id) }
                        store.drop(row.id, "ExecutorError:${t.javaClass.simpleName}")
                    } else {
                        lock.withLock { errorAttempts[row.id] = n; cooldownUntil[row.flowId] = clock.now() + backoffMs(n - 1) }
                        store.release(row.id)
                    }
                }
            }
        } finally {
            renew.cancel()
            withContext(NonCancellable) {
                runCatching { listener.onFinished(row, outcome) }
                lock.withLock { inFlight.remove(row.flowId) }
            }
            wake.trySend(Unit)
        }
    }

    private suspend fun resetBusy(flowId: String) = lock.withLock { busyAttempts.remove(flowId); Unit }
}
