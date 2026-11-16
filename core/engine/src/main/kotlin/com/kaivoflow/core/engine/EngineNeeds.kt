package com.kaivoflow.core.engine

import com.kaivoflow.core.model.Flow

/** How much a Spark type depends on the always-on Engine (spec section 3.3). */
enum class EngineNeed {
    /** Needs a live process (socket, runtime-registered receiver). */
    Required,
    /** Works without it, but the Engine makes delivery faster and more reliable. Counts as needing it. */
    Wanted,
    /** Has a no-Engine path; uses the Engine only when it is running anyway. Does not start it. */
    Optional,
    /** Delivered by the system (alarm, PendingIntent, manifest receiver) or user initiated. */
    None,
}

/** Spark type ids, mirrored from `TriggerSpecs` (core:nodes depends on this module, not the reverse). */
object SparkTypes {
    const val MANUAL = "trigger.manual"
    const val SCHEDULE = "trigger.schedule"
    const val WEBHOOK = "trigger.webhook"
    const val NOTIFICATION = "trigger.notification"
    const val SMS = "trigger.sms"
    const val SHARE = "trigger.share"
    const val BATTERY = "trigger.battery"
    const val GEOFENCE = "trigger.geofence"
    const val BLUETOOTH = "trigger.bluetooth"
    const val WIFI = "trigger.wifi"
    const val HEADSET = "trigger.headset"
    const val BOOT = "trigger.boot"
    /** Future Sparks named in spec 3.8: broadcasts only an Engine receiver can hold. */
    const val SCREEN = "trigger.screen"
    const val POWER = "trigger.power"

    val all = listOf(MANUAL, SCHEDULE, WEBHOOK, NOTIFICATION, SMS, SHARE, BATTERY, GEOFENCE, BLUETOOTH, WIFI, HEADSET, BOOT)

    fun need(type: String): EngineNeed = when (type) {
        WEBHOOK, HEADSET, SCREEN, POWER -> EngineNeed.Required
        NOTIFICATION -> EngineNeed.Wanted
        BATTERY, BLUETOOTH -> EngineNeed.Optional
        else -> EngineNeed.None
    }
}

/** A Flow as the Engine sees it: its lifecycle plus the revision that actually runs (the published one). */
data class EngineFlow(val flow: Flow, val lifecycle: LifecycleState)

data class EngineNeedReason(val flowId: String, val stepId: String, val sparkType: String, val need: EngineNeed)

data class EngineNeedsResult(
    val engineNeeded: Boolean,
    val reasons: List<EngineNeedReason>,
    /** At least one live Flow exists but none needs the Engine ("Android's own wake-ups are enough"). */
    val systemWakeupsOnly: Boolean,
    val liveFlowCount: Int,
)

object EngineNeeds {
    /**
     * The Engine runs iff a live (Published or Error) Flow has an enabled Spark that is [EngineNeed.Required]
     * or [EngineNeed.Wanted], or the user switched "Always-on mode" on. Draft and Paused Flows never count.
     */
    fun compute(flows: Collection<EngineFlow>, alwaysOnMode: Boolean = false): EngineNeedsResult {
        val live = flows.filter { it.lifecycle.isLive }
        val reasons = live.flatMap { ef ->
            ef.flow.steps.filter { it.isTrigger && !it.disabled }.mapNotNull { step ->
                val need = SparkTypes.need(step.type)
                if (need == EngineNeed.Required || need == EngineNeed.Wanted) {
                    EngineNeedReason(ef.flow.id, step.id, step.type, need)
                } else null
            }
        }
        val needed = alwaysOnMode || reasons.isNotEmpty()
        return EngineNeedsResult(
            engineNeeded = needed,
            reasons = reasons,
            systemWakeupsOnly = !needed && live.isNotEmpty(),
            liveFlowCount = live.size,
        )
    }
}
