package com.kaivoflow.core.nodes.device.io

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.DeviceGateway
import kotlinx.collections.immutable.persistentListOf

class SpeakNode(private val gateway: DeviceGateway) : StepExecutor {
    override val spec = NodeSpec(
        id = "device.speak",
        title = "Speak out loud",
        category = StepCategory.Device,
        subtitle = "Read text with the phone's voice",
        iconKey = "speaker",
        params = persistentListOf(
            ParamSpec("text", "What to say", ParamType.Multiline, required = true, placeholder = "{{ ${'$'}json.answer }}"),
            ParamSpec("language", "Language", ParamType.Text, default = "en-IN", advanced = true),
        ),
        keywords = persistentListOf("speak", "tts", "voice", "say", "read aloud"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val text = Values.asString(params["text"])
        if (text.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "there is nothing to say")
        gateway.speak(text, Values.asString(params["language"], "en-IN"))
        return ctx.output(ctx.input)
    }
}
