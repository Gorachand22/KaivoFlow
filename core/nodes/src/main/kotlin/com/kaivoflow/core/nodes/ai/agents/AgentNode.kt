package com.kaivoflow.core.nodes.ai.agents

import com.kaivoflow.core.ai.AgentLimits
import com.kaivoflow.core.ai.AgentLoop
import com.kaivoflow.core.ai.ApprovalCallback
import com.kaivoflow.core.ai.ApprovalHost
import com.kaivoflow.core.ai.ApprovalPolicy
import com.kaivoflow.core.ai.CostRates
import com.kaivoflow.core.ai.InMemoryStore
import com.kaivoflow.core.ai.MemoryStore
import com.kaivoflow.core.ai.Redactor
import com.kaivoflow.core.ai.SchemaCheck
import com.kaivoflow.core.ai.StepTool
import com.kaivoflow.core.ai.StepTools
import com.kaivoflow.core.ai.StructuredOutputParser
import com.kaivoflow.core.ai.ToolRegistry
import com.kaivoflow.core.ai.WindowMemory
import com.kaivoflow.core.engine.ExecutionMode
import com.kaivoflow.core.engine.IssueSeverity
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.ValidationIssue
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.nodes.CatalogAliases
import com.kaivoflow.core.nodes.ai.shared.AiSlots
import com.kaivoflow.core.nodes.ai.shared.ChatModelFactory
import com.kaivoflow.core.nodes.resolveText
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException

/** Finds the executor of any Step type, so an Agent can offer existing Steps as tools. */
fun interface ToolStepResolver {
    fun executor(type: String): StepExecutor?
}

/**
 * AI Agent: a model that can call tools in a loop, per input item.
 *
 * Attached parts live inside this Step (taxonomy decision 4), as Json params: `chatModel` (one),
 * `memory` (optional), `tools` (any Step, with params the author locks) and `outputParser` (optional
 * JSON schema). Keys never appear here; the model slot names a vault credential. Nothing in it needs
 * a screen, so a Flow with an Agent runs the same from the canvas, a Schedule or the always-on engine.
 *
 * Tool safety: read-only tools run on their own; side-effecting tools need approval. With an
 * [approvals] callback and an interactive Run a person is asked. Otherwise (always-on, or no one
 * attached) only tools the author marked `allowUnattended` run, each at most `rateLimit` times per
 * item; destructive and persisting tools are not offered at all.
 */
class AgentNode(
    private val toolSteps: ToolStepResolver = ToolStepResolver { null },
    private val factory: ChatModelFactory = ChatModelFactory.Default,
    private val memoryStore: MemoryStore = InMemoryStore(),
    private val approvals: ApprovalCallback? = null,
) : StepExecutor {

    override val spec = NodeSpec(
        id = "ai.agent",
        title = "AI Agent",
        category = StepCategory.Ai,
        subtitle = "A model that uses tools to finish a task",
        iconKey = "sparkles",
        params = listOf(
            ParamSpec("prompt", "Task", ParamType.Multiline, required = true, placeholder = "Check the weather and tell me if I need an umbrella"),
            ParamSpec("system", "Instructions", ParamType.Multiline, hint = "How the agent should behave. This is your own text, so it is trusted."),
            ParamSpec("data", "Data to read", ParamType.Multiline, advanced = true,
                hint = "Item data the agent may use, for example {{ \$json.text }}. It is fenced as untrusted data, so text inside it cannot give orders."),
            ParamSpec("chatModel", "Chat model", ParamType.Json, required = true, supportsExpression = false,
                hint = "{\"provider\":\"openai\",\"model\":\"gpt-4o-mini\",\"credential\":\"<vault id>\"}"),
            ParamSpec("memory", "Memory", ParamType.Json, supportsExpression = false, advanced = true,
                hint = "{\"type\":\"window\",\"maxMessages\":20,\"key\":\"{{ \$json.chatId }}\"}"),
            ParamSpec("tools", "Tools", ParamType.Json, supportsExpression = false,
                hint = "[{\"step\":\"net.http\",\"name\":\"fetch\",\"params\":{\"url\":\"https://...\",\"method\":\"GET\"},\"allowUnattended\":false}]"),
            ParamSpec("outputParser", "Output parser", ParamType.Json, supportsExpression = false, advanced = true,
                hint = "{\"schema\":{\"city\":\"string\",\"temp\":\"number\"},\"maxRepairs\":2}"),
            ParamSpec("maxIterations", "Max steps", ParamType.Number, default = 10),
            ParamSpec("maxToolCalls", "Max tool calls", ParamType.Number, default = 25, advanced = true),
            ParamSpec("maxTotalTokens", "Max tokens in total", ParamType.Number, default = 50_000, advanced = true),
            ParamSpec("timeoutSeconds", "Time limit (seconds)", ParamType.Number, default = 120, advanced = true),
            ParamSpec("maxCost", "Max cost", ParamType.Number, advanced = true, hint = "In the unit of the prices below; empty means no limit."),
            ParamSpec("promptPricePer1k", "Price per 1000 prompt tokens", ParamType.Number, advanced = true),
            ParamSpec("completionPricePer1k", "Price per 1000 answer tokens", ParamType.Number, advanced = true),
            ParamSpec("outputField", "Put the answer in", ParamType.Text, default = "answer"),
        ).toImmutableList(),
        keywords = listOf("agent", "ai", "tools", "llm", "assistant", "tool calling", "claude", "gpt", "gemini").toImmutableList(),
    )

    override val requiresNetwork: Boolean get() = true

    override fun validate(step: StepNode, spec: NodeSpec): List<ValidationIssue> {
        val out = ArrayList<ValidationIssue>()
        val model = AiSlots.asObject(step.params["chatModel"])
        if (model == null || model["model"]?.toString().isNullOrBlank()) {
            out += ValidationIssue("agent.model", "Attach a chat model to the agent.", IssueSeverity.Blocking, step.id, listOf("chatModel"))
        } else if (model.keys.any { it.lowercase() in setOf("apikey", "api_key", "key", "token", "secret", "password") }) {
            out += ValidationIssue("agent.key_in_params", "Remove the key from the chat model and pick a credential instead.", IssueSeverity.Blocking, step.id, listOf("chatModel"))
        }
        val tools = step.params["tools"]?.let { AiSlots.asList(it) }
        if (step.params["tools"] != null && tools == null) {
            out += ValidationIssue("agent.tools", "Tools must be a list.", IssueSeverity.Blocking, step.id, listOf("tools"))
        }
        return out
    }

    override suspend fun execute(ctx: StepContext): StepResult {
        fun fail(msg: String): Nothing = throw StepExecutionException(ctx.step.id, ctx.step.name, msg)
        val raw = ctx.step.params
        val modelValues = AiSlots.asObject(raw["chatModel"]) ?: fail("attach a chat model (provider, model and credential)")
        val slot = AiSlots.slotFrom(ctx, modelValues)
        val client = AiSlots.client(ctx, slot, factory)

        val global = ctx.resolved()
        val limits = try {
            AgentLimits(
                maxIterations = global.int("maxIterations", 10).coerceIn(1, 50),
                maxToolCalls = global.int("maxToolCalls", 25).coerceIn(0, 200),
                maxTotalTokens = global.long("maxTotalTokens", 50_000),
                maxCostUnits = global.doubleOrNull("maxCost"),
                maxWallMs = global.long("timeoutSeconds", 120).coerceIn(1, 3_600) * 1000,
                maxOutputTokens = slot.maxTokens,
            )
        } catch (e: IllegalArgumentException) {
            fail(e.message.orEmpty())
        }
        val rates = global.doubleOrNull("promptPricePer1k")?.let { p -> CostRates(p, global.double("completionPricePer1k", p)) }

        val parser = raw["outputParser"]?.let { p ->
            val cfg = AiSlots.asObject(p) ?: fail("the output parser must be an object with a schema")
            val schema = SchemaCheck.normalize(cfg["schema"]) ?: fail("the output parser schema is not valid")
            StructuredOutputParser(schema, (cfg["maxRepairs"] as? Number)?.toInt()?.coerceIn(0, 5) ?: 2)
        }
        val memoryCfg = raw["memory"]?.let { AiSlots.asObject(it) }
        val memory = memoryCfg?.takeIf { (it["type"]?.toString() ?: "window") == "window" }?.let {
            WindowMemory(memoryStore, ((it["maxMessages"] as? Number)?.toInt() ?: 20).coerceIn(2, 200))
        }

        val interactive = approvals != null && ctx.mode != ExecutionMode.Production
        val toolList = buildTools(ctx, raw["tools"])
        val unattendedAllow = toolList.second
        val rateLimit = toolList.third
        val redactor = Redactor()
        val registry = ToolRegistry(toolList.first)
        val outField = global.string("outputField", "answer")

        return ctx.perItem { index, item ->
            val row = ctx.resolvedFor(index)
            val task = row.string("prompt", "")
            if (task.isBlank()) fail("the task is empty")
            val loop = AgentLoop(
                model = client, tools = registry, policy = ApprovalPolicy(if (interactive) ApprovalHost.Interactive else ApprovalHost.Unattended, if (interactive) approvals else null, unattendedAllow, rateLimit),
                limits = limits, memory = memory, parser = parser, redactor = redactor, costRates = rates, temperature = slot.temperature,
            )
            val key = memoryCfg?.get("key")?.let { ctx.resolveText(it, index) }?.takeIf { it.isNotBlank() } ?: "${ctx.flow.id}:${ctx.step.id}"
            val result = loop.run(row.stringOrNull("system"), task, row.stringOrNull("data"), key)
            result.trace.forEach { ctx.log("agent tool ${it.tool}: ${it.status} ${it.detail}") }
            if (!result.isSuccess) AiSlots.failure(ctx, result.error!!)
            val answer: Any? = if (parser != null) result.structured else result.text
            listOf(item.copy(json = item.json + (outField to answer) + ("_ai" to AiSlots.meta(result, slot))))
        }
    }

    /** Returns the tools, the names the author allowed to run unattended, and the per call rate limit. */
    private fun buildTools(ctx: StepContext, raw: Any?): Triple<List<StepTool>, Set<String>, Int> {
        fun fail(msg: String): Nothing = throw StepExecutionException(ctx.step.id, ctx.step.name, msg)
        if (raw == null) return Triple(emptyList(), emptySet(), 5)
        val entries = AiSlots.asList(raw) ?: fail("tools must be a list")
        val allow = HashSet<String>()
        var limit = 5
        val tools = entries.mapIndexed { i, e ->
            val m = AiSlots.asObject(e) ?: fail("tool ${i + 1} must be an object with a \"step\"")
            val type = m["step"]?.toString()?.trim().orEmpty().let { CatalogAliases.renames[it] ?: it }
            val executor = toolSteps.executor(type) ?: fail("tool ${i + 1}: Step \"$type\" is not available")
            if (!StepTools.isOfferable(executor.spec)) fail("tool ${i + 1}: Step \"$type\" cannot be used as a tool")
            val name = m["name"]?.toString()?.trim()?.ifBlank { null } ?: type.replace('.', '_')
            val fixed = AiSlots.asObject(m["params"]).orEmpty()
            if (m["allowUnattended"] == true) allow += name
            (m["rateLimit"] as? Number)?.toInt()?.let { limit = it.coerceIn(1, 100) }
            StepTool(
                name = name,
                description = m["description"]?.toString()?.take(500)?.ifBlank { null } ?: "${executor.spec.title}: ${executor.spec.subtitle}",
                executor = executor,
                fixedParams = fixed,
            ) { node, args ->
                ctx.copy(step = node, input = listOf(FlowData(args)), inputs = emptyList(), inputRefs = emptyList(), streamInputs = emptyList(), spec = executor.spec)
            }
        }
        return Triple(tools, allow, limit)
    }
}
