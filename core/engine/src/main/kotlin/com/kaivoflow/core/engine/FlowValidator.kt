package com.kaivoflow.core.engine

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode

/**
 * Pre-flight checks. A Flow either passes cleanly or the user gets a precise, tap-to-fix list —
 * never a mysterious failure halfway through a Run.
 */
class FlowValidator(private val registry: NodeRegistry) {

    fun validate(flow: Flow): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()

        if (flow.steps.isEmpty()) {
            issues += validationError("empty_flow", "Add a Spark to start this Flow.")
            return ValidationReport(issues)
        }

        // Duplicate ids would silently corrupt routing.
        flow.steps.groupBy { it.id }.filter { it.value.size > 1 }.forEach { (id, _) ->
            issues += validationError("duplicate_step_id", "Two Steps share an internal id ($id). Duplicate the Flow to fix.", id)
        }

        val known = flow.steps.map { it.id }.toSet()

        // Streams pointing at Steps that no longer exist.
        flow.streams.forEach { stream ->
            if (stream.fromStepId !in known) {
                issues += validationError("dangling_stream", "A Stream starts from a Step that was deleted.", stream.fromStepId)
            }
            if (stream.toStepId !in known) {
                issues += validationError("dangling_stream", "A Stream ends at a Step that was deleted.", stream.toStepId)
            }
            if (stream.fromStepId == stream.toStepId) {
                issues += validationError("self_stream", "A Stream cannot connect a Step to itself.", stream.toStepId)
            }
        }

        // Trigger rules.
        val enabledSteps = flow.steps.filterNot { it.disabled }
        val triggers = enabledSteps.filter { it.isTrigger }
        if (triggers.isEmpty()) {
            issues += validationError(
                "no_trigger",
                "Every Flow needs one Spark (the thing that starts it).",
                fixHint = "Tap the + button at the top of the canvas.",
            )
        }
        if (triggers.size > 1) {
            triggers.drop(1).forEach {
                issues += validationWarning(
                    "multiple_triggers",
                    "\"${it.name}\" is another Spark. Events should identify the Spark that received them; a manual Run prefers a manual Spark, otherwise the first enabled Spark.",
                    it.id,
                )
            }
        }

        // Per-Step validation from the catalog.
        flow.steps.forEach { step -> issues += registry.validate(step) }

        // Reachability: a Step nobody can reach never runs, which is always a mistake.
        val roots = triggers.map { it.id }.toSet()
        val reachable = reachableFrom(roots, flow)
        flow.steps.filterNot { it.disabled }.forEach { step ->
            if (step.id == flow.settings.errorStepId) return@forEach // error Steps are reachable by definition
            val hasIncoming = flow.streams.any { it.toStepId == step.id }
            if (!step.isTrigger && !hasIncoming) {
                issues += validationWarning(
                    "unconnected_step",
                    "\"${step.name}\" is not connected yet.",
                    step.id,
                    fixHint = "Drag from a previous Step to connect a Stream.",
                )
            } else if (!step.isTrigger && step.id !in reachable) {
                issues += validationWarning(
                    "unreachable_step",
                    "\"${step.name}\" cannot be reached from the Spark.",
                    step.id,
                )
            }
        }

        // Cycles: a Flow graph must be acyclic (loops are the Loop Step's job).
        detectCycle(flow)?.let { cycle ->
            val names = cycle.mapNotNull { id -> flow.step(id)?.name }.joinToString(" → ")
            issues += validationError(
                "cycle",
                "These Steps form a loop: $names. Use the Loop Step to repeat work.",
                cycle.firstOrNull(),
            )
        }

        // Error-flow reference must exist and not be the trigger itself.
        flow.settings.errorStepId?.let { errorStepId ->
            if (errorStepId !in known) {
                issues += validationError("bad_error_step", "The error-handling Step was deleted. Pick another one in Flow settings.")
            }
            if (errorStepId in roots) {
                issues += validationError("bad_error_step", "The error-handling Step cannot be the Spark.")
            }
        }

        // Every Stream must leave from a port that exists on its source Step.
        flow.steps.forEach { step ->
            val outputs = registry.spec(step.type)?.outputs ?: 1
            val outOfRange = flow.streams.filter { it.fromStepId == step.id && (it.fromPort < 0 || it.fromPort >= outputs) }
            if (outOfRange.isNotEmpty()) {
                issues += validationError(
                    "bad_port",
                    "\"${step.name}\" has a Stream on a branch that does not exist.",
                    step.id,
                    fixHint = "Delete that Stream and reconnect it to a real branch.",
                )
            }
            val duplicates = flow.streams.filter { it.fromStepId == step.id }
                .groupBy { it.fromPort to it.toStepId }
                .filterValues { it.size > 1 }
            if (duplicates.isNotEmpty()) {
                issues += validationWarning("duplicate_branch", "\"${step.name}\" has two identical Streams to the same Step.", step.id)
            }
        }

        return ValidationReport(issues)
    }

    private fun reachableFrom(roots: Set<String>, flow: Flow): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            flow.streams.filter { it.fromStepId == id }.forEach { queue += it.toStepId }
        }
        return visited
    }

    /** Returns one cycle as a list of Step ids, or null when the graph is acyclic. */
    fun detectCycle(flow: Flow): List<String>? {
        val WHITE = 0; val GREY = 1; val BLACK = 2
        val color = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>()

        fun visit(id: String): List<String>? {
            color[id] = GREY
            stack += id
            for (stream in flow.streams.filter { it.fromStepId == id }) {
                when (color[stream.toStepId] ?: WHITE) {
                    WHITE -> visit(stream.toStepId)?.let { return it }
                    GREY -> {
                        val start = stack.indexOf(stream.toStepId)
                        return if (start >= 0) stack.subList(start, stack.size).toList() + stream.toStepId else listOf(id, stream.toStepId)
                    }
                    else -> Unit
                }
            }
            stack.removeAt(stack.size - 1)
            color[id] = BLACK
            return null
        }

        for (step in flow.steps) if ((color[step.id] ?: WHITE) == WHITE) visit(step.id)?.let { return it }
        return null
    }
}

/** A Flow compiled for fast execution: adjacency, ports and a topological order. */
class CompiledFlow(
    val flow: Flow,
    val order: List<StepNode>,
    val downstream: Map<String, List<com.kaivoflow.core.model.StreamEdge>>,
    val upstream: Map<String, List<com.kaivoflow.core.model.StreamEdge>>,
    val triggerStepIds: List<String>,
    val errorStepId: String?,
) {
    val stepsById: Map<String, StepNode> = flow.steps.associateBy { it.id }
}

class FlowCompiler(private val validator: FlowValidator) {

    fun compile(flow: Flow): CompiledFlow {
        val report = validator.validate(flow)
        if (report.blocking.isNotEmpty()) throw FlowNotRunnableException(report)

        // Disabled Steps stay in the graph: they behave as a pass-through so switching one off
        // never silently breaks the rest of the Flow.
        val enabled = flow.steps
        val downstream = enabled.associate { it.id to flow.streams.filter { s -> s.fromStepId == it.id }.sortedBy { s -> s.fromPort } }
        val upstream = enabled.associate { it.id to flow.streams.filter { s -> s.toStepId == it.id } }

        // Kahn's algorithm. Deterministic: ties broken by canvas Y then X, which matches
        // the visual top-to-bottom order the user sees in Guided Path mode.
        val inDegree = enabled.associate { it.id to (upstream[it.id]?.size ?: 0) }.toMutableMap()
        val ordered = enabled.sortedWith(compareBy({ it.y }, { it.x }))
        val queue = ordered.filter { inDegree[it.id] == 0 }.toMutableList()
        val result = mutableListOf<StepNode>()
        while (queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            result += next
            downstream[next.id].orEmpty().forEach { stream ->
                val remaining = (inDegree[stream.toStepId] ?: return@forEach) - 1
                inDegree[stream.toStepId] = remaining
                if (remaining == 0) {
                    enabled.firstOrNull { it.id == stream.toStepId }?.let { queue += it }
                }
            }
        }
        if (result.size != enabled.size) {
            // Validator already rejects cycles; this is a defensive guard.
            throw FlowNotRunnableException(ValidationReport(listOf(validationError("cycle", "The Flow has a loop and cannot be ordered."))))
        }

        return CompiledFlow(
            flow = flow,
            order = result,
            downstream = downstream,
            upstream = upstream,
            triggerStepIds = enabled.filter { it.isTrigger }.map { it.id },
            errorStepId = flow.settings.errorStepId?.takeIf { id -> flow.steps.any { it.id == id } },
        )
    }
}
