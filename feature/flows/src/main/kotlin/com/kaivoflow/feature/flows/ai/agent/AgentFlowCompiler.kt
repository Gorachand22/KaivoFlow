package com.kaivoflow.feature.flows.ai.agent

import com.kaivoflow.core.ai.ProviderEndpoints
import com.kaivoflow.core.ai.StepTools
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.feature.flows.ai.AiProvider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap

/**
 * Turns an Agent Builder agent into a one-Step Flow (`ai.agent`) so a Test Run goes through the real engine path
 * and follows the Test Step rule (audit 8.3): exactly that Step, its own input, test mode, never a production Run.
 * The Flow lives only in memory; it is never saved and it has no Spark, so nothing can be armed from it.
 *
 * Differences from the production compile ([AgentProductionCompiler]), on purpose and all listed here:
 *  1. Tools: a test marks every tool `allowUnattended = false`, so each one asks the person first (approve/deny card). Production
 *     uses the author's own `allowUnattended` flag and nobody is asked.
 *  2. A tool type with no implementation is left out with a note. Production refuses to publish instead.
 *  3. Instructions, knowledge and credential fields are resolved here, in the builder, against the workspace variables, then
 *     neutralised. Production hands the expressions to the engine, which resolves them at every run.
 *  4. The input is the person's test message. Production runs from a trigger item and builds the task from the trigger's objective
 *     plus the current date, time and zone; webhook data goes in as fenced untrusted data.
 *  5. No delivery Step (the answer is shown on the sheet). Production may add a notification Step.
 *  6. Memory, when on, uses the fixed key "test" in this Flow's own namespace. Production keys memory by trigger rules.
 *  7. It runs as a Test Step (never a production Run, nothing saved, nothing armed); no Spark, no hidden Flow.
 * The model, limits (steps, tool calls, tokens, time) and output field are the same in both.
 *
 * Instructions and knowledge are resolved before this point (spec P3, `AgentPrompt.buildSystem`) and then neutralised,
 * so the Step's own parameter resolver cannot read a `{{` in them as a second expression.
 */
object AgentFlowCompiler {
    const val STEP_ID = "agent-test-step"

    data class Compiled(
        val flow: Flow,
        val stepId: String,
        /** The input item for the test: the person's test message, nothing else. */
        val input: List<FlowData>,
        /** Tools the agent lists that have no implementation yet, as readable lines for the result note. */
        val unavailableTools: List<String>,
    )

    /** Agent tool types that map to a real Step. Everything else has no runtime yet and is not offered to the model. */
    internal val STEP_FOR_TYPE = mapOf("device" to "device.notify")
    internal val FIXED_PARAMS = mapOf("device.notify" to mapOf<String, Any?>("mode" to "notification"))

    /** Provider-safe, unique tool name; shared with the production compile so a tool has the same name in both. */
    internal fun toolName(t: AgentTool, step: String, used: MutableSet<String>): String {
        var name = t.name.lowercase().replace(Regex("[^a-z0-9_-]+"), "_").trim('_').ifBlank { step.replace('.', '_') }.take(48)
        while (!used.add(name)) name = "${name.take(44)}_${used.size}"
        return name
    }

    fun compile(
        agent: CustomAgent,
        systemText: String,
        task: String,
        provider: AiProvider,
        model: String,
        credentialId: String?,
        baseUrl: String,
    ): Compiled {
        val unavailable = ArrayList<String>()
        val used = HashSet<String>()
        val tools = ArrayList<Map<String, Any?>>()
        agent.tools.filter { it.isEnabled }.forEach { t ->
            val step = STEP_FOR_TYPE[t.type]
            if (step == null) {
                unavailable += "${t.name} (${t.type}) has no implementation yet"
                return@forEach
            }
            val name = toolName(t, step, used)
            tools += mapOf(
                "step" to step,
                "name" to name,
                "description" to t.description.take(300).ifBlank { t.name },
                "params" to (FIXED_PARAMS[step] ?: emptyMap()),
                // A test run asks a person before any tool acts; nothing here may run on its own.
                "allowUnattended" to false,
            )
        }
        val kind = provider.toKind()
        val chatModel = buildMap<String, Any?> {
            put("provider", kind.key)
            put("model", model)
            credentialId?.let { put("credential", it) }
            val base = ProviderEndpoints.normalizeBase(kind, baseUrl)
            if (base.isNotBlank()) put("baseUrl", base)
            // Only the explicit local provider may reach this phone or the home network.
            put("localAccess", if (kind.key == "ollama") "lan" else "none")
        }
        val params = buildMap<String, Any?> {
            put("prompt", StepTools.neutralize(task))
            put("system", StepTools.neutralize(systemText))
            put("chatModel", chatModel)
            if (tools.isNotEmpty()) put("tools", tools)
            put("maxIterations", agent.settings.maxIterations.coerceIn(1, 50))
            put("maxToolCalls", agent.settings.maxToolCalls.coerceIn(0, 200))
            put("maxTotalTokens", agent.settings.maxTotalTokens.toLong().coerceAtLeast(1_000))
            if (agent.crossSessionMemoryEnabled) put("memory", mapOf("type" to "window", "maxMessages" to (agent.settings.memoryDepth * 2).coerceIn(2, 200), "key" to "test"))
            put("timeoutSeconds", agent.settings.timeoutSeconds.coerceIn(5, 600))
            put("outputField", "answer")
        }
        val step = StepNode(id = STEP_ID, type = "ai.agent", name = agent.name.ifBlank { "Agent" }, params = params.toImmutableMap(), x = 0f, y = 0f)
        val flow = Flow(id = "agent-test:${agent.id}", name = agent.name.ifBlank { "Agent" }, steps = persistentListOf(step))
        return Compiled(flow, STEP_ID, listOf(FlowData(json = mapOf("text" to task))), unavailable)
    }
}
