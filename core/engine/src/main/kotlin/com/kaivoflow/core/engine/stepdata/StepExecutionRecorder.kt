package com.kaivoflow.core.engine.stepdata

import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.SchemaInference
import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.core.model.StepRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Identity of one execution of a Step inside a Run. */
data class StepExecutionKey(val runId: String, val stepId: String, val runIndex: Int = 0) {
    /** True for an execution written by Test Step: never part of a production or manual Run. */
    val isTest: Boolean get() = runId.startsWith(TEST_RUN_PREFIX)
}

/** Run ids of Test Step executions start with this, so storage can tell a test from a Run without a schema change. */
const val TEST_RUN_PREFIX = "test-"

/** Which Step and output port fed an input port. */
data class ParentRef(val stepId: String, val port: Int = 0)

/** Failure of a whole Step execution, as the panels show it. */
data class StepErrorInfo(
    val message: String,
    /** Index of the failing input item when one item caused it. */
    val itemIndex: Int? = null,
    val kind: String = "step",
    val details: String? = null,
    val httpStatus: Int? = null,
    val httpBody: String? = null,
)

/** What is known about the stored data of one port. [itemCount] is the true count. */
data class PortSnapshot(
    val port: Int,
    val label: String?,
    val itemCount: Int,
    val storedCount: Int,
    val bytes: Long,
    val truncation: Truncation?,
    val schema: SchemaNode?,
    val parents: List<ParentRef> = emptyList(),
)

/** Execution metadata written to the store. [dataDropped] means the capture channel overflowed and no items were kept. */
data class StepExecutionRecord(
    val key: StepExecutionKey,
    val flowId: String,
    val status: StepRunStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val attempts: Int,
    val inputs: List<PortSnapshot>,
    val outputs: List<PortSnapshot>,
    val error: StepErrorInfo?,
    val pinned: Boolean,
    val manual: Boolean,
    val dataDropped: Boolean = false,
)

/** Raw items of one port as the engine holds them. [totalCount] exceeds `items.size` when the list is already cut. */
data class RawPort(
    val port: Int,
    val label: String? = null,
    val items: List<Item>,
    val parents: List<ParentRef> = emptyList(),
    val totalCount: Int = items.size,
)

/** What the runner hands over after a Step finished: raw data, nothing processed yet. */
data class StepExecutionCapture(
    val key: StepExecutionKey,
    val flowId: String,
    val status: StepRunStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val attempts: Int = 1,
    val inputs: List<RawPort> = emptyList(),
    val outputs: List<RawPort> = emptyList(),
    val error: StepErrorInfo? = null,
    val pinned: Boolean = false,
    val manual: Boolean = false,
)

/** One port as it is stored: redacted, limited items and where each came from. */
data class CapturedPort(
    val snapshot: PortSnapshot,
    val items: List<Item>,
    val originalIndices: List<Int>,
)

data class CapturedExecution(
    val record: StepExecutionRecord,
    val inputs: List<CapturedPort>,
    val outputs: List<CapturedPort>,
)

/** Where captured executions go (Room in the app, a fake in tests). Called off the Run's thread. */
fun interface StepExecutionSink {
    suspend fun write(execution: CapturedExecution)
}

sealed interface RecordOutcome {
    data object Queued : RecordOutcome

    /** The channel was full or closed; the execution will be stored without data and flagged `dataDropped`. */
    data object Dropped : RecordOutcome
}

/**
 * Called by the runner after each Step. Must be cheap and never block or fail the Run
 * (spec 5.3 rule 6).
 */
interface StepExecutionRecorder {
    fun record(capture: StepExecutionCapture): RecordOutcome

    /** Waits until everything queued so far reached the sink. */
    suspend fun flush()

    /** Stops accepting, drains what is queued (up to [timeoutMillis]) and stops. */
    suspend fun close(timeoutMillis: Long = 5_000)
}

object NoopStepExecutionRecorder : StepExecutionRecorder {
    override fun record(capture: StepExecutionCapture): RecordOutcome = RecordOutcome.Dropped
    override suspend fun flush() {}
    override suspend fun close(timeoutMillis: Long) {}
}

/**
 * Bounded-channel recorder. [record] only tries to enqueue; limits, redaction, schema inference
 * and the sink call happen on the consumer coroutine in [scope]. When the channel is full the
 * capture is dropped (the Run is never slowed down) and a small marker record without data,
 * flagged `dataDropped`, is queued on a side list so the panels can say "data not captured".
 * A failing sink is counted and swallowed.
 */
class ChannelStepExecutionRecorder(
    private val sink: StepExecutionSink,
    scope: CoroutineScope,
    private val policy: StepDataPolicy = StepDataPolicy(),
    capacity: Int = DEFAULT_CAPACITY,
    private val onError: (Throwable) -> Unit = {},
) : StepExecutionRecorder {

    private sealed interface Msg {
        class Write(val capture: StepExecutionCapture) : Msg
        class Flush(val done: CompletableDeferred<Unit>) : Msg
    }

    private val channel = Channel<Msg>(capacity.coerceAtLeast(1))
    private val markers = ConcurrentLinkedQueue<CapturedExecution>()
    private val markerCount = AtomicInteger(0)
    private val dropped = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val written = AtomicLong(0)

    /** Captures that could not be queued (including ones whose marker also did not fit). */
    val droppedCount: Long get() = dropped.get()

    /** Executions the sink accepted, markers included. */
    val writtenCount: Long get() = written.get()

    /** Sink or processing failures that were swallowed. */
    val failureCount: Long get() = failures.get()

    private val job: Job = scope.launch {
        for (msg in channel) {
            drainMarkers()
            when (msg) {
                is Msg.Write -> process(msg.capture)
                is Msg.Flush -> { drainMarkers(); msg.done.complete(Unit) }
            }
        }
        drainMarkers()
    }

    override fun record(capture: StepExecutionCapture): RecordOutcome {
        if (channel.trySend(Msg.Write(capture)).isSuccess) return RecordOutcome.Queued
        dropped.incrementAndGet()
        if (markerCount.get() < MAX_MARKERS) {
            markerCount.incrementAndGet()
            markers.add(marker(capture))
        }
        return RecordOutcome.Dropped
    }

    override suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        try {
            channel.send(Msg.Flush(done))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // closed: nothing more will be queued
        }
        done.await()
    }

    override suspend fun close(timeoutMillis: Long) {
        channel.close()
        if (withTimeoutOrNull(timeoutMillis) { job.join() } == null) job.cancel()
    }

    private suspend fun drainMarkers() {
        while (true) {
            val m = markers.poll() ?: return
            markerCount.decrementAndGet()
            deliver(m)
        }
    }

    private suspend fun process(capture: StepExecutionCapture) {
        val captured = try {
            build(capture)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failures.incrementAndGet(); onError(e); return
        }
        deliver(captured)
    }

    private suspend fun deliver(execution: CapturedExecution) {
        try {
            sink.write(execution)
            written.incrementAndGet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failures.incrementAndGet(); onError(e)
        }
    }

    private fun build(c: StepExecutionCapture): CapturedExecution {
        val ins = c.inputs.map { capturePort(it) }
        val outs = c.outputs.map { capturePort(it) }
        return CapturedExecution(
            record = StepExecutionRecord(
                key = c.key, flowId = c.flowId, status = c.status, startedAt = c.startedAt, finishedAt = c.finishedAt,
                attempts = c.attempts, inputs = ins.map { it.snapshot }, outputs = outs.map { it.snapshot },
                error = c.error, pinned = c.pinned, manual = c.manual,
            ),
            inputs = ins,
            outputs = outs,
        )
    }

    private fun capturePort(raw: RawPort): CapturedPort {
        val cap = policy.capture(raw.items, raw.totalCount)
        val schema = if (cap.items.isEmpty()) null else SchemaInference.infer(cap.items.asSequence())
        val snapshot = PortSnapshot(
            port = raw.port, label = raw.label, itemCount = cap.itemCount, storedCount = cap.items.size,
            bytes = cap.bytes, truncation = cap.truncation, schema = schema, parents = raw.parents,
        )
        return CapturedPort(snapshot, cap.items, cap.originalIndices)
    }

    /** Record without data for a capture that did not fit: counts are kept, items are not. */
    private fun marker(c: StepExecutionCapture): CapturedExecution {
        fun empty(p: RawPort) = CapturedPort(
            PortSnapshot(p.port, p.label, maxOf(p.totalCount, p.items.size), 0, 0L, null, null, p.parents),
            emptyList(), emptyList(),
        )
        val ins = c.inputs.map { empty(it) }
        val outs = c.outputs.map { empty(it) }
        return CapturedExecution(
            StepExecutionRecord(
                key = c.key, flowId = c.flowId, status = c.status, startedAt = c.startedAt, finishedAt = c.finishedAt,
                attempts = c.attempts, inputs = ins.map { it.snapshot }, outputs = outs.map { it.snapshot },
                error = c.error, pinned = c.pinned, manual = c.manual, dataDropped = true,
            ),
            ins, outs,
        )
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
        const val MAX_MARKERS = 1024
    }
}
