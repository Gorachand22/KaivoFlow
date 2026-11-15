package com.kaivoflow.core.engine

import org.junit.Assert.*
import org.junit.Test

class PerFlowEventQueueTest {
    @Test fun fifoPerFlow() {
        val q = PerFlowEventQueue<Int>(5)
        q.offer("a", 1); q.offer("a", 2); q.offer("b", 9)
        assertEquals(1, q.poll("a")); assertEquals(2, q.poll("a")); assertNull(q.poll("a"))
        assertEquals(9, q.poll("b"))
    }

    @Test fun fullQueueDropsOldestAndCounts() {
        val q = PerFlowEventQueue<Int>(3)
        assertNull(q.offer("a", 1)); q.offer("a", 2); q.offer("a", 3)
        assertEquals(1, q.offer("a", 4))
        assertEquals(2, q.offer("a", 5))
        assertEquals(3, q.size("a"))
        assertEquals(2, q.takeDroppedCount("a"))
        assertEquals(0, q.takeDroppedCount("a"))
        assertEquals(listOf(3, 4, 5), listOf(q.poll("a"), q.poll("a"), q.poll("a")))
    }

    @Test fun clearEmptiesQueue() {
        val q = PerFlowEventQueue<String>()
        q.offer("a", "x"); q.clear("a")
        assertEquals(0, q.size("a")); assertNull(q.poll("a"))
        assertEquals(50, q.maxSize)
    }
}
