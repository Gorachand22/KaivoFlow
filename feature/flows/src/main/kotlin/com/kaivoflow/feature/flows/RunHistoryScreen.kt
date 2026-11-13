package com.kaivoflow.feature.flows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.StepRunStatus

/** Run history: what happened, when, how long, and exactly which Step broke. */
@Composable
fun RunHistoryScreen(
    onBack: () -> Unit,
    viewModel: FlowListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = KaivoTheme.colors

    Column(Modifier.fillMaxSize().background(colors.canvasVoid)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Run history", style = androidx.compose.material3.MaterialTheme.typography.displayLarge, color = colors.inkPrimary)
        }
        if (state.recentRuns.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing has run yet. Tap Run on a Flow and it will show up here.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.recentRuns, key = { it.id }) { run ->
                    RunRow(run)
                }
            }
        }
    }
}

@Composable
private fun RunRow(run: RunRecord) {
    val colors = KaivoTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.stepCard)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                run.flowName,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = colors.inkPrimary,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(
                text = when (run.status) {
                    RunStatus.Succeeded -> "Succeeded"
                    RunStatus.PartialSuccess -> "Warnings"
                    RunStatus.Failed -> "Failed"
                    RunStatus.Canceled -> "Stopped"
                    else -> run.status.name
                },
                kind = when (run.status) {
                    RunStatus.Succeeded -> BadgeKind.Success
                    RunStatus.PartialSuccess -> BadgeKind.Warning
                    RunStatus.Failed -> BadgeKind.Error
                    else -> BadgeKind.Neutral
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${relativeTime(run.startedAt)} • ${run.durationMs / 1000}s • via ${run.trigger}",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = colors.inkSecondary,
        )
        run.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = colors.statusError,
                maxLines = 3,
            )
        }
        if (run.stepRuns.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            run.stepRuns.filter { it.status == StepRunStatus.Failed || it.status == StepRunStatus.Skipped }
                .take(3)
                .forEach { stepRun ->
                    Text(
                        "• ${stepRun.stepName}: ${stepRun.status.name.lowercase()}${stepRun.error?.let { " — $it" } ?: ""}",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = colors.inkMuted,
                        maxLines = 2,
                    )
                }
        }
    }
}
