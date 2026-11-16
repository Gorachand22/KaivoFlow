package com.kaivoflow.core.nodes.flow.timing

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay

class WaitNode : StepExecutor {
    override val spec = NodeSpec(
        id = "flow.wait",
        title = "Wait",
        category = StepCategory.Logic,
        subtitle = "Pause before continuing (respects rate limits)",
        iconKey = "clock",
        params = persistentListOf(
            ParamSpec("seconds", "Wait for (seconds)", ParamType.Number, default = 1, required = true),
            ParamSpec("randomJitter", "Add random extra (seconds)", ParamType.Number, default = 0),
        ),
        keywords = persistentListOf("delay", "sleep", "throttle", "pause"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val base = (Values.asDouble(params["seconds"]) ?: 1.0).coerceIn(0.0, 900.0)
        val jitter = (Values.asDouble(params["randomJitter"]) ?: 0.0).coerceIn(0.0, 60.0)
        val total = base + if (jitter > 0) Math.random() * jitter else 0.0
        delay((total * 1000).toLong())
        return ctx.output(ctx.input)
    }
}
