package com.kaivoflow.feature.flows.ai.agent

import com.kaivoflow.core.engine.Resolved
import com.kaivoflow.core.engine.UniversalResolver
import com.kaivoflow.core.model.ExprFieldCodec
import com.kaivoflow.core.model.ParamValue

/**
 * Builds the text an agent sends to the model, resolving expressions in the agent's own
 * configuration (spec P3). Instructions and knowledge content are stored with [ExprFieldCodec], so
 * agents saved before expressions existed read as fixed text. Resolution happens on every call, in
 * the scope given (`$vars`, `$now`, flow info), and never throws: a bad expression is a
 * [Built.Failed] naming the field, which the caller shows instead of calling the model.
 */
object AgentPrompt {
    sealed interface Built {
        data class Ok(val text: String) : Built
        data class Failed(val message: String) : Built
    }

    private class Parts(val instructions: String, val knowledge: List<Pair<String, String>>)

    private fun parts(agent: CustomAgent, resolver: UniversalResolver, scope: Map<String, Any?>): Pair<Parts?, String?> {
        val problems = ArrayList<String>()
        fun text(stored: String, label: String): String =
            when (val r = resolver.resolveText(ExprFieldCodec.decode(stored), scope, label)) {
                is Resolved.Ok -> r.value as? String ?: ""
                is Resolved.Failed -> { problems += r.message; "" }
            }
        val instructions = text(agent.instructions, "Instructions")
        val knowledge = agent.knowledge.map { it.title to text(it.content, "Knowledge \"${it.title}\"") }
        return if (problems.isNotEmpty()) null to problems.joinToString("\n") else Parts(instructions, knowledge) to null
    }

    /**
     * The system text for the agent loop: instructions and knowledge, resolved on this call. No tool list is pasted
     * here (tools travel natively and only the ones that exist are offered) and no user prompt is mixed in.
     */
    fun buildSystem(agent: CustomAgent, resolver: UniversalResolver, scope: Map<String, Any?>): Built {
        val (p, err) = parts(agent, resolver, scope)
        if (p == null) return Built.Failed(err.orEmpty())
        return Built.Ok(buildString {
            append(p.instructions)
            if (p.knowledge.isNotEmpty()) {
                append("\n\nKnowledge you may use:\n")
                p.knowledge.forEach { (title, content) -> append("- $title: $content\n") }
            }
        }.trim())
    }

    fun build(agent: CustomAgent, userPrompt: String, resolver: UniversalResolver, scope: Map<String, Any?>): Built {
        val (p, err) = parts(agent, resolver, scope)
        if (p == null) return Built.Failed(err.orEmpty())
        val tools = agent.tools.filter { it.isEnabled }
        return Built.Ok(buildString {
            append("AGENT INSTRUCTIONS:\n")
            append(p.instructions)
            if (p.knowledge.isNotEmpty()) {
                append("\n\nKNOWLEDGE BASE CONTEXT:\n")
                p.knowledge.forEach { (title, content) -> append("- $title: $content\n") }
            }
            if (agent.tools.isNotEmpty()) {
                append("\nAVAILABLE TOOLS:\n")
                tools.forEach { append("- ${it.name} (${it.type}): ${it.description}\n") }
            }
            append("\n\nUSER PROMPT / TASK:\n")
            append(userPrompt)
        })
    }
}
