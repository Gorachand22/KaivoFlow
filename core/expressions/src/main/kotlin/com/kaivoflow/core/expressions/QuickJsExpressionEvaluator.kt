package com.kaivoflow.core.expressions

import com.kaivoflow.core.engine.ExpressionEvaluator

/**
 * Expression evaluator using built-in pure-Kotlin [FallbackExpressionEvaluator].
 */
class QuickJsExpressionEvaluator(
    private val timeoutMs: Long = 400,
    private val prelude: String = "",
) : ExpressionEvaluator, AutoCloseable {

    private val fallback = FallbackExpressionEvaluator()

    override fun evaluate(code: String, scope: Map<String, Any?>): Any? {
        if (code.isBlank()) return null
        return fallback.evaluate(code, scope)
    }

    override fun close() {
        // No-op for pure-Kotlin engine
    }

    companion object {
        private const val TAG = "QuickJsEvaluator"
    }
}
