package com.kaivoflow.core.engine

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.LogLevel
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepNode

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
    /** Items arriving on output port 0 of the upstream Step(s). */
    val input: List<FlowData>,
    val runId: String,
    val mode: ExecutionMode,
    val attempt: Int,
    val evaluator: ExpressionEvaluator,
    val http: HttpGateway,
    val credentials: CredentialProvider,
    val clock: () -> Long,
    val timezone: String,
    /** Outputs of Steps that already finished in this Run, keyed by Step id. */
    val stepOutputs: Map<String, List<FlowData>>,
    val logger: (message: String, level: LogLevel) -> Unit,
) {
    /** Resolves params with expressions applied. Executors call this instead of touching raw maps. */
    fun params(): Map<String, Any?> =
        ParamResolver(evaluator).resolveParams(step, scope())

    fun scope(index: Int = 0): Map<String, Any?> = ScopeBuilder(
        flowVars = flow.vars,
        runId = runId,
        stepOutputs = stepOutputs,
        clock = clock,
        timezone = timezone,
    ).build(input, index, step.name)

    fun stringParam(key: String, params: Map<String, Any?> = params()): String = Values.asString(params[key])

    fun requireParam(key: String, params: Map<String, Any?> = params()): Any =
        params[key] ?: throw StepExecutionException(step.id, step.name, "missing \"$key\"")

    /** Credential lookup by the id stored on the Step, e.g. `credentials.openai`. */
    suspend fun credential(key: String = "credential"): Map<String, String> {
        val params = params()
        val ref = Values.asString(params[key])
        if (ref.isBlank()) throw StepExecutionException(step.id, step.name, "no credential selected")
        return credentials.resolve(ref)
    }

    fun log(message: String, level: LogLevel = LogLevel.Info) = logger(message, level)

    /** Terminal output for a linear Step. */
    fun output(items: List<FlowData>) = StepResult(mapOf(0 to items))

    fun outputOf(vararg columns: Pair<String, Any?>) = output(listOf(FlowData(mapOf(*columns))))
}

data class StepResult(
    /** Keyed by output port index. Missing ports mean "nothing on that branch". */
    val outputs: Map<Int, List<FlowData>> = emptyMap(),
    val logs: List<String> = emptyList(),
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
