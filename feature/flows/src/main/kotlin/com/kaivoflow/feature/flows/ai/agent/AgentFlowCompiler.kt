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
    private val STEP_FOR_TYPE = mapOf("device" to "device.notify")
    private val FIXED_PARAMS = mapOf("device.notify" to mapOf<String, Any?>("mode" to "notification"))

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
            var name = t.name.lowercase().replace(Regex("[^a-z0-9_-]+"), "_").trim('_').ifBlank { step.replace('.', '_') }.take(48)
            while (!used.add(name)) name = "${name.take(44)}_${used.size}"
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
            put("timeoutSeconds", agent.settings.timeoutSeconds.coerceIn(5, 600))
            put("outputField", "answer")
        }
        val step = StepNode(id = STEP_ID, type = "ai.agent", name = agent.name.ifBlank { "Agent" }, params = params.toImmutableMap(), x = 0f, y = 0f)
        val flow = Flow(id = "agent-test:${agent.id}", name = agent.name.ifBlank { "Agent" }, steps = persistentListOf(step))
        return Compiled(flow, STEP_ID, listOf(FlowData(json = mapOf("text" to task))), unavailable)
    }
}
