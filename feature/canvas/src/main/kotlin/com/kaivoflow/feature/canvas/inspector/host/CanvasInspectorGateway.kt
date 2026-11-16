package com.kaivoflow.feature.canvas.inspector.host

import com.kaivoflow.core.engine.stepdata.Direction
import com.kaivoflow.core.engine.stepdata.PinResult
import com.kaivoflow.core.engine.stepdata.PinStore
import com.kaivoflow.core.engine.stepdata.StepDataStore
import com.kaivoflow.core.engine.stepdata.StepExecutionKey
import com.kaivoflow.core.expressions.assist.ExpressionAssist
import com.kaivoflow.core.expressions.assist.PendingReason
import com.kaivoflow.core.expressions.assist.PreviewContext
import com.kaivoflow.core.expressions.assist.PreviewResult
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.SchemaInference
import com.kaivoflow.core.model.StepRun
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.feature.canvas.inspector.logic.DataAvailability
import com.kaivoflow.feature.canvas.inspector.logic.ParentEdge
import com.kaivoflow.feature.canvas.inspector.logic.TestPlan
import com.kaivoflow.feature.canvas.inspector.logic.TestPlanner
import com.kaivoflow.feature.canvas.inspector.state.DataRequest
import com.kaivoflow.feature.canvas.inspector.state.DataSummary
import com.kaivoflow.feature.canvas.inspector.state.InspectorGateway
import com.kaivoflow.feature.canvas.inspector.state.InspectorTestEvent
import com.kaivoflow.feature.canvas.inspector.state.ParentPolicy
import com.kaivoflow.feature.canvas.inspector.state.PinInfo
import com.kaivoflow.feature.canvas.inspector.state.PreviewState
import com.kaivoflow.feature.canvas.inspector.state.RunSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow as KFlow
import kotlinx.coroutines.flow.flow

/** Runs one Step on its own. The engine writes the execution to the StepDataStore as a manual execution. */
fun interface StepTestRunner {
    suspend fun test(flow: Flow, stepId: String, input: List<Item>): StepRun
}

/**
 * The inspector's connection to storage, pins, the engine and the expression assist for ONE open Step.
 * Reads go through [StepDataStore] and [PinStore]; nothing is kept in memory between calls, so what the
 * panels show survives a restart (audit 08 P0 F3).
 */
class CanvasInspectorGateway(
    private val flowId: String,
    private val stepId: String,
    private val currentFlow: () -> Flow,
    private val stepData: StepDataStore,
    private val pins: PinStore,
    private val tester: StepTestRunner,
    private val assist: ExpressionAssist,
    private val onParam: (String, ParamValue?) -> Unit,
    /** Per-parent item cap handed to a Test Step as input (the spec's TestOptions.itemLimit). */
    private val inputLimit: Int = 50,
) : InspectorGateway {

    private suspend fun keyOf(r: DataRequest): StepExecutionKey? {
        val id = r.stepId ?: return null
        return when (val run = r.run) {
            is RunSelection.Specific -> StepExecutionKey(run.runId, id, run.runIndex)
            RunSelection.Latest -> stepData.latestKey(flowId, id)
            RunSelection.Pinned -> null
        }
    }

    override suspend fun loadData(request: DataRequest): DataSummary {
        val id = request.stepId ?: throw IllegalStateException("No Step is selected.")
        if (request.run == RunSelection.Pinned) {
            val items = pins.pinnedFor(flowId, id)?.ports?.getOrNull(request.port) ?: throw IllegalStateException("Nothing is pinned for this Step.")
            return DataSummary(items.size, items.size, truncated = false, hasBinary = items.any { it.binary.isNotEmpty() })
        }
        val key = keyOf(request) ?: return DataSummary(0, 0)
        val rec = stepData.summary(key) ?: return DataSummary(0, 0)
        val snap = rec.outputs.firstOrNull { it.port == request.port } ?: return DataSummary(0, 0)
        return DataSummary(
            itemCount = snap.itemCount,
            storedCount = snap.storedCount,
            truncated = snap.truncation != null || rec.dataDropped,
            hasBinary = snap.schema?.fields?.containsKey(SchemaInference.BINARY_KEY) == true,
        )
    }

    override suspend fun pinCurrent(request: DataRequest): PinInfo {
        val key = keyOf(request) ?: throw IllegalStateException("There is no run to pin.")
        return when (val r = pins.pinFromExecution(flowId, key, request.port)) {
            is PinResult.Ok -> PinInfo(r.data.ports.getOrNull(request.port)?.size ?: 0, r.data.bytes, r.data.pinnedAt)
            is PinResult.TooLarge -> throw IllegalStateException("The data is too large to pin (${r.bytes} bytes, limit ${r.limit}).")
            is PinResult.Invalid -> throw IllegalStateException(r.message)
            PinResult.Truncated -> throw IllegalStateException("Only part of this data was kept, so it cannot be pinned.")
        }
    }

    override suspend fun unpin() = pins.unpin(flowId, stepId)

    override fun persistParam(key: String, value: ParamValue?) = onParam(key, value)

    // ---- preview ----

    override suspend fun evaluatePreview(text: String, itemIndex: Int): PreviewState {
        val flow = currentFlow()
        val step = flow.step(stepId)
        val parents = flow.incoming(stepId)
        val first = parents.firstNotNullOfOrNull { e -> storedItems(e.fromStepId, e.fromPort).takeIf { it.isNotEmpty() } }.orEmpty()
        val names = flow.steps.associate { it.id to it.name }
        val outputs = parents.associate { e -> e.fromStepId to storedItems(e.fromStepId, e.fromPort) }
        val ctx = PreviewContext(
            stepName = step?.name ?: "",
            itemIndex = itemIndex.coerceIn(0, (first.size - 1).coerceAtLeast(0)),
            inputs = first,
            stepOutputs = outputs,
            stepNames = names,
            vars = flow.vars,
            timezone = flow.settings.timezone,
        )
        return when (val r = assist.preview(text, ctx)) {
            is PreviewResult.Value -> PreviewState.Value(r.rendered, r.type.name.lowercase())
            is PreviewResult.Pending -> PreviewState.Pending(
                if (r.reason == PendingReason.NoUpstreamData) "Test the previous Step to preview this." else "That Step is not before this one.",
            )
            is PreviewResult.Error -> PreviewState.Error(r.error.detail, r.span?.start, r.span?.end)
        }
    }

    // ---- data helpers ----

    private suspend fun storedItems(parentId: String, port: Int): List<Item> {
        pins.pinnedFor(flowId, parentId)?.ports?.getOrNull(port)?.let { return it.take(inputLimit) }
        val key = stepData.latestKey(flowId, parentId) ?: return emptyList()
        return stepData.page(key, Direction.Out, port, 0, inputLimit).items
    }

    private suspend fun hasData(id: String): Boolean {
        if (pins.pinnedFor(flowId, id) != null) return true
        val key = stepData.latestKey(flowId, id) ?: return false
        val rec = stepData.summary(key) ?: return false
        return rec.outputs.any { it.storedCount > 0 }
    }

    // ---- test step ----

    override fun runTest(stepId: String, policy: ParentPolicy): KFlow<InspectorTestEvent> = flow {
        val flow = currentFlow()
        val have = HashSet<String>()
        for (s in flow.steps) if (hasData(s.id)) have += s.id
        val plan = TestPlanner.plan(
            stepId,
            parentsOf = { id -> flow.incoming(id).map { ParentEdge(it.fromStepId, it.fromPort) } },
            data = DataAvailability { it in have },
            policy = policy,
        )
        val order: List<String> = when (plan) {
            is TestPlan.NeedsParents -> { emit(InspectorTestEvent.NeedsParents(plan.stepIds)); return@flow }
            TestPlan.RunNow -> listOf(stepId)
            is TestPlan.RunInOrder -> plan.steps
        }
        for ((i, id) in order.withIndex()) {
            emit(InspectorTestEvent.Progress(order.drop(i)))
            val input = flow.incoming(id).flatMap { storedItems(it.fromStepId, it.fromPort) }
            val run = try {
                tester.test(currentFlow(), id, input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emit(InspectorTestEvent.Failed(e.message ?: "The Step failed.")); return@flow
            }
            val key = stepData.latestKey(flowId, id)
            val rec = key?.let { stepData.summary(it) }
            if (run.status == StepRunStatus.Failed) {
                emit(InspectorTestEvent.Failed(run.error ?: rec?.error?.message ?: "The Step failed.", rec?.error?.itemIndex, rec?.outputs?.any { it.storedCount > 0 } == true))
                return@flow
            }
            if (id == stepId) emit(InspectorTestEvent.Finished(key?.runId))
        }
    }
}
