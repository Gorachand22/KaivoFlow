package com.kaivoflow.feature.canvas.inspector.logic

import com.kaivoflow.feature.canvas.inspector.state.InspectorPage

enum class InspectorLayoutKind { PhonePortrait, PhoneLandscape, ThreePane }

/** Chooses the inspector layout from the window size in dp (spec P2 3.2). Pure. */
object InspectorLayouts {
    const val COMPACT_WIDTH_DP = 600
    /** A wide but short window (a phone on its side) gets the side sheet, not three panes. */
    const val COMPACT_HEIGHT_DP = 480
    const val MIN_PANE_DP = 280

    fun choose(widthDp: Int, heightDp: Int): InspectorLayoutKind = when {
        widthDp < COMPACT_WIDTH_DP -> if (widthDp > heightDp && heightDp < COMPACT_HEIGHT_DP) InspectorLayoutKind.PhoneLandscape else InspectorLayoutKind.PhonePortrait
        heightDp < COMPACT_HEIGHT_DP -> InspectorLayoutKind.PhoneLandscape
        else -> InspectorLayoutKind.ThreePane
    }

    /** Side sheet width as a share of the screen. */
    const val LANDSCAPE_SHEET_FRACTION = 0.6f
}

/** Widths of the three panes in dp. Collapsed side panes are 0 wide. */
data class PaneWidths(val input: Float, val params: Float, val output: Float) {
    val total: Float get() = input + params + output

    /** Moves divider 0 (Input|Params) or 1 (Params|Output) by [delta] dp, keeping every open pane at [min] or more. */
    fun drag(divider: Int, delta: Float, min: Float = InspectorLayouts.MIN_PANE_DP.toFloat()): PaneWidths = when (divider) {
        0 -> if (input <= 0f) this else {
            val d = delta.coerceIn(min - input, params - min)
            copy(input = input + d, params = params - d)
        }
        1 -> if (output <= 0f) this else {
            val d = delta.coerceIn(-(params - min), output - min)
            copy(params = params + d, output = output - d)
        }
        else -> this
    }

    companion object {
        const val INPUT_SHARE = 0.28f
        const val OUTPUT_SHARE = 0.28f

        /**
         * Default widths for [totalDp]. When the window cannot give every open pane [min] dp, the Output pane
         * collapses first, then the Input pane (the Parameters pane always stays).
         */
        fun initial(totalDp: Float, inputOpen: Boolean = true, outputOpen: Boolean = true, min: Float = InspectorLayouts.MIN_PANE_DP.toFloat()): PaneWidths {
            var inOpen = inputOpen
            var outOpen = outputOpen
            if (inOpen && outOpen && totalDp < 3 * min) outOpen = false
            if (inOpen && !outOpen && totalDp < 2 * min) inOpen = false
            return fit(totalDp, inOpen, outOpen, min)
        }

        private fun fit(totalDp: Float, inOpen: Boolean, outOpen: Boolean, min: Float): PaneWidths {
            var i = if (inOpen) maxOf(min, totalDp * INPUT_SHARE) else 0f
            var o = if (outOpen) maxOf(min, totalDp * OUTPUT_SHARE) else 0f
            var p = totalDp - i - o
            if (p < min) { // shrink the side panes evenly, never below min
                val spare = (i - min).coerceAtLeast(0f) + (o - min).coerceAtLeast(0f)
                val need = minOf(min - p, spare)
                if (spare > 0f) {
                    val si = (i - min).coerceAtLeast(0f) / spare * need
                    val so = (o - min).coerceAtLeast(0f) / spare * need
                    i -= si; o -= so
                }
                p = totalDp - i - o
            }
            return PaneWidths(i, p, o)
        }

        /** Re-fits remembered widths to a new window width, keeping the proportions. */
        fun rescale(old: PaneWidths, totalDp: Float, min: Float = InspectorLayouts.MIN_PANE_DP.toFloat()): PaneWidths {
            if (old.total <= 0f) return initial(totalDp, true, true, min)
            val k = totalDp / old.total
            return fit(totalDp, old.input > 0f, old.output > 0f, min).let { base ->
                val i = if (old.input > 0f) (old.input * k).coerceAtLeast(min) else 0f
                val o = if (old.output > 0f) (old.output * k).coerceAtLeast(min) else 0f
                val p = totalDp - i - o
                if (p >= min) PaneWidths(i, p, o) else base
            }
        }
    }
}

/** Page navigation shared by the tab pill, swipes and the edge dwell of a drag. */
object InspectorPages {
    private val order = InspectorPage.entries

    fun next(page: InspectorPage): InspectorPage = order[(order.indexOf(page) + 1).coerceAtMost(order.lastIndex)]
    fun previous(page: InspectorPage): InspectorPage = order[(order.indexOf(page) - 1).coerceAtLeast(0)]

    /** A horizontal drag of [dragPx] (negative = finger moved left) moves to the next page past [thresholdPx]. */
    fun afterSwipe(page: InspectorPage, dragPx: Float, thresholdPx: Float): InspectorPage = when {
        dragPx <= -thresholdPx -> next(page)
        dragPx >= thresholdPx -> previous(page)
        else -> page
    }
}
