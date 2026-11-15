package com.kaivoflow.feature.canvas.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SheetDragHandle
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import com.kaivoflow.core.model.StepRun
import com.kaivoflow.core.model.StepRunStatus
import kotlinx.collections.immutable.ImmutableList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ExecutionFilter { All, Succeeded, Failed }

/**
 * Dedicated per-workflow execution history sheet, displaying past runs, start time,
 * durations, success/failure status, and expandable step-by-step execution snapshots.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowExecutionsSheet(
    flowName: String,
    runs: ImmutableList<RunRecord>,
    selectedRun: RunRecord?,
    onSelectRun: (RunRecord?) -> Unit,
    onRunNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KaivoTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var filter by remember { mutableStateOf(ExecutionFilter.All) }

    val filteredRuns = remember(runs, filter) {
        when (filter) {
            ExecutionFilter.All -> runs
            ExecutionFilter.Succeeded -> runs.filter { it.status == RunStatus.Succeeded }
            ExecutionFilter.Failed -> runs.filter { it.status == RunStatus.Failed }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceAtelier,
        dragHandle = {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SheetDragHandle()
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(colors.royalGold.copy(alpha = 0.15f))
                        .border(1.dp, colors.royalGold.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        KaivoIcons.icon("history"),
                        contentDescription = null,
                        tint = colors.royalGold,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Executions & Snapshots",
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        color = colors.inkPrimary,
                    )
                    Text(
                        "$flowName • ${runs.size} recorded runs",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                }
                GoldButton(
                    text = "Run Flow",
                    icon = "play",
                    onClick = onRunNow,
                    compact = true,
                )
            }

            Spacer(Modifier.height(14.dp))

            // Filter chips
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExecutionFilter.entries.forEach { f ->
                    val selected = filter == f
                    val count = when (f) {
                        ExecutionFilter.All -> runs.size
                        ExecutionFilter.Succeeded -> runs.count { it.status == RunStatus.Succeeded }
                        ExecutionFilter.Failed -> runs.count { it.status == RunStatus.Failed }
                    }
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(if (selected) colors.royalGold.copy(alpha = 0.2f) else colors.surfaceElevated)
                            .border(
                                1.dp,
                                if (selected) colors.royalGold.copy(alpha = 0.6f) else colors.hairline,
                                CircleShape,
                            )
                            .clickable { filter = f }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            "${f.name} ($count)",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            color = if (selected) colors.royalGold else colors.inkSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Run list or empty state
            if (filteredRuns.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            KaivoIcons.icon("history"),
                            contentDescription = null,
                            tint = colors.inkMuted,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No execution history for this filter yet.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = colors.inkSecondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap \"Run Flow\" above or trigger this flow to create a snapshot.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.inkMuted,
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filteredRuns, key = { it.id }) { run ->
                        val isExpanded = selectedRun?.id == run.id
                        ExecutionCard(
                            run = run,
                            isExpanded = isExpanded,
                            onToggle = {
                                onSelectRun(if (isExpanded) null else run)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ExecutionCard(
    run: RunRecord,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = KaivoTheme.colors
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss • dd MMM", Locale.getDefault()) }
    val timeStr = remember(run.startedAt) { timeFormat.format(Date(run.startedAt)) }

    val statusBadgeKind = when (run.status) {
        RunStatus.Succeeded -> BadgeKind.Success
        RunStatus.Failed -> BadgeKind.Error
        RunStatus.Running -> BadgeKind.Gold
        RunStatus.Canceled -> BadgeKind.Neutral
        else -> BadgeKind.Neutral
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(
                1.dp,
                if (isExpanded) colors.royalGold.copy(alpha = 0.5f) else colors.hairline,
                KaivoShapes.stepCard,
            )
            .clickable(onClick = onToggle)
            .padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status indicator icon
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when (run.status) {
                            RunStatus.Succeeded -> Color(0xFF10B981).copy(alpha = 0.15f)
                            RunStatus.Failed -> Color(0xFFEF4444).copy(alpha = 0.15f)
                            RunStatus.Running -> colors.royalGold.copy(alpha = 0.15f)
                            else -> colors.surfaceElevated
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when (run.status) {
                        RunStatus.Succeeded -> KaivoIcons.icon("check")
                        RunStatus.Failed -> KaivoIcons.icon("close")
                        RunStatus.Running -> KaivoIcons.icon("bolt")
                        else -> KaivoIcons.icon("stop")
                    },
                    contentDescription = null,
                    tint = when (run.status) {
                        RunStatus.Succeeded -> Color(0xFF34D399)
                        RunStatus.Failed -> Color(0xFFF87171)
                        RunStatus.Running -> colors.royalGold
                        else -> colors.inkMuted
                    },
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${run.trigger} Trigger",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(run.status.name, statusBadgeKind)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "$timeStr • ${run.durationMs}ms",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )
            }

            Icon(
                KaivoIcons.icon(if (isExpanded) "unfoldMore" else "unfoldMore"),
                contentDescription = null,
                tint = colors.inkMuted,
                modifier = Modifier.size(18.dp),
            )
        }

        if (run.error != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Error: ${run.error}",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = colors.velvetRose,
                maxLines = if (isExpanded) 6 else 1,
            )
        }

        // Expandable step snapshots
        AnimatedVisibility(visible = isExpanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Text(
                    "Step Execution Snapshots (${run.stepRuns.size})",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = colors.royalGold,
                )
                Spacer(Modifier.height(6.dp))

                if (run.stepRuns.isEmpty()) {
                    Text(
                        "No per-step records captured for this run.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        run.stepRuns.forEach { stepRun ->
                            StepRunSnapshotItem(stepRun = stepRun)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRunSnapshotItem(stepRun: StepRun) {
    val colors = KaivoTheme.colors
    var showOutput by remember { mutableStateOf(false) }

    val stepBadgeKind = when (stepRun.status) {
        StepRunStatus.Succeeded -> BadgeKind.Success
        StepRunStatus.Failed -> BadgeKind.Error
        StepRunStatus.Running -> BadgeKind.Gold
        StepRunStatus.Skipped -> BadgeKind.Neutral
        StepRunStatus.Frozen -> BadgeKind.Gold
        else -> BadgeKind.Neutral
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.chip)
            .background(colors.surfaceElevated.copy(alpha = 0.5f))
            .border(1.dp, colors.hairline, KaivoShapes.chip)
            .clickable { showOutput = !showOutput }
            .padding(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stepRun.stepName,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = colors.inkPrimary,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(
                "${stepRun.status.name} • ${stepRun.durationMs ?: 0}ms",
                stepBadgeKind,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Items: in ${stepRun.itemsIn} → out ${stepRun.itemsOut}",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
            Spacer(Modifier.weight(1f))
            if (stepRun.output.isNotEmpty() || stepRun.error != null) {
                Text(
                    if (showOutput) "Hide output" else "View snapshot",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = colors.royalGold,
                )
            }
        }

        if (showOutput) {
            Spacer(Modifier.height(6.dp))
            if (stepRun.error != null) {
                Text(
                    "Error: ${stepRun.error}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.velvetRose,
                )
            }
            if (stepRun.output.isNotEmpty()) {
                val previewJson = remember(stepRun.output) {
                    stepRun.output.joinToString("\n---\n") { it.json.toString() }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(KaivoShapes.chip)
                        .background(colors.surfaceAtelier)
                        .padding(8.dp)
                ) {
                    Text(
                        previewJson.take(800),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                            fontFamily = KaivoTheme.type.monoFamily,
                        ),
                        color = colors.inkPrimary,
                        maxLines = 10,
                    )
                }
            }
        }
    }
}
