package com.kaivoflow.core.data

import com.kaivoflow.core.engine.LifecycleReason
import com.kaivoflow.core.engine.LifecycleSnapshot
import com.kaivoflow.core.engine.LifecycleState

/**
 * Android-free decisions around the lifecycle columns, kept apart from Room so they run on the JVM.
 */
object LifecycleLogic {

    /**
     * True when a stored Flow document has top-level-looking `"isActive": true`. Phase 1 kept the flag
     * only inside the JSON. A string value that merely contains the text is escaped (`\"isActive\"`) and
     * cannot match. A Step parameter named isActive would match; that is accepted and harmless, since
     * the result is only a starting lifecycle the owner can unpublish.
     */
    fun isActiveInJson(flowJson: String): Boolean = ACTIVE.containsMatchIn(flowJson)

    private val ACTIVE = Regex("\"isActive\"\\s*:\\s*true")

    /**
     * Phase 1 bridge until the publish UI uses the state machine (AO-16): the old save path still
     * toggles `Flow.isActive`. Keep the lifecycle column consistent with it without touching Paused
     * (nothing in Phase 1 can produce Paused) and without inventing a revision (no snapshot exists).
     */
    fun syncWithLegacyActive(current: LifecycleSnapshot, isActive: Boolean, now: Long): LifecycleSnapshot = when {
        isActive && !current.state.isLive && current.state != LifecycleState.Paused ->
            LifecycleSnapshot(LifecycleState.Published, reason = LifecycleReason.Published, since = now)
        !isActive && current.state.isLive ->
            LifecycleSnapshot(LifecycleState.Draft, reason = LifecycleReason.Unpublished, since = now)
        else -> current
    }
}
