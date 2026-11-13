package com.kaivoflow.core.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Spatial scale. A strict 4dp rhythm keeps the canvas visually calm and makes every padding
 * decision reviewable.
 */
@Immutable
data class KaivoSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
)

@Immutable
data class KaivoRadii(
    val stepCard: Dp = 20.dp,
    val sheetTop: Dp = 28.dp,
    val medallion: Dp = 14.dp,
    val chip: Dp = 10.dp,
    val pill: Dp = 999.dp,
)

@Immutable
data class KaivoSizes(
    val stepCardWidth: Dp = 220.dp,
    val stepCardMinHeight: Dp = 84.dp,
    val medallion: Dp = 44.dp,
    val medallionIcon: Dp = 22.dp,
    val portDot: Dp = 12.dp,
    /** Non-negotiable: every interactive target, even a 12dp dot. */
    val touchTarget: Dp = 48.dp,
    val dragPillWidth: Dp = 36.dp,
    val dragPillHeight: Dp = 4.dp,
    val guidedStepGap: Dp = 48.dp,
    val snapGrid: Dp = 16.dp,
    val streamStroke: Dp = 2.dp,
    val liveOrb: Dp = 6.dp,
)

/**
 * Motion. One place to change how the whole app feels, and one place to hold the
 * `ANIMATOR_DURATION_SCALE == 0` reduced-motion contract.
 */
@Immutable
data class KaivoMotion(
    val springSnappy: androidx.compose.animation.core.SpringSpec<Float> =
        spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
    val springLuxurious: androidx.compose.animation.core.SpringSpec<Float> =
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
    val easeSilkDurationMs: Int = 260,
    val easeSilk: Easing = FastOutSlowInEasing,
    val stagerStepMs: Long = 55,
    val goldenOrbDurationMs: Int = 1400,
    val breathingDurationMs: Int = 2400,
    /** Set by the theme from Settings.Global.ANIMATOR_DURATION_SCALE. */
    val reduceMotion: Boolean = false,
) {
    fun <T> tween(durationMs: Int = easeSilkDurationMs): androidx.compose.animation.core.TweenSpec<T> =
        tween(durationMillis = if (reduceMotion) 0 else durationMs, easing = easeSilk)

    fun spring(spec: androidx.compose.animation.core.SpringSpec<Float>) =
        if (reduceMotion) spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessHigh) else spec

    companion object {
        val Standard = KaivoMotion()
        val Reduced = KaivoMotion(reduceMotion = true)
    }
}

/**
 * Type scale. Plus Jakarta Sans for display, Inter for body, JetBrains Mono for anything
 * numeric or code-like (tabular figures matter for latency and currency).
 *
 * The families resolve to the bundled fonts when they are present; until the font files are
 * dropped into `core/design/src/main/res/font`, the system families are used so the app builds
 * and looks correct on any device.
 */
@Immutable
data class KaivoTypography(
    val displayMagicSize: TextUnit = 24.sp,
    val displayMagicLine: TextUnit = 30.sp,
    val titleFlowSize: TextUnit = 18.sp,
    val titleFlowLine: TextUnit = 24.sp,
    val titleStepSize: TextUnit = 15.sp,
    val titleStepLine: TextUnit = 20.sp,
    val bodyStepSize: TextUnit = 13.sp,
    val bodyStepLine: TextUnit = 18.sp,
    val codeInlineSize: TextUnit = 12.sp,
    val codeInlineLine: TextUnit = 16.sp,
    val displayFamily: FontFamily = FontFamily.SansSerif,
    val bodyFamily: FontFamily = FontFamily.SansSerif,
    val monoFamily: FontFamily = FontFamily.Monospace,
) {
    val lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )

    val displayWeight = FontWeight.Bold
    val titleWeight = FontWeight.SemiBold
    val bodyWeight = FontWeight.Normal
    val labelWeight = FontWeight.Medium

    companion object {
        val Standard = KaivoTypography()
    }
}

val LocalKaivoSpacing = staticCompositionLocalOf { KaivoSpacing() }
val LocalKaivoRadii = staticCompositionLocalOf { KaivoRadii() }
val LocalKaivoSizes = staticCompositionLocalOf { KaivoSizes() }
val LocalKaivoMotion = staticCompositionLocalOf { KaivoMotion.Standard }
val LocalKaivoTypography = staticCompositionLocalOf { KaivoTypography.Standard }

object KaivoShapes {
    val stepCard = RoundedCornerShape(20.dp)
    val sheetTop = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val medallion = RoundedCornerShape(14.dp)
    val chip = RoundedCornerShape(10.dp)
    val pill = RoundedCornerShape(999.dp)
}

/** The easing used by the Golden Current and staggered materialisation. */
val KaivoEaseFlowing: Easing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)
