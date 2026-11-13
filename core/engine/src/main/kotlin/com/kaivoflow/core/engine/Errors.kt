package com.kaivoflow.core.engine

/** Base class for every error the engine can surface to the user. */
open class KaivoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A Step failed in a way that should be shown to the user verbatim. */
class StepExecutionException(
    val stepId: String,
    val stepName: String,
    message: String,
    cause: Throwable? = null,
) : KaivoException("Step \"$stepName\" failed: $message", cause)

/** The Flow itself is not runnable (structural problem). */
class FlowNotRunnableException(val report: ValidationReport) : KaivoException(
    buildString {
        append("This Flow cannot run yet:\n")
        report.blocking.forEach { append("• ").append(it.message).append('\n') }
    }.trimEnd(),
)

enum class IssueSeverity { Blocking, Warning }

/**
 * A single validation finding. [stepId] lets the canvas highlight the offending Step card
 * and [code] lets the UI offer a one-tap fix.
 */
data class ValidationIssue(
    val code: String,
    val message: String,
    val severity: IssueSeverity = IssueSeverity.Blocking,
    val stepId: String? = null,
    val params: List<String> = emptyList(),
    val fixHint: String? = null,
)

data class ValidationReport(val issues: List<ValidationIssue>) {
    val blocking: List<ValidationIssue> get() = issues.filter { it.severity == IssueSeverity.Blocking }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == IssueSeverity.Warning }
    val isValid: Boolean get() = blocking.isEmpty()

    fun forStep(stepId: String): List<ValidationIssue> = issues.filter { it.stepId == stepId }

    companion object {
        val Empty = ValidationReport(emptyList())
    }
}

internal fun validationError(
    code: String,
    message: String,
    stepId: String? = null,
    fixHint: String? = null,
): ValidationIssue = ValidationIssue(code, message, IssueSeverity.Blocking, stepId, fixHint = fixHint)

internal fun validationWarning(
    code: String,
    message: String,
    stepId: String? = null,
    fixHint: String? = null,
): ValidationIssue = ValidationIssue(code, message, IssueSeverity.Warning, stepId, fixHint = fixHint)
