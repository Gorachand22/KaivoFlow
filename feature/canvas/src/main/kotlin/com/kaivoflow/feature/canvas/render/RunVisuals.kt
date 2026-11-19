package com.kaivoflow.feature.canvas.render

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.AnimatedCounter
import com.kaivoflow.core.design.KaivoMotionTokens
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.StreamMotion
import com.kaivoflow.core.design.StreamVisual
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.feature.canvas.RunUiState
import com.kaivoflow.feature.canvas.StepNodeUiModel
import com.kaivoflow.feature.canvas.StreamEdgeUiModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Streams with their run-derived flags set, plus the item count to show on each finished edge. */
@Immutable
class LiveStreams(
    val streams: ImmutableList<StreamEdgeUiModel>,
    val counts: Map<String, Int>,
)

/**
 * Turns RunUiState (which the view model already maintains from RunEvents) into per-Stream
 * isActive / isComplete / isDimmed flags. This is what finally lights the Golden Current: the
 * view model fills `activeStreamIds` on StepStarted, but nothing ever copied that onto the
 * Stream models the canvas draws.
 *
 * Item counts come from the source Step's itemCount. A Step with several output ports reports
 * one total, so no chip is shown for those until per-port counts reach the UI model.
 */
fun deriveLiveStreams(
    streams: ImmutableList<StreamEdgeUiModel>,
    run: RunUiState,
    steps: ImmutableList<StepNodeUiModel>,
): LiveStreams {
    if (run.runId == null) return LiveStreams(streams, emptyMap())
    val active = run.activeStreamIds.toHashSet()
    val completed = run.completedStreamIds.toHashSet()
    val byId = steps.associateBy { it.id }
    val counts = HashMap<String, Int>()
    val mapped = streams.map { stream ->
        val raw = StreamMotion.visual(stream.id, active, completed, run.isRunning)
        // A Stream feeding a failed Step stops flowing: the current must not keep running into an error.
        val visual = if (raw == StreamVisual.Flowing && byId[stream.toStepId]?.status == StepRunStatus.Failed) {
            if (run.isRunning) StreamVisual.Dimmed else StreamVisual.Idle
        } else raw
        if (visual == StreamVisual.Done) {
            val source = byId[stream.fromStepId]
            val count = source?.itemCount
            val finished = source?.status == StepRunStatus.Succeeded || source?.status == StepRunStatus.Frozen
            if (source != null && source.outputPorts <= 1 && finished && count != null) counts[stream.id] = count
        }
        stream.copy(
            isActive = visual == StreamVisual.Flowing,
            isComplete = visual == StreamVisual.Done,
            isDimmed = visual == StreamVisual.Dimmed,
        )
    }.toImmutableList()
    return LiveStreams(mapped, counts)
}

/** Small glass chip riding a finished Stream: pops in with a spring, number ticks to its value. */
@Composable
fun StreamCountChip(count: Int, modifier: Modifier = Modifier) {
    val colors = KaivoTheme.colors
    val policy = KaivoTheme.motionPolicy
    val pop = remember { Animatable(if (policy.animationsEnabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (policy.animationsEnabled) pop.animateTo(1f, KaivoMotionTokens.settleSpring)
    }
    val description = StreamMotion.chipText(count) ?: ""
    Row(
        modifier
            .graphicsLayer {
                val p = pop.value
                alpha = p.coerceIn(0f, 1f)
                val s = 0.7f + 0.3f * p
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .background(colors.surfaceGlass)
            .border(1.dp, colors.statusSuccess.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedCounter(
            value = count,
            style = MaterialTheme.typography.labelMedium,
            color = colors.statusSuccess,
        )
        Text(
            text = if (count == 1) " item" else " items",
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkSecondary,
            maxLines = 1,
        )
    }
}
