package com.kaivoflow.core.design

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * Semantic haptics. Uses the richer platform constants where they exist (API 30+) and falls back
 * to the two Compose types elsewhere. The system touch-feedback setting still applies, because
 * the calls go through View.performHapticFeedback.
 */
class KaivoHaptics internal constructor(
    private val view: View,
    private val fallback: HapticFeedback,
    private val enabled: Boolean,
) {
    /** Light tick: selection changes, a Step starting. */
    fun tick() {
        if (!enabled) return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Positive confirmation: Run succeeded, Flow published. */
    fun confirm() {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        else fallback.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** Negative: Step or Run failed. Pairs with the error shake. */
    fun reject() {
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        else fallback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

@Composable
fun rememberKaivoHaptics(): KaivoHaptics {
    val view = LocalView.current
    val fallback = LocalHapticFeedback.current
    val enabled = LocalMotionPolicy.current.haptics
    return remember(view, fallback, enabled) { KaivoHaptics(view, fallback, enabled) }
}
