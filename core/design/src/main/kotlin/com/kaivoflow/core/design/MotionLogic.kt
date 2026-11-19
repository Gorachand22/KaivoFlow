package com.kaivoflow.core.design

import com.kaivoflow.core.model.StepRunStatus
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/*
 * Pure motion logic: no Android, no Compose. Everything that decides WHETHER and HOW MUCH to
 * animate lives here so it can be unit-tested on the plain JVM. The Compose layer (Motion.kt,
 * MotionModifiers.kt, MotionComponents.kt) only turns these decisions into pixels.
 */

/** The owner's setting. Full = everything, Subtle = shorter, no ambient loops, Off = none. */
enum class MotionLevel {
    Full, Subtle, Off;

    companion object {
        fun fromName(name: String?): MotionLevel = values().firstOrNull { it.name == name } ?: Full
    }
}

/** Device capability bucket. Low halves effect density and drops decorative loops. */
enum class QualityTier { High, Balanced, Low }

data class DeviceProfile(
    val isLowRamDevice: Boolean,
    val memoryClassMb: Int,
    val sdkInt: Int,
    val powerSave: Boolean,
)

object MotionDecisions {
    /** System "remove animations" always wins; battery saver can only lower Full to Subtle. */
    fun effectiveLevel(user: MotionLevel, systemAnimationsOff: Boolean, powerSave: Boolean): MotionLevel = when {
        systemAnimationsOff -> MotionLevel.Off
        powerSave && user == MotionLevel.Full -> MotionLevel.Subtle
        else -> user
    }

    fun qualityTier(p: DeviceProfile): QualityTier {
        val base = when {
            p.isLowRamDevice || p.memoryClassMb <= 128 || p.sdkInt < 28 -> QualityTier.Low
            p.memoryClassMb <= 192 -> QualityTier.Balanced
            else -> QualityTier.High
        }
        // Battery saver never raises a tier and lowers High to Balanced.
        return if (p.powerSave && base == QualityTier.High) QualityTier.Balanced else base
    }
}

/** Every "how much" knob derives from level and tier, so no call site hard-codes a budget. */
data class MotionPolicy(
    val level: MotionLevel = MotionLevel.Full,
    val tier: QualityTier = QualityTier.High,
    val haptics: Boolean = true,
) {
    val animationsEnabled: Boolean get() = level != MotionLevel.Off
    val durationScale: Float get() = when (level) {
        MotionLevel.Full -> 1f
        MotionLevel.Subtle -> 0.6f
        MotionLevel.Off -> 0f
    }

    fun ms(baseMs: Int): Int = (baseMs * durationScale).toInt()

    /** Idle breathing, aura, shimmer: decorative, so only Full on a non-Low device. */
    val ambientLoops: Boolean get() = level == MotionLevel.Full && tier != QualityTier.Low

    /** Functional loops (Run current, running ring): kept in Subtle, removed in Off. */
    val runLoops: Boolean get() = level != MotionLevel.Off

    val maxInfiniteAnimations: Int get() = when {
        level == MotionLevel.Off -> 0
        level == MotionLevel.Subtle -> 1
        tier == QualityTier.High -> 4
        tier == QualityTier.Balanced -> 3
        else -> 2
    }

    val orbsPerStream: Int get() = when {
        level != MotionLevel.Full -> 1
        tier == QualityTier.High -> 3
        tier == QualityTier.Balanced -> 2
        else -> 1
    }

    val orbTrail: Int get() = when {
        level != MotionLevel.Full -> 0
        tier == QualityTier.High -> 3
        tier == QualityTier.Balanced -> 2
        else -> 0
    }

    val maxAnimatedStreams: Int get() = when (tier) {
        QualityTier.High -> 24
        QualityTier.Balanced -> 12
        QualityTier.Low -> 6
    }

    val staggerStepMs: Long get() = when (tier) {
        QualityTier.Low -> MotionTokenValues.STAGGER_STEP_MS / 2
        else -> MotionTokenValues.STAGGER_STEP_MS
    }

    val staggerMaxItems: Int get() = when (tier) {
        QualityTier.High -> 8
        QualityTier.Balanced -> 6
        QualityTier.Low -> 4
    }

    val shimmer: Boolean get() = ambientLoops
}

/** Plain numbers shared by Motion.kt tokens and the pure logic above. */
object MotionTokenValues {
    const val INSTANT_MS = 90
    const val QUICK_MS = 160
    const val STANDARD_MS = 260
    const val EMPHASIZED_MS = 420
    const val SLOW_MS = 700
    const val STAGGER_STEP_MS = 55L
    const val STAGGER_TOTAL_CAP_MS = 360L
    const val CURRENT_PERIOD_MS = 1400
    const val BREATHING_PERIOD_MS = 2400
    const val SHIMMER_PERIOD_MS = 1300
}

object Stagger {
    /** Delay for the item at [index]: only the first [maxItems] stagger, total capped. */
    fun delayMs(index: Int, stepMs: Long, maxItems: Int, totalCapMs: Long = MotionTokenValues.STAGGER_TOTAL_CAP_MS): Long {
        if (index <= 0) return 0L
        return min(min(index, maxItems).toLong() * stepMs, totalCapMs)
    }

    /** An entrance plays once per key; recycled or re-scrolled items must not replay it. */
    fun shouldAnimate(policy: MotionPolicy, alreadyPlayed: Boolean): Boolean =
        policy.animationsEnabled && !alreadyPlayed
}

enum class StepVisual { Idle, Running, Waiting, Succeeded, Failed, Skipped, Frozen }
enum class AccentRole { None, Gold, Success, Error, Muted }
enum class StepEffect { None, Begin, Settle, Shake, Fade }

data class StepLook(
    val accent: AccentRole,
    val borderAlpha: Float,
    val contentAlpha: Float,
    val ring: Boolean,
    val glow: Boolean,
)

object StepMotion {
    fun visual(status: StepRunStatus): StepVisual = when (status) {
        StepRunStatus.Pending -> StepVisual.Idle
        StepRunStatus.Running -> StepVisual.Running
        StepRunStatus.Waiting -> StepVisual.Waiting
        StepRunStatus.Succeeded -> StepVisual.Succeeded
        StepRunStatus.Failed -> StepVisual.Failed
        StepRunStatus.Skipped -> StepVisual.Skipped
        StepRunStatus.Frozen -> StepVisual.Frozen
    }

    fun look(v: StepVisual): StepLook = when (v) {
        StepVisual.Idle -> StepLook(AccentRole.None, 0.35f, 1f, ring = false, glow = false)
        StepVisual.Running -> StepLook(AccentRole.Gold, 1f, 1f, ring = true, glow = true)
        StepVisual.Waiting -> StepLook(AccentRole.Gold, 0.6f, 1f, ring = false, glow = true)
        StepVisual.Succeeded -> StepLook(AccentRole.Success, 0.9f, 1f, ring = false, glow = false)
        StepVisual.Failed -> StepLook(AccentRole.Error, 1f, 1f, ring = false, glow = false)
        StepVisual.Skipped -> StepLook(AccentRole.Muted, 0.2f, 0.55f, ring = false, glow = false)
        StepVisual.Frozen -> StepLook(AccentRole.Gold, 0.7f, 1f, ring = false, glow = false)
    }

    /** One-shot effect for a transition. First composition (from == to) never replays an effect. */
    fun effect(from: StepVisual, to: StepVisual): StepEffect = when {
        from == to -> StepEffect.None
        to == StepVisual.Running -> StepEffect.Begin
        to == StepVisual.Succeeded -> StepEffect.Settle
        to == StepVisual.Failed -> StepEffect.Shake
        to == StepVisual.Skipped -> StepEffect.Fade
        else -> StepEffect.None
    }
}

enum class StreamVisual { Idle, Flowing, Done, Dimmed }

object StreamMotion {
    /**
     * Active wins over done. While a Run is in flight, Streams not yet reached recede (Dimmed),
     * after it ends the finished ones stay Done so the path the data took remains readable.
     */
    fun visual(streamId: String, active: Set<String>, completed: Set<String>, runInFlight: Boolean): StreamVisual = when {
        streamId in active -> StreamVisual.Flowing
        streamId in completed -> StreamVisual.Done
        runInFlight -> StreamVisual.Dimmed
        else -> StreamVisual.Idle
    }

    /** Edge chip text: "1 item", "12 items", "1.2k items". Null count shows no chip. */
    fun chipText(count: Int?): String? {
        if (count == null || count < 0) return null
        if (count == 1) return "1 item"
        if (count < 1000) return "$count items"
        val tenths = count / 100
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0) "${whole}k items" else "$whole.${frac}k items"
    }
}

object CounterMath {
    /** Short for small jumps, longer for big ones, always inside 280..900 ms. */
    fun durationMs(from: Int, to: Int): Int {
        val delta = abs(to - from)
        if (delta == 0) return 0
        val scaled = 280 + (ln(1.0 + delta) * 110.0).toInt()
        return max(280, min(900, scaled))
    }
}

object RingMath {
    fun fraction(score: Int, max: Int = 100): Float {
        if (max <= 0) return 0f
        return (score.toFloat() / max).coerceIn(0f, 1f)
    }
    fun sweepDegrees(score: Int, max: Int = 100): Float = fraction(score, max) * 360f
}
