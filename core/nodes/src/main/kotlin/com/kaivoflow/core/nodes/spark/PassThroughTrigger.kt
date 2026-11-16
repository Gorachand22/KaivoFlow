package com.kaivoflow.core.nodes.spark

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult

/** Base for the Spark executors: forwards trigger items. */
abstract class PassThroughTrigger : StepExecutor {
    override suspend fun execute(ctx: StepContext): StepResult =
        StepResult(mapOf(0 to ctx.input))
}
