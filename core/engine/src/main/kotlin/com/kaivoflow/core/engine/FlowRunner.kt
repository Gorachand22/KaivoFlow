package com.kaivoflow.core.engine

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
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
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
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
) {
    private val validator = FlowValidator(registry)
    val compiler = FlowCompiler(validator)

    fun validate(flow: Flow): ValidationReport = validator.validate(flow)

    /** Runs a whole Flow. Cancelling the calling coroutine cancels the Run cleanly. */
    suspend fun run(
        flow: Flow,
        trigger: RunTrigger = RunTrigger(),
        options: RunOptions = RunOptions(),
        onEvent: (RunEvent) -> Unit = {},
    ): RunRecord = Execution(flow, trigger, options, onEvent).execute()

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
        val effectiveInput = if (input.isEmpty()) listOf(FlowData()) else input
        val execution = Execution(flow, RunTrigger(kind = "test", items = effectiveInput.toImmutableList()), RunOptions(mode = ExecutionMode.TestStep), onEvent)
        onEvent(RunEvent.RunStarted(execution.runId, flow.id, 1))
        val result = try {
            execution.runWithRetries(step, effectiveInput)
        } catch (e: CancellationException) {
            onEvent(RunEvent.RunFinished(execution.runId, RunStatus.Canceled))
            throw e
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

    internal class NodeResult(
        val status: StepRunStatus,
        val outputs: Map<Int, List<FlowData>>,
        val itemsIn: Int,
        val durationMs: Long,
        val record: StepRun,
        val errorItems: List<FlowData> = emptyList(),
    )

    /**
     * One Run's mutable state. A runner instance is safely reused: all per-Run state lives here,
     * so two Flows can run at the same time on different coroutines.
     */
    private inner class Execution(
        val flow: Flow,
        val trigger: RunTrigger,
        val options: RunOptions,
        val onEvent: (RunEvent) -> Unit,
    ) {
        val runId: String = newId()
        val startedAt: Long = clock()

        /** Step outputs keyed by Step NAME so `${'$'}step["Send to Telegram"]` works in expressions. */
        val stepOutputs = ConcurrentHashMap<String, List<FlowData>>()
        val stepRuns: MutableList<StepRun> = Collections.synchronizedList(mutableListOf())
        val errorItems: MutableList<FlowData> = Collections.synchronizedList(mutableListOf())
        val failureMessages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val softFailures: MutableList<String> = Collections.synchronizedList(mutableListOf())

        lateinit var compiled: CompiledFlow
        private lateinit var selectedTriggerId: String

        private fun snapshotOutputs(): Map<String, List<FlowData>> = HashMap(stepOutputs)

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
                "schedule", "scheduled" -> "trigger.schedule"
                else -> if (kind.startsWith("trigger.")) kind else "trigger.$kind"
            }
            val selected = if (requested != null) {
                enabledTriggers.firstOrNull { it.id == requested && (expectedType == null || it.type == expectedType) }
            } else if (expectedType == null) {
                enabledTriggers.firstOrNull { it.type == "trigger.manual" } ?: enabledTriggers.firstOrNull()
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
                onEvent(RunEvent.Log(runId, "Run canceled.", LogLevel.Warn))
                RunStatus.Canceled
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
            val upstreamResults = compiled.upstream[step.id].orEmpty().map { edge ->
                edge to memo.getValue(edge.fromStepId).await()
            }

            // An event belongs only to the selected Spark; disabled/other Sparks cannot fire.
            if (step.isTrigger && (step.disabled || step.id != selectedTriggerId)) {
                return markSkipped(step, "This Spark did not start this Run.")
            }

            // A switched-off ordinary Step passes its items straight through instead of dropping them.
            if (step.disabled) {
                val passed = if (step.isTrigger) trigger.items.toList() else collectInput(step, upstreamResults)
                return markBypassed(step, passed)
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

            val inputItems = collectInput(step, upstreamResults)
            if (inputItems.isEmpty() && !step.isTrigger) {
                return markSkipped(step, "The Step before this one produced no items.")
            }
            // Freeze Output: replay the pinned result instead of executing.
            val frozen = step.frozenOutput
            if (frozen != null && !options.ignoreFrozen && options.mode != ExecutionMode.TestStep) {
                return useFrozen(step, frozen.toList(), inputItems.size)
            }

            val result = permits.withPermit { runWithRetries(step, inputItems) }
            // Collect error items so the Flow's dedicated error Step can be invoked once at the end.
            result.errorItems.forEach { errorItems += it }
            return result
        }

        private val memo = ConcurrentHashMap<String, Deferred<NodeResult>>()
        private lateinit var scope: CoroutineScope
        private lateinit var permits: Semaphore

        private fun collectInput(step: StepNode, upstream: List<Pair<com.kaivoflow.core.model.StreamEdge, NodeResult>>): List<FlowData> {
            val raw = if (step.isTrigger) {
                if (trigger.items.isEmpty()) {
                    if (options.mode == ExecutionMode.TestStep || trigger.kind.lowercase() in setOf("manual", "schedule", "scheduled", "subflow")) {
                        listOf(FlowData(json = defaultTriggerPayload(step)))
                    } else {
                        emptyList()
                    }
                } else trigger.items.toList()
            } else {
                upstream
                    .sortedBy { (edge, _) -> compiled.flow.streams.indexOf(edge) }
                    .flatMap { (edge, result) -> result.outputs[edge.fromPort].orEmpty() }
            }
            val limit = options.itemLimit ?: flow.settings.maxItemsPerStep
            if (raw.size <= limit) return raw
            onEvent(RunEvent.Log(runId, "\"${step.name}\" received ${raw.size} items; processing the first $limit.", LogLevel.Warn))
            return raw.take(limit)
        }

        private fun defaultTriggerPayload(step: StepNode): Map<String, Any?> {
            val now = clock()
            return when (step.type) {
                "trigger.webhook" -> mapOf(
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
                "trigger.notification" -> mapOf(
                    "app" to "com.whatsapp",
                    "appName" to "WhatsApp",
                    "title" to (step.params["titleContains"] as? String ?: "Notification"),
                    "text" to (step.params["textContains"] as? String ?: "Sample notification text"),
                    "isOngoing" to false,
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "trigger.sms" -> mapOf(
                    "from" to (step.params["senderContains"] as? String ?: "+919876543210"),
                    "body" to (step.params["bodyContains"] as? String ?: "Sample SMS message body"),
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "trigger.battery" -> mapOf(
                    "level" to (step.params["threshold"] as? Number ?: 15),
                    "isCharging" to false,
                    "temperature" to 31.5,
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "trigger.wifi" -> mapOf(
                    "ssid" to (step.params["ssidContains"] as? String ?: "MyHome_WiFi"),
                    "event" to (step.params["event"] as? String ?: "connected"),
                    "ipAddress" to "192.168.1.100",
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "trigger.bluetooth" -> mapOf(
                    "name" to (step.params["deviceNameContains"] as? String ?: "Wireless Headphones"),
                    "event" to (step.params["event"] as? String ?: "connected"),
                    "address" to "12:34:56:78:9A:BC",
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "trigger.geofence" -> mapOf(
                    "transition" to (step.params["transition"] as? String ?: "enter"),
                    "latitude" to (step.params["latitude"] as? Number ?: 20.2961),
                    "longitude" to (step.params["longitude"] as? Number ?: 85.8245),
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "trigger.headset" -> mapOf(
                    "event" to (step.params["event"] as? String ?: "plugged"),
                    "hasMic" to true,
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "trigger.boot" -> mapOf(
                    "event" to "boot_completed",
                    "reason" to "reboot",
                    "boot" to true,
                    "uptimeMs" to 60_000L,
                    "timestamp" to now,
                    "executionId" to runId,
                )
                "trigger.share" -> mapOf(
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

        private fun useFrozen(step: StepNode, items: List<FlowData>, itemsIn: Int): NodeResult {
            val now = clock()
            stepOutputs[step.name] = items
            val record = StepRun(
                stepId = step.id,
                stepName = step.name,
                status = StepRunStatus.Frozen,
                startedAt = now,
                finishedAt = now,
                itemsIn = itemsIn,
                itemsOut = items.size,
                output = items.toImmutableList(),
            )
            stepRuns += record
            onEvent(RunEvent.StepFinished(runId, step.id, itemsIn, items.size, 0, frozen = true, output = items.toImmutableList()))
            return NodeResult(StepRunStatus.Frozen, mapOf(0 to items), itemsIn, 0, record)
        }

        /** A disabled Step: reported as Skipped, but its items continue downstream untouched. */
        private fun markBypassed(step: StepNode, items: List<FlowData>): NodeResult {
            val now = clock()
            val record = StepRun(step.id, step.name, StepRunStatus.Skipped, now, now, itemsIn = items.size, itemsOut = items.size)
            stepRuns += record
            onEvent(RunEvent.StepSkipped(runId, step.id, "This Step is switched off; items passed through."))
            return NodeResult(StepRunStatus.Skipped, mapOf(0 to items), items.size, 0, record)
        }

        private fun markSkipped(step: StepNode, reason: String): NodeResult {
            val now = clock()
            val record = StepRun(step.id, step.name, StepRunStatus.Skipped, now, now)
            stepRuns += record
            onEvent(RunEvent.StepSkipped(runId, step.id, reason))
            return NodeResult(StepRunStatus.Skipped, emptyMap(), 0, 0, record)
        }

        suspend fun runWithRetries(step: StepNode, input: List<FlowData>): NodeResult {
            val executor = registry.executor(step.type)
                ?: return hardFail(step, "This Step type (${step.type}) is not available on this build.", input, 0)

            val attempts = if (step.retry.enabled || step.onError == OnErrorPolicy.Retry) {
                step.retry.maxAttempts.coerceAtLeast(1)
            } else 1

            var lastError: String? = null
            val stepStartedAt = clock()

            for (attempt in 1..attempts) {
                onEvent(RunEvent.StepStarted(runId, step.id, attempt))
                val attemptStarted = clock()
                try {
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
                        stepOutputs = snapshotOutputs(),
                        logger = { message, level -> onEvent(RunEvent.Log(runId, message, level)) },
                        flowInvoker = flowInvoker,
                        subFlowDepth = options.subFlowDepth,
                    )
                    val timeout = step.timeoutMs
                    val result = if (timeout != null) {
                        withTimeout(timeout) { executor.execute(ctx) }
                    } else {
                        executor.execute(ctx)
                    }
                    val finished = clock()
                    val primary = result.port(0)
                    val itemsOut = result.outputs.values.sumOf { it.size }
                    val record = StepRun(
                        stepId = step.id,
                        stepName = step.name,
                        status = StepRunStatus.Succeeded,
                        startedAt = attemptStarted,
                        finishedAt = finished,
                        itemsIn = input.size,
                        itemsOut = itemsOut,
                        output = primary.toImmutableList(),
                        attempts = attempt,
                    )
                    stepOutputs[step.name] = primary
                    stepRuns += record
                    onEvent(
                        RunEvent.StepFinished(
                            runId, step.id, input.size, itemsOut, finished - attemptStarted,
                            output = primary.toImmutableList(),
                        )
                    )
                    return NodeResult(StepRunStatus.Succeeded, result.outputs, input.size, finished - attemptStarted, record)
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
                val errorItem = FlowData(
                    json = mapOf(
                        "error" to message,
                        "step" to step.name,
                        "stepId" to step.id,
                        "failedAt" to finished,
                    ),
                    error = message,
                )
                val record = StepRun(
                    stepId = step.id,
                    stepName = step.name,
                    status = StepRunStatus.Failed,
                    startedAt = stepStartedAt,
                    finishedAt = finished,
                    itemsIn = input.size,
                    itemsOut = 1,
                    error = message,
                    output = listOf(errorItem).toImmutableList(),
                    attempts = attempts,
                )
                stepRuns += record
                stepOutputs[step.name] = listOf(errorItem)
                softFailures += "${step.name}: $message"
                onEvent(RunEvent.Log(runId, "\"${step.name}\" failed but the Flow continues: $message", LogLevel.Warn))
                return NodeResult(StepRunStatus.Failed, mapOf(0 to listOf(errorItem)), input.size, finished - stepStartedAt, record)
            }
            return hardFail(step, message, input, finished - stepStartedAt, attempts, stepStartedAt)
        }

        private fun hardFail(
            step: StepNode,
            message: String,
            input: List<FlowData>,
            durationMs: Long,
            attempts: Int = 1,
            startedAtOverride: Long? = null,
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
            failureMessages += "${step.name}: $message"
            onEvent(RunEvent.StepFailed(runId, step.id, message, false))
            val errorItem = FlowData(
                json = mapOf("error" to message, "step" to step.name, "stepId" to step.id, "failedAt" to now),
                error = message,
            )
            return NodeResult(StepRunStatus.Failed, emptyMap(), input.size, durationMs, record, errorItems = listOf(errorItem))
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
                        stepOutputs = snapshotOutputs(),
                        logger = { message, level -> onEvent(RunEvent.Log(runId, message, level)) },
                        flowInvoker = flowInvoker,
                        subFlowDepth = options.subFlowDepth,
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
