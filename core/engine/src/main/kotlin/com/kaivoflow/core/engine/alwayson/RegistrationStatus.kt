package com.kaivoflow.core.engine.alwayson

import com.kaivoflow.core.engine.FailureCode
import com.kaivoflow.core.engine.RegistrationFailure

/**
 * What a Spark registrar found when it tried to arm one Spark (webhook route, geofence, notification listener, Bluetooth).
 * One honest status per Spark: it is what the registrar writes to `trigger_registration` and what Publish validation reads
 * through [RegistrationStatuses.failures] (spec 4.3 rule 5, "arming dry run").
 */
data class SparkRegState(val kind: Kind, val detail: String = "", val failure: FailureCode? = null) {
    enum class Kind {
        /** The platform accepted the registration and it is live. */
        Armed,
        /** Waiting for something that will happen on its own (the Engine starting). Not a failure. */
        Pending,
        /** Failed, but retrying can succeed (location switched off, port busy, listener not yet bound). */
        FailedTransient,
        /** Failed until the user or the Flow changes (permission, bad configuration). Never retried blindly. */
        FailedPermanent,
    }

    val isArmed: Boolean get() = kind == Kind.Armed
    val isFailure: Boolean get() = kind == Kind.FailedTransient || kind == Kind.FailedPermanent

    companion object {
        fun armed(detail: String = "") = SparkRegState(Kind.Armed, detail)
        fun pending(detail: String) = SparkRegState(Kind.Pending, detail)

        /** Picks transient or permanent from the code's own flag (spec 4.6: permanent codes are not retried). */
        fun failed(code: FailureCode, detail: String = "") =
            SparkRegState(if (code.permanent) Kind.FailedPermanent else Kind.FailedTransient, detail, code)
    }
}

object RegistrationStatuses {
    /** Maps registrar results to the failures the lifecycle state machine and the Publish validator consume. */
    fun failures(statuses: Map<String, SparkRegState>): List<RegistrationFailure> =
        statuses.entries.sortedBy { it.key }.mapNotNull { (sparkId, s) ->
            if (!s.isFailure) null
            else RegistrationFailure(sparkId, s.failure ?: FailureCode.Unknown, s.detail)
        }

    /** True when no Spark is failed. Pending Sparks do not block: they are waiting on the Engine, not broken. */
    fun allHealthy(statuses: Map<String, SparkRegState>): Boolean = statuses.values.none { it.isFailure }
}
