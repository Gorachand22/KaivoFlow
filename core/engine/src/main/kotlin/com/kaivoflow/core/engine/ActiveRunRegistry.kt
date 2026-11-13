package com.kaivoflow.core.engine

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/** In-process ownership of executing coroutine jobs; this is not a durable event queue. */
class ActiveRunRegistry {
    private val owners = ConcurrentHashMap<String, Job>()

    /** Atomic: only one caller can own a given Flow until that caller releases it. */
    fun tryAcquire(flowId: String, job: Job): Boolean = owners.putIfAbsent(flowId, job) == null

    /** Release only our own entry, so stale cleanup cannot remove a newer owner. */
    fun release(flowId: String, job: Job): Boolean = owners.remove(flowId, job)

    /** Includes cancelling jobs until their cleanup is finished. */
    fun isOwned(flowId: String): Boolean = owners.containsKey(flowId)

    /** Request cancellation without allowing a new owner until the old owner releases. */
    fun cancel(flowId: String): Boolean {
        val job = owners[flowId] ?: return false
        job.cancel()
        return true
    }
}
