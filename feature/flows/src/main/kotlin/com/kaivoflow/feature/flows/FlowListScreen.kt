package com.kaivoflow.feature.flows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.kaivoflow.core.data.WorkflowGroupRepository
import com.kaivoflow.core.data.WorkflowVariableRepository
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.model.FlowTemplates
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.WorkflowGroup
import com.kaivoflow.core.model.WorkflowVariable
import com.kaivoflow.core.work.FlowScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Flows home. Sovereign visual workflow automation dashboard.
 * Supports Organizing Flows into Spaces/Groups (Projects), managing Group Variables ($vars),
 * and executing workflows.
 */
@HiltViewModel
class FlowListViewModel @Inject constructor(
    private val flowRepository: FlowRepository,
    private val runRepository: RunRepository,
    private val groupRepository: WorkflowGroupRepository,
    private val variableRepository: WorkflowVariableRepository,
    private val scheduler: FlowScheduler,
) : ViewModel() {

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _showTemplates = MutableStateFlow(false)
    val showTemplates: StateFlow<Boolean> = _showTemplates.asStateFlow()

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _showCreateGroupDialog = MutableStateFlow(false)
    val showCreateGroupDialog: StateFlow<Boolean> = _showCreateGroupDialog.asStateFlow()

    private val _showVariablesDialog = MutableStateFlow(false)
    val showVariablesDialog: StateFlow<Boolean> = _showVariablesDialog.asStateFlow()

    private val _movingFlowId = MutableStateFlow<String?>(null)
    val movingFlowId: StateFlow<String?> = _movingFlowId.asStateFlow()

    val groups: StateFlow<List<WorkflowGroup>> = groupRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val groupVariables: StateFlow<List<WorkflowVariable>> = _selectedGroupId.flatMapLatest { id: String? ->
        if (id != null) variableRepository.observeForGroup(id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun selectGroup(groupId: String?) {
        _selectedGroupId.value = groupId
    }

    fun openCreateGroupDialog() {
        _showCreateGroupDialog.value = true
    }

    fun closeCreateGroupDialog() {
        _showCreateGroupDialog.value = false
    }

    fun openVariablesDialog() {
        _showVariablesDialog.value = true
    }

    fun closeVariablesDialog() {
        _showVariablesDialog.value = false
    }

    fun openMoveFlowDialog(flowId: String) {
        _movingFlowId.value = flowId
    }

    fun closeMoveFlowDialog() {
        _movingFlowId.value = null
    }

    fun createGroup(name: String, description: String = "") {
        viewModelScope.launch {
            val group = WorkflowGroup(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                description = description.trim(),
            )
            groupRepository.save(group)
            _selectedGroupId.value = group.id
            closeCreateGroupDialog()
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            flowRepository.observeSummaries().first().filter { it.groupId == groupId }.forEach {
                flowRepository.updateGroup(it.id, null)
            }
            groupRepository.delete(groupId)
            if (_selectedGroupId.value == groupId) {
                _selectedGroupId.value = null
            }
        }
    }

    fun moveFlowToGroup(flowId: String, groupId: String?) {
        viewModelScope.launch {
            flowRepository.updateGroup(flowId, groupId)
            closeMoveFlowDialog()
        }
    }

    fun saveVariable(key: String, value: String) {
        val groupId = _selectedGroupId.value ?: return
        viewModelScope.launch {
            variableRepository.save(
                WorkflowVariable(
                    id = UUID.randomUUID().toString(),
                    groupId = groupId,
                    key = key.trim(),
                    value = value,
                )
            )
        }
    }

    fun deleteVariable(variableId: String) {
        viewModelScope.launch {
            variableRepository.delete(variableId)
        }
    }

    fun createFlow(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _isCreating.value = true
            val groupId = _selectedGroupId.value
            val flow = flowRepository.createEmpty("New Flow").copy(groupId = groupId)
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
                val flow = template.build().copy(groupId = _selectedGroupId.value)
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
    onOpenPrompt: (String) -> Unit = {},
    viewModel: FlowListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val selectedGroupId by viewModel.selectedGroupId.collectAsStateWithLifecycle()
    val groupVariables by viewModel.groupVariables.collectAsStateWithLifecycle()
    val creating by viewModel.isCreating.collectAsStateWithLifecycle()
    val showTemplates by viewModel.showTemplates.collectAsStateWithLifecycle()
    val showCreateGroup by viewModel.showCreateGroupDialog.collectAsStateWithLifecycle()
    val showVariables by viewModel.showVariablesDialog.collectAsStateWithLifecycle()
    val movingFlowId by viewModel.movingFlowId.collectAsStateWithLifecycle()
    val colors = KaivoTheme.colors

    val filteredFlows = remember(state.flows, selectedGroupId) {
        if (selectedGroupId == null) {
            state.flows
        } else {
            state.flows.filter { it.groupId == selectedGroupId }
        }
    }

    val selectedGroup = remember(groups, selectedGroupId) {
        groups.firstOrNull { it.id == selectedGroupId }
    }

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
                // Top Header Row
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("KaivoFlow", style = androidx.compose.material3.MaterialTheme.typography.displayLarge, color = colors.inkPrimary)
                        Text(
                            if (filteredFlows.isEmpty()) "Your automations live here" else "${filteredFlows.size} Flows",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = colors.inkSecondary,
                        )
                    }
                    IconPill("history", "Run history", onOpenHistory)
                    Spacer(Modifier.width(8.dp))
                    IconPill("settings", "Settings", onOpenSettings)
                }

                // Transfer & Templates Action Buttons
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = KaivoTheme.spacing.md, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GhostButton(
                        text = stringResource(R.string.transfer_import_button),
                        icon = "download",
                        onClick = onImport,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        text = "Templates",
                        icon = "sparkles",
                        onClick = { viewModel.showTemplates() },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Group / Spaces Filter Chips
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item {
                        GroupFilterChip(
                            label = "All Flows (${state.flows.size})",
                            isSelected = selectedGroupId == null,
                            onClick = { viewModel.selectGroup(null) },
                        )
                    }
                    items(groups, key = { it.id }) { group ->
                        val count = state.flows.count { it.groupId == group.id }
                        GroupFilterChip(
                            label = "${group.name} ($count)",
                            isSelected = selectedGroupId == group.id,
                            onClick = { viewModel.selectGroup(group.id) },
                        )
                    }
                    item {
                        Box(
                            Modifier
                                .clip(KaivoShapes.chip)
                                .background(colors.surfaceElevated.copy(alpha = 0.5f))
                                .border(1.dp, colors.hairline, KaivoShapes.chip)
                                .clickable(onClick = viewModel::openCreateGroupDialog)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(KaivoIcons.icon("add"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("New Group", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = colors.royalGold)
                            }
                        }
                    }
                }

                // Selected Group Header Banner (with Variables and Management options)
                if (selectedGroup != null) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(KaivoShapes.stepCard)
                            .background(colors.surfaceCard)
                            .border(1.dp, colors.hairline, KaivoShapes.stepCard)
                            .padding(12.dp),
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(KaivoIcons.icon("group"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    selectedGroup.name,
                                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                    color = colors.inkPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    Modifier
                                        .clip(CircleShape)
                                        .clickable { viewModel.deleteGroup(selectedGroup.id) }
                                        .padding(4.dp),
                                ) {
                                    Icon(KaivoIcons.icon("delete"), contentDescription = "Delete Group", tint = colors.statusError.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                                }
                            }
                            if (selectedGroup.description.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(selectedGroup.description, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    Modifier
                                        .clip(KaivoShapes.chip)
                                        .background(colors.surfaceElevated)
                                        .border(1.dp, colors.royalGold.copy(alpha = 0.3f), KaivoShapes.chip)
                                        .clickable(onClick = viewModel::openVariablesDialog)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(KaivoIcons.icon("variable"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Variables (${groupVariables.size})", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = colors.inkPrimary)
                                    }
                                }
                            }
                        }
                    }
                }

                // Main Flow List or Empty State
                if (filteredFlows.isEmpty()) {
                    EmptyFlows(
                        isCreating = creating,
                        onCreate = { viewModel.createFlow(onOpenFlow) },
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(filteredFlows, key = { it.id }) { flow ->
                            val flowGroup = groups.firstOrNull { it.id == flow.groupId }
                            FlowRowCard(
                                flow = flow,
                                groupName = if (selectedGroupId == null) flowGroup?.name else null,
                                lastRun = state.lastRunByFlow[flow.id],
                                onOpen = { onOpenFlow(flow.id) },
                                onExport = { onExport(flow.id) },
                                onBackup = { onBackup(flow.id) },
                                onDuplicate = { viewModel.duplicate(flow.id, onOpenFlow) },
                                onMoveGroup = { viewModel.openMoveFlowDialog(flow.id) },
                                onDelete = { viewModel.delete(flow.id) },
                                onToggleFavorite = { viewModel.toggleFavorite(flow.id, !flow.isFavorite) },
                            )
                        }
                    }

                    // Bottom Bar Actions: "New Group" on Left, "+ New Flow" on Right
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GhostButton(
                            text = "New Group",
                            icon = "group",
                            onClick = viewModel::openCreateGroupDialog,
                            modifier = Modifier.weight(1f),
                        )
                        GoldButton(
                            text = if (creating) "Creating…" else "New Flow",
                            icon = "add",
                            enabled = !creating,
                            onClick = { viewModel.createFlow(onOpenFlow) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Dialogs
            if (showTemplates) {
                TemplatePickerDialog(
                    onDismiss = { viewModel.hideTemplates() },
                    onPick = { templateId -> viewModel.createFromTemplate(templateId, onOpenFlow) },
                )
            }

            if (showCreateGroup) {
                CreateWorkflowGroupDialog(
                    onDismiss = viewModel::closeCreateGroupDialog,
                    onCreate = viewModel::createGroup,
                )
            }

            if (showVariables && selectedGroup != null) {
                GroupVariablesDialog(
                    groupName = selectedGroup.name,
                    variables = groupVariables,
                    onAddVariable = viewModel::saveVariable,
                    onDeleteVariable = viewModel::deleteVariable,
                    onDismiss = viewModel::closeVariablesDialog,
                )
            }

            movingFlowId?.let { targetFlowId ->
                val targetFlow = state.flows.firstOrNull { it.id == targetFlowId }
                if (targetFlow != null) {
                    MoveFlowGroupDialog(
                        flowName = targetFlow.name,
                        currentGroupId = targetFlow.groupId,
                        groups = groups,
                        onSelectGroup = { newGroupId -> viewModel.moveFlowToGroup(targetFlowId, newGroupId) },
                        onDismiss = viewModel::closeMoveFlowDialog,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = KaivoTheme.colors
    Box(
        Modifier
            .clip(KaivoShapes.chip)
            .background(if (isSelected) colors.royalGold else colors.surfaceCard)
            .border(1.dp, if (isSelected) colors.royalGold else colors.hairline, KaivoShapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = if (isSelected) colors.canvasVoid else colors.inkSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun CreateWorkflowGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    val colors = KaivoTheme.colors
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceAtelier,
        shape = KaivoShapes.stepCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(KaivoIcons.icon("group"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("New Workflow Group", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            }
        },
        text = {
            Column {
                Text("Organize related workflows and define shared variables and credentials.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group Name") },
                    singleLine = true,
                    shape = KaivoShapes.chip,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.royalGold,
                        unfocusedBorderColor = colors.hairline,
                        focusedTextColor = colors.inkPrimary,
                        unfocusedTextColor = colors.inkPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (Optional)") },
                    shape = KaivoShapes.chip,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.royalGold,
                        unfocusedBorderColor = colors.hairline,
                        focusedTextColor = colors.inkPrimary,
                        unfocusedTextColor = colors.inkPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name, description) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = colors.canvasVoid),
                shape = KaivoShapes.chip,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = colors.inkSecondary)) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun GroupVariablesDialog(
    groupName: String,
    variables: List<WorkflowVariable>,
    onAddVariable: (String, String) -> Unit,
    onDeleteVariable: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KaivoTheme.colors
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceAtelier,
        shape = KaivoShapes.stepCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(KaivoIcons.icon("variable"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Variables • $groupName", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Store values referenced across your workflows with {{ \$vars.KEY }}.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )
                Spacer(Modifier.height(12.dp))

                if (variables.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(KaivoShapes.chip)
                            .background(colors.surfaceElevated.copy(alpha = 0.5f))
                            .padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No variables yet.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        variables.forEach { variable ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(KaivoShapes.chip)
                                    .background(colors.surfaceElevated)
                                    .border(1.dp, colors.hairline, KaivoShapes.chip)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(variable.key, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.royalGold)
                                    Text(
                                        variable.value,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                        color = colors.inkSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Box(
                                    Modifier
                                        .clip(CircleShape)
                                        .clickable { onDeleteVariable(variable.id) }
                                        .padding(4.dp),
                                ) {
                                    Icon(KaivoIcons.icon("delete"), contentDescription = "Delete", tint = colors.statusError, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Add Variable", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newKey,
                    onValueChange = { newKey = it },
                    label = { Text("Key (e.g. API_URL)") },
                    singleLine = true,
                    shape = KaivoShapes.chip,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.royalGold,
                        unfocusedBorderColor = colors.hairline,
                        focusedTextColor = colors.inkPrimary,
                        unfocusedTextColor = colors.inkPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = newValue,
                    onValueChange = { newValue = it },
                    label = { Text("Value") },
                    singleLine = true,
                    shape = KaivoShapes.chip,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.royalGold,
                        unfocusedBorderColor = colors.hairline,
                        focusedTextColor = colors.inkPrimary,
                        unfocusedTextColor = colors.inkPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (newKey.isNotBlank()) {
                            onAddVariable(newKey, newValue)
                            newKey = ""
                            newValue = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = colors.canvasVoid),
                    shape = KaivoShapes.chip,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("+ Add Variable")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = colors.inkPrimary)) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun MoveFlowGroupDialog(
    flowName: String,
    currentGroupId: String?,
    groups: List<WorkflowGroup>,
    onSelectGroup: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KaivoTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceAtelier,
        shape = KaivoShapes.stepCard,
        title = {
            Text("Move \"$flowName\" to Group", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Option: No Group
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(KaivoShapes.chip)
                        .background(if (currentGroupId == null) colors.surfaceCard else colors.surfaceElevated)
                        .border(1.dp, if (currentGroupId == null) colors.royalGold else colors.hairline, KaivoShapes.chip)
                        .clickable { onSelectGroup(null) }
                        .padding(12.dp),
                ) {
                    Text("No Group (All Flows)", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
                }

                // Groups
                groups.forEach { group ->
                    val isSelected = currentGroupId == group.id
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(KaivoShapes.chip)
                            .background(if (isSelected) colors.surfaceCard else colors.surfaceElevated)
                            .border(1.dp, if (isSelected) colors.royalGold else colors.hairline, KaivoShapes.chip)
                            .clickable { onSelectGroup(group.id) }
                            .padding(12.dp),
                    ) {
                        Text(group.name, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = colors.inkSecondary)) {
                Text("Cancel")
            }
        },
    )
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
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
    groupName: String?,
    lastRun: RunRecord?,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onBackup: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveGroup: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val colors = KaivoTheme.colors
    var showDeleteConfirm by remember { mutableStateOf(false) }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${flow.stepCount} Steps",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                    if (groupName != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "• $groupName",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = colors.royalGold,
                        )
                    }
                }
            }
            Box(Modifier.clip(CircleShape).clickable(onClick = onToggleFavorite).padding(6.dp)) {
                Icon(
                    KaivoIcons.icon("bookmark"),
                    if (flow.isFavorite) "Remove from favourites" else "Add to favourites",
                    tint = if (flow.isFavorite) colors.royalGold else colors.inkMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box(Modifier.clip(CircleShape).clickable(onClick = onMoveGroup).padding(6.dp)) {
                Icon(
                    KaivoIcons.icon("group"),
                    "Move to Group",
                    tint = colors.inkMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box(Modifier.clip(CircleShape).clickable(onClick = onDuplicate).padding(6.dp)) {
                Icon(
                    KaivoIcons.icon("clipboard"),
                    "Duplicate Flow",
                    tint = colors.inkMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box(Modifier.clip(CircleShape).clickable(onClick = { showDeleteConfirm = true }).padding(6.dp)) {
                Icon(
                    KaivoIcons.icon("delete"),
                    "Delete Flow",
                    tint = colors.statusError.copy(alpha = 0.8f),
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
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Flow?", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkPrimary) },
            text = { Text("This will permanently remove \"${flow.name}\".", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("Delete", color = colors.statusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = colors.inkSecondary)
                }
            },
            containerColor = colors.surfaceAtelier,
        )
    }
}

@Composable
private fun EmptyFlows(isCreating: Boolean, onCreate: () -> Unit) {
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
            Icon(KaivoIcons.icon("bolt"), null, tint = colors.royalGold, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Automate your phone",
            style = androidx.compose.material3.MaterialTheme.typography.displayLarge,
            color = colors.inkPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Build automated flows Step by Step with sovereign local power.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(24.dp))
        GoldButton(text = if (isCreating) "Creating…" else "New Flow", icon = "add", enabled = !isCreating, onClick = onCreate)
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
