package com.kaivoflow.core.ai.flowedit

import com.kaivoflow.core.model.Flow

/** Re-checks a Flow against the catalog. Used again at Apply time, because a stored proposal may be old or edited. */
object FlowProposalValidator {
    /** Reasons the Flow must not be saved. [base] is the Flow it was derived from: Steps it already holds are left as they are. */
    fun validate(flow: Flow, base: Flow?, catalog: AssistantCatalog): List<String> {
        val errors = ArrayList<String>()
        if (flow.steps.isEmpty()) errors += "The Flow has no Steps."
        if (flow.steps.size > ProposalLimits.MAX_STEPS) errors += "More than ${ProposalLimits.MAX_STEPS} Steps."
        if (flow.streams.size > ProposalLimits.MAX_STREAMS) errors += "More than ${ProposalLimits.MAX_STREAMS} Streams."
        val ids = flow.steps.map { it.id }
        if (ids.size != ids.toSet().size) errors += "Two Steps share an id."
        val baseTypes = base?.steps?.associate { it.id to it.type }.orEmpty()
        flow.steps.forEach { s ->
            val unchanged = baseTypes[s.id] == s.type
            if (!unchanged && !catalog.isOffered(s.type) || (!unchanged && catalog.canonicalId(s.type) != s.type)) errors += "\"${s.name}\" uses \"${s.type}\", which cannot be added here."
            s.params.forEach { (k, v) -> if (com.kaivoflow.core.model.ParamJson.write(v).length > ProposalLimits.MAX_PARAM_BYTES) errors += "\"${s.name}\": setting $k is too large." }
        }
        val idSet = ids.toSet()
        flow.streams.forEach { e ->
            if (e.fromStepId !in idSet || e.toStepId !in idSet) errors += "A Stream points at a Step that is not in the Flow."
            else {
                val outputs = flow.step(e.fromStepId)?.let { catalog.spec(it.type)?.outputs } ?: 1
                if (e.fromPort !in 0 until outputs) errors += "A Stream leaves an output that does not exist."
            }
        }
        return errors.distinct()
    }
}

sealed interface ApplyPlan {
    /** [save] is what to write; [undoTo] is the exact Flow to write back to undo (null for a brand new Flow). */
    data class Ready(val save: Flow, val undoTo: Flow?, val warnings: List<String>) : ApplyPlan
    data class Conflict(val message: String) : ApplyPlan
    data class Invalid(val errors: List<String>) : ApplyPlan
}

/**
 * What happens when the person taps Apply. Nothing is written before this; the host writes [ApplyPlan.Ready.save]
 * and keeps [ApplyPlan.Ready.undoTo] for Undo. A Flow edited elsewhere since the proposal was made is a conflict,
 * so an old proposal (also one restored from a saved chat) never silently replaces newer work.
 */
object ProposalApply {
    /**
     * [baseUpdatedAt] is the `updatedAt` of the Flow the proposal started from, or null for a brand new Flow.
     * [current] is what the repository holds right now.
     */
    fun plan(proposal: Flow, baseUpdatedAt: Long?, current: Flow?, catalog: AssistantCatalog, now: Long): ApplyPlan {
        val errors = FlowProposalValidator.validate(proposal, current, catalog)
        if (errors.isNotEmpty()) return ApplyPlan.Invalid(errors)
        if (baseUpdatedAt == null) {
            if (current != null) return ApplyPlan.Conflict("A Flow with this id already exists. Ask the assistant again.")
            return ApplyPlan.Ready(proposal.copy(updatedAt = now), undoTo = null, warnings = emptyList())
        }
        if (current == null) return ApplyPlan.Conflict("The Flow was deleted since this proposal was made.")
        if (current.id != proposal.id || current.updatedAt != baseUpdatedAt) {
            return ApplyPlan.Conflict("The Flow was changed after this proposal was made. Open it again and ask for the change again, so nothing you did is lost.")
        }
        // Everything outside Steps, Streams and name stays as it is now (settings, variables, groups, notes, armed state).
        val merged = current.copy(name = proposal.name, steps = proposal.steps, streams = proposal.streams, updatedAt = now)
        val warnings = if (current.isActive) listOf("This Flow is armed. The change goes live as soon as you apply it.") else emptyList()
        return ApplyPlan.Ready(merged, undoTo = current, warnings = warnings)
    }
}
