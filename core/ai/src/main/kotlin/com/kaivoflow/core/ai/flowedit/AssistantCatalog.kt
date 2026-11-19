package com.kaivoflow.core.ai.flowedit

import com.kaivoflow.core.engine.FlowValidator
import com.kaivoflow.core.engine.NodeRegistry
import com.kaivoflow.core.engine.ValidationIssue
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.Lifecycle
import com.kaivoflow.core.model.NodeKind
import com.kaivoflow.core.model.NodeRole
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType

/**
 * What the Ask AI assistant may put into a Flow, read from the real Step catalog so the prompt, the
 * tools and the validator can never disagree with what the app can run (audit 7.3).
 *
 * New Steps are limited to Stable and Beta main Steps. Legacy, Hidden and Removed Steps are not offered
 * and not accepted for new additions; a Step already in the open Flow stays untouched whatever its type.
 * [aliases] maps old ids to current ones (the host passes the catalog's rename table), so an old id the model
 * remembers is fixed instead of rejected.
 */
class AssistantCatalog(private val registry: NodeRegistry, private val aliases: Map<String, String> = emptyMap()) {
    private val specsById: Map<String, NodeSpec> = registry.specs.associateBy { it.id }

    /** Specs the assistant may add, in a stable order (path, then title). */
    val offered: List<NodeSpec> = specsById.values
        .filter { it.role == NodeRole.Main && it.lifecycle in setOf(Lifecycle.Stable, Lifecycle.Beta) }
        .sortedWith(compareBy({ it.categoryPath.ifEmpty { "~" } }, { it.title }))

    val offeredIds: Set<String> = offered.map { it.id }.toSet()

    fun spec(id: String): NodeSpec? = specsById[id]

    /** The same check the canvas and publishing use, so the assistant and the app never disagree about "valid". */
    fun validate(flow: Flow): List<ValidationIssue> = FlowValidator(registry).validate(flow).issues

    /** The current id for [typed]: itself when known, the alias target, else null. */
    fun canonicalId(typed: String): String? {
        val t = typed.trim()
        if (t in specsById) return t
        return aliases[t]?.takeIf { it in specsById }
    }

    fun isOffered(id: String): Boolean = canonicalId(id) in offeredIds

    /** Closest offered ids for an unknown one, for the error message. */
    fun suggest(typed: String, max: Int = 3): List<String> {
        val t = typed.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        val tail = t.substringAfterLast('.')
        val direct = offeredIds.filter { it.contains(t) || t.contains(it.substringAfterLast('.')) || it.endsWith(".$tail") }.sorted()
        if (direct.isNotEmpty()) return direct.take(max)
        // Same family (for example `code.script` when only `code.javascript` exists), but only when the family is small enough to be a real hint.
        val family = offeredIds.filter { it.startsWith(t.substringBefore('.') + ".") }.sorted()
        return if (family.size <= max) family else emptyList()
    }

    /** Keys the assistant may write for [id]: every non-secret param. */
    fun writableKeys(id: String): Set<String> = specsById[id]?.params?.filter { it.type != ParamType.Secret }?.map { it.key }?.toSet().orEmpty()

    fun secretKeys(id: String): Set<String> = specsById[id]?.params?.filter { it.type == ParamType.Secret }?.map { it.key }?.toSet().orEmpty()

    /**
     * The Step list for the system prompt, generated from the specs: one line per Step with its id, what it
     * does, and its main settings. Secrets and advanced settings are left out; the list is capped per Step.
     */
    fun stepList(maxParamsPerStep: Int = 8): String = buildString {
        val triggers = offered.filter { it.kind == NodeKind.Trigger }
        val actions = offered.filter { it.kind != NodeKind.Trigger }
        append("Triggers (a Flow starts at one of these):\n")
        triggers.forEach { appendLine(line(it, maxParamsPerStep)) }
        append("\nSteps:\n")
        actions.forEach { appendLine(line(it, maxParamsPerStep)) }
    }.trimEnd()

    private fun line(s: NodeSpec, max: Int): String {
        val params = s.params.filter { it.type != ParamType.Secret && !it.advanced }.take(max).joinToString(", ") { paramText(it) }
        val ports = if (s.outputs > 1) " Outputs: " + (0 until s.outputs).joinToString(", ") { i -> "$i=" + (s.outputLabels.getOrNull(i) ?: "out$i") } + "." else ""
        return "- `${s.id}`: ${s.title}. ${s.subtitle.trim().trimEnd('.')}.$ports" + if (params.isNotEmpty()) " Settings: $params." else ""
    }

    private fun paramText(p: ParamSpec): String {
        val opts = if (p.type == ParamType.Select && p.options.isNotEmpty()) "=" + p.options.joinToString("|") { it.value } else ""
        return p.key + opts + if (p.required) "*" else ""
    }
}
