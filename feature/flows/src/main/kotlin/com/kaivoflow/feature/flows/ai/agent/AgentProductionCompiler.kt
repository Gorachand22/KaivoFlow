package com.kaivoflow.feature.flows.ai.agent

import com.kaivoflow.core.ai.ProviderEndpoints
import com.kaivoflow.core.ai.StepTools
import com.kaivoflow.core.engine.SparkTypes
import com.kaivoflow.core.model.ExprFieldCodec
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowSettings
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StreamEdge
import com.kaivoflow.feature.flows.ai.AiProvider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

object AgentIds {
    const val PREFIX = "agent:"

    /** Tag that marks a Flow as the compiled form of an agent. Lists and the canvas skip tagged Flows. */
    const val TAG = "kaivo:agent"
    fun flowId(agentId: String) = PREFIX + agentId
    fun isAgentFlow(flowId: String) = flowId.startsWith(PREFIX)
    fun agentId(flowId: String): String? = if (isAgentFlow(flowId)) flowId.removePrefix(PREFIX) else null
}

enum class AgentIssueSeverity { Blocking, Warning }

/** One finding of the agent-specific publish checks. [code] is stable (tests, UI), [field] names where to fix it. */
data class AgentIssue(val code: String, val message: String, val severity: AgentIssueSeverity = AgentIssueSeverity.Blocking, val field: String? = null)

/** The credential as the vault reports it. Never carries the secret. */
data class AgentCredentialInfo(val id: String, val provider: AiProvider, val baseUrl: String)

/** What a live call with the credential said. [NotChecked] is only for the pure compile (the "changes not live" check). */
sealed interface CredentialProbe {
    data object NotChecked : CredentialProbe
    data class Working(val modelListed: Boolean) : CredentialProbe
    data class Failed(val message: String) : CredentialProbe
}

/** Result of the production compile. [flow] is null exactly when there is a blocking issue. */
data class ProductionCompile(val flow: Flow?, val issues: List<AgentIssue>) {
    val blocking: List<AgentIssue> get() = issues.filter { it.severity == AgentIssueSeverity.Blocking }
    val warnings: List<AgentIssue> get() = issues.filter { it.severity == AgentIssueSeverity.Warning }
}

/** Where an agent trigger's settings turn into the settings of a Spark Step. */
object AgentTriggerConfig {
    fun dailyAt(time: String): Map<String, String> = mapOf("frequency" to "daily", "time" to time)

    sealed interface Mapped {
        data class Ok(val params: Map<String, Any?>) : Mapped
        data class Bad(val message: String) : Mapped
    }

    private val TIME = Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")

    /** Schedule settings, from `frequency` keys or from a simple legacy `cron` text. Anything else is refused, never approximated. */
    fun schedule(config: Map<String, String>): Mapped {
        val zone = config["timezone"]?.trim().orEmpty().ifBlank { "device" }
        val freq = config["frequency"]?.trim().orEmpty()
        if (freq.isEmpty()) {
            val cron = config["cron"]?.trim().orEmpty()
            if (cron.isEmpty()) return Mapped.Bad("The schedule has no time. Pick when it runs.")
            return fromCron(cron, zone)
        }
        val p = linkedMapOf<String, Any?>("frequency" to freq, "timezone" to zone)
        when (freq) {
            "minutes" -> p["interval"] = config["interval"]?.trim()?.toIntOrNull() ?: 15
            "hourly" -> Unit
            "daily", "weekly", "monthly" -> {
                val t = config["time"]?.trim().orEmpty().ifBlank { "09:00" }
                if (!TIME.matches(t)) return Mapped.Bad("\"$t\" is not a time. Use 24-hour HH:MM, like 09:00.")
                p["time"] = t
                if (freq == "weekly") p["daysOfWeek"] = config["daysOfWeek"]?.trim().orEmpty().ifBlank { "1,2,3,4,5,6,7" }
                if (freq == "monthly") p["dayOfMonth"] = config["dayOfMonth"]?.trim()?.toIntOrNull() ?: 1
            }
            else -> return Mapped.Bad("Unknown schedule frequency \"$freq\".")
        }
        return Mapped.Ok(p)
    }

    private fun fromCron(cron: String, zone: String): Mapped {
        val f = cron.split(Regex("\\s+"))
        if (f.size != 5) return Mapped.Bad("\"$cron\" is not a 5-field schedule. Use a daily, weekly or hourly time instead.")
        val (min, hour, dom, mon, dow) = f
        val m = min.toIntOrNull()
        val h = hour.toIntOrNull()
        fun time() = "%02d:%02d".format(h, m)
        val base = linkedMapOf<String, Any?>("timezone" to zone)
        return when {
            mon != "*" -> Mapped.Bad("Month restrictions in \"$cron\" are not supported.")
            min.startsWith("*/") && hour == "*" && dom == "*" && dow == "*" -> {
                val n = min.removePrefix("*/").toIntOrNull()
                if (n == null || n < 1 || n > 59) Mapped.Bad("\"$cron\" has an interval that is not valid.") else Mapped.Ok(base + mapOf("frequency" to "minutes", "interval" to n))
            }
            m == 0 && hour == "*" && dom == "*" && dow == "*" -> Mapped.Ok(base + mapOf("frequency" to "hourly"))
            m != null && h != null && m in 0..59 && h in 0..23 && dom == "*" && dow == "*" -> Mapped.Ok(base + mapOf("frequency" to "daily", "time" to time()))
            m != null && h != null && m in 0..59 && h in 0..23 && dom == "*" && Regex("^[0-7](,[0-7])*$").matches(dow) -> {
                val days = dow.split(',').map { it.toInt().let { d -> if (d == 0) 7 else d } }.distinct().sorted()
                Mapped.Ok(base + mapOf("frequency" to "weekly", "time" to time(), "daysOfWeek" to days.joinToString(",")))
            }
            m != null && h != null && m in 0..59 && h in 0..23 && dow == "*" && dom.toIntOrNull() in 1..28 ->
                Mapped.Ok(base + mapOf("frequency" to "monthly", "time" to time(), "dayOfMonth" to dom.toInt()))
            else -> Mapped.Bad("\"$cron\" is more complex than the schedule supports. Use a daily, weekly, monthly or hourly time.")
        }
    }
}

/**
 * Publish compile: turns an agent into the Flow the Always-On engine runs (spec 02 section 11). Pure Kotlin.
 *
 * The Flow `agent:<id>` is tagged [AgentIds.TAG] (hidden from the Flow list), holds one Spark per enabled trigger, one `ai.agent`
 * Step and, when asked for, a notification Step with the answer. Nothing here arms anything: the result goes through the normal
 * ActivationCoordinator and PublishValidator, so permissions, webhook paths and Spark checks are the engine's own.
 *
 * It refuses (never drops silently) when: the model or credential is missing or refused, the instructions are empty, no trigger
 * can run on its own, a trigger or tool has no implementation, a limit is out of range, memory has no safe key.
 * See [AgentFlowCompiler] for how the Test Run differs.
 */
object AgentProductionCompiler {
    const val AGENT_STEP = "agent"
    const val DELIVER_STEP = "deliver"
    private const val DEFAULT_OBJECTIVE = "Carry out your instructions now."

    fun compile(agent: CustomAgent, credential: AgentCredentialInfo?, probe: CredentialProbe = CredentialProbe.NotChecked): ProductionCompile {
        val issues = ArrayList<AgentIssue>()
        fun block(code: String, msg: String, field: String? = null) { issues += AgentIssue(code, msg, AgentIssueSeverity.Blocking, field) }
        fun warn(code: String, msg: String, field: String? = null) { issues += AgentIssue(code, msg, AgentIssueSeverity.Warning, field) }

        if (!AgentJsonCodec.ID_OK.matches(agent.id)) block("agent_id", "This agent has an id that cannot be published.")

        // ---- model and credential ---------------------------------------------------------------------------------
        if (agent.model.isBlank()) block("model_missing", "Choose a model for this agent.", "model")
        if (agent.credentialId == null) block("credential_missing", "Pick an AI credential for this agent.", "credential")
        else if (credential == null) block("credential_not_found", "The credential this agent uses is no longer in the vault. Pick another one.", "credential")
        val kind = (credential?.provider ?: agent.provider).toKind()
        if (credential != null) {
            val base = ProviderEndpoints.normalizeBase(kind, credential.baseUrl)
            if (base.isNotBlank()) {
                val verdict = ProviderEndpoints.policyFor(kind).check(base)
                if (verdict is com.kaivoflow.core.ai.EndpointPolicy.Verdict.Blocked) block("endpoint_refused", "The model address is refused: ${verdict.reason}.", "credential")
            }
        }
        when (probe) {
            is CredentialProbe.Failed -> block("credential_failed", "The credential did not work: ${probe.message}", "credential")
            is CredentialProbe.Working -> if (!probe.modelListed) warn("model_not_listed", "The provider did not list the model \"${agent.model}\". Check the name.", "model")
            CredentialProbe.NotChecked -> Unit
        }

        // ---- text --------------------------------------------------------------------------------------------------
        if (agent.instructions.isBlank()) block("instructions_empty", "Write instructions so the agent knows what to do.", "instructions")

        // ---- limits ------------------------------------------------------------------------------------------------
        val s = agent.settings
        if (s.maxTotalTokens !in AgentSettings.TOKEN_BUDGET_RANGE) block("budget_tokens", "The token budget per run must be between ${AgentSettings.TOKEN_BUDGET_RANGE.first} and ${AgentSettings.TOKEN_BUDGET_RANGE.last}.", "settings")
        if (s.maxIterations !in 1..50) block("limit_iterations", "Max iterations must be between 1 and 50 for a published agent.", "settings")
        if (s.maxToolCalls !in 0..200) block("limit_tool_calls", "Max tool calls must be between 0 and 200.", "settings")
        if (s.timeoutSeconds !in 5..600) block("limit_timeout", "The time limit must be between 5 and 600 seconds.", "settings")
        if (s.rateLimitPerTool !in 1..100) block("limit_rate", "Calls per tool in one run must be between 1 and 100.", "settings")
        if (s.deliverResult != AgentSettings.DELIVER_NOTIFICATION && s.deliverResult != AgentSettings.DELIVER_NONE) block("deliver_unknown", "Unknown way to deliver the result.", "settings")

        // ---- tools -------------------------------------------------------------------------------------------------
        if (agent.webSearchEnabled) block("web_search_unavailable", "Web search is switched on, but it is not available yet. Switch it off to publish.", "tools")
        val used = HashSet<String>()
        val tools = ArrayList<Map<String, Any?>>()
        for (t in agent.tools.filter { it.isEnabled }) {
            val step = AgentFlowCompiler.STEP_FOR_TYPE[t.type]
            if (step == null) {
                block("tool_unavailable", "\"${t.name}\" (${t.type}) has no implementation yet, so the agent could not use it. Remove it to publish.", "tools")
                continue
            }
            val name = AgentFlowCompiler.toolName(t, step, used)
            if (!t.allowUnattended) warn("tool_not_unattended", "\"${t.name}\" is not allowed to run on its own, so the model will not be offered it in a published run. Allow it for unattended runs if it should work.", "tools")
            tools += mapOf(
                "step" to step,
                "name" to name,
                "description" to t.description.take(300).ifBlank { t.name },
                "params" to (AgentFlowCompiler.FIXED_PARAMS[step] ?: emptyMap()),
                "allowUnattended" to t.allowUnattended,
                "rateLimit" to s.rateLimitPerTool.coerceIn(1, 100),
            )
        }

        // ---- triggers ----------------------------------------------------------------------------------------------
        val sparks = ArrayList<StepNode>()
        val objectives = LinkedHashSet<String>()
        var hasWebhook = false
        var memoryKey: String? = null
        var memoryProblem: String? = null
        val enabledTriggers = agent.triggers.filter { it.isEnabled }
        enabledTriggers.forEachIndexed { index, t ->
            val id = "spark-" + t.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "t$index" }
            t.config["objective"]?.trim()?.takeIf { it.isNotEmpty() }?.let { objectives += it }
            when (t.type) {
                "manual" -> sparks += spark(id, SparkTypes.MANUAL, t.name, index, emptyMap())
                "schedule" -> when (val m = AgentTriggerConfig.schedule(t.config)) {
                    is AgentTriggerConfig.Mapped.Ok -> {
                        sparks += spark(id, SparkTypes.SCHEDULE, t.name, index, m.params)
                        if (agent.crossSessionMemoryEnabled && memoryKey == null) memoryKey = "agent"
                    }
                    is AgentTriggerConfig.Mapped.Bad -> block("schedule_invalid", "\"${t.name}\": ${m.message}", "triggers")
                }
                "webhook" -> {
                    hasWebhook = true
                    val path = t.config["path"]?.trim()?.ifBlank { null } ?: "agent/${agent.id.lowercase()}"
                    sparks += spark(id, SparkTypes.WEBHOOK, t.name, index, mapOf("path" to path, "method" to "POST", "responseMode" to "immediate"))
                    if (agent.crossSessionMemoryEnabled) {
                        val key = t.config["memoryKey"]?.trim().orEmpty()
                        if (key.isEmpty()) memoryProblem = "\"${t.name}\" has no memory key, so callers would share one history. Set a memory key such as {{ \$json.body.sessionId }}, or switch cross-session memory off."
                        else if (memoryKey == null) memoryKey = key
                        else if (memoryKey != key) memoryProblem = "Two triggers want different memory keys. Use one key, or switch cross-session memory off."
                    }
                }
                else -> block("trigger_unavailable", "\"${t.name}\" (${t.type}) is not available yet. Remove it to publish.", "triggers")
            }
        }
        if (sparks.none { it.type != SparkTypes.MANUAL }) {
            block("no_live_trigger", "Add a schedule or a webhook so the agent has something to run it on its own. A manual trigger only runs when you tap Run.", "triggers")
        }
        if (objectives.size > 1) block("objective_conflict", "The triggers give different tasks. A published agent has one task; use the same text, or one trigger.", "triggers")
        if (memoryProblem != null) block("memory_key", memoryProblem!!, "memory")
        if (agent.crossSessionMemoryEnabled && memoryProblem == null) {
            warn("memory_volatile", "Cross-session memory is kept in the app's memory only. It is lost when Android stops the app, and then the agent starts a fresh history.", "memory")
        }
        if (hasWebhook && tools.isNotEmpty()) {
            warn("webhook_steers_tools", "Anyone who can call the webhook can steer this agent. Only the tools you allowed run, and the payload is fenced as data, but keep the webhook token private.", "triggers")
        }

        if (issues.any { it.severity == AgentIssueSeverity.Blocking }) return ProductionCompile(null, issues)
        val cred = credential!!

        // ---- the agent Step ----------------------------------------------------------------------------------------
        val chatModel = buildMap<String, Any?> {
            put("provider", kind.key)
            put("model", agent.model)
            put("credential", cred.id)
            val base = ProviderEndpoints.normalizeBase(kind, cred.baseUrl)
            if (base.isNotBlank()) put("baseUrl", base)
            put("localAccess", if (kind.key == "ollama") "lan" else "none")
        }
        val objective = objectives.firstOrNull() ?: DEFAULT_OBJECTIVE
        // Step parameters use the engine's own top-level reading: a string with {{ }} is an expression, any other {{ is first
        // neutralised so author text can never be read as one (the same rule the Test Run applies).
        val prompt = neutral(objective) + "\n\nCurrent date and time: {{ \$now }}"
        val params = buildMap<String, Any?> {
            put("prompt", prompt)
            put("system", system(agent))
            if (hasWebhook) put("data", "{{ JSON.stringify(\$json) }}")
            put("chatModel", chatModel)
            if (tools.isNotEmpty()) put("tools", tools)
            if (memoryKey != null) put("memory", mapOf("type" to "window", "maxMessages" to (s.memoryDepth * 2).coerceIn(2, 200), "key" to memoryKey))
            put("maxIterations", s.maxIterations)
            put("maxToolCalls", s.maxToolCalls)
            put("maxTotalTokens", s.maxTotalTokens.toLong())
            put("timeoutSeconds", s.timeoutSeconds)
            put("outputField", "answer")
        }
        val name = agent.name.ifBlank { "Agent" }
        val steps = ArrayList<StepNode>(sparks)
        steps += StepNode(id = AGENT_STEP, type = "ai.agent", name = name, params = params.toImmutableMap(), x = 320f, y = 0f)
        val streams = ArrayList<StreamEdge>()
        sparks.forEach { streams += StreamEdge(id = "edge-${it.id}", fromStepId = it.id, toStepId = AGENT_STEP) }
        if (s.deliverResult == AgentSettings.DELIVER_NOTIFICATION) {
            steps += StepNode(
                id = DELIVER_STEP, type = "device.notify", name = "Deliver the answer",
                params = mapOf<String, Any?>(
                    "mode" to "notification",
                    "title" to neutral(name).take(60),
                    "body" to "{{ \$json.answer }}",
                    "channel" to "default",
                ).toImmutableMap(),
                x = 640f, y = 0f,
            )
            streams += StreamEdge(id = "edge-deliver", fromStepId = AGENT_STEP, toStepId = DELIVER_STEP)
        }
        val flow = Flow(
            id = AgentIds.flowId(agent.id),
            name = name,
            steps = steps.toImmutableList(),
            streams = streams.toImmutableList(),
            settings = FlowSettings(executionTimeoutMs = ((s.timeoutSeconds + 60L) * 1000L).coerceAtLeast(120_000L)),
            tags = persistentListOf(AgentIds.TAG),
        )
        return ProductionCompile(flow, issues)
    }

    private fun spark(id: String, type: String, name: String, index: Int, params: Map<String, Any?>) =
        StepNode(id = id, type = type, name = name.ifBlank { type }, params = params.toImmutableMap(), x = -320f, y = index * 120f)

    /**
     * Instructions and knowledge as one Step text. Parts the author wrote as expressions keep their `{{ }}`, so the engine
     * resolves them at every run; fixed parts are neutralised.
     */
    private fun system(agent: CustomAgent): String {
        fun part(stored: String): String = when (val v = ExprFieldCodec.decode(stored)) {
            is ParamValue.Expr -> v.text
            is ParamValue.Fixed -> neutral(v.value?.toString().orEmpty())
        }
        val text = buildString {
            append(part(agent.instructions))
            if (agent.knowledge.isNotEmpty()) {
                append("\n\nKnowledge you may use:\n")
                agent.knowledge.forEach { append("- ").append(neutral(it.title)).append(": ").append(part(it.content)).append('\n') }
            }
        }.trim()
        return text
    }

    private fun neutral(s: String): String = StepTools.neutralize(s) as String
}
