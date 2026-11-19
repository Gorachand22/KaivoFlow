package com.kaivoflow.core.design

import com.kaivoflow.core.model.StepRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionLogicTest {
    private val phone = DeviceProfile(isLowRamDevice = false, memoryClassMb = 256, sdkInt = 34, powerSave = false)

    // ---- reduced motion and levels
    @Test fun systemAnimationsOffAlwaysWins() {
        for (user in MotionLevel.values()) {
            assertEquals(MotionLevel.Off, MotionDecisions.effectiveLevel(user, systemAnimationsOff = true, powerSave = false))
        }
    }

    @Test fun powerSaveLowersFullToSubtleButNeverRaises() {
        assertEquals(MotionLevel.Subtle, MotionDecisions.effectiveLevel(MotionLevel.Full, false, true))
        assertEquals(MotionLevel.Subtle, MotionDecisions.effectiveLevel(MotionLevel.Subtle, false, true))
        assertEquals(MotionLevel.Off, MotionDecisions.effectiveLevel(MotionLevel.Off, false, true))
    }

    @Test fun levelParsingFallsBackToFull() {
        assertEquals(MotionLevel.Subtle, MotionLevel.fromName("Subtle"))
        assertEquals(MotionLevel.Full, MotionLevel.fromName("garbage"))
        assertEquals(MotionLevel.Full, MotionLevel.fromName(null))
    }

    // ---- quality tier
    @Test fun tierSelection() {
        assertEquals(QualityTier.High, MotionDecisions.qualityTier(phone))
        assertEquals(QualityTier.Balanced, MotionDecisions.qualityTier(phone.copy(memoryClassMb = 192)))
        assertEquals(QualityTier.Low, MotionDecisions.qualityTier(phone.copy(memoryClassMb = 128)))
        assertEquals(QualityTier.Low, MotionDecisions.qualityTier(phone.copy(isLowRamDevice = true)))
        assertEquals(QualityTier.Low, MotionDecisions.qualityTier(phone.copy(sdkInt = 26)))
    }

    @Test fun powerSaveDemotesHighToBalancedOnly() {
        assertEquals(QualityTier.Balanced, MotionDecisions.qualityTier(phone.copy(powerSave = true)))
        assertEquals(QualityTier.Low, MotionDecisions.qualityTier(phone.copy(memoryClassMb = 128, powerSave = true)))
    }

    // ---- policy budgets
    @Test fun offDisablesEverythingAndZeroesDurations() {
        val p = MotionPolicy(MotionLevel.Off, QualityTier.High)
        assertFalse(p.animationsEnabled)
        assertFalse(p.runLoops)
        assertFalse(p.ambientLoops)
        assertEquals(0, p.maxInfiniteAnimations)
        assertEquals(0, p.ms(260))
    }

    @Test fun subtleKeepsRunCurrentButDropsAmbient() {
        val p = MotionPolicy(MotionLevel.Subtle, QualityTier.High)
        assertTrue(p.runLoops)
        assertFalse(p.ambientLoops)
        assertFalse(p.shimmer)
        assertEquals(1, p.orbsPerStream)
        assertEquals(0, p.orbTrail)
        assertEquals(156, p.ms(260))
    }

    @Test fun lowTierHalvesDensity() {
        val high = MotionPolicy(MotionLevel.Full, QualityTier.High)
        val low = MotionPolicy(MotionLevel.Full, QualityTier.Low)
        assertEquals(3, high.orbsPerStream)
        assertEquals(1, low.orbsPerStream)
        assertEquals(0, low.orbTrail)
        assertEquals(high.staggerStepMs / 2, low.staggerStepMs)
        assertEquals(high.maxAnimatedStreams / 4, low.maxAnimatedStreams)
        assertFalse(low.ambientLoops)
        assertTrue(low.runLoops)
        assertTrue(low.maxInfiniteAnimations < high.maxInfiniteAnimations)
    }

    // ---- stagger
    @Test fun staggerIsCappedAndNeverNegative() {
        assertEquals(0L, Stagger.delayMs(0, 55, 8))
        assertEquals(0L, Stagger.delayMs(-3, 55, 8))
        assertEquals(165L, Stagger.delayMs(3, 55, 8))
        assertEquals(360L, Stagger.delayMs(100, 55, 8)) // 8 * 55 = 440, total cap 360
        assertEquals(110L, Stagger.delayMs(100, 55, 2))
    }

    @Test fun entranceReplayRules() {
        val full = MotionPolicy()
        assertTrue(Stagger.shouldAnimate(full, alreadyPlayed = false))
        assertFalse(Stagger.shouldAnimate(full, alreadyPlayed = true))
        assertFalse(Stagger.shouldAnimate(MotionPolicy(MotionLevel.Off), alreadyPlayed = false))
    }

    // ---- step state to animation
    @Test fun everyRunStatusMapsToAVisual() {
        val expected = mapOf(
            StepRunStatus.Pending to StepVisual.Idle,
            StepRunStatus.Running to StepVisual.Running,
            StepRunStatus.Waiting to StepVisual.Waiting,
            StepRunStatus.Succeeded to StepVisual.Succeeded,
            StepRunStatus.Failed to StepVisual.Failed,
            StepRunStatus.Skipped to StepVisual.Skipped,
            StepRunStatus.Frozen to StepVisual.Frozen,
        )
        assertEquals(StepRunStatus.values().toSet(), expected.keys)
        expected.forEach { (status, visual) -> assertEquals(visual, StepMotion.visual(status)) }
    }

    @Test fun looks() {
        assertTrue(StepMotion.look(StepVisual.Running).ring)
        assertEquals(AccentRole.Error, StepMotion.look(StepVisual.Failed).accent)
        assertEquals(AccentRole.Success, StepMotion.look(StepVisual.Succeeded).accent)
        assertTrue(StepMotion.look(StepVisual.Skipped).contentAlpha < 1f)
        assertFalse(StepMotion.look(StepVisual.Idle).ring)
    }

    @Test fun effectsFireOnTransitionsOnly() {
        assertEquals(StepEffect.None, StepMotion.effect(StepVisual.Idle, StepVisual.Idle))
        assertEquals(StepEffect.None, StepMotion.effect(StepVisual.Failed, StepVisual.Failed))
        assertEquals(StepEffect.Begin, StepMotion.effect(StepVisual.Idle, StepVisual.Running))
        assertEquals(StepEffect.Settle, StepMotion.effect(StepVisual.Running, StepVisual.Succeeded))
        assertEquals(StepEffect.Settle, StepMotion.effect(StepVisual.Idle, StepVisual.Succeeded))
        assertEquals(StepEffect.Shake, StepMotion.effect(StepVisual.Running, StepVisual.Failed))
        assertEquals(StepEffect.Fade, StepMotion.effect(StepVisual.Idle, StepVisual.Skipped))
        assertEquals(StepEffect.None, StepMotion.effect(StepVisual.Succeeded, StepVisual.Idle))
    }

    // ---- streams
    @Test fun streamVisuals() {
        val active = setOf("a")
        val done = setOf("a", "b")
        assertEquals(StreamVisual.Flowing, StreamMotion.visual("a", active, done, true))
        assertEquals(StreamVisual.Done, StreamMotion.visual("b", active, done, true))
        assertEquals(StreamVisual.Dimmed, StreamMotion.visual("c", active, done, true))
        assertEquals(StreamVisual.Idle, StreamMotion.visual("c", active, done, false))
        assertEquals(StreamVisual.Done, StreamMotion.visual("b", emptySet(), done, false))
    }

    @Test fun chipText() {
        assertNull(StreamMotion.chipText(null))
        assertNull(StreamMotion.chipText(-1))
        assertEquals("0 items", StreamMotion.chipText(0))
        assertEquals("1 item", StreamMotion.chipText(1))
        assertEquals("12 items", StreamMotion.chipText(12))
        assertEquals("999 items", StreamMotion.chipText(999))
        assertEquals("1k items", StreamMotion.chipText(1000))
        assertEquals("1.2k items", StreamMotion.chipText(1234))
        assertEquals("12.5k items", StreamMotion.chipText(12_599))
    }

    // ---- counter and ring
    @Test fun counterDurationBounds() {
        assertEquals(0, CounterMath.durationMs(5, 5))
        assertTrue(CounterMath.durationMs(0, 1) in 280..900)
        assertTrue(CounterMath.durationMs(0, 100_000) <= 900)
        assertTrue(CounterMath.durationMs(0, 10) < CounterMath.durationMs(0, 1000))
        assertEquals(CounterMath.durationMs(3, 40), CounterMath.durationMs(40, 3))
    }

    @Test fun ringMath() {
        assertEquals(0f, RingMath.sweepDegrees(-5), 0.001f)
        assertEquals(180f, RingMath.sweepDegrees(50), 0.001f)
        assertEquals(360f, RingMath.sweepDegrees(250), 0.001f)
        assertEquals(0f, RingMath.fraction(10, 0), 0.001f)
    }
}
