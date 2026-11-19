package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.expressions.assist.KaivoExpressionAssist
import com.kaivoflow.core.model.Flow

/** One expression with a syntax error found in text an AI wrote. [where] names the step and parameter. */
data class ExpressionProblem(val where: String, val message: String)

/**
 * Checks the expressions in AI-written parameters before they reach the canvas (spec P3, AI tools).
 * Both storage forms count: a string containing `{{` and the `{"kx": text}` object. Nothing is
 * evaluated; this only parses, so it is cheap and cannot run user data. The caller keeps the
 * change and shows the problems, so a typo never loses the proposal and never crashes the chat.
 */
object ExpressionGuard {
    private val assist = KaivoExpressionAssist()

    fun check(params: Map<String, Any?>, stepName: String): List<ExpressionProblem> {
        val out = ArrayList<ExpressionProblem>()
        for ((k, v) in params) walk(v, "\"$stepName\" / $k", out)
        return out
    }

    fun check(flow: Flow): List<ExpressionProblem> = flow.steps.flatMap { check(it.params, it.name) }

    private fun walk(v: Any?, where: String, out: MutableList<ExpressionProblem>) {
        when (v) {
            is String -> if (v.contains("{{")) test(v, where, out)
            is Map<*, *> -> {
                val only = if (v.size == 1) v.entries.first() else null
                if (only != null && only.key == "kx" && only.value is String) test(only.value as String, where, out)
                else v.forEach { (k, x) -> walk(x, "$where.$k", out) }
            }
            is List<*> -> v.forEachIndexed { i, x -> walk(x, "$where[$i]", out) }
            else -> Unit
        }
    }

    private fun test(text: String, where: String, out: MutableList<ExpressionProblem>) {
        val errors = try { assist.analyze(text).errors } catch (e: Exception) { return }
        errors.firstOrNull()?.let { out += ExpressionProblem(where, it.message) }
    }

    fun summary(problems: List<ExpressionProblem>): String =
        problems.joinToString("\n") { "- ${it.where}: ${it.message}" }
}
