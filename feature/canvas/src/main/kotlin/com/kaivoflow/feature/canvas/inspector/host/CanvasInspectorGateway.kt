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
import com.kaivoflow.core.engine.SparkSamples
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.ParamValueCodec
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.SchemaInference
import com.kaivoflow.core.model.StepRun
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.feature.canvas.inspector.logic.DataAvailability
import com.kaivoflow.feature.canvas.inspector.logic.JsonPretty
import com.kaivoflow.feature.canvas.inspector.logic.ManualInput
import com.kaivoflow.feature.canvas.inspector.logic.SampleFromSchema
import com.kaivoflow.feature.canvas.inspector.logic.TestInputPolicy
import com.kaivoflow.feature.canvas.inspector.logic.TestRisks
import com.kaivoflow.feature.canvas.inspector.logic.ParentEdge
import com.kaivoflow.feature.canvas.inspector.logic.TestPlan
import com.kaivoflow.feature.canvas.inspector.logic.TestPlanner
import com.kaivoflow.feature.canvas.inspector.state.DataRequest
import com.kaivoflow.feature.canvas.inspector.state.DataSummary
import com.kaivoflow.feature.canvas.inspector.state.InputCatalogInfo
import com.kaivoflow.feature.canvas.inspector.state.InputOption
import com.kaivoflow.feature.canvas.inspector.state.InputSource
import com.kaivoflow.feature.canvas.inspector.state.InspectorGateway
import com.kaivoflow.feature.canvas.inspector.state.TestRequest
import com.kaivoflow.feature.canvas.inspector.state.TestSummary
import com.kaivoflow.feature.canvas.inspector.state.InspectorTestEvent
import com.kaivoflow.feature.canvas.inspector.state.ParentPolicy
import com.kaivoflow.feature.canvas.inspector.state.PinInfo
import com.kaivoflow.feature.canvas.inspector.state.PreviewState
import com.kaivoflow.feature.canvas.inspector.state.RunSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow as KFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/** Runs one Step on its own. The engine writes the execution to the StepDataStore as a manual execution. */
fun interface StepTestRunner {
    /** [upstream] is the parents' stored output (Step id to port to items) for `$step(...)` lookups; no parent is executed. */
    suspend fun test(flow: Flow, stepId: String, input: List<Item>, upstream: Map<String, Map<Int, List<Item>>>): StepRun
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
        val snap = (if (request.inbound) rec.inputs else rec.outputs).firstOrNull { it.port == request.port } ?: return DataSummary(0, 0)
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

    // ---- test input ----

    private data class Gathered(val source: InputSource, val items: List<Item>, val detail: String? = null)

    private suspend fun pinnedOnly(parentId: String, port: Int): List<Item>? =
        pins.pinnedFor(flowId, parentId)?.ports?.getOrNull(port)?.take(inputLimit)

    private suspend fun lastRunOnly(parentId: String, port: Int): List<Item> {
        val key = stepData.latestKey(flowId, parentId) ?: return emptyList()
        return stepData.page(key, Direction.Out, port, 0, inputLimit).items
    }

    private suspend fun lastTestKey(id: String): StepExecutionKey? =
        stepData.observeExecutions(flowId, id, 20).first().firstOrNull { it.key.isTest }?.key

    private suspend fun lastTestInput(id: String): Pair<StepExecutionKey, List<Item>>? {
        val key = lastTestKey(id) ?: return null
        val rec = stepData.summary(key) ?: return null
        val items = rec.inputs.flatMap { stepData.page(key, Direction.In, it.port, 0, inputLimit).items }
        return key to items
    }

    /** Gathers what [source] means for [id]: Pinned = pins where a parent has one, else its last output; LastRun ignores pins. */
    private suspend fun gather(flow: Flow, id: String, source: InputSource, manualText: String): Gathered? {
        val edges = flow.incoming(id)
        return when (source) {
            InputSource.Pinned -> {
                val parts = edges.map { e -> e to pinnedOnly(e.fromStepId, e.fromPort) }
                val items = parts.flatMap { (e, p) -> p ?: lastRunOnly(e.fromStepId, e.fromPort) }
                val detail = if (parts.size > 1) parts.joinToString(", ") { (e, p) -> "${flow.step(e.fromStepId)?.name ?: e.fromStepId}: ${if (p != null) "pinned" else "last run"}" } else null
                Gathered(InputSource.Pinned, items, detail)
            }
            InputSource.LastRun -> Gathered(InputSource.LastRun, edges.flatMap { lastRunOnly(it.fromStepId, it.fromPort) })
            InputSource.LastTest -> lastTestInput(id)?.let { Gathered(InputSource.LastTest, it.second) }
            InputSource.Manual, InputSource.Sample -> when (val p = ManualInput.parse(manualText)) {
                is ManualInput.Parsed.Items -> Gathered(source, p.items)
                is ManualInput.Parsed.Bad -> null
            }
        }
    }

    private suspend fun catalogOptions(flow: Flow): List<InputOption> {
        val edges = flow.incoming(stepId)
        val out = ArrayList<InputOption>()
        val pinned = edges.mapNotNull { pinnedOnly(it.fromStepId, it.fromPort) }
        if (pinned.isNotEmpty()) out += InputOption(InputSource.Pinned, edges.sumOf { e -> pinnedOnly(e.fromStepId, e.fromPort)?.size ?: lastRunOnly(e.fromStepId, e.fromPort).size })
        val last = edges.sumOf { lastRunOnly(it.fromStepId, it.fromPort).size }
        if (last > 0) out += InputOption(InputSource.LastRun, last)
        lastTestInput(stepId)?.let { (key, items) -> if (items.isNotEmpty()) out += InputOption(InputSource.LastTest, items.size, key.runId) }
        return out
    }

    override suspend fun loadInputCatalog(): InputCatalogInfo {
        val flow = currentFlow()
        val step = flow.step(stepId) ?: return InputCatalogInfo()
        if (step.isTrigger) {
            val sample = SparkSamples.payload(step, System.currentTimeMillis(), "", "test")
            return InputCatalogInfo(emptyList(), JsonPretty.write(listOf(sample)))
        }
        val options = catalogOptions(flow)
        val basis = (gather(flow, stepId, InputSource.Pinned, "")?.items?.takeIf { it.isNotEmpty() } ?: lastTestInput(stepId)?.second).orEmpty()
        val schema = if (basis.isEmpty()) null else SchemaInference.infer(basis.asSequence())
        return InputCatalogInfo(options, SampleFromSchema.generate(schema))
    }

    private suspend fun upstreamOf(flow: Flow, id: String): Map<String, Map<Int, List<Item>>> {
        val out = LinkedHashMap<String, MutableMap<Int, List<Item>>>()
        for (e in flow.incoming(id)) out.getOrPut(e.fromStepId) { LinkedHashMap() }[e.fromPort] = storedItems(e.fromStepId, e.fromPort)
        return out
    }

    // ---- test step ----

    private fun decoded(step: com.kaivoflow.core.model.StepNode): Map<String, ParamValue?> =
        step.params.mapValues { ParamValueCodec.decodeLenient(it.value) }

    override fun runTest(request: TestRequest): KFlow<InspectorTestEvent> = flow {
        val flow = currentFlow()
        val target = flow.step(request.stepId)
        if (target == null) { emit(InspectorTestEvent.Failed("This Step no longer exists.")); return@flow }
        if (target.disabled) { emit(InspectorTestEvent.Refused("This Step is switched off. Turn it on to test it.")); return@flow }
        val have = HashSet<String>()
        for (s in flow.steps) if (hasData(s.id)) have += s.id
        val plan = TestPlanner.plan(
            request.stepId,
            parentsOf = { id -> flow.incoming(id).map { ParentEdge(it.fromStepId, it.fromPort) } },
            data = DataAvailability { it in have },
            policy = request.policy,
        )
        val order: List<String> = when (plan) {
            TestPlan.RunNow -> listOf(request.stepId)
            is TestPlan.RunInOrder -> plan.steps.filter { it == request.stepId || flow.step(it)?.disabled != true }
        }
        if (!request.confirmed) {
            val risks = order.mapNotNull { id -> flow.step(id)?.let { st -> TestRisks.of(st.type, decoded(st))?.let { "\"${st.name}\" ${it.label}" } } }
            if (risks.isNotEmpty()) { emit(InspectorTestEvent.NeedsConfirm(risks, request.policy)); return@flow }
        }
        for ((i, id) in order.withIndex()) {
            val last = id == request.stepId
            val step = flow.step(id) ?: continue
            // The tested Step uses the chosen source; an earlier Step run on purpose uses its own stored or sample data.
            val source = if (last && request.policy == ParentPolicy.OnlyThis) request.source ?: TestInputPolicy.auto(catalogOptions(flow), step.isTrigger) else if (step.isTrigger) InputSource.Sample else InputSource.Pinned
            val sampleText = if (step.isTrigger && !(last && request.policy == ParentPolicy.OnlyThis)) JsonPretty.write(listOf(SparkSamples.payload(step, System.currentTimeMillis(), "", "test"))) else request.manualText
            val gathered = gather(flow, id, source, sampleText) ?: when {
                source.isText -> { emit(InspectorTestEvent.Refused("The manual input is not valid JSON. Fix it and test again.")); return@flow }
                else -> Gathered(source, emptyList())
            }
            emit(InspectorTestEvent.Progress(order.drop(i)))
            val input = gathered.items
            val run = try {
                tester.test(currentFlow(), id, input, upstreamOf(flow, id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emit(InspectorTestEvent.Failed(e.message ?: "The Step failed.")); return@flow
            }
            if (run.status == StepRunStatus.Skipped && run.error != null) { emit(InspectorTestEvent.Refused(run.error!!)); return@flow }
            val key = stepData.latestKey(flowId, id)
            val rec = key?.let { stepData.summary(it) }
            val summary = TestSummary(
                runId = key?.runId, source = gathered.source, detail = gathered.detail,
                itemsIn = run.itemsIn, itemsOut = run.itemsOut, portCounts = run.portCounts, durationMs = run.durationMs, attempts = run.attempts,
            )
            if (run.status == StepRunStatus.Failed) {
                emit(InspectorTestEvent.Failed(run.error ?: rec?.error?.message ?: "The Step failed.", rec?.error?.itemIndex, rec?.outputs?.any { it.storedCount > 0 } == true, summary))
                return@flow
            }
            if (last) emit(InspectorTestEvent.Finished(key?.runId, summary))
        }
    }
}
