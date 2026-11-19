package com.kaivoflow.core.ai.flowedit

import com.kaivoflow.core.ai.AgentTool
import com.kaivoflow.core.ai.Redactor
import com.kaivoflow.core.ai.ToolOutcome
import com.kaivoflow.core.ai.ToolRisk
import com.kaivoflow.core.model.Flow

/**
 * The tools the Ask AI assistant gets: read the open Flow, list the Steps that exist, and edit a [FlowDraft].
 * Risk classes follow audit 8.4: reading is Read, every edit is WriteDraft (interactive hosts only, nothing is
 * persisted). There is no tool that saves, deletes a Flow or touches credentials.
 */
class FlowEditTools(
    private val draft: FlowDraft,
    private val catalog: AssistantCatalog,
    private val redactor: Redactor = Redactor(),
    /** Optional extra check of AI-written settings (for example an expression syntax check); returns problems. */
    private val paramCheck: (stepName: String, params: Map<String, Any?>) -> List<String> = { _, _ -> emptyList() },
) {

    private fun obj(props: Map<String, Any?>, required: List<String> = emptyList()) =
        mapOf("type" to "object", "properties" to props, "required" to required, "additionalProperties" to false)
    private val str = mapOf("type" to "string")

    val all: List<AgentTool> = listOf(overview(), listSteps(), checkFlow(), addStep(), connect(), disconnect(), removeStep(), renameStep(), updateParams(), renameFlow())

    private fun args(a: Map<String, Any?>, key: String): String = a[key]?.toString().orEmpty()

    @Suppress("UNCHECKED_CAST")
    private fun mapArg(a: Map<String, Any?>, key: String): Map<String, Any?> = (a[key] as? Map<String, Any?>).orEmpty()

    private inner class T(
        override val name: String,
        override val description: String,
        override val parameters: Map<String, Any?>,
        override val risk: ToolRisk,
        val body: (Map<String, Any?>) -> ToolOutcome,
    ) : AgentTool {
        override suspend fun execute(arguments: Map<String, Any?>): ToolOutcome = body(arguments)
    }

    private fun edit(name: String, description: String, schema: Map<String, Any?>, run: (Map<String, Any?>) -> String) =
        T(name, description, schema, ToolRisk.WriteDraft) { a ->
            val before = draft.editCount()
            val text = run(a)
            ToolOutcome(redactor.redact(text), isError = draft.editCount() > before && draft.lastFailed())
        }

    private fun overview() = T(
        "get_flow_overview", "Shows the open Flow: its Steps with ids, types, settings and connections.", obj(emptyMap()), ToolRisk.Read,
    ) { ToolOutcome(redactor.redact(FlowSummary.of(draft.flow))) }

    private fun listSteps() = T(
        "list_steps", "Lists every Step type you may add, with its settings. Use only these ids.", obj(emptyMap()), ToolRisk.Read,
    ) { ToolOutcome(catalog.stepList()) }

    private fun checkFlow() = T(
        "check_flow", "Runs the app's own checks on the draft Flow and lists what is missing or broken.", obj(emptyMap()), ToolRisk.Read,
    ) {
        val issues = catalog.validate(draft.flow)
        ToolOutcome(if (issues.isEmpty()) "No problems found." else issues.joinToString("\n") { "- ${it.message}" + (it.stepId?.let { id -> " (step $id)" } ?: "") })
    }

    private fun addStep() = edit(
        "add_step", "Adds a Step to the draft. type must be an id from list_steps. Pass after (a Step id or name) to connect it.",
        obj(mapOf("type" to str, "name" to str, "params" to mapOf("type" to "object"), "after" to str), listOf("type")),
    ) { a ->
        val params = mapArg(a, "params")
        val name = args(a, "name").ifBlank { null }
        val text = draft.add(catalog, args(a, "type"), name, params, args(a, "after").ifBlank { null })
        val problems = paramCheck(name ?: args(a, "type"), params)
        if (problems.isEmpty()) text else "$text. Problem: ${problems.joinToString("; ")}"
    }

    private fun connect() = edit(
        "connect_steps", "Connects the output of one Step to the input of another. fromPort is 0 unless the Step branches.",
        obj(mapOf("from" to str, "to" to str, "fromPort" to mapOf("type" to "integer")), listOf("from", "to")),
    ) { a -> draft.connect(args(a, "from"), args(a, "to"), (a["fromPort"] as? Number)?.toInt() ?: 0, catalog) }

    private fun disconnect() = edit(
        "disconnect_steps", "Removes the connection between two Steps.", obj(mapOf("from" to str, "to" to str), listOf("from", "to")),
    ) { a -> draft.disconnect(args(a, "from"), args(a, "to")) }

    private fun removeStep() = edit(
        "remove_step", "Removes a Step and its connections from the draft. The person still has to apply the draft.", obj(mapOf("step" to str), listOf("step")),
    ) { a -> draft.remove(args(a, "step")) }

    private fun renameStep() = edit(
        "rename_step", "Renames a Step.", obj(mapOf("step" to str, "name" to str), listOf("step", "name")),
    ) { a -> draft.rename(args(a, "step"), args(a, "name")) }

    private fun updateParams() = edit(
        "update_step_params", "Changes settings of a Step. Only settings listed by list_steps exist; credentials are chosen by the person.",
        obj(mapOf("step" to str, "params" to mapOf("type" to "object")), listOf("step", "params")),
    ) { a ->
        val step = draft.find(args(a, "step"))
        val params = mapArg(a, "params")
        val text = draft.update(args(a, "step"), params, catalog)
        val problems = if (step == null) emptyList() else paramCheck(step.name, params)
        if (problems.isEmpty()) text else "$text. Problem: ${problems.joinToString("; ")}"
    }

    private fun renameFlow() = edit(
        "rename_flow", "Renames the Flow in the draft.", obj(mapOf("name" to str), listOf("name")),
    ) { a -> draft.setName(args(a, "name")) }
}

/** A compact, bounded text view of a Flow for the model. Values are cut short; callers redact before use. */
object FlowSummary {
    fun of(flow: Flow, maxValueChars: Int = 80): String = buildString {
        appendLine("Flow \"${flow.name}\" (${flow.steps.size} Steps, ${flow.streams.size} Streams, ${if (flow.isActive) "armed" else "not armed"})")
        flow.steps.forEach { s ->
            val ins = flow.streams.filter { it.toStepId == s.id }.joinToString(",") { it.fromStepId }
            val outs = flow.streams.filter { it.fromStepId == s.id }.joinToString(",") { it.toStepId + if (it.fromPort != 0) "@${it.fromPort}" else "" }
            val p = s.params.entries.take(12).joinToString(", ") { (k, v) -> "$k=" + (v?.toString().orEmpty().replace('\n', ' ').take(maxValueChars)) }
            appendLine("- id=${s.id} type=${s.type} name=\"${s.name}\" in=[$ins] out=[$outs] settings{$p}")
        }
    }.trimEnd()
}
