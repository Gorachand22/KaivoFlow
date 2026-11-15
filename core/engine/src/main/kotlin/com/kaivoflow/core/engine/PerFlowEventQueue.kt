package com.kaivoflow.core.engine

/**
 * Small in-memory FIFO queues, one per Flow, for events that arrive while the Flow is already
 * running. Bounded: when a queue is full the OLDEST entry is dropped and counted, so the next
 * Run can say so in its log. Pure Kotlin, thread-safe via one lock.
 */
class PerFlowEventQueue<T>(val maxSize: Int = DEFAULT_MAX_SIZE) {
    init { require(maxSize > 0) { "maxSize must be positive" } }

    private val lock = Any()
    private val queues = HashMap<String, ArrayDeque<T>>()
    private val dropped = HashMap<String, Int>()

    /** Adds [item]; returns the entry that was evicted to make room, or null. */
    fun offer(flowId: String, item: T): T? = synchronized(lock) {
        val q = queues.getOrPut(flowId) { ArrayDeque() }
        var evicted: T? = null
        if (q.size >= maxSize) {
            evicted = q.removeFirst()
            dropped[flowId] = (dropped[flowId] ?: 0) + 1
        }
        q.addLast(item)
        evicted
    }

    fun poll(flowId: String): T? = synchronized(lock) {
        val q = queues[flowId] ?: return null
        val item = q.removeFirstOrNull()
        if (q.isEmpty()) queues.remove(flowId)
        item
    }

    fun size(flowId: String): Int = synchronized(lock) { queues[flowId]?.size ?: 0 }

    /** Returns and resets the number of events dropped for [flowId] since the last call. */
    fun takeDroppedCount(flowId: String): Int = synchronized(lock) { dropped.remove(flowId) ?: 0 }

    fun clear(flowId: String) = synchronized(lock) { queues.remove(flowId); dropped.remove(flowId); Unit }

    companion object { const val DEFAULT_MAX_SIZE = 50 }
}
