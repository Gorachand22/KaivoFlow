package com.kaivoflow.core.data

import com.kaivoflow.core.engine.LifecycleLogEntry
import com.kaivoflow.core.engine.LifecycleReason
import com.kaivoflow.core.engine.LifecycleSnapshot
import com.kaivoflow.core.engine.LifecycleState

fun FlowEntity.lifecycleSnapshot(): LifecycleSnapshot = LifecycleSnapshot(
    state = LifecycleState.fromDb(lifecycle),
    publishedRevision = publishedRevision,
    reason = LifecycleReason.fromDb(lifecycleReason),
    since = lifecycleSince,
    pausedUntil = pausedUntil,
    errorCode = errorCode,
    errorSince = errorSince,
    retryCount = retryCount,
    nextRetryAt = nextRetryAt,
    healthySince = healthySince,
)

fun FlowEntity.withLifecycle(s: LifecycleSnapshot): FlowEntity = copy(
    lifecycle = s.state.db,
    publishedRevision = s.publishedRevision,
    lifecycleReason = s.reason.db,
    lifecycleSince = s.since,
    pausedUntil = s.pausedUntil,
    errorCode = s.errorCode,
    errorSince = s.errorSince,
    retryCount = s.retryCount,
    nextRetryAt = s.nextRetryAt,
    healthySince = s.healthySince,
)

fun LifecycleLogEntry.toEntity(flowId: String) = FlowLifecycleLogEntity(
    flowId = flowId, at = at, fromState = from.db, toState = to.db, reason = reason.db, actor = actor.db,
)
