package com.kaivoflow.core.engine

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ActiveRunRegistryTest {
    @Test fun oneOwnerPerFlowAndSeparateFlowsWork() {
        val registry = ActiveRunRegistry()
        val first = Job()
        val second = Job()
        assertTrue(registry.tryAcquire("one", first))
        assertFalse(registry.tryAcquire("one", second))
        assertTrue(registry.tryAcquire("two", second))
        assertTrue(registry.isOwned("one"))
        assertFalse(registry.release("one", second))
        assertTrue(registry.release("one", first))
        assertTrue(registry.tryAcquire("one", second))
    }

    @Test fun cancellationKeepsOwnershipUntilCleanup() {
        val registry = ActiveRunRegistry()
        val first = Job()
        assertTrue(registry.tryAcquire("f", first))
        assertTrue(registry.cancel("f"))
        assertTrue(first.isCancelled)
        assertTrue(registry.isOwned("f"))
        assertFalse(registry.tryAcquire("f", Job()))
        assertTrue(registry.release("f", first))
        assertFalse(registry.cancel("f"))
    }

    @Test fun simultaneousAcquisitionsHaveExactlyOneWinner() {
        val registry = ActiveRunRegistry()
        val pool = Executors.newFixedThreadPool(12)
        val ready = CountDownLatch(12)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(12)
        val winners = AtomicInteger()
        try {
            repeat(12) {
                pool.execute {
                    ready.countDown()
                    start.await()
                    if (registry.tryAcquire("f", Job())) winners.incrementAndGet()
                    finished.countDown()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, winners.get())
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }
}
