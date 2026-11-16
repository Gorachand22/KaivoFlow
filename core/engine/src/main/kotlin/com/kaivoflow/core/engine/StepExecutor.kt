package com.kaivoflow.core.engine

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.ItemRef
import com.kaivoflow.core.model.LogLevel
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.toSchema
import kotlinx.coroutines.CancellationException

/** Where a Run is coming from. Behaviour changes subtly (Freeze Output, item limits, logging). */
enum class ExecutionMode {
    /** Background / scheduled / webhook. Frozen outputs are used, nothing is interactive. */
    Production,

    /** The big gold "Run" button. Frozen outputs are respected. */
    Manual,

    /** "Test Step" in the inspector: only this Step runs, frozen output ignored. */
    TestStep,
    ;

    val isInteractive: Boolean get() = this != Production
}

data class StepContext(
    val step: StepNode,
    val flow: Flow,
    /**
     * Every item that reached this Step, flattened in Stream order. Kept for Steps that do not
     * care which input port an item arrived on; multi-input Steps read [inputs] instead.
     */
    val input: List<FlowData>,
    val runId: String,
    val mode: ExecutionMode,
    val attempt: Int,
    val evaluator: ExpressionEvaluator,
    val http: HttpGateway,
    val credentials: CredentialProvider,
    val clock: () -> Long,
    val timezone: String,
    /**
     * Primary-port (port 0) outputs of Steps that already finished in this Run, keyed by Step id.
     * Ids are stable across renames; expressions reach them by name through [stepNames].
     */
    val stepOutputs: Map<String, List<FlowData>>,
    val logger: (message: String, level: LogLevel) -> Unit,
    /** Optional composition hook: runs another Flow and returns its terminal items. */
    val flowInvoker: FlowInvoker? = null,
    /** How many Sub-Flows are already below this Run; used to stop recursive composition. */
    val subFlowDepth: Int = 0,
    /** Spec of the executor running this Step; lets the resolver honour `supportsExpression`. Bound by [NodeRegistry]. */
    val spec: NodeSpec? = null,
    /**
     * Items per input port: `inputs[toPort]`. Ports that are connected but received nothing hold
     * an empty list. Empty when the context was not built by the runner (unit tests); use
     * [inputPort] to read it safely.
     */
    val inputs: List<List<FlowData>> = emptyList(),
    /** Identity of each item in [input], same order. Empty when not built by the runner. */
    val inputRefs: List<ItemRef> = emptyList(),
    /** One entry per incoming Stream (in Stream order), with its own items and refs. */
    val streamInputs: List<StreamInput> = emptyList(),
    /** Outputs of finished Steps for every output port, keyed by Step id, then port. */
    val stepPorts: Map<String, Map<Int, List<FlowData>>> = emptyMap(),
    /** Execution number of this Step inside the Run (always 0 until Loops exist). */
    val runIndex: Int = 0,
) {
    private val scopeBuilder: ScopeBuilder by lazy {
        ScopeBuilder(
            flowVars = flow.vars,
            runId = runId,
            stepOutputs = stepOutputs,
            clock = clock,
            timezone = timezone,
            stepNames = flow.steps.associate { it.id to it.name },
            stepPorts = stepPorts,
            inputRefs = inputRefs,
            flowId = flow.id,
            flowName = flow.name,
            stepId = step.id,
            mode = mode.name,
            attempt = attempt,
        )
    }

    /** Items that arrived on input port [port]; port 0 falls back to [input] outside the runner. */
    fun inputPort(port: Int): List<FlowData> =
        if (inputs.isEmpty()) (if (port == 0) input else emptyList()) else inputs.getOrElse(port) { emptyList() }

    /** Identity of the input item at [index], or null when lineage is not tracked (plain tests). */
    fun refOf(index: Int): ItemRef? = inputRefs.getOrNull(index)

    /**
     * Step-level params, resolved against item 0. Use only for values that must not depend on an
     * item (operation, credential, mode). Executors that process items one by one use [paramsFor].
     */
    @Deprecated("Read typed values with resolved(); this map has no defaults and no show rules.", ReplaceWith("resolved().toMap()"))
    fun params(): Map<String, Any?> = legacyParamsFor(0)

    /** Params resolved against the item at [index]; fields with supportsExpression=false are passed through verbatim. */
    @Deprecated("Read typed values with resolvedFor(index); this map has no defaults and no show rules.", ReplaceWith("resolvedFor(index).toMap()"))
    fun paramsFor(index: Int): Map<String, Any?> = legacyParamsFor(index)

    private fun legacyParamsFor(index: Int): Map<String, Any?> =
        ParamResolver(evaluator).resolveParams(step, scope(index), spec)

    private val schema: ParamSchema? by lazy { spec?.toSchema() }

    /**
     * Typed params for the item at [index]: defaults applied for visible params, hidden params
     * omitted, expressions resolved against that item, Secret and Code never expanded.
     * Falls back to the plain stored values when the Step has no spec (plain unit tests).
     */
    fun resolvedFor(index: Int): ResolvedParams {
        val s = schema ?: return ResolvedParams(legacyParamsFor(index))
        return ParamResolver(evaluator).resolveStored(s, step.params, scope(index))
    }

    /** Typed params against item 0; use for values that must not depend on an item. */
    fun resolved(): ResolvedParams = resolvedFor(0)

    fun scope(index: Int = 0): Map<String, Any?> = scopeBuilder.build(input, index, step.name)

    @Suppress("DEPRECATION")
    fun stringParam(key: String, params: Map<String, Any?> = params()): String = Values.asString(params[key])

    @Suppress("DEPRECATION")
    fun requireParam(key: String, params: Map<String, Any?> = params()): Any =
        params[key] ?: throw StepExecutionException(step.id, step.name, "missing \"$key\"")

    /** Credential lookup by the id stored on the Step, e.g. `credentials.openai`. */
    suspend fun credential(key: String = "credential"): Map<String, String> {
        val params = legacyParamsFor(0)
        val ref = Values.asString(params[key])
        if (ref.isBlank()) throw StepExecutionException(step.id, step.name, "no credential selected")
        return credentials.resolve(ref)
    }

    fun log(message: String, level: LogLevel = LogLevel.Info) = logger(message, level)

    /** Terminal output for a linear Step. */
    fun output(items: List<FlowData>) = StepResult(mapOf(0 to items))

    fun outputOf(vararg columns: Pair<String, Any?>) = output(listOf(FlowData(mapOf(*columns))))

    /**
     * Runs [block] once per input item and keeps going when one item throws. Results become
     * port 0; each failure is reported in [StepResult.failed] so the runner can apply the Step's
     * error policy per item instead of discarding the whole Step. Output items get this input
     * item as their source.
     */
    suspend fun perItem(block: suspend (index: Int, item: FlowData) -> List<FlowData>?): StepResult {
        val out = ArrayList<FlowData>()
        val failed = ArrayList<ItemFailure>()
        input.forEachIndexed { index, item ->
            try {
                val produced = block(index, item) ?: return@forEachIndexed
                val ref = refOf(index)
                produced.forEach { out += if (ref != null) it.copy(sources = listOf(ref)) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed += ItemFailure(index, e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
            }
        }
        return StepResult(mapOf(0 to out), failed = failed)
    }
}

/** Items that reached a Step over one incoming Stream. */
data class StreamInput(
    val streamId: String,
    val fromStepId: String,
    val fromPort: Int,
    val toPort: Int,
    val items: List<FlowData>,
    val refs: List<ItemRef>,
)

/** One input item a Step could not process. [inputIndex] indexes [StepContext.input]. */
data class ItemFailure(val inputIndex: Int, val message: String, val kind: String = "item")

/** Asks the runner to end the whole Run after this Step (see the Stop Step). */
data class StopSignal(val message: String)

data class StepResult(
    /** Keyed by output port index. Missing ports mean "nothing on that branch". */
    val outputs: Map<Int, List<FlowData>> = emptyMap(),
    val logs: List<String> = emptyList(),
    /** Input items that failed individually; the Step's error policy decides what happens to them. */
    val failed: List<ItemFailure> = emptyList(),
    /** When set, the runner cancels the remaining Steps and ends the Run as stopped. */
    val stop: StopSignal? = null,
) {
    fun port(index: Int): List<FlowData> = outputs[index] ?: emptyList()
    val primary: List<FlowData> get() = port(0)
}

/** One executable Step type. Executors are pure: no Android, no global state. */
interface StepExecutor {
    val spec: NodeSpec
    suspend fun execute(ctx: StepContext): StepResult

    /** Optional cheap validation so the canvas can warn before a Run starts. */
    fun validate(step: StepNode, spec: NodeSpec): List<ValidationIssue> = emptyList()

    /**
     * Whether this Step needs the network. The runner uses this to enforce the
     * "runs stay on device" promise in the privacy centre and to fail fast when offline.
     */
    val requiresNetwork: Boolean get() = false
}

/** Networking is injected so executors stay testable and the HTTP client stays in `:core:network`. */
interface HttpGateway {
    suspend fun request(request: HttpRequestSpec): HttpResponseSpec
}

data class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val body: Any? = null,
    val bodyKind: BodyKind = BodyKind.Json,
    val timeoutMs: Long = 60_000,
    val followRedirects: Boolean = true,
    /** Fail on 4xx/5xx instead of returning them as data. */
    val failOnErrorStatus: Boolean = true,
    val formFields: Map<String, String> = emptyMap(),
    /** When true the response bytes are returned as base64 in [HttpResponseSpec.bodyBase64]. */
    val responseAsBinary: Boolean = false,
    /** When set, the body is sent as multipart/form-data carrying one binary attachment. */
    val binaryUpload: BinaryUploadSpec? = null,
) {
    enum class BodyKind { Json, Form, Raw, Multipart, None }
}

/** One file to upload as multipart/form-data. The bytes are base64 so the contract stays JSON-ish. */
data class BinaryUploadSpec(
    val field: String,
    val fileName: String,
    val mimeType: String,
    val base64: String,
    val extraFields: Map<String, String> = emptyMap(),
)

data class HttpResponseSpec(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val bodyText: String = "",
    val body: Any? = null,
    val durationMs: Long = 0,
    /** Base64 of the raw response bytes, populated only when [HttpRequestSpec.responseAsBinary]. */
    val bodyBase64: String = "",
) {
    val isSuccess: Boolean get() = status in 200..299
}

/** Resolves credential references from the encrypted vault. */
interface CredentialProvider {
    suspend fun resolve(ref: String): Map<String, String>
    suspend fun list(): List<com.kaivoflow.core.model.CredentialRef> = emptyList()
}

/** A step type the engine does not know about — reported as a validation error, never a crash. */
class UnknownStepTypeException(val type: String) : KaivoException("Unknown Step type: $type")
