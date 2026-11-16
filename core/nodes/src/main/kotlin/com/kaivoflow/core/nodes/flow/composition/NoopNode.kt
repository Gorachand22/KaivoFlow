package com.kaivoflow.core.nodes.flow.composition

import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.nodes.outputField
import com.kaivoflow.core.nodes.outputName
import com.kaivoflow.core.nodes.resolveText
import com.kaivoflow.core.nodes.textParam
import kotlinx.collections.immutable.persistentListOf

/** A deliberately transparent pass-through Step for notes, branching and debugging. */
class NoopNode : StepExecutor {
    override val spec = NodeSpec(
        id = "flow.noop",
        title = "Pass through",
        category = StepCategory.Logic,
        subtitle = "Leave items untouched, optionally add a note",
        iconKey = "syncAlt",
        params = persistentListOf(
            textParam("note", "Note", placeholder = "Saved for later"),
            textParam("outputField", "Save the note in", default = "note"),
        ),
        keywords = persistentListOf("noop", "pass", "do nothing", "annotation", "debug", "marker"),
    )

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.step.params
        val note = Values.asString(params["note"])
        if (note.isBlank()) return ctx.output(ctx.input)
        val field = ctx.outputName("note")
        val out = ctx.input.mapIndexed { index, item ->
            item.copy(json = item.json + (field to ctx.resolveText(params["note"], index)))
        }
        ctx.log("Passed ${out.size} items through.")
        return ctx.output(out)
    }
}
