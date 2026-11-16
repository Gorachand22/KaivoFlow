package com.kaivoflow.core.engine

import com.kaivoflow.core.engine.stepdata.NoopStepExecutionRecorder
import com.kaivoflow.core.engine.stepdata.ParentRef
import com.kaivoflow.core.engine.stepdata.PinReader
import com.kaivoflow.core.engine.stepdata.PinnedData
import com.kaivoflow.core.engine.stepdata.RawPort
import com.kaivoflow.core.engine.stepdata.StepErrorInfo
import com.kaivoflow.core.engine.stepdata.StepExecutionCapture
import com.kaivoflow.core.engine.stepdata.StepExecutionKey
import com.kaivoflow.core.engine.stepdata.StepExecutionRecorder
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.ItemError
import com.kaivoflow.core.model.ItemRef
import com.kaivoflow.core.model.StreamEdge
import com.kaivoflow.core.model.LogLevel
import com.kaivoflow.core.model.OnErrorPolicy
import com.kaivoflow.core.model.RunEvent
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.RunTrigger
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StepRun
import com.kaivoflow.core.model.StepRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class RunOptions(
    val mode: ExecutionMode = ExecutionMode.Manual,
    val maxConcurrency: Int = 3,
    /** Overrides the Flow-level timeout. */
    val timeoutMs: Long? = null,
    /** Re-run Steps whose output was Frozen. */
    val ignoreFrozen: Boolean = false,
    val itemLimit: Int? = null,
    /** Nested composition depth; 0 for a top-level Run. */
    val subFlowDepth: Int = 0,
)

/**
 * The KaivoFlow executor.
 *
 * Improvements over a naive "walk the nodes in order" runner:
 *  • Independent branches run concurrently (bounded), so a Flow with three API calls takes
 *    the time of the slowest one, not the sum.
 *  • Routing is item-aware: a branch that produced zero items does not execute downstream,
 *    and Steps report Skipped instead of silently "succeeding".
 *  • Per-Step retry/backoff, timeout and error policy; errors can be routed to an error Step.
 *  • Fully cancellable: cancelling the calling coroutine cancels the Run without leaving a
 *    Step stuck in Running.
 */
class FlowRunner(
    private val registry: NodeRegistry,
    private val evaluator: ExpressionEvaluator,
    private val http: HttpGateway,
    private val credentials: CredentialProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    /** Supplied by the app so a Step can compose another Flow without importing Android/Room. */
    private val flowInvoker: FlowInvoker? = null,
    /** Receives the inputs and outputs of every finished Step. The default drops everything. */
    private val recorder: StepExecutionRecorder = NoopStepExecutionRecorder,
    /** Pinned data replayed for Steps in manual Runs; null means pins are not used. */
    private val pins: PinReader? = null,
) {
    private val validator = FlowValidator(registry)
    val compiler = FlowCompiler(validator)

    fun validate(flow: Flow): ValidationReport = validator.validate(flow)

    private companion object {
        const val FLUSH_TIMEOUT_MS = 5_000L
    }

    /** Runs a whole Flow. Cancelling the calling coroutine cancels the Run cleanly. */
    suspend fun run(
        flow: Flow,
        trigger: RunTrigger = RunTrigger(),
        options: RunOptions = RunOptions(),
        onEvent: (RunEvent) -> Unit = {},
        /** Called once the Run has a Spark and is about to execute, with a Running record to persist. */
        onRunStarted: (suspend (RunRecord) -> Unit)? = null,
    ): RunRecord = try {
        Execution(flow, trigger, options, onEvent, onRunStarted).execute()
    } finally {
        flushRecorder()
    }

    /**
     * Makes sure everything recorded so far reached the store, also when the Run was cancelled or
     * crashed, so finished Steps always keep their data. Bounded, and never fails the caller.
     */
    private suspend fun flushRecorder() {
        if (recorder === NoopStepExecutionRecorder) return
        withContext(NonCancellable) {
            try { withTimeoutOrNull(FLUSH_TIMEOUT_MS) { recorder.flush() } } catch (_: Throwable) { }
        }
    }

    /**
     * Executes one Step on its own — the "Test Step" button in the inspector.
     * Uses the previous Step's known output as input and never touches other Steps.
     */
    suspend fun testStep(
        flow: Flow,
        stepId: String,
        input: List<FlowData>,
        onEvent: (RunEvent) -> Unit = {},
    ): StepRun {
        val step = flow.step(stepId) ?: throw KaivoException("Step not found: $stepId")
        val pinnedInput = if (input.isEmpty()) pinnedParentItems(flow, stepId) else emptyList()
        val effectiveInput = if (input.isNotEmpty()) input else pinnedInput.ifEmpty { listOf(FlowData()) }
        val execution = Execution(flow, RunTrigger(kind = "test", items = effectiveInput.toImmutableList()), RunOptions(mode = ExecutionMode.TestStep), onEvent)
        onEvent(RunEvent.RunStarted(execution.runId, flow.id, 1))
        val result = try {
            execution.runWithRetries(step, Collected.of(effectiveInput))
        } catch (e: CancellationException) {
            onEvent(RunEvent.RunFinished(execution.runId, RunStatus.Canceled))
            throw e
        } finally {
            flushRecorder()
        }
        onEvent(
            RunEvent.RunFinished(
                execution.runId,
                if (result.status == StepRunStatus.Failed) RunStatus.Failed else RunStatus.Succeeded,
                result.record.error,
                result.record.durationMs ?: 0,
            )
        )
        return result.record
    }

    /** Items of the pinned parents of [stepId] (for Test Step without explicit input); empty when none are pinned. */
    private suspend fun pinnedParentItems(flow: Flow, stepId: String): List<FlowData> {
        val reader = pins ?: return emptyList()
        val out = ArrayList<FlowData>()
        for (edge in flow.streams.filter { it.toStepId == stepId }) {
            val data = try { reader.pinnedFor(flow.id, edge.fromStepId) } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
            data?.ports?.getOrNull(edge.fromPort)?.let { out += it }
        }
        return out
    }

    internal class NodeResult(
        val status: StepRunStatus,
        val outputs: Map<Int, List<FlowData>>,
        val itemsIn: Int,
        val durationMs: Long,
        val record: StepRun,
        val errorItems: List<FlowData> = emptyList(),
        val itemsFailed: Int = 0,
    )

    /** Everything a Step receives: flattened, per Stream and per input port, with item identities. */
    internal class Collected(
        val streams: List<StreamInput>,
        val items: List<FlowData>,
        val refs: List<ItemRef>,
        val ports: List<List<FlowData>>,
    ) {
        companion object {
            /** Input that did not come from upstream Steps (trigger items, Test Step input): no lineage. */
            fun of(items: List<FlowData>) = Collected(emptyList(), items, emptyList(), listOf(items))
        }
    }

    /**
     * One Run's mutable state. A runner instance is safely reused: all per-Run state lives here,
     * so two Flows can run at the same time on different coroutines.
     */
    private inner class Execution(
        val flow: Flow,
        val trigger: RunTrigger,
        val options: RunOptions,
        val onEvent: (RunEvent) -> Unit,
        val onRunStarted: (suspend (RunRecord) -> Unit)? = null,
    ) {
        val runId: String = newId()
        val startedAt: Long = clock()

        /**
         * Step outputs for every output port, keyed by Step ID, so renaming a Step never loses its
         * data. Expressions still say `${'$'}step("Send to Telegram")`; the scope builder maps the
         * name to the id through the Flow's Steps.
         */
        val stepOutputs = ConcurrentHashMap<String, Map<Int, List<FlowData>>>()
        @Volatile var stopMessage: String? = null
        val stepRuns: MutableList<StepRun> = Collections.synchronizedList(mutableListOf())
        val errorItems: MutableList<FlowData> = Collections.synchronizedList(mutableListOf())
        val failureMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val softFailures: MutableList<String> = Collections.synchronizedList(mutableListOf())

        lateinit var compiled: CompiledFlow
        private lateinit var selectedTriggerId: String

        private fun snapshotPorts(): Map<String, Map<Int, List<FlowData>>> = HashMap(stepOutputs)

        private fun portZero(ports: Map<String, Map<Int, List<FlowData>>>): Map<String, List<FlowData>> =
            ports.mapValues { (_, byPort) -> byPort[0].orEmpty() }

        /** Ends the Run after the current Step: remaining work is cancelled, the Run reports Stopped. */
        private fun requestStop(step: StepNode, message: String) {
            if (options.mode == ExecutionMode.TestStep || stopMessage != null) return
            stopMessage = message.ifBlank { "Stopped by \"${step.name}\"." }
            onEvent(RunEvent.Log(runId, "Run stopped by \"${step.name}\": $stopMessage", LogLevel.Warn))
            if (this::scope.isInitialized) scope.cancel(CancellationException("Run stopped by ${step.name}"))
        }

        /** After a Stop: every Step that never finished is reported Skipped instead of vanishing. */
        private fun markStopped() {
            val finished = synchronized(stepRuns) { stepRuns.map { it.stepId }.toSet() }
            compiled.order.filter { it.id !in finished }.forEach { markSkipped(it, "The Run was stopped before this Step ran.") }
        }

        suspend fun execute(): RunRecord {
            compiled = try {
                compiler.compile(flow)
            } catch (e: FlowNotRunnableException) {
                onEvent(RunEvent.RunStarted(runId, flow.id, flow.steps.size))
                onEvent(RunEvent.RunFinished(runId, RunStatus.Failed, e.message, clock() - startedAt))
                return RunRecord(
                    id = runId,
                    flowId = flow.id,
                    flowName = flow.name,
                    status = RunStatus.Failed,
                    startedAt = startedAt,
                    finishedAt = clock(),
                    trigger = trigger.kind,
                    error = e.message,
                )
            }

            // Exactly one Spark owns an event. Never feed one event to every Spark in a Flow.
            val enabledTriggers = flow.triggers.filterNot { it.disabled }
            val requested = trigger.startAtStepId
            val kind = trigger.kind.lowercase()
            val expectedType = when (kind) {
                // Manual testing and Sub-Flow composition may use whichever Spark is enabled.
                "manual", "test", "subflow" -> null
                "schedule", "scheduled" -> "spark.schedule"
                else -> if (kind.startsWith("spark.")) kind else "spark.$kind"
            }
            val selected = if (requested != null) {
                enabledTriggers.firstOrNull { it.id == requested && (expectedType == null || it.type == expectedType) }
            } else if (expectedType == null) {
                enabledTriggers.firstOrNull { it.type == "spark.manual" } ?: enabledTriggers.firstOrNull()
            } else {
                enabledTriggers.firstOrNull { it.type == expectedType }
            }
            if (selected == null) {
                val message = "No enabled Spark matches this Run. Check its trigger and configuration."
                onEvent(RunEvent.RunStarted(runId, flow.id, compiled.order.size))
                onEvent(RunEvent.RunFinished(runId, RunStatus.Failed, message, clock() - startedAt))
                return RunRecord(id = runId, flowId = flow.id, flowName = flow.name,
                    status = RunStatus.Failed, startedAt = startedAt, finishedAt = clock(),
                    trigger = trigger.kind, error = message)
            }
            selectedTriggerId = selected.id
            onEvent(RunEvent.RunStarted(runId, flow.id, compiled.order.size))
            // A failure to persist the start must never stop the Run itself.
            onRunStarted?.let { cb ->
                try {
                    cb(RunRecord(id = runId, flowId = flow.id, flowName = flow.name, status = RunStatus.Running,
                        startedAt = startedAt, trigger = trigger.kind, triggerData = trigger.meta))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) { }
            }

            val timeout = options.timeoutMs ?: flow.settings.executionTimeoutMs
            val status = try {
                withTimeout(timeout) { runDag() }
                when {
                    failureMessages.isNotEmpty() -> RunStatus.Failed
                    softFailures.isNotEmpty() -> RunStatus.PartialSuccess
                    else -> RunStatus.Succeeded
                }
            } catch (e: TimeoutCancellationException) {
                val message = "The Run hit its time limit (${timeout / 1000}s) and was stopped."
                failureMessages += message
                onEvent(RunEvent.Log(runId, message, LogLevel.Error))
                RunStatus.Failed
            } catch (e: CancellationException) {
                if (stopMessage == null) onEvent(RunEvent.Log(runId, "Run canceled.", LogLevel.Warn))
                if (stopMessage != null) {
                    markStopped()
                    if (failureMessages.isNotEmpty()) RunStatus.Failed else RunStatus.Canceled
                } else RunStatus.Canceled
            } catch (e: Throwable) {
                val message = e.message ?: e::class.java.simpleName
                failureMessages += message
                onEvent(RunEvent.Log(runId, "Run crashed: $message", LogLevel.Error))
                RunStatus.Failed
            }

            val finishedAt = clock()
            val record = RunRecord(
                id = runId,
                flowId = flow.id,
                flowName = flow.name,
                status = status,
                startedAt = startedAt,
                finishedAt = finishedAt,
                trigger = trigger.kind,
                stepRuns = stepRuns.toList().toImmutableList(),
                error = when (status) {
                    RunStatus.Failed -> failureMessages.firstOrNull() ?: "A Step failed."
                    RunStatus.PartialSuccess -> softFailures.firstOrNull()
                    RunStatus.Canceled -> stopMessage
                    else -> null
                },
                triggerData = trigger.meta,
            )
            onEvent(RunEvent.RunFinished(runId, record.status, record.error, finishedAt - startedAt))
            return record
        }

        /**
         * Scheduling model:
         * every Step gets one eagerly started Deferred in topological order. A Step waits for
         * its upstream results before acquiring a permit, so branches can run concurrently.
         */
        private suspend fun runDag() = coroutineScope {
            this@Execution.scope = this
            this@Execution.permits = Semaphore(options.maxConcurrency.coerceAtLeast(1))

            // Topological order guarantees a Step's upstream Deferred already exists, so every
            // Step can start eagerly: it suspends on its upstream instead of blocking a thread.
            // Sibling branches therefore run in parallel (bounded by the permit semaphore).
            compiled.order.forEach { step ->
                memo[step.id] = async { executeStep(step) }
            }

            // Awaiting every Step guarantees the whole DAG is settled. Real cancellation
            // (Run timeout / user cancel) must propagate, so it is re-thrown.
            compiled.order.forEach { step ->
                runCatching { memo.getValue(step.id).await() }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }

            // Dedicated error Step receives every error item once the graph has settled.
            val errorStepId = compiled.errorStepId
            if (errorStepId != null && errorItems.isNotEmpty()) {
                val errorStep = compiled.stepsById.getValue(errorStepId)
                runErrorStep(errorStep, errorItems.toList())
            }
            awaitAllDone()
        }

        /** Waits for any deferred still running (defensive; normally already settled). */
        private suspend fun awaitAllDone() {
            val pending = memo.values.filter { it.isActive || !it.isCompleted }
            if (pending.isNotEmpty()) runCatching { pending.awaitAll() }
        }

        private suspend fun executeStep(step: StepNode): NodeResult {
            // The dedicated error Step is driven by the runner after the graph settles, so it is
            // never executed as part of the normal top-to-bottom flow.
            if (step.id == compiled.errorStepId) {
                return NodeResult(StepRunStatus.Skipped, emptyMap(), 0, 0, StepRun(step.id, step.name, StepRunStatus.Skipped, clock(), clock()))
            }

            // Every Step runs at most once per Run; awaiting here is what makes the graph work.
            // ALL upstream Steps are awaited, not only the live ones: a Merge must see both
            // branches finished (or empty) before it runs.
            val upstreamResults = compiled.upstream[step.id].orEmpty().map { edge ->
                edge to memo.getValue(edge.fromStepId).await()
            }

            // An event belongs only to the selected Spark; disabled/other Sparks cannot fire.
            if (step.isTrigger && (step.disabled || step.id != selectedTriggerId)) {
                return markSkipped(step, "This Spark did not start this Run.")
            }

            // A switched-off ordinary Step passes its items straight through instead of dropping them.
            if (step.disabled) {
                val collected = collectInput(step, upstreamResults)
                return markBypassed(step, collected)
            }

            if (!step.isTrigger) {
                val hasIncoming = compiled.upstream[step.id].orEmpty().isNotEmpty()
                if (!hasIncoming) return markSkipped(step, "This Step is not connected to a Spark.")
                // Liveness is per PORT, not per Step: an If Step with a full "true" branch and an
                // empty "false" branch must not run the false branch's downstream.
                // A Step that failed with onError = Continue still counts as live (error item).
                val live = upstreamResults.filter { (edge, result) ->
                    result.outputs[edge.fromPort].orEmpty().isNotEmpty()
                }
                if (live.isEmpty()) return markSkipped(step, "No items reached this Step.")
            }

            val collected = collectInput(step, upstreamResults)
            if (collected.items.isEmpty() && !step.isTrigger) {
                return markSkipped(step, "The Step before this one produced no items.")
            }
            // Pinned data (manual Runs only): replay it instead of executing. Production Runs and
            // Runs with ignoreFrozen execute the Step normally.
            if (pins != null && options.mode == ExecutionMode.Manual && !options.ignoreFrozen) {
                val pinned = try {
                    pins.pinnedFor(flow.id, step.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                if (pinned != null) return usePinned(step, pinned, collected)
            }
            // Freeze Output: replay the pinned result instead of executing.
            val frozen = step.frozenOutput
            if (frozen != null && !options.ignoreFrozen && options.mode != ExecutionMode.TestStep) {
                return useFrozen(step, frozen.toList(), collected)
            }

            val result = permits.withPermit { runWithRetries(step, collected) }
            // Collect error items so the Flow's dedicated error Step can be invoked once at the end.
            result.errorItems.forEach { errorItems += it }
            return result
        }

        private val memo = ConcurrentHashMap<String, Deferred<NodeResult>>()
        private lateinit var scope: CoroutineScope
        private lateinit var permits: Semaphore

        private fun collectInput(step: StepNode, upstream: List<Pair<StreamEdge, NodeResult>>): Collected {
            if (step.isTrigger) {
                val raw = if (trigger.items.isEmpty()) {
                    if (options.mode == ExecutionMode.TestStep || trigger.kind.lowercase() in setOf("manual", "schedule", "scheduled", "subflow")) {
                        listOf(FlowData(json = defaultTriggerPayload(step)))
                    } else {
                        emptyList()
                    }
                } else trigger.items.toList()
                val limit = options.itemLimit ?: flow.settings.maxItemsPerStep
                if (raw.size <= limit) return Collected.of(raw)
                onEvent(RunEvent.Log(runId, "\"${step.name}\" received ${raw.size} items; processing the first $limit.", LogLevel.Warn))
                return Collected.of(raw.take(limit))
            }

            // One entry per incoming Stream, in Stream order. A Stream keeps its destination port,
            // so a multi-input Step gets separate lists; `items` is the same data flattened.
            val limit = options.itemLimit ?: flow.settings.maxItemsPerStep
            val total = upstream.sumOf { (edge, result) -> result.outputs[edge.fromPort].orEmpty().size }
            var budget = limit
            val streams = upstream
                .sortedBy { (edge, _) -> compiled.flow.streams.indexOf(edge) }
                .map { (edge, result) ->
                    val all = result.outputs[edge.fromPort].orEmpty()
                    val taken = all.take(budget.coerceAtLeast(0))
                    budget -= taken.size
                    StreamInput(
                        streamId = edge.id,
                        fromStepId = edge.fromStepId,
                        fromPort = edge.fromPort,
                        toPort = edge.toPort,
                        items = taken,
                        refs = taken.indices.map { ItemRef(edge.fromStepId, 0, it, edge.fromPort) },
                    )
                }
            if (total > limit) {
                onEvent(RunEvent.Log(runId, "\"${step.name}\" received $total items; processing the first $limit.", LogLevel.Warn))
            }
            val ports = if (streams.isEmpty()) listOf(emptyList()) else {
                (0..streams.maxOf { it.toPort }).map { p -> streams.filter { it.toPort == p }.flatMap { it.items } }
            }
            return Collected(streams, streams.flatMap { it.items }, streams.flatMap { it.refs }, ports)
        }

        private fun defaultTriggerPayload(step: StepNode): Map<String, Any?> {
            val now = clock()
            return when (step.type) {
                "spark.webhook" -> mapOf(
                    "headers" to mapOf(
                        "host" to "kaivoflow.local",
                        "user-agent" to "KaivoFlow/1.0",
                        "content-type" to "application/json",
                    ),
                    "params" to emptyMap<String, Any>(),
                    "query" to emptyMap<String, Any>(),
                    "body" to mapOf("message" to "Webhook trigger payload", "timestamp" to now),
                    "path" to (step.params["path"] as? String ?: "my-flow"),
                    "method" to (step.params["method"] as? String ?: "POST"),
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "spark.notification" -> mapOf(
                    "app" to "com.whatsapp",
                    "appName" to "WhatsApp",
                    "title" to (step.params["titleContains"] as? String ?: "Notification"),
                    "text" to (step.params["textContains"] as? String ?: "Sample notification text"),
                    "isOngoing" to false,
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "spark.sms" -> mapOf(
                    "from" to (step.params["senderContains"] as? String ?: "+919876543210"),
                    "body" to (step.params["bodyContains"] as? String ?: "Sample SMS message body"),
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "spark.battery" -> mapOf(
                    "level" to (step.params["threshold"] as? Number ?: 15),
                    "isCharging" to false,
                    "temperature" to 31.5,
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "spark.wifi" -> mapOf(
                    "ssid" to (step.params["ssidContains"] as? String ?: "MyHome_WiFi"),
                    "event" to (step.params["event"] as? String ?: "connected"),
                    "ipAddress" to "192.168.1.100",
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "spark.bluetooth" -> mapOf(
                    "name" to (step.params["deviceNameContains"] as? String ?: "Wireless Headphones"),
                    "event" to (step.params["event"] as? String ?: "connected"),
                    "address" to "12:34:56:78:9A:BC",
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "spark.geofence" -> mapOf(
                    "transition" to (step.params["transition"] as? String ?: "enter"),
                    "latitude" to (step.params["latitude"] as? Number ?: 20.2961),
                    "longitude" to (step.params["longitude"] as? Number ?: 85.8245),
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "spark.headset" -> mapOf(
                    "event" to (step.params["event"] as? String ?: "plugged"),
                    "hasMic" to true,
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "spark.boot" -> mapOf(
                    "event" to "boot_completed",
                    "reason" to "reboot",
                    "boot" to true,
                    "uptimeMs" to 60_000L,
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "spark.share" -> mapOf(
                    "text" to "https://kaivoflow.app",
                    "type" to "text",
                    "timestamp" to now,
                    "executionId" to runId,
                )
                else -> mapOf(
                    "timestamp" to now,
                    "executionId" to runId,
                    "trigger" to trigger.kind,
                    "stepId" to step.id,
                    "stepName" to step.name,
                )
            }
        }

        private fun portCountsOf(outputs: Map<Int, List<FlowData>>) =
            outputs.mapValues { it.value.size }.toImmutableMap()

        /** Inputs of a Step for the recorder: one raw port per destination port, with the parents that fed it. */
        private fun rawInputs(collected: Collected?): List<RawPort> {
            if (collected == null) return emptyList()
            return collected.ports.mapIndexed { p, items ->
                RawPort(
                    port = p,
                    items = items,
                    parents = collected.streams.filter { it.toPort == p }.map { ParentRef(it.fromStepId, it.fromPort) },
                )
            }
        }

        /** Hands one finished Step to the recorder. Cheap, never blocks, never fails the Run. */
        private fun recordStep(
            step: StepNode,
            collected: Collected?,
            outputs: Map<Int, List<FlowData>>,
            status: StepRunStatus,
            startedAt: Long,
            finishedAt: Long,
            attempts: Int = 1,
            error: String? = null,
            pinned: Boolean = false,
        ) {
            if (recorder === NoopStepExecutionRecorder) return
            try {
                recorder.record(
                    StepExecutionCapture(
                        key = StepExecutionKey(runId, step.id, 0),
                        flowId = flow.id,
                        status = status,
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        attempts = attempts,
                        inputs = rawInputs(collected),
                        outputs = outputs.entries.sortedBy { it.key }.map { (port, items) -> RawPort(port = port, items = items) },
                        error = error?.let { StepErrorInfo(message = it) },
                        pinned = pinned,
                        manual = options.mode != ExecutionMode.Production,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Capture must never fail a Run.
            }
        }

        /** Replays user-pinned data on every pinned port; the Step itself does not execute. */
        private fun usePinned(step: StepNode, pinned: PinnedData, collected: Collected): NodeResult {
            val now = clock()
            val outputs = LinkedHashMap<Int, List<FlowData>>()
            pinned.ports.forEachIndexed { port, items ->
                // Pinned items are replayed as stored: their old lineage no longer applies.
                outputs[port] = items.map { if (it.sources.isEmpty()) it else it.copy(sources = emptyList()) }
            }
            if (outputs.isEmpty()) outputs[0] = emptyList()
            stepOutputs[step.id] = outputs
            val counts = portCountsOf(outputs)
            val primary = outputs[0].orEmpty()
            val itemsOut = outputs.values.sumOf { it.size }
            val record = StepRun(
                stepId = step.id, stepName = step.name, status = StepRunStatus.Frozen, startedAt = now, finishedAt = now,
                itemsIn = collected.items.size, itemsOut = itemsOut, output = primary.toImmutableList(), portCounts = counts,
            )
            stepRuns += record
            recordStep(step, collected, outputs, StepRunStatus.Frozen, now, now, pinned = true)
            onEvent(RunEvent.StepFinished(runId, step.id, collected.items.size, itemsOut, 0, frozen = true, output = primary.toImmutableList(), portCounts = counts))
            return NodeResult(StepRunStatus.Frozen, outputs, collected.items.size, 0, record)
        }

        private fun useFrozen(step: StepNode, items: List<FlowData>, collected: Collected): NodeResult {
            val itemsIn = collected.items.size
            val now = clock()
            // Pinned items are replayed as they were stored: their old lineage no longer applies.
            val fresh = items.map { if (it.sources.isEmpty()) it else it.copy(sources = emptyList()) }
            stepOutputs[step.id] = mapOf(0 to fresh)
            val counts = persistentMapOfCounts(0 to fresh.size)
            val record = StepRun(
                stepId = step.id,
                stepName = step.name,
                status = StepRunStatus.Frozen,
                startedAt = now,
                finishedAt = now,
                itemsIn = itemsIn,
                itemsOut = fresh.size,
                output = fresh.toImmutableList(),
                portCounts = counts,
            )
            stepRuns += record
            recordStep(step, collected, mapOf(0 to fresh), StepRunStatus.Frozen, now, now, pinned = true)
            onEvent(RunEvent.StepFinished(runId, step.id, itemsIn, fresh.size, 0, frozen = true, output = fresh.toImmutableList(), portCounts = counts))
            return NodeResult(StepRunStatus.Frozen, mapOf(0 to fresh), itemsIn, 0, record)
        }

        private fun persistentMapOfCounts(vararg pairs: Pair<Int, Int>) = mapOf(*pairs).toImmutableMap()

        /** A disabled Step: reported as Skipped, but its items continue downstream untouched. */
        private fun markBypassed(step: StepNode, collected: Collected): NodeResult {
            val now = clock()
            val outputs = Lineage.fill(mapOf(0 to collected.items), collected.items, collected.refs)
            val items = outputs.getValue(0)
            stepOutputs[step.id] = outputs
            val counts = portCountsOf(outputs)
            val record = StepRun(step.id, step.name, StepRunStatus.Skipped, now, now, itemsIn = items.size, itemsOut = items.size, portCounts = counts)
            stepRuns += record
            recordStep(step, collected, outputs, StepRunStatus.Skipped, now, now)
            onEvent(RunEvent.StepSkipped(runId, step.id, "This Step is switched off; items passed through."))
            return NodeResult(StepRunStatus.Skipped, outputs, items.size, 0, record)
        }

        private fun markSkipped(step: StepNode, reason: String): NodeResult {
            val now = clock()
            val record = StepRun(step.id, step.name, StepRunStatus.Skipped, now, now)
            stepRuns += record
            onEvent(RunEvent.StepSkipped(runId, step.id, reason))
            return NodeResult(StepRunStatus.Skipped, emptyMap(), 0, 0, record)
        }

        suspend fun runWithRetries(step: StepNode, collected: Collected): NodeResult {
            val input = collected.items
            val executor = registry.executor(step.type)
                ?: return hardFail(step, "This Step type (${step.type}) is not available on this build.", input, 0, collected = collected)

            val attempts = if (step.retry.enabled || step.onError == OnErrorPolicy.Retry) {
                step.retry.maxAttempts.coerceAtLeast(1)
            } else 1

            var lastError: String? = null
            val stepStartedAt = clock()

            for (attempt in 1..attempts) {
                onEvent(RunEvent.StepStarted(runId, step.id, attempt))
                val attemptStarted = clock()
                try {
                    val ports = snapshotPorts()
                    val ctx = StepContext(
                        step = step,
                        flow = flow,
                        input = input,
                        runId = runId,
                        mode = options.mode,
                        attempt = attempt,
                        evaluator = evaluator,
                        http = http,
                        credentials = credentials,
                        clock = clock,
                        timezone = flow.settings.timezone,
                        stepOutputs = portZero(ports),
                        logger = { message, level -> onEvent(RunEvent.Log(runId, message, level)) },
                        flowInvoker = flowInvoker,
                        subFlowDepth = options.subFlowDepth,
                        inputs = collected.ports,
                        inputRefs = collected.refs,
                        streamInputs = collected.streams,
                        stepPorts = ports,
                    )
                    val timeout = step.timeoutMs
                    val result = if (timeout != null) {
                        withTimeout(timeout) { executor.execute(ctx) }
                    } else {
                        executor.execute(ctx)
                    }
                    val finished = clock()

                    // Per-item failures: the Step's policy decides what happens to the failed items.
                    val failures = result.failed.filter { it.inputIndex in input.indices || input.isEmpty() }
                    if (failures.isNotEmpty() && step.onError != OnErrorPolicy.Continue && step.onError != OnErrorPolicy.ErrorFlow) {
                        throw StepExecutionException(step.id, step.name, describeFailures(failures, input.size))
                    }
                    var outputs = Lineage.fill(result.outputs, input, collected.refs)
                    var failedItems: List<FlowData> = emptyList()
                    if (failures.isNotEmpty()) {
                        failedItems = failures.map { failure -> errorItemFor(step, failure, input, collected.refs, finished) }
                        if (step.onError == OnErrorPolicy.Continue) {
                            // Failed items travel on the primary port after the good ones, marked as errors.
                            outputs = outputs + (0 to outputs[0].orEmpty() + failedItems)
                        }
                    }
                    val itemsOut = outputs.values.sumOf { it.size }
                    val allFailed = failures.isNotEmpty() && failures.size >= input.size && result.outputs.values.all { it.isEmpty() }
                    val status = if (allFailed) StepRunStatus.Failed else StepRunStatus.Succeeded
                    val primary = outputs[0].orEmpty()
                    val counts = portCountsOf(outputs)
                    val summary = if (failures.isNotEmpty()) describeFailures(failures, input.size) else null
                    val record = StepRun(
                        stepId = step.id,
                        stepName = step.name,
                        status = status,
                        startedAt = attemptStarted,
                        finishedAt = finished,
                        itemsIn = input.size,
                        itemsOut = itemsOut,
                        error = summary,
                        output = primary.toImmutableList(),
                        attempts = attempt,
                        portCounts = counts,
                        itemsFailed = failures.size,
                    )
                    stepOutputs[step.id] = outputs
                    stepRuns += record
                    recordStep(step, collected, outputs, status, attemptStarted, finished, attempt, summary)
                    if (summary != null) {
                        softFailures += "${step.name}: $summary"
                        onEvent(RunEvent.Log(runId, "\"${step.name}\" kept going after item errors: $summary", LogLevel.Warn))
                    }
                    if (allFailed) onEvent(RunEvent.StepFailed(runId, step.id, summary ?: "All items failed", false))
                    onEvent(
                        RunEvent.StepFinished(
                            runId, step.id, input.size, itemsOut, finished - attemptStarted,
                            output = primary.toImmutableList(),
                            portCounts = counts,
                            itemsFailed = failures.size,
                        )
                    )
                    result.stop?.let { requestStop(step, it.message) }
                    return NodeResult(
                        status, outputs, input.size, finished - attemptStarted, record,
                        errorItems = if (step.onError == OnErrorPolicy.ErrorFlow) failedItems else emptyList(),
                        itemsFailed = failures.size,
                    )
                } catch (e: TimeoutCancellationException) {
                    // Distinguish "this Step timed out" from "the whole Run was cancelled".
                    // A dead parent means the Run hit its limit or the user hit Cancel.
                    if (!currentCoroutineContext().isActive) throw e
                    lastError = step.timeoutMs?.let { "Timed out after ${it}ms" }
                        ?: "The Step timed out (${e.message ?: "internal timeout"})"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
                }

                val willRetry = attempt < attempts
                onEvent(RunEvent.StepFailed(runId, step.id, lastError ?: "Unknown error", willRetry))
                if (willRetry) {
                    val wait = step.retry.delayFor(attempt)
                    onEvent(RunEvent.Log(runId, "Retrying \"${step.name}\" in ${wait}ms (attempt ${attempt + 1} of $attempts).", LogLevel.Warn))
                    delay(wait)
                }
            }

            val message = lastError ?: "Unknown error"
            val finished = clock()
            if (step.onError == OnErrorPolicy.Continue) {
                val errorItem = wholeStepErrorItem(step, message, finished)
                val outputs = Lineage.fill(mapOf(0 to listOf(errorItem)), input, collected.refs)
                val counts = portCountsOf(outputs)
                val record = StepRun(
                    stepId = step.id,
                    stepName = step.name,
                    status = StepRunStatus.Failed,
                    startedAt = stepStartedAt,
                    finishedAt = finished,
                    itemsIn = input.size,
                    itemsOut = 1,
                    error = message,
                    output = outputs.getValue(0).toImmutableList(),
                    attempts = attempts,
                    portCounts = counts,
                    itemsFailed = input.size,
                )
                stepRuns += record
                stepOutputs[step.id] = outputs
                recordStep(step, collected, outputs, StepRunStatus.Failed, stepStartedAt, finished, attempts, message)
                softFailures += "${step.name}: $message"
                onEvent(RunEvent.Log(runId, "\"${step.name}\" failed but the Flow continues: $message", LogLevel.Warn))
                return NodeResult(StepRunStatus.Failed, outputs, input.size, finished - stepStartedAt, record, itemsFailed = input.size)
            }
            return hardFail(step, message, input, finished - stepStartedAt, attempts, stepStartedAt, collected)
        }

        private fun describeFailures(failures: List<ItemFailure>, total: Int): String {
            val first = failures.first()
            return if (failures.size == 1) "item ${first.inputIndex + 1} of $total failed: ${first.message}"
            else "${failures.size} of $total items failed (first: item ${first.inputIndex + 1}: ${first.message})"
        }

        private fun wholeStepErrorItem(step: StepNode, message: String, at: Long) = FlowData(
            json = mapOf("error" to message, "step" to step.name, "stepId" to step.id, "failedAt" to at),
            error = ItemError(message, step.id, "step", at),
        )

        private fun errorItemFor(step: StepNode, failure: ItemFailure, input: List<FlowData>, refs: List<ItemRef>, at: Long): FlowData {
            val source = input.getOrNull(failure.inputIndex)
            return FlowData(
                json = mapOf(
                    "error" to failure.message,
                    "step" to step.name,
                    "stepId" to step.id,
                    "failedAt" to at,
                    "itemIndex" to failure.inputIndex,
                ),
                binary = source?.binary.orEmpty(),
                error = ItemError(failure.message, step.id, failure.kind, at),
                sources = listOfNotNull(refs.getOrNull(failure.inputIndex)),
            )
        }

        private fun hardFail(
            step: StepNode,
            message: String,
            input: List<FlowData>,
            durationMs: Long,
            attempts: Int = 1,
            startedAtOverride: Long? = null,
            collected: Collected? = null,
        ): NodeResult {
            val now = clock()
            val record = StepRun(
                stepId = step.id,
                stepName = step.name,
                status = StepRunStatus.Failed,
                startedAt = startedAtOverride ?: (now - durationMs),
                finishedAt = now,
                itemsIn = input.size,
                error = message,
                attempts = attempts,
            )
            stepRuns += record
            recordStep(step, collected, emptyMap(), StepRunStatus.Failed, record.startedAt, now, attempts, message)
            failureMessages += "${step.name}: $message"
            onEvent(RunEvent.StepFailed(runId, step.id, message, false))
            return NodeResult(StepRunStatus.Failed, emptyMap(), input.size, durationMs, record, errorItems = listOf(wholeStepErrorItem(step, message, now)))
        }

        private suspend fun runErrorStep(errorStep: StepNode, items: List<FlowData>) {
            val executor = registry.executor(errorStep.type) ?: return
            onEvent(RunEvent.StepStarted(runId, errorStep.id, 1))
            val started = clock()
            try {
                val result = executor.execute(
                    StepContext(
                        step = errorStep,
                        flow = flow,
                        input = items,
                        runId = runId,
                        mode = options.mode,
                        attempt = 1,
                        evaluator = evaluator,
                        http = http,
                        credentials = credentials,
                        clock = clock,
                        timezone = flow.settings.timezone,
                        stepOutputs = portZero(snapshotPorts()),
                        logger = { message, level -> onEvent(RunEvent.Log(runId, message, level)) },
                        flowInvoker = flowInvoker,
                        subFlowDepth = options.subFlowDepth,
                        stepPorts = snapshotPorts(),
                    )
                )
                val finished = clock()
                stepRuns += StepRun(
                    stepId = errorStep.id,
                    stepName = errorStep.name,
                    status = StepRunStatus.Succeeded,
                    startedAt = started,
                    finishedAt = finished,
                    itemsIn = items.size,
                    itemsOut = result.outputs.values.sumOf { it.size },
                )
                onEvent(RunEvent.StepFinished(runId, errorStep.id, items.size, result.outputs.values.sumOf { it.size }, finished - started))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val finished = clock()
                stepRuns += StepRun(
                    stepId = errorStep.id,
                    stepName = errorStep.name,
                    status = StepRunStatus.Failed,
                    startedAt = started,
                    finishedAt = finished,
                    itemsIn = items.size,
                    error = e.message,
                )
                onEvent(RunEvent.StepFailed(runId, errorStep.id, e.message ?: "Error handler failed", false))
            }
        }
    }
}
