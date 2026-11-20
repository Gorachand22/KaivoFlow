package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.CredentialProvider
import com.kaivoflow.core.engine.ExpressionEvaluator
import com.kaivoflow.core.engine.FlowInvoker
import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.HttpResponseSpec
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.StepNode
import kotlinx.collections.immutable.toImmutableMap

/** Evaluator that understands `$json.path` / `$vars.path` and leaves everything else literal. */
class TestEvaluator : ExpressionEvaluator {
    override fun evaluate(code: String, scope: Map<String, Any?>): Any? {
        val text = code.trim()
        if (!text.startsWith("$")) return text
        val path = text.drop(1)
        val head = path.substringBefore('.').substringBefore('[')
        val rest = path.removePrefix(head).trimStart('.', '[').trimEnd(']')
        val root = scope[head] ?: return null
        return if (rest.isEmpty()) root else Values.deepGet(root, rest)
    }
}

class TestCredentials(private val map: Map<String, Map<String, String>> = mapOf("cred-1" to mapOf("apiKey" to "test-token"))) :
    CredentialProvider {
    override suspend fun resolve(ref: String): Map<String, String> =
        map[ref] ?: throw IllegalStateException("no credential $ref")
}

/** Records requests and replays a queue of canned responses. */
class RecordingHttp(private val responses: MutableList<HttpResponseSpec> = mutableListOf()) : HttpGateway {
    val requests = mutableListOf<HttpRequestSpec>()
    var responder: (HttpRequestSpec) -> HttpResponseSpec = { HttpResponseSpec(status = 200, body = mapOf("ok" to true)) }

    override suspend fun request(request: HttpRequestSpec): HttpResponseSpec {
        requests += request
        return if (responses.isEmpty()) responder(request) else responses.removeAt(0)
    }
}

class RecordingDevices : DeviceGateway {
    val notifications = mutableListOf<Triple<String, String, String>>()
    val sms = mutableListOf<Pair<String, String>>()
    val spoken = mutableListOf<String>()
    val files = mutableMapOf<String, String>()
    var clipboard: String? = null
    var opened: String? = null
    var smsAllowed = true
    var notificationAccess = true

    override suspend fun sendNotification(title: String, body: String, channel: String, deepLink: String?) {
        notifications += Triple(title, body, channel)
    }
    override suspend fun sendSms(to: String, body: String, simSlot: Int?) { sms += to to body }
    override suspend fun copyToClipboard(text: String) { clipboard = text }
    override suspend fun openUrlOrApp(uri: String, packageName: String?) { opened = uri }
    override suspend fun speak(text: String, language: String) { spoken += text }
    override suspend fun writeFile(fileName: String, content: String): String {
        files[fileName] = content
        return fileName
    }
    override suspend fun readFile(fileName: String): String? = files[fileName]
    override suspend fun vibrate(pattern: List<Long>) = Unit
    override suspend fun isNotificationAccessGranted(): Boolean = notificationAccess
    override suspend fun isSmsPermissionGranted(): Boolean = smsAllowed
}

fun stepOf(
    type: String,
    name: String = type,
    params: Map<String, Any?> = emptyMap(),
): StepNode = StepNode(
    id = "step-$type",
    type = type,
    name = name,
    params = params.toImmutableMap(),
)

fun ctx(
    step: StepNode,
    input: List<FlowData>,
    http: HttpGateway = RecordingHttp(),
    devices: DeviceGateway = RecordingDevices(),
    flow: Flow = Flow(id = "f", name = "Flow"),
    stepOutputs: Map<String, List<FlowData>> = emptyMap(),
    evaluator: ExpressionEvaluator = TestEvaluator(),
    flowInvoker: FlowInvoker? = null,
    subFlowDepth: Int = 0,
    credentials: CredentialProvider = TestCredentials(),
): StepContext = StepContext(
    step = step,
    flow = flow,
    input = input,
    runId = "run-1",
    mode = com.kaivoflow.core.engine.ExecutionMode.Manual,
    attempt = 1,
    evaluator = evaluator,
    http = http,
    credentials = credentials,
    clock = { 1_700_000_000_000 },
    timezone = "Asia/Kolkata",
    stepOutputs = stepOutputs,
    logger = { _, _ -> },
    flowInvoker = flowInvoker,
    subFlowDepth = subFlowDepth,
)

fun item(vararg pairs: Pair<String, Any?>) = FlowData(mapOf(*pairs))
