package com.kaivoflow.core.nodes.flow.timing

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf

class ErrorHandlerNode : StepExecutor {
    override val spec = NodeSpec(
        id = "logic.onError",
        title = "When a Step fails",
        category = StepCategory.Logic,
        subtitle = "Clean up or alert when something breaks",
        iconKey = "shield",
        params = persistentListOf(
            ParamSpec("message", "Alert text", ParamType.Multiline, placeholder = "{{ ${'$'}json.step }} failed: {{ ${'$'}json.error }}"),
        ),
        keywords = persistentListOf("catch", "error", "handler", "alert"),
    )

    override suspend fun execute(ctx: StepContext): StepResult = ctx.output(ctx.input)
}
