package com.kaivoflow.core.work.alwayson

import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.JsonCodec
import com.kaivoflow.core.engine.LifecycleSnapshot
import com.kaivoflow.core.engine.LifecycleState
import com.kaivoflow.core.model.Flow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the Engine executes: the PUBLISHED revision of every live Flow, never the draft (spec 4.1, "Edits to a Published Flow
 * never change live behaviour until the user presses Publish changes"). Every reader of "which Flows are live" goes through here,
 * which replaces the Phase 1 `Flow.isActive` checks. A live Flow without a stored revision (cannot happen after the 3 to 4
 * migration, which writes revision 1) falls back to its draft.
 *
 * While [arming] runs, the candidate Flow is overlaid as live, so the registrars (which read this class) register the Sparks
 * of a Flow that is not yet committed as Published. The overlay is removed in `finally`.
 */
@Singleton
class LiveFlows @Inject constructor(
    private val flows: FlowRepository,
    private val lifecycle: FlowLifecycleRepository,
) {
    data class Entry(val flow: Flow, val snapshot: LifecycleSnapshot)

    private val overlay = ConcurrentHashMap<String, Flow>()
    private val decoded = ConcurrentHashMap<Pair<String, Int>, Flow>()

    /** Every Flow in its executing form (published content when live) with its lifecycle. */
    suspend fun entries(): List<Entry> {
        val snapshots = lifecycle.allSnapshots()
        return flows.all().map { draft ->
            val ov = overlay[draft.id]
            if (ov != null) Entry(ov, LifecycleSnapshot(LifecycleState.Published, since = 0L))
            else {
                val snap = snapshots[draft.id] ?: LifecycleSnapshot()
                Entry(if (snap.state.isLive) publishedOrDraft(draft, snap) else draft, snap)
            }
        }
    }

    suspend fun live(): List<Flow> = entries().filter { it.snapshot.state.isLive }.map { it.flow }

    /** The executing form of one live Flow, or null when it is not live (or does not exist). */
    suspend fun liveFlow(id: String): Flow? {
        overlay[id]?.let { return it }
        val snap = lifecycle.snapshot(id) ?: return null
        if (!snap.state.isLive) return null
        val draft = flows.flow(id) ?: return null
        return publishedOrDraft(draft, snap)
    }

    suspend fun isLive(id: String): Boolean = overlay.containsKey(id) || lifecycle.snapshot(id)?.state?.isLive == true

    suspend fun published(flowId: String, revision: Int): Flow? {
        decoded[flowId to revision]?.let { return it }
        val row = lifecycle.version(flowId, revision) ?: return null
        val flow = JsonCodec.decodeFlow(row.flowJson) ?: return null
        if (decoded.size > MAX_CACHED) decoded.clear()
        decoded[flowId to revision] = flow
        return flow
    }

    suspend fun <T> arming(flow: Flow, block: suspend () -> T): T {
        overlay[flow.id] = flow
        try { return block() } finally { overlay.remove(flow.id) }
    }

    private suspend fun publishedOrDraft(draft: Flow, snap: LifecycleSnapshot): Flow =
        snap.publishedRevision?.let { published(draft.id, it) } ?: draft

    private companion object { const val MAX_CACHED = 200 }
}
