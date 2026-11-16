package com.kaivoflow.core.data

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
}
