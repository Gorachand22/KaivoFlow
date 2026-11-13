package com.kaivoflow.core.design

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class KaivoThemeMode { RoyalVelvet, IvorySilk, System }

@Composable
fun KaivoTheme(
    mode: KaivoThemeMode = KaivoThemeMode.RoyalVelvet,
    forceReducedMotion: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        KaivoThemeMode.RoyalVelvet -> true
        KaivoThemeMode.IvorySilk -> false
        KaivoThemeMode.System -> isSystemInDarkTheme()
    }
    val colors = if (dark) RoyalVelvetColors else IvorySilkColors

    val context = LocalContext.current
    val systemReduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val reduceMotion = forceReducedMotion ?: systemReduceMotion

    val typography = KaivoTypography.Standard
    val materialTypography = Typography(
        displayLarge = TextStyle(
            fontFamily = typography.displayFamily, fontWeight = FontWeight.Bold,
            fontSize = typography.displayMagicSize, lineHeight = typography.displayMagicLine,
            letterSpacing = (-0.02).sp,
        ),
        titleLarge = TextStyle(
            fontFamily = typography.displayFamily, fontWeight = FontWeight.SemiBold,
            fontSize = typography.titleFlowSize, lineHeight = typography.titleFlowLine,
            letterSpacing = (-0.02).sp,
        ),
        titleMedium = TextStyle(
            fontFamily = typography.displayFamily, fontWeight = FontWeight.SemiBold,
            fontSize = typography.titleStepSize, lineHeight = typography.titleStepLine,
        ),
        bodyMedium = TextStyle(
            fontFamily = typography.bodyFamily, fontWeight = FontWeight.Normal,
            fontSize = typography.bodyStepSize, lineHeight = typography.bodyStepLine,
        ),
        labelMedium = TextStyle(
            fontFamily = typography.monoFamily, fontWeight = FontWeight.Medium,
            fontSize = typography.codeInlineSize, lineHeight = typography.codeInlineLine,
            fontFeatureSettings = "tnum",
        ),
    )

    CompositionLocalProvider(
        LocalKaivoColors provides colors,
        LocalKaivoSpacing provides KaivoSpacing(),
        LocalKaivoRadii provides KaivoRadii(),
        LocalKaivoSizes provides KaivoSizes(),
        LocalKaivoMotion provides if (reduceMotion) KaivoMotion.Reduced else KaivoMotion.Standard,
        LocalKaivoTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialColors(),
            typography = materialTypography,
            shapes = androidx.compose.material3.Shapes(
                extraSmall = KaivoShapes.chip,
                small = KaivoShapes.chip,
                medium = KaivoShapes.stepCard,
                large = KaivoShapes.sheetTop,
                extraLarge = KaivoShapes.sheetTop,
            ),
            content = content,
        )
    }
}

/** Convenience accessors used everywhere instead of `LocalKaivoColors.current`. */
object KaivoTheme {
    val colors: KaivoColorScheme
        @Composable get() = LocalKaivoColors.current
    val spacing: KaivoSpacing
        @Composable get() = LocalKaivoSpacing.current
    val radii: KaivoRadii
        @Composable get() = LocalKaivoRadii.current
    val sizes: KaivoSizes
        @Composable get() = LocalKaivoSizes.current
    val motion: KaivoMotion
        @Composable get() = LocalKaivoMotion.current
    val type: KaivoTypography
        @Composable get() = LocalKaivoTypography.current
}
