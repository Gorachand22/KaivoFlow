package com.kaivoflow.core.design

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Motion tokens. Durations come from [MotionTokenValues] (pure, tested); this object adds the
 * Compose easings and springs. Call sites never write a millisecond literal.
 */
object KaivoMotionTokens {
    // Easings
    val silk: Easing = FastOutSlowInEasing
    val flowing: Easing = KaivoEaseFlowing
    /** Emphasized decelerate: arrivals and settles. */
    val settle: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Accelerate: exits leave faster than they arrive. */
    val exit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    // Springs
    val pressSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 800f)
    val settleSpring = spring<Float>(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)
    val ringSpring = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)

    // Scalars
    const val pressScale = 0.97f
    const val settlePeak = 1.04f
    val entranceRise = 12.dp
    val shakeAmplitude = 7.dp
    /** Glow strengths (alpha of gold). Glow is drawn as stacked low-alpha strokes, never blur. */
    const val glowRest = 0.0f
    const val glowPress = 0.35f
    const val glowRunning = 0.55f
    val glowRadius = 14.dp
}

/** `tween` that honours the policy: instant when animations are off, scaled when Subtle. */
fun <T> MotionPolicy.tweenSpec(baseMs: Int, easing: Easing = KaivoMotionTokens.silk, delayMs: Int = 0): FiniteAnimationSpec<T> =
    if (!animationsEnabled) snap() else tween(durationMillis = ms(baseMs), delayMillis = delayMs, easing = easing)

fun MotionPolicy.floatSpring(spec: FiniteAnimationSpec<Float>): FiniteAnimationSpec<Float> =
    if (!animationsEnabled) snap() else spec

val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy() }

/** Lets Settings read and change the owner's motion level. Provided by KaivoTheme. */
@Immutable
class MotionController(val level: MotionLevel, val setLevel: (MotionLevel) -> Unit)

val LocalMotionController = staticCompositionLocalOf { MotionController(MotionLevel.Full) { } }

/** The owner's choice, stored next to the other app preferences ("kaivo.*" SharedPreferences). */
object MotionPrefs {
    private const val FILE = "kaivo.motion"
    private const val KEY_LEVEL = "level"

    fun read(context: Context): MotionLevel = runCatching {
        MotionLevel.fromName(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LEVEL, null))
    }.getOrDefault(MotionLevel.Full)

    fun write(context: Context, level: MotionLevel) {
        runCatching {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_LEVEL, level.name).apply()
        }
    }
}

internal fun readDeviceProfile(context: Context): DeviceProfile {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return DeviceProfile(
        isLowRamDevice = am?.isLowRamDevice ?: false,
        memoryClassMb = am?.memoryClass ?: 256,
        sdkInt = Build.VERSION.SDK_INT,
        powerSave = pm?.isPowerSaveMode ?: false,
    )
}

/** Convenience for composables: `val policy = rememberMotionPolicy()`. */
@Composable
fun rememberMotionPolicy(): MotionPolicy = LocalMotionPolicy.current
