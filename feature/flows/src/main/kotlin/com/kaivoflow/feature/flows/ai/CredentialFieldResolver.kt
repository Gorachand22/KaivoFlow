package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.data.WorkflowVariableRepository
import com.kaivoflow.core.engine.CredentialExpr
import com.kaivoflow.core.engine.GlobalScope
import com.kaivoflow.core.engine.VariableResolution
import com.kaivoflow.core.expressions.KaivoExpressionEvaluator

/**
 * Evaluates the non-secret credential fields that the AI screens read straight from the vault
 * (base URL and the like), the same way `ExprCredentialProvider` does for nodes (spec P3). Scope is
 * global: workspace `$vars`, `$now`, `$today`. Secret fields are never evaluated. Never throws:
 * a bad expression comes back in [CredentialExpr.Result.errors].
 */
class CredentialFieldResolver(private val variables: WorkflowVariableRepository) {
    private val evaluator = KaivoExpressionEvaluator()

    suspend fun resolve(type: String?, stored: Map<String, String>): CredentialExpr.Result {
        if (!CredentialExpr.hasExpression(stored)) return CredentialExpr.Result(stored, emptyMap())
        val raw = runCatching { variables.resolveVariables(null) }.getOrNull().orEmpty()
        val vars = VariableResolution.resolve(raw, evaluator).vars
        return CredentialExpr.resolveFields(type, stored, evaluator, GlobalScope.build(vars))
    }
}
