package com.kaivoflow.feature.flows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.FlowSummary
import com.kaivoflow.core.data.RunRepository
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.model.FlowTemplates
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.work.FlowScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Flows home. Deliberately a list, not a grid: one-handed use and glanceable run status matter more
 * than density on a phone.
 */
@HiltViewModel
class FlowListViewModel @Inject constructor(
    private val flowRepository: FlowRepository,
    private val runRepository: RunRepository,
    private val scheduler: FlowScheduler,
) : ViewModel() {

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _showTemplates = MutableStateFlow(false)
    val showTemplates: StateFlow<Boolean> = _showTemplates.asStateFlow()

    val state: StateFlow<FlowListUiState> = combine(
        flowRepository.observeSummaries(),
        runRepository.observeRecent(20),
    ) { flows, runs ->
        FlowListUiState(
            flows = flows,
            recentRuns = runs,
            lastRunByFlow = runs.groupBy { it.flowId }.mapValues { it.value.first() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlowListUiState())

    init {
        viewModelScope.launch { runCatching { scheduler.rescheduleAll() } }
    }

    fun createFlow(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _isCreating.value = true
            val flow = flowRepository.createEmpty("New Flow")
            flowRepository.save(flow)
            _isCreating.value = false
            onCreated(flow.id)
        }
    }

    fun showTemplates() {
        _showTemplates.value = true
    }

    fun hideTemplates() {
        _showTemplates.value = false
    }

    fun createFromTemplate(templateId: String, onCreated: (String) -> Unit) {
        val template = FlowTemplates.byId(templateId) ?: run {
            hideTemplates()
            return
        }
        viewModelScope.launch {
            _isCreating.value = true
            try {
                val flow = template.build()
                flowRepository.save(flow)
                hideTemplates()
                onCreated(flow.id)
            } finally {
                _isCreating.value = false
            }
        }
    }

    fun duplicate(id: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            flowRepository.duplicate(id)?.let { onCreated(it.id) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            scheduler.cancel(id)
            flowRepository.delete(id)
        }
    }

    fun toggleFavorite(id: String, favorite: Boolean) {
        viewModelScope.launch { flowRepository.setFavorite(id, favorite) }
    }
}

data class FlowListUiState(
    val flows: List<FlowSummary> = emptyList(),
    val recentRuns: List<RunRecord> = emptyList(),
    val lastRunByFlow: Map<String, RunRecord> = emptyMap(),
)

@Composable
fun FlowListScreen(
    onOpenFlow: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrompt: (String) -> Unit,
    viewModel: FlowListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val creating by viewModel.isCreating.collectAsStateWithLifecycle()
    val showTemplates by viewModel.showTemplates.collectAsStateWithLifecycle()
    val colors = KaivoTheme.colors

    FlowTransferHost(onImported = onOpenFlow) { onImport, onExport, onBackup ->
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvasVoid)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("KaivoFlow", style = androidx.compose.material3.MaterialTheme.typography.displayLarge, color = colors.inkPrimary)
                    Text(
                        if (state.flows.isEmpty()) "Your automations live here" else "${state.flows.size} Flows",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                }
                IconPill("history", "Run history", onOpenHistory)
                Spacer(Modifier.width(8.dp))
                IconPill("settings", "Settings", onOpenSettings)
            }

            GhostButton(
                text = stringResource(R.string.transfer_import_button),
                onClick = onImport,
                modifier = Modifier.padding(horizontal = KaivoTheme.spacing.md),
            )
            GhostButton(
                text = "Start from a template",
                icon = "sparkles",
                onClick = { viewModel.showTemplates() },
                modifier = Modifier.padding(horizontal = KaivoTheme.spacing.md, vertical = 8.dp),
            )
            if (state.flows.isEmpty()) {
                EmptyFlows(
                    isCreating = creating,
                    onCreate = { viewModel.createFlow(onOpenFlow) },
                    onPrompt = { viewModel.createFlow(onOpenPrompt) },
                )
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(state.flows, key = { it.id }) { flow ->
                        FlowRowCard(
                            flow = flow,
                            lastRun = state.lastRunByFlow[flow.id],
                            onOpen = { onOpenFlow(flow.id) },
                            onExport = { onExport(flow.id) },
                            onBackup = { onBackup(flow.id) },
                            onDuplicate = { viewModel.duplicate(flow.id, onOpenFlow) },
                            onDelete = { viewModel.delete(flow.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(flow.id, !flow.isFavorite) },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GhostButton(
                        text = "Describe a Flow",
                        icon = "sparkles",
                        onClick = { viewModel.createFlow(onOpenPrompt) },
                        modifier = Modifier.weight(1f),
                    )
                    GoldButton(
                        text = if (creating) "Creating…" else "New Flow",
                        icon = "add",
                        enabled = !creating,
                        onClick = { viewModel.createFlow(onOpenFlow) },
                    )
                }
            }
        }
        if (showTemplates) {
            TemplatePickerDialog(
                onDismiss = { viewModel.hideTemplates() },
                onPick = { templateId -> viewModel.createFromTemplate(templateId, onOpenFlow) },
            )
        }
    }
    }
}

@Composable
private fun TemplatePickerDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val colors = KaivoTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.canvasVoid,
        title = {
            Text("Start from a template", color = colors.inkPrimary,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowTemplates.all.forEach { template ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(KaivoShapes.medallion)
                            .background(colors.surfaceCard)
                            .border(1.dp, colors.hairline, KaivoShapes.medallion)
                            .clickable { onPick(template.id) }
                            .padding(12.dp),
                    ) {
                        Text(template.name, color = colors.inkPrimary,
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(template.description, color = colors.inkSecondary,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = colors.inkSecondary)
            }
        },
    )
}

@Composable
private fun IconPill(icon: String, description: String, onClick: () -> Unit) {
    val colors = KaivoTheme.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(KaivoIcons.icon(icon), description, tint = colors.inkSecondary, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun FlowRowCard(
    flow: FlowSummary,
    lastRun: RunRecord?,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onBackup: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val colors = KaivoTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.stepCard)
            .clickable(onClick = onOpen)
            .padding(16.dp)
            .semantics { contentDescription = "${flow.name}, ${flow.stepCount} Steps" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(KaivoShapes.medallion)
                    .background(Brush.radialGradient(listOf(colors.royalGold.copy(alpha = 0.3f), colors.royalGold.copy(alpha = 0.08f)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(KaivoIcons.icon("bolt"), null, tint = colors.royalGold, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    flow.name,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${flow.stepCount} Steps",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                )
            }
            Box(Modifier.clip(CircleShape).clickable(onClick = onToggleFavorite).padding(8.dp)) {
                Icon(
                    KaivoIcons.icon("bookmark"),
                    if (flow.isFavorite) "Remove from favourites" else "Add to favourites",
                    tint = if (flow.isFavorite) colors.royalGold else colors.inkMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(KaivoTheme.spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(
                text = stringResource(R.string.transfer_export_button),
                onClick = onExport,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                text = stringResource(R.string.transfer_backup_button),
                onClick = onBackup,
                modifier = Modifier.weight(1f),
            )
        }
        if (lastRun != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(
                    text = when (lastRun.status) {
                        com.kaivoflow.core.model.RunStatus.Succeeded -> "✓ last run worked"
                        com.kaivoflow.core.model.RunStatus.PartialSuccess -> "✓ with warnings"
                        com.kaivoflow.core.model.RunStatus.Canceled -> "stopped"
                        com.kaivoflow.core.model.RunStatus.Running -> "running now"
                        else -> "last run failed"
                    },
                    kind = when (lastRun.status) {
                        com.kaivoflow.core.model.RunStatus.Succeeded -> BadgeKind.Success
                        com.kaivoflow.core.model.RunStatus.PartialSuccess -> BadgeKind.Warning
                        com.kaivoflow.core.model.RunStatus.Failed -> BadgeKind.Error
                        else -> BadgeKind.Neutral
                    },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    relativeTime(lastRun.startedAt),
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = colors.inkMuted,
                )
            }
        }
    }
}

@Composable
private fun EmptyFlows(isCreating: Boolean, onCreate: () -> Unit, onPrompt: () -> Unit) {
    val colors = KaivoTheme.colors
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(colors.velvetRose.copy(alpha = 0.35f), colors.royalGold.copy(alpha = 0.12f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(KaivoIcons.icon("sparkles"), null, tint = colors.velvetRose, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Automate your phone",
            style = androidx.compose.material3.MaterialTheme.typography.displayLarge,
            color = colors.inkPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Describe what you want to happen, or build it Step by Step.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(24.dp))
        GoldButton(text = if (isCreating) "Creating…" else "Describe a Flow", icon = "sparkles", enabled = !isCreating, onClick = onPrompt)
        Spacer(Modifier.height(10.dp))
        GhostButton(text = "Start from scratch", icon = "add", onClick = onCreate)
    }
}

internal fun relativeTime(timestamp: Long): String {
    val delta = System.currentTimeMillis() - timestamp
    return when {
        delta < 60_000 -> "just now"
        delta < 3_600_000 -> "${delta / 60_000} min ago"
        delta < 86_400_000 -> "${delta / 3_600_000} h ago"
        else -> "${delta / 86_400_000} d ago"
    }
}
