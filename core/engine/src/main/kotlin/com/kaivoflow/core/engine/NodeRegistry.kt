package com.kaivoflow.core.engine

import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.StepNode

/**
 * The Step catalog. Both the runner and the UI read from here, so a Step can never be
 * visible in the palette but unrunnable (or vice versa).
 */
class NodeRegistry(catalog: List<StepExecutor>) {

    private val byType: Map<String, StepExecutor> = catalog.associateBy { it.spec.id }

    val executors: List<StepExecutor> get() = byType.values.toList()

    val specs: List<NodeSpec> get() = byType.values.map { it.spec }.sortedBy { it.title }

    fun spec(type: String): NodeSpec? = byType[type]?.spec

    fun executor(type: String): StepExecutor? = byType[type]

    fun requireExecutor(type: String): StepExecutor =
        byType[type] ?: throw UnknownStepTypeException(type)

    fun byCategory(category: StepCategory): List<NodeSpec> =
        specs.filter { it.category == category }

    fun triggers(): List<NodeSpec> = specs.filter { it.kind == com.kaivoflow.core.model.NodeKind.Trigger }

    /** Palette + inspector search. Matches title, keywords and category label. */
    fun search(query: String, category: StepCategory? = null): List<NodeSpec> {
        val pool = if (category == null) specs else byCategory(category)
        val q = query.trim().lowercase()
        if (q.isEmpty()) return pool
        return pool
            .map { spec -> spec to score(spec, q) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun score(spec: NodeSpec, q: String): Int {
        var score = 0
        if (spec.title.lowercase().startsWith(q)) score += 100
        if (spec.title.lowercase().contains(q)) score += 50
        if (spec.subtitle.lowercase().contains(q)) score += 20
        if (spec.id.lowercase().contains(q)) score += 15
        if (spec.category.label.lowercase().contains(q)) score += 10
        if (spec.keywords.any { it.lowercase().contains(q) }) score += 25
        return score
    }

    /** Validator entry point: spec-level plus executor-level checks. */
    fun validate(step: StepNode): List<ValidationIssue> {
        val executor = byType[step.type]
            ?: return listOf(
                validationError(
                    "unknown_step_type",
                    "This Step type (${step.type}) is not available on this build. Remove it or update the app.",
                    step.id,
                )
            )
        val spec = executor.spec
        val issues = mutableListOf<ValidationIssue>()
        spec.params.filter { it.required }.forEach { param ->
            val value = step.params[param.key]
            if (Values.isBlank(value)) {
                issues += validationError(
                    "missing_param",
                    "\"${step.name}\" needs a value for ${param.label}.",
                    step.id,
                    fixHint = "Open the Step and fill in ${param.label}.",
                )
            }
        }
        issues += executor.validate(step, spec)
        return issues
    }
}
