package com.kaivoflow.core.work.alwayson

import com.kaivoflow.core.data.RegistrationState
import com.kaivoflow.core.data.TriggerRegistrationEntity
import com.kaivoflow.core.data.TriggerRegistrationRepository
import com.kaivoflow.core.engine.alwayson.ReconcileTrigger
import com.kaivoflow.core.engine.alwayson.ReliabilityKinds
import com.kaivoflow.core.engine.alwayson.Severity
import com.kaivoflow.core.engine.alwayson.SparkRegState
import com.kaivoflow.core.model.Flow

/**
 * One kind of platform registration the Reconciler keeps true: webhook routes, geofences, the notification listener, the Engine's
 * receivers. Contributed to a Hilt multibinding (`@Binds @IntoSet` in `AlwaysOnBindings`), so a new Spark source adds one class and
 * touches nothing else. Must be idempotent and must write `trigger_registration` rows that say what is really armed: that row is
 * what Publish validation and the Reliability Center read.
 *
 * [live] and [all] can be partial: `Reconciler.syncFlow` passes only the Flow that changed. A registrar that needs the complete
 * picture reads the repository itself.
 */
interface SparkRegistrar {
    /** [live] are the Flows that should be armed, [all] every Flow the caller knows of (to remove what no longer should be). */
    suspend fun reconcile(live: List<Flow>, all: List<Flow>, trigger: ReconcileTrigger)
}

/** Shared writer for `trigger_registration`: keeps `since` while the state is unchanged and writes only on a change. */
internal class RegistrationWriter(
    private val registrations: TriggerRegistrationRepository,
    private val recorder: ReliabilityRecorder,
    private val nowMs: () -> Long,
) {
    suspend fun write(flowId: String, sparkId: String, kind: String, state: SparkRegState, old: TriggerRegistrationEntity?) {
        val db = when (state.kind) {
            SparkRegState.Kind.Armed -> RegistrationState.ARMED
            SparkRegState.Kind.Pending -> RegistrationState.PENDING
            SparkRegState.Kind.FailedTransient -> RegistrationState.FAILED_TRANSIENT
            SparkRegState.Kind.FailedPermanent -> RegistrationState.FAILED_PERMANENT
        }
        if (old != null && old.state == db && old.detail == state.detail) return
        val now = nowMs()
        registrations.upsert(
            TriggerRegistrationEntity(
                flowId = flowId, sparkId = sparkId, kind = kind, state = db, detail = state.detail,
                since = if (old?.state == db) old.since else now,
                retryCount = if (state.isFailure) (old?.retryCount ?: 0) + 1 else 0,
                nextRetryAt = null,
            ),
        )
        // One event per change into a failure, so a Spark that stays broken does not flood the log on every Pulse.
        if (state.isFailure && old?.state != db) {
            recorder.record(ReliabilityKinds.SPARK_REGISTRATION_FAILED, Severity.WARN, flowId, "$kind ${state.detail}")
        }
    }
}
