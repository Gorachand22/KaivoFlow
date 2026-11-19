package com.kaivoflow.feature.flows

import com.kaivoflow.core.model.ExprFieldCodec
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.WorkflowVariable
import com.kaivoflow.feature.canvas.input.GlobalExpressionScope

/**
 * Scope for expression inputs outside a node (variable values, credential fields): the fixed variables
 * as `$vars` (an expression variable never reads another one, so there are no cycles), plus `$now` and `$today`.
 */
internal fun variableExprScope(all: List<WorkflowVariable>): GlobalExpressionScope =
    GlobalExpressionScope(
        vars = all
            .filter { !ExprFieldCodec.isExpression(it.value) }
            .associate { it.key to (ExprFieldCodec.decode(it.value) as ParamValue.Fixed).value },
    )
