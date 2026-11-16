package com.kaivoflow.feature.canvas.inspector.logic

import com.kaivoflow.core.model.CategoryTree
import com.kaivoflow.core.model.Lifecycle
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.OnErrorPolicy
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.RetryPolicy
import com.kaivoflow.core.model.StepNode

/** What happens when the Step fails. The legacy `Retry` policy reads as Stop with retries on. */
enum class OnErrorChoice(val label: String, val hint: String) {
    Stop("Stop the Run", "The Run ends and is marked failed."),
    Continue("Continue", "The failed item becomes an error item and the Run goes on."),
    ErrorOutput("Error output", "Failed items go to the Flow's error Step; the rest continue."),
}

/** Pure edits of the Behaviour tab on the fields StepNode already has (Flow.kt). Every function returns a new Step. */
object StepBehaviour {
    const val MIN_ATTEMPTS = 2
    const val MAX_ATTEMPTS = 10
    const val MAX_WAIT_MS = 300_000L
    const val MIN_BACKOFF = 1.0
    const val MAX_BACKOFF = 5.0
    const val MAX_TIMEOUT_S = 3600L

    fun onErrorOf(step: StepNode): OnErrorChoice = when (step.onError) {
        OnErrorPolicy.Continue -> OnErrorChoice.Continue
        OnErrorPolicy.ErrorFlow -> OnErrorChoice.ErrorOutput
        OnErrorPolicy.Stop, OnErrorPolicy.Retry -> OnErrorChoice.Stop
    }

    fun retryOn(step: StepNode): Boolean = step.retry.enabled || step.onError == OnErrorPolicy.Retry

    fun setOnError(step: StepNode, choice: OnErrorChoice): StepNode = step.copy(
        onError = when (choice) {
            OnErrorChoice.Stop -> OnErrorPolicy.Stop
            OnErrorChoice.Continue -> OnErrorPolicy.Continue
            OnErrorChoice.ErrorOutput -> OnErrorPolicy.ErrorFlow
        },
        // The legacy Retry policy folded into the retry switch: keep its effect.
        retry = if (step.onError == OnErrorPolicy.Retry) step.retry.copy(enabled = true) else step.retry,
    )

    fun setRetryEnabled(step: StepNode, on: Boolean): StepNode = step.copy(
        retry = step.retry.copy(enabled = on),
        onError = if (!on && step.onError == OnErrorPolicy.Retry) OnErrorPolicy.Stop else step.onError,
    )

    /** [attempts] counts the first try, so 3 means two retries. */
    fun setMaxAttempts(step: StepNode, attempts: Int): StepNode =
        step.copy(retry = step.retry.copy(maxAttempts = attempts.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS)))

    fun setWaitMs(step: StepNode, ms: Long): StepNode {
        val w = ms.coerceIn(0L, MAX_WAIT_MS)
        return step.copy(retry = step.retry.copy(initialDelayMs = w, maxDelayMs = maxOf(step.retry.maxDelayMs, w)))
    }

    fun setBackoff(step: StepNode, multiplier: Double): StepNode =
        step.copy(retry = step.retry.copy(backoffMultiplier = if (multiplier.isNaN()) RetryPolicy().backoffMultiplier else multiplier.coerceIn(MIN_BACKOFF, MAX_BACKOFF)))

    /** Null or a value of 0 or less clears the timeout. */
    fun setTimeoutSeconds(step: StepNode, seconds: Long?): StepNode =
        step.copy(timeoutMs = seconds?.takeIf { it > 0 }?.coerceAtMost(MAX_TIMEOUT_S)?.times(1000))

    fun timeoutSeconds(step: StepNode): Long? = step.timeoutMs?.let { it / 1000 }

    fun setNotes(step: StepNode, notes: String): StepNode = step.copy(notes = notes)

    /** A blank name is refused (the old name stays). */
    fun rename(step: StepNode, name: String): StepNode = if (name.isBlank()) step else step.copy(name = name.trim())

    fun setDisabled(step: StepNode, disabled: Boolean): StepNode = step.copy(disabled = disabled)

    /** "Up to 3 tries, 1 s apart, doubling." */
    fun retrySummary(r: RetryPolicy): String {
        val wait = if (r.initialDelayMs % 1000L == 0L) "${r.initialDelayMs / 1000} s" else "${r.initialDelayMs} ms"
        val grow = if (r.backoffMultiplier > 1.0) ", growing x${trim(r.backoffMultiplier)} each time" else ""
        return "Up to ${r.maxAttempts} tries, $wait between the first two$grow."
    }

    private fun trim(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}

/** One line of the About tab's field list. */
data class AboutField(val label: String, val hint: String?, val required: Boolean)

data class AboutModel(
    val title: String,
    val description: String,
    /** "Device > Sensors", or the legacy category label when no path is assigned. */
    val categoryPath: String,
    val outputs: List<String>,
    val permissionNote: String?,
    val lifecycleNote: String?,
    val fields: List<AboutField>,
) {
    companion object {
        fun build(spec: NodeSpec?, schema: ParamSchema): AboutModel {
            if (spec == null) return AboutModel("Unknown Step", "This Step is not available on this build.", "", emptyList(), null, null, emptyList())
            val path = if (spec.categoryPath.isNotBlank()) CategoryTree.breadcrumb(spec.categoryPath) else spec.category.label
            val outs = if (spec.outputs <= 1) listOf("Output") else (0 until spec.outputs).map { spec.outputLabels.getOrNull(it) ?: "Output ${it + 1}" }
            val life = when (spec.lifecycle) {
                Lifecycle.Beta -> "Beta: behaviour may still change."
                Lifecycle.Legacy -> "Legacy: it still works, but a newer Step replaces it."
                else -> null
            }
            val fields = schema.params.filter { it.kind !is ParamKind.Notice }.map { AboutField(it.label.ifBlank { it.key }, it.hint ?: it.docs, it.required) }
            return AboutModel(spec.title, spec.subtitle, path, outs, spec.requiresSensitivePermission?.let { "Needs the $it permission." }, life, fields)
        }
    }
}
