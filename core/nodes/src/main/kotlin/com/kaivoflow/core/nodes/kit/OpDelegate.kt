package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.FlowData
import kotlinx.collections.immutable.toImmutableMap

/**
 * Runs [exec] (a retired Step kept as an internal operation engine, not registered in the catalog)
 * as if it were this Step with [params]. Used by the merged hub Steps so that behaviour moves
 * unchanged. [items] defaults to the Step's own input; lineage is kept only while the item count
 * is unchanged.
 */
internal suspend fun StepContext.delegate(
    exec: StepExecutor,
    params: Map<String, Any?>,
    items: List<FlowData> = input,
): StepResult = exec.execute(
    copy(
        step = step.copy(params = params.toImmutableMap()),
        spec = exec.spec,
        input = items,
        inputs = emptyList(),
        inputRefs = if (items.size == input.size) inputRefs else emptyList(),
        streamInputs = emptyList(),
    ),
)
