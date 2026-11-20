package com.kaivoflow.core.ai

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.model.Lifecycle
import com.kaivoflow.core.model.NodeRole
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.ParamValueCodec
import com.kaivoflow.core.model.StepNode
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException

/**
 * [httpStatus] and [errorBody] are for failures that came from an HTTP answer: the loop puts the status first and
 * appends the body, redacted and capped, in the stable shape of [ToolErrors].
 */
data class ToolOutcome(val text: String, val isError: Boolean = false, val httpStatus: Int? = null, val errorBody: String? = null)

/** One capability offered to the model. Implementations must be safe to call with any model-chosen arguments. */
interface AgentTool {
    /** Provider-safe name: letters, digits, `_` and `-`, at most 64 characters. */
    val name: String
    val description: String

    /** JSON Schema (type object) for the arguments. */
    val parameters: Map<String, Any?>
    val risk: ToolRisk
    suspend fun execute(arguments: Map<String, Any?>): ToolOutcome

    /** Short human text for the approval sheet, for example the recipient or host. */
    fun describeTarget(arguments: Map<String, Any?>): String = ""
}

/**
 * The tools one Run may use. [allow], when given, is the allow-list: any tool not named in it is
 * dropped here, so it is neither offered to the model nor callable if the model invents the name.
 */
class ToolRegistry(tools: List<AgentTool>, allow: Set<String>? = null) {
    private val byName: Map<String, AgentTool>

    init {
        val kept = tools.filter { allow == null || it.name in allow }
        kept.forEach { require(NAME_RE.matches(it.name)) { "invalid tool name \"${it.name}\"" } }
        val dupes = kept.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "duplicate tool names: $dupes" }
        byName = kept.associateBy { it.name }
    }

    val all: List<AgentTool> get() = byName.values.toList()
    operator fun get(name: String): AgentTool? = byName[name]
    fun offered(policy: ApprovalPolicy): List<AgentTool> = all.filter { policy.isOffered(it) }
    fun specs(policy: ApprovalPolicy): List<ToolSpec> = offered(policy).map { ToolSpec(it.name, it.description.take(1_000), it.parameters) }

    companion object { val NAME_RE = Regex("[A-Za-z0-9_-]{1,64}") }
}

/** Which Steps can become tools, how risky each is, and the JSON Schema for its parameters. */
object StepTools {
    /** Real catalog ids that only transform data already in hand. */
    private val READ = setOf(
        "data.compare", "data.date", "data.filter", "data.html", "data.ical", "data.limit", "data.markdown",
        "data.set", "data.sort", "data.split_out", "data.xml", "security.crypto",
        "device.battery_status", "device.network_status",
    )

    /** Fetch-style Steps: Read only when the author locked the address, otherwise the model could send data anywhere. */
    private val FETCH = setOf("net.http", "net.rss")

    /** Not offerable: flow control, triggers and anything that would recurse into the AI family. */
    fun isOfferable(spec: NodeSpec): Boolean =
        !spec.isTrigger &&
            spec.role == NodeRole.Main &&
            spec.lifecycle != Lifecycle.Hidden && spec.lifecycle != Lifecycle.Removed &&
            !spec.id.startsWith("ai.") && !spec.id.startsWith("flow.") && !spec.id.startsWith("spark.")

    fun riskOf(stepType: String, fixed: Map<String, Any?> = emptyMap()): ToolRisk = when {
        stepType in READ -> ToolRisk.Read
        stepType in FETCH -> {
            val urlLocked = fixed["url"]?.toString()?.isNotBlank() == true && !fixed["url"].toString().contains("{{")
            val getOnly = stepType == "net.rss" || fixed["method"]?.toString().equals("GET", true)
            if (urlLocked && getOnly) ToolRisk.Read else ToolRisk.ExternalEffect
        }
        stepType == "device.file" -> if (fixed["action"]?.toString().equals("read", true)) ToolRisk.ExternalEffect else ToolRisk.Destructive
        // Everything else, including unknown ids, is treated as able to leave the device.
        else -> ToolRisk.ExternalEffect
    }

    /** JSON Schema for the model-controlled params: secrets, locked params and advanced params are left out. */
    fun schema(spec: NodeSpec, locked: Set<String> = emptySet()): Map<String, Any?> {
        val props = LinkedHashMap<String, Any?>()
        val required = ArrayList<String>()
        for (p in spec.params) {
            if (p.type == ParamType.Secret || p.key in locked) continue
            if (p.advanced && !p.required) continue
            val prop = LinkedHashMap<String, Any?>()
            prop["type"] = when (p.type) {
                ParamType.Number -> "number"
                ParamType.Boolean -> "boolean"
                ParamType.Json, ParamType.KeyValue -> "object"
                else -> "string"
            }
            prop["description"] = listOf(p.label, p.hint).filter { it.isNotBlank() }.joinToString(". ").take(300)
            if (p.type == ParamType.Select && p.options.isNotEmpty()) prop["enum"] = p.options.map { it.value }
            props[p.key] = prop
            if (p.required && p.default == null && p.showWhen.isEmpty()) required += p.key
        }
        return mapOf("type" to "object", "properties" to props, "required" to required, "additionalProperties" to false)
    }

    /** Model-chosen text must never be read as an expression by the Step's parameter resolver. */
    fun neutralize(v: Any?): Any? = when (v) {
        is String -> v.replace("{{", "{​{")
        is Map<*, *> -> v.entries.associate { (k, x) ->
            val key = k.toString().let { if (it == ParamValueCodec.EXPR_KEY || it == ParamValueCodec.LITERAL_KEY) "_$it" else it }
            key to neutralize(x)
        }
        is List<*> -> v.map { neutralize(it) }
        else -> v
    }
}

/**
 * Offers one existing Step as a tool. Author-fixed params always win over what the model sends,
 * only params in the schema are taken from the model, and the Step runs through its normal executor
 * with a context built by [contextFor], so credentials, expressions and Run logging stay the same.
 */
class StepTool(
    override val name: String,
    override val description: String,
    private val executor: StepExecutor,
    private val fixedParams: Map<String, Any?> = emptyMap(),
    override val risk: ToolRisk = StepTools.riskOf(executor.spec.id, fixedParams),
    private val maxResultChars: Int = 8_000,
    private val redactor: Redactor = Redactor(),
    private val contextFor: (StepNode, Map<String, Any?>) -> StepContext,
) : AgentTool {
    override val parameters: Map<String, Any?> = StepTools.schema(executor.spec, fixedParams.keys)
    private val allowedKeys: Set<String> = J.obj(parameters["properties"]).orEmpty().keys

    override fun describeTarget(arguments: Map<String, Any?>): String =
        listOf("to", "url", "chatId", "phone", "fileName", "channel", "webhookUrl")
            .firstNotNullOfOrNull { k -> (fixedParams[k] ?: arguments[k])?.toString()?.takeIf { it.isNotBlank() } }
            ?.take(120).orEmpty()

    override suspend fun execute(arguments: Map<String, Any?>): ToolOutcome {
        @Suppress("UNCHECKED_CAST")
        val safe = (StepTools.neutralize(arguments) as Map<String, Any?>).filterKeys { it in allowedKeys }
        val node = StepNode(
            id = "tool-$name", type = executor.spec.id, name = name,
            params = (safe + fixedParams).toImmutableMap(),
        )
        return try {
            val result = executor.execute(contextFor(node, safe))
            val rows = result.outputs.toSortedMap().values.flatten().map { it.json }
            val text = J.write(if (rows.size == 1) rows[0] else rows)
            ToolOutcome(if (text.length > maxResultChars) text.take(maxResultChars) + "…[truncated]" else text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StepExecutionException) {
            val m = redactor.redact(e.message ?: "the step failed")
            ToolOutcome(m, isError = true, httpStatus = ToolErrors.statusIn(m))
        } catch (e: Throwable) {
            val m = "the step failed: ${redactor.redact(e.message ?: e::class.java.simpleName).take(200)}"
            ToolOutcome(m, isError = true, httpStatus = ToolErrors.statusIn(m))
        }
    }
}
