package com.kaivoflow.feature.canvas.inspector.host

import com.kaivoflow.core.engine.stepdata.PinStore
import com.kaivoflow.core.engine.stepdata.StepDataStore
import com.kaivoflow.core.expressions.assist.ExpressionAssist
import com.kaivoflow.core.expressions.assist.SchemaScope
import com.kaivoflow.core.expressions.assist.UpstreamStep
import com.kaivoflow.core.model.CredentialRef
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.ParamValueCodec
import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.core.model.toSchema
import com.kaivoflow.feature.canvas.inspector.logic.CredentialFill
import com.kaivoflow.feature.canvas.inspector.panels.PanelDataSource
import com.kaivoflow.feature.canvas.inspector.panels.StepDataStorePanelSource
import com.kaivoflow.feature.canvas.inspector.logic.PinAwarePanelSource
import com.kaivoflow.feature.canvas.inspector.state.InspectorIntent
import com.kaivoflow.feature.canvas.inspector.state.InspectorReducer
import com.kaivoflow.feature.canvas.inspector.state.InspectorState
import com.kaivoflow.feature.canvas.inspector.state.InspectorStateHolder
import com.kaivoflow.feature.canvas.inspector.state.PanelSide
import com.kaivoflow.feature.canvas.inspector.state.PinInfo
import com.kaivoflow.feature.canvas.inspector.state.PortChip
import com.kaivoflow.feature.canvas.inspector.state.RunChip
import com.kaivoflow.feature.canvas.inspector.state.SourceChip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Everything the UI needs for one open Step: the state holder, where its panels read from and the expression scope. */
class InspectorSession(
    val stepId: String,
    val holder: InspectorStateHolder,
    val source: PanelDataSource,
    val scope: StateFlow<SchemaScope>,
    val assist: ExpressionAssist,
    private val jobs: List<Job>,
) {
    fun close() {
        jobs.forEach { it.cancel() }
        holder.close()
    }
}

/** Builds an [InspectorSession] and wires the observers that keep it in step with storage. */
object InspectorSessions {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun open(
        flowId: String,
        stepId: String,
        replayRunId: String?,
        scope: CoroutineScope,
        currentFlow: () -> Flow,
        spec: NodeSpec?,
        registrySpec: (String) -> NodeSpec?,
        stepData: StepDataStore,
        pins: PinStore,
        tester: StepTestRunner,
        assist: ExpressionAssist,
        credentialRefs: StateFlow<List<CredentialRef>?>,
        onParam: (String, ParamValue?) -> Unit,
    ): InspectorSession? {
        val flow = currentFlow()
        val step = flow.step(stepId) ?: return null
        val schema: ParamSchema = spec?.toSchema() ?: ParamSchema.Empty
        val gateway = CanvasInspectorGateway(flowId, stepId, currentFlow, stepData, pins, tester, assist, onParam)
        val reducer = InspectorReducer(exprSyntax = { text -> assist.analyze(text).errors.firstOrNull()?.message })
        val holder = InspectorStateHolder(InspectorState.initial(stepId, replayRunId, step.type), gateway, scope, reducer)
        val scopeFlow = MutableStateFlow(SchemaScope())
        val jobs = ArrayList<Job>()

        fun values(): Map<String, ParamValue?> = (currentFlow().step(stepId) ?: step).params.mapValues { ParamValueCodec.decodeLenient(it.value) }
        fun parents(): List<SourceChip> = currentFlow().incoming(stepId).map { it.fromStepId }.distinct()
            .mapNotNull { id -> currentFlow().step(id)?.let { SourceChip(it.id, it.name) } }
        fun portsOf(id: String?): List<PortChip> {
            val s = id?.let { currentFlow().step(it) }?.let { registrySpec(it.type) } ?: return listOf(PortChip(0))
            return (0 until s.outputs.coerceAtLeast(1)).map { PortChip(it, s.outputLabels.getOrNull(it)) }
        }

        holder.dispatch(InspectorIntent.ParamsLoaded(schema, values(), CredentialFill.filled(schema, values(), credentialRefs.value)))
        holder.dispatch(InspectorIntent.ParentsChanged(parents()))
        holder.dispatch(InspectorIntent.PortsChanged(PanelSide.Output, portsOf(stepId)))
        holder.dispatch(InspectorIntent.Refresh)

        // Credentials: a deleted or newly added vault entry changes the validation result.
        jobs += scope.launch {
            credentialRefs.collect { refs ->
                val v = holder.state.value.values
                holder.dispatch(InspectorIntent.ParamsLoaded(schema, v, CredentialFill.filled(schema, v, refs)))
            }
        }
        // Own executions: run list, and a reload when a newer one is stored.
        jobs += scope.launch {
            var lastLatest: String? = null
            stepData.observeExecutions(flowId, stepId, 20).collect { list ->
                holder.dispatch(InspectorIntent.RunsChanged(PanelSide.Output, list.map { RunChip(it.key.runId, it.startedAt, it.error != null) }))
                val latest = list.firstOrNull()?.key?.runId
                if (lastLatest != null && latest != lastLatest) holder.dispatch(InspectorIntent.StepDataChanged(stepId))
                lastLatest = latest
            }
        }
        // The selected parent: its runs and ports, and a reload when it stores new data.
        jobs += scope.launch {
            val seen = HashMap<String, String?>()
            holder.state.map { it.input.sourceStepId }.distinctUntilChanged().flatMapLatest { id ->
                if (id == null) emptyFlow() else stepData.observeExecutions(flowId, id, 20).map { id to it }
            }.collect { (id, list) ->
                holder.dispatch(InspectorIntent.RunsChanged(PanelSide.Input, list.map { RunChip(it.key.runId, it.startedAt, it.error != null) }))
                holder.dispatch(InspectorIntent.PortsChanged(PanelSide.Input, portsOf(id)))
                val latest = list.firstOrNull()?.key?.runId
                if (seen.containsKey(id) && seen[id] != latest) holder.dispatch(InspectorIntent.StepDataChanged(id))
                seen[id] = latest
                scopeFlow.value = buildScope(flowId, stepId, currentFlow(), stepData, pins)
            }
        }
        // Own pin.
        jobs += scope.launch {
            pins.observe(flowId, stepId).collect { p ->
                holder.dispatch(InspectorIntent.PinChanged(p?.let { PinInfo(it.ports.sumOf { port -> port.size }, it.bytes, it.pinnedAt) }))
            }
        }
        // The expression scope also changes when this Step finishes a test that fills a parent chain.
        jobs += scope.launch { scopeFlow.value = buildScope(flowId, stepId, currentFlow(), stepData, pins) }

        val source = PinAwarePanelSource(StepDataStorePanelSource(stepData, flowId), pins, flowId)
        return InspectorSession(stepId, holder, source, scopeFlow.asStateFlow(), assist, jobs)
    }

    /** Parent schemas from the latest stored data (or pins), for autocomplete, diagnostics and the Mapper tray. */
    suspend fun buildScope(flowId: String, stepId: String, flow: Flow, stepData: StepDataStore, pins: PinStore): SchemaScope {
        val parentIds = flow.incoming(stepId).map { it.fromStepId }.distinct()
        val ups = ArrayList<UpstreamStep>()
        for (id in parentIds) {
            val st = flow.step(id) ?: continue
            val port = flow.incoming(stepId).firstOrNull { it.fromStepId == id }?.fromPort ?: 0
            val schema: SchemaNode? = runCatching {
                pins.pinnedFor(flowId, id)?.ports?.getOrNull(port)?.let { items -> com.kaivoflow.core.model.SchemaInference.infer(items.asSequence()) }
                    ?: stepData.latestKey(flowId, id)?.let { stepData.schema(it, com.kaivoflow.core.engine.stepdata.Direction.Out, port) }
            }.getOrNull()
            ups += UpstreamStep(id, st.name, schema, isImmediate = true)
        }
        val others = flow.steps.map { it.name }.toSet() - flow.step(stepId)?.name.orEmpty()
        return SchemaScope(input = ups.firstOrNull { it.schema != null }?.schema, upstream = ups, otherSteps = others, varNames = flow.vars.keys)
    }
}
