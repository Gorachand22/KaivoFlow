package com.kaivoflow.core.ai.flowedit

import com.kaivoflow.core.ai.J
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.ParamJson
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

/** Hard caps on what a model reply may create (audit S7). */
object ProposalLimits {
    const val MAX_STEPS = 100
    const val MAX_STREAMS = 300
    const val MAX_PARAM_BYTES = 20 * 1024
    const val MAX_NAME_CHARS = 120
}

data class ProposalCheck(
    /** The Flow to show and, after Apply, save. Unknown and secret settings are already removed. */
    val flow: Flow?,
    /** Reasons the proposal cannot be used. Non-empty means [flow] is null. */
    val errors: List<String>,
    /** Things that were changed or look wrong but do not block. */
    val warnings: List<String>,
) {
    val isUsable: Boolean get() = flow != null && errors.isEmpty()
}

/**
 * Reads the fenced Flow block of a model answer and checks it against the real catalog. Pure: nothing is saved,
 * nothing runs. The proposal is built on top of [base] (the open Flow), so its settings, variables, groups,
 * notes and favourite flag survive; only the name, Steps and Streams come from the model.
 */
object FlowProposalParser {
    private val kaivoFence = Regex("```kaivoflow\\s*([\\s\\S]*?)```")
    private val jsonFence = Regex("```json\\s*([\\s\\S]*?)```")

    /** The answer text without the fenced block, for showing in the chat. */
    fun visibleText(answer: String): String = answer.replace(kaivoFence, "").trim()

    /** Null when the answer has no Flow block at all (a normal chat reply). */
    fun parse(answer: String, base: Flow?, catalog: AssistantCatalog, newId: () -> String, now: Long): ProposalCheck? {
        val block = kaivoFence.find(answer)?.groupValues?.get(1)
            ?: jsonFence.findAll(answer).map { it.groupValues[1] }.firstOrNull { looksLikeFlow(it) }
            ?: return null
        val root = J.obj(ParamJson.parse(block.trim()).let { (it as? ParamJson.Parsed.Ok)?.value })
            ?: return ProposalCheck(null, listOf("The Flow in the answer is not valid JSON."), emptyList())
        return fromMap(root, base, catalog, newId, now)
    }

    private fun looksLikeFlow(text: String): Boolean {
        val root = J.obj((ParamJson.parse(text.trim()) as? ParamJson.Parsed.Ok)?.value) ?: return false
        return J.list(root["steps"]).any { J.obj(it)?.get("type") != null }
    }

    fun fromMap(root: Map<String, Any?>, base: Flow?, catalog: AssistantCatalog, newId: () -> String, now: Long): ProposalCheck {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val rawSteps = J.list(root["steps"])
        val rawStreams = J.list(root["streams"])
        if (rawSteps.isEmpty()) return ProposalCheck(null, listOf("The proposal has no Steps."), emptyList())
        if (rawSteps.size > ProposalLimits.MAX_STEPS) errors += "The proposal has ${rawSteps.size} Steps; the limit is ${ProposalLimits.MAX_STEPS}."
        if (rawStreams.size > ProposalLimits.MAX_STREAMS) errors += "The proposal has ${rawStreams.size} Streams; the limit is ${ProposalLimits.MAX_STREAMS}."
        if (errors.isNotEmpty()) return ProposalCheck(null, errors, emptyList())

        val existing = base?.steps?.associateBy { it.id }.orEmpty()
        val steps = ArrayList<StepNode>()
        val seen = HashSet<String>()
        val rejected = HashSet<String>()
        var y = 100f
        rawSteps.forEachIndexed { i, raw ->
            val m = J.obj(raw)
            if (m == null) { errors += "Step ${i + 1} is not an object."; return@forEachIndexed }
            val typed = J.str(m["type"]).orEmpty()
            val id = J.str(m["id"])?.takeIf { it.isNotBlank() } ?: "step_${newId().take(6)}"
            if (!seen.add(id)) { errors += "Two Steps share the id \"$id\"."; return@forEachIndexed }
            val before = existing[id]
            val canonical = catalog.canonicalId(typed)
            val unchangedType = before != null && before.type == typed
            val type: String = when {
                unchangedType -> typed
                canonical != null && catalog.isOffered(canonical) -> canonical
                else -> {
                    val hint = catalog.suggest(typed).let { if (it.isEmpty()) "" else " Did you mean ${it.joinToString { s -> "`$s`" }}?" }
                    rejected += id
                    errors += "Step ${i + 1} (\"${J.str(m["name"]).orEmpty().take(40)}\") uses \"${typed.take(60)}\", which does not exist or cannot be added here.$hint"
                    return@forEachIndexed
                }
            }
            if (canonical != null && canonical != typed && !unchangedType) warnings += "Step type \"$typed\" was replaced by `$canonical`."
            val name = (J.str(m["name"])?.takeIf { it.isNotBlank() } ?: catalog.spec(type)?.title ?: type).take(ProposalLimits.MAX_NAME_CHARS)
            val params = LinkedHashMap<String, Any?>()
            val writable = catalog.writableKeys(type)
            val secrets = catalog.secretKeys(type)
            val spec = catalog.spec(type)
            for ((k, v) in J.obj(m["params"]).orEmpty()) {
                when {
                    k in secrets -> warnings += "\"$name\": the assistant cannot set $k; pick it yourself in the Step."
                    spec != null && k !in writable -> warnings += "\"$name\": unknown setting $k was ignored."
                    ParamJson.write(v).length > ProposalLimits.MAX_PARAM_BYTES -> errors += "\"$name\": setting $k is larger than ${ProposalLimits.MAX_PARAM_BYTES / 1024} KB."
                    else -> params[k] = v
                }
            }
            // A Step that already exists keeps every setting the model did not change, including secrets and credentials.
            if (before != null && before.type == type) before.params.forEach { (k, v) -> if (k !in params) params[k] = v }
            val x = (m["x"] as? Number)?.toFloat() ?: before?.x ?: 100f
            val yy = (m["y"] as? Number)?.toFloat() ?: before?.y ?: y
            y += 160f
            val node = before?.takeIf { it.type == type }?.copy(name = name, params = params.toImmutableMap(), x = x, y = yy)
                ?: StepNode(id = id, type = type, name = name, params = params.toImmutableMap(), x = x, y = yy)
            steps += node
        }
        val ids = steps.map { it.id }.toSet()
        val streams = ArrayList<StreamEdge>()
        rawStreams.forEachIndexed { i, raw ->
            val m = J.obj(raw) ?: run { errors += "Stream ${i + 1} is not an object."; return@forEachIndexed }
            val from = J.str(m["fromStepId"]).orEmpty()
            val to = J.str(m["toStepId"]).orEmpty()
            if (from in rejected || to in rejected) return@forEachIndexed
            if (from !in ids || to !in ids) { errors += "Stream ${i + 1} connects \"$from\" to \"$to\", but one of them is not a Step in the proposal."; return@forEachIndexed }
            if (from == to) { errors += "Stream ${i + 1} connects a Step to itself."; return@forEachIndexed }
            val fromPort = (m["fromPort"] as? Number)?.toInt() ?: 0
            val toPort = (m["toPort"] as? Number)?.toInt() ?: 0
            val outputs = catalog.spec(steps.first { it.id == from }.type)?.outputs ?: 1
            if (fromPort !in 0 until outputs) { errors += "Stream ${i + 1} leaves port $fromPort of a Step that has $outputs output(s)."; return@forEachIndexed }
            val dup = streams.any { it.fromStepId == from && it.fromPort == fromPort && it.toStepId == to }
            if (dup) return@forEachIndexed
            streams += StreamEdge(id = J.str(m["id"])?.takeIf { it.isNotBlank() } ?: "stream_${newId().take(6)}", fromStepId = from, toStepId = to, fromPort = fromPort, toPort = toPort)
        }
        if (steps.none { it.isTrigger }) warnings += "The Flow has no Trigger, so it can only be started by hand."
        steps.filter { !it.isTrigger && streams.none { s -> s.toStepId == it.id } && steps.size > 1 }.forEach { warnings += "\"${it.name}\" has no input connection." }
        if (errors.isNotEmpty()) return ProposalCheck(null, errors, warnings)
        val name = (J.str(root["name"])?.takeIf { it.isNotBlank() } ?: base?.name ?: "Proposed Flow").take(ProposalLimits.MAX_NAME_CHARS)
        val flow = (base ?: Flow(id = newId(), name = name, createdAt = now)).copy(
            name = name, steps = steps.toImmutableList(), streams = streams.toImmutableList(), updatedAt = now,
        )
        return ProposalCheck(flow, emptyList(), warnings)
    }
}
