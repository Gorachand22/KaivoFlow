package com.kaivoflow.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.kaivoflow.core.model.StepCategory

/**
 * "Velvet Obsidian & Champagne Gold".
 *
 * Every colour in the app comes from here — feature modules must never write a hex literal.
 * The scheme is a plain @Immutable data class rather than a Material3 ColorScheme because the
 * design system needs tokens Material does not model (canvas void, medallion tints, jewel tones).
 */
@Immutable
data class KaivoColorScheme(
    // Surfaces
    val canvasVoid: Color,
    val surfaceAtelier: Color,
    val surfaceCard: Color,
    val surfaceGlass: Color,
    val surfaceElevated: Color,
    val hairline: Color,
    // Royal accents
    val royalGold: Color,
    val royalGoldDeep: Color,
    val velvetRose: Color,
    val imperialAmethyst: Color,
    // Ink
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    // Step categories
    val stepTrigger: Color,
    val stepAi: Color,
    val stepSaas: Color,
    val stepLogic: Color,
    val stepTransform: Color,
    val stepDevice: Color,
    // Status
    val statusSuccess: Color,
    val statusWarning: Color,
    val statusError: Color,
    val isDark: Boolean,
) {
    /** The jewel tone for a Step category — medallions, Stream gradients and branch pills. */
    fun categoryColor(category: StepCategory): Color = when (category) {
        StepCategory.Trigger -> stepTrigger
        StepCategory.Ai -> stepAi
        StepCategory.Saas -> stepSaas
        StepCategory.Logic -> stepLogic
        StepCategory.Transform -> stepTransform
        StepCategory.Device -> stepDevice
    }
}

/** Royal Velvet — the default, AMOLED-ready dark theme. */
val RoyalVelvetColors = KaivoColorScheme(
    canvasVoid = Color(0xFF09080E),
    surfaceAtelier = Color(0xFF12101C),
    surfaceCard = Color(0xFF1A1728),
    surfaceGlass = Color(0xFF231F36).copy(alpha = 0.78f),
    surfaceElevated = Color(0xFF2B2642),
    hairline = Color(0x14FFFFFF),
    royalGold = Color(0xFFF3C677),
    royalGoldDeep = Color(0xFFC8963E),
    velvetRose = Color(0xFFF28BA8),
    imperialAmethyst = Color(0xFF9D7BFF),
    inkPrimary = Color(0xFFF8F6FC),
    inkSecondary = Color(0xFFB4AEC8),
    inkMuted = Color(0xFF756E8C),
    stepTrigger = Color(0xFFFFB454),
    stepAi = Color(0xFFF272B6),
    stepSaas = Color(0xFF5CA8FF),
    stepLogic = Color(0xFF3DD6A3),
    stepTransform = Color(0xFFA97DFF),
    stepDevice = Color(0xFF45D9D2),
    statusSuccess = Color(0xFF3EE0A5),
    statusWarning = Color(0xFFFFBE55),
    statusError = Color(0xFFFF6B8B),
    isDark = true,
)

/** Ivory Silk — daylight luxury. */
val IvorySilkColors = KaivoColorScheme(
    canvasVoid = Color(0xFFF7F5FA),
    surfaceAtelier = Color(0xFFFFFFFF),
    surfaceCard = Color(0xFFFDFCFE),
    surfaceGlass = Color(0xFFFFFFFF).copy(alpha = 0.86f),
    surfaceElevated = Color(0xFFF0EDF6),
    hairline = Color(0x14000000),
    royalGold = Color(0xFFB87D1E),
    royalGoldDeep = Color(0xFF8E5C0E),
    velvetRose = Color(0xFFC93B6B),
    imperialAmethyst = Color(0xFF6D4BD8),
    inkPrimary = Color(0xFF181524),
    inkSecondary = Color(0xFF56506B),
    inkMuted = Color(0xFF8A849C),
    stepTrigger = Color(0xFFC97A16),
    stepAi = Color(0xFFC93B6B),
    stepSaas = Color(0xFF2D6FD1),
    stepLogic = Color(0xFF17946E),
    stepTransform = Color(0xFF6D4BD8),
    stepDevice = Color(0xFF12857F),
    statusSuccess = Color(0xFF12905F),
    statusWarning = Color(0xFFB57708),
    statusError = Color(0xFFCF2E52),
    isDark = false,
)

/** Material 3 mapping, so Material components inherit the brand automatically. */
fun KaivoColorScheme.toMaterialColors(): androidx.compose.material3.ColorScheme =
    if (isDark) {
        androidx.compose.material3.darkColorScheme(
            primary = royalGold,
            onPrimary = Color(0xFF241A05),
            primaryContainer = royalGoldDeep.copy(alpha = 0.32f),
            onPrimaryContainer = inkPrimary,
            secondary = imperialAmethyst,
            onSecondary = Color(0xFF12092B),
            tertiary = velvetRose,
            onTertiary = Color(0xFF2A0913),
            background = canvasVoid,
            onBackground = inkPrimary,
            surface = surfaceAtelier,
            onSurface = inkPrimary,
            surfaceVariant = surfaceElevated,
            onSurfaceVariant = inkSecondary,
            outline = inkMuted.copy(alpha = 0.55f),
            outlineVariant = hairline,
            error = statusError,
            onError = Color(0xFF2B0710),
            scrim = Color(0xCC000000),
        )
    } else {
        androidx.compose.material3.lightColorScheme(
            primary = royalGold,
            onPrimary = Color.White,
            primaryContainer = royalGold.copy(alpha = 0.16f),
            onPrimaryContainer = inkPrimary,
            secondary = imperialAmethyst,
            onSecondary = Color.White,
            tertiary = velvetRose,
            onTertiary = Color.White,
            background = canvasVoid,
            onBackground = inkPrimary,
            surface = surfaceAtelier,
            onSurface = inkPrimary,
            surfaceVariant = surfaceElevated,
            onSurfaceVariant = inkSecondary,
            outline = inkMuted,
            outlineVariant = hairline,
            error = statusError,
            onError = Color.White,
            scrim = Color(0x66000000),
        )
    }

val LocalKaivoColors = staticCompositionLocalOf { RoyalVelvetColors }
