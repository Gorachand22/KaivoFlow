package com.kaivoflow.core.ai.flowedit

import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

/** One edit as the chat shows it. */
data class DraftEdit(val tool: String, val description: String, val ok: Boolean)

/**
 * A private working copy of a Flow. Every assistant edit lands here and nowhere else: this class has no
 * repository, so there is no way for it to save. The host decides what happens to [flow] after the turn.
 * [original] is the Flow the draft started from (null for a new Flow), kept to tell what really changed.
 */
class FlowDraft(val original: Flow?, private val start: Flow, private val newId: () -> String, private val clock: () -> Long) {
    var flow: Flow = start
        private set
    private val _edits = ArrayList<DraftEdit>()
    val edits: List<DraftEdit> get() = _edits

    val changed: Boolean get() = flow.steps != start.steps || flow.streams != start.streams || flow.name != start.name

    private fun record(tool: String, text: String, ok: Boolean = true) { _edits += DraftEdit(tool, text, ok) }

    /** A Step by id, or by name when the name is unique. */
    fun find(ref: String): StepNode? {
        val r = ref.trim()
        flow.step(r)?.let { return it }
        val byName = flow.steps.filter { it.name.equals(r, ignoreCase = true) }
        return byName.singleOrNull()
    }

    fun add(catalog: AssistantCatalog, typed: String, name: String?, params: Map<String, Any?>, after: String?): String {
        if (flow.steps.size >= ProposalLimits.MAX_STEPS) return fail("add_step", "The Flow already has ${ProposalLimits.MAX_STEPS} Steps, the most allowed.")
        val id = catalog.canonicalId(typed)?.takeIf { catalog.isOffered(it) }
            ?: return fail("add_step", "\"${typed.take(60)}\" is not a Step you can add." + catalog.suggest(typed).let { if (it.isEmpty()) "" else " Did you mean ${it.joinToString { s -> "`$s`" }}?" })
        val spec = catalog.spec(id)!!
        val writable = catalog.writableKeys(id)
        val clean = LinkedHashMap<String, Any?>()
        val ignored = ArrayList<String>()
        for ((k, v) in params) if (k in writable) clean[k] = v else ignored += k
        val parent = after?.let { find(it) ?: return fail("add_step", "There is no Step \"$it\" to connect after.") }
        if (spec.isTrigger && flow.steps.any { it.isTrigger }) return fail("add_step", "The Flow already has a Trigger. Remove it first or use another Step.")
        val stepId = "step_${newId().take(6)}"
        val y = (flow.steps.maxOfOrNull { it.y } ?: -60f) + 160f
        val step = StepNode(id = stepId, type = id, name = (name?.takeIf { it.isNotBlank() } ?: spec.title).take(ProposalLimits.MAX_NAME_CHARS), params = clean.toImmutableMap(), x = 100f, y = y)
        flow = flow.plusStep(step).copy(updatedAt = clock())
        if (parent != null && !spec.isTrigger) flow = flow.plusStream(StreamEdge("stream_${newId().take(6)}", parent.id, stepId))
        val note = if (ignored.isEmpty()) "" else " (ignored unknown settings: ${ignored.joinToString()})"
        record("add_step", "Added \"${step.name}\" (${step.type})$note")
        return "Added step id=$stepId (${step.type})$note"
    }

    fun connect(from: String, to: String, fromPort: Int, catalog: AssistantCatalog): String {
        val a = find(from) ?: return fail("connect_steps", "There is no Step \"$from\".")
        val b = find(to) ?: return fail("connect_steps", "There is no Step \"$to\".")
        if (a.id == b.id) return fail("connect_steps", "A Step cannot connect to itself.")
        if (b.isTrigger) return fail("connect_steps", "\"${b.name}\" is a Trigger and cannot have an input.")
        val outputs = catalog.spec(a.type)?.outputs ?: 1
        if (fromPort !in 0 until outputs) return fail("connect_steps", "\"${a.name}\" has $outputs output(s); port $fromPort does not exist.")
        if (flow.streams.size >= ProposalLimits.MAX_STREAMS) return fail("connect_steps", "The Flow has the most Streams allowed.")
        if (flow.streams.any { it.fromStepId == a.id && it.fromPort == fromPort && it.toStepId == b.id }) return fail("connect_steps", "\"${a.name}\" is already connected to \"${b.name}\" on that port.")
        flow = flow.plusStream(StreamEdge("stream_${newId().take(6)}", a.id, b.id, fromPort)).copy(updatedAt = clock())
        record("connect_steps", "Connected \"${a.name}\" to \"${b.name}\"")
        return "Connected ${a.id} to ${b.id}"
    }

    fun disconnect(from: String, to: String): String {
        val a = find(from) ?: return fail("disconnect_steps", "There is no Step \"$from\".")
        val b = find(to) ?: return fail("disconnect_steps", "There is no Step \"$to\".")
        val gone = flow.streams.filter { it.fromStepId == a.id && it.toStepId == b.id }
        if (gone.isEmpty()) return fail("disconnect_steps", "\"${a.name}\" is not connected to \"${b.name}\".")
        flow = flow.copy(streams = flow.streams.filterNot { it in gone }.toImmutableList(), updatedAt = clock())
        record("disconnect_steps", "Disconnected \"${a.name}\" from \"${b.name}\"")
        return "Disconnected ${gone.size} stream(s)"
    }

    fun remove(ref: String): String {
        val s = find(ref) ?: return fail("remove_step", "There is no Step \"$ref\".")
        flow = flow.minusStep(s.id)
        record("remove_step", "Removed \"${s.name}\" and its Streams")
        return "Removed ${s.id}"
    }

    fun rename(ref: String, newName: String): String {
        val s = find(ref) ?: return fail("rename_step", "There is no Step \"$ref\".")
        if (newName.isBlank()) return fail("rename_step", "The new name is empty.")
        flow = flow.withStep(s.copy(name = newName.trim().take(ProposalLimits.MAX_NAME_CHARS)))
        record("rename_step", "Renamed \"${s.name}\" to \"${newName.trim()}\"")
        return "Renamed ${s.id}"
    }

    /**
     * Changes settings. [catalog] decides which keys exist; secret settings and unknown keys are refused so the model
     * cannot invent a setting the Step ignores or overwrite a credential pick.
     */
    fun update(ref: String, params: Map<String, Any?>, catalog: AssistantCatalog): String {
        val s = find(ref) ?: return fail("update_step_params", "There is no Step \"$ref\".")
        val writable = catalog.writableKeys(s.type)
        val bad = params.keys.filter { it !in writable }
        if (bad.isNotEmpty()) return fail("update_step_params", "\"${s.name}\" has no changeable setting ${bad.joinToString()}. Settings: ${writable.sorted().joinToString()}.")
        flow = flow.withStep(s.copy(params = (s.params + params).toImmutableMap()))
        record("update_step_params", "Changed ${params.keys.joinToString()} on \"${s.name}\"")
        return "Updated ${params.keys.joinToString()} on ${s.id}"
    }

    fun setName(name: String): String {
        if (name.isBlank()) return fail("rename_flow", "The name is empty.")
        flow = flow.copy(name = name.trim().take(ProposalLimits.MAX_NAME_CHARS), updatedAt = clock())
        record("rename_flow", "Renamed the Flow to \"${flow.name}\"")
        return "Renamed the Flow"
    }

    private fun fail(tool: String, text: String): String { record(tool, text, ok = false); return text }

    /** Marks a failure the tool wrapper reports as an error. */
    internal fun lastFailed(): Boolean = _edits.lastOrNull()?.ok == false
    internal fun editCount(): Int = _edits.size
}
