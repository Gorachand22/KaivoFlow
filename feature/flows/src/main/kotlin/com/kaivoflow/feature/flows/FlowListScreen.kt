package com.kaivoflow.feature.flows

import com.kaivoflow.core.engine.LifecycleState
import com.kaivoflow.feature.flows.ai.agent.AgentRepository
import com.kaivoflow.feature.flows.ai.agent.CustomAgent

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import com.kaivoflow.core.model.RunStatus
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
import com.kaivoflow.core.data.CredentialRepository
import com.kaivoflow.core.data.DataTableRepository
import com.kaivoflow.core.model.CredentialItem
import com.kaivoflow.core.model.DataTable
import com.kaivoflow.core.model.DataTableColumn
import com.kaivoflow.core.model.DataTableRow
import java.util.UUID
import javax.inject.Inject

enum class DashboardTab {
    Flows, Credentials, Variables, DataTables
}

/**
 * Flows home. Sovereign visual workflow automation dashboard.
 * Supports Organizing Flows into Spaces/Groups (Projects), managing Group Variables ($vars),
 * Credentials, Data Tables, and executing workflows.
 */
@HiltViewModel
class FlowListViewModel @Inject constructor(
    private val flowRepository: FlowRepository,
    private val runRepository: RunRepository,
    private val groupRepository: WorkflowGroupRepository,
    private val variableRepository: WorkflowVariableRepository,
    private val credentialRepository: CredentialRepository,
    private val dataTableRepository: DataTableRepository,
    private val agentRepository: AgentRepository,
    private val scheduler: FlowScheduler,
    private val activation: com.kaivoflow.core.work.alwayson.ActivationManager,
) : ViewModel() {

    val agents: StateFlow<List<CustomAgent>> = agentRepository.observeAgents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteAgent(id: String) {
        viewModelScope.launch { agentRepository.deleteAgent(id) }
    }

    fun duplicateAgent(id: String) {
        viewModelScope.launch { agentRepository.duplicateAgent(id) }
    }

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _showCreateGroupDialog = MutableStateFlow(false)
    val showCreateGroupDialog: StateFlow<Boolean> = _showCreateGroupDialog.asStateFlow()

    private val _showVariablesDialog = MutableStateFlow(false)
    val showVariablesDialog: StateFlow<Boolean> = _showVariablesDialog.asStateFlow()

    private val _showCreateVariableDialog = MutableStateFlow(false)
    val showCreateVariableDialog: StateFlow<Boolean> = _showCreateVariableDialog.asStateFlow()

    private val _movingFlowId = MutableStateFlow<String?>(null)
    val movingFlowId: StateFlow<String?> = _movingFlowId.asStateFlow()

    private val _currentTab = MutableStateFlow(DashboardTab.Flows)
    val currentTab: StateFlow<DashboardTab> = _currentTab.asStateFlow()

    private val _variableSearchQuery = MutableStateFlow("")
    val variableSearchQuery: StateFlow<String> = _variableSearchQuery.asStateFlow()

    private val _credentialSearchQuery = MutableStateFlow("")
    val credentialSearchQuery: StateFlow<String> = _credentialSearchQuery.asStateFlow()

    private val _showCreateCredentialDialog = MutableStateFlow(false)
    val showCreateCredentialDialog: StateFlow<Boolean> = _showCreateCredentialDialog.asStateFlow()

    val credentials: StateFlow<List<CredentialItem>> = combine(
        credentialRepository.observeCredentials(),
        flowRepository.observeSummaries(),
    ) { credList, flows ->
        credList.map { cred ->
            cred.copy(usedInFlowCount = 0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _dataTableSearchQuery = MutableStateFlow("")
    val dataTableSearchQuery: StateFlow<String> = _dataTableSearchQuery.asStateFlow()

    private val _showCreateDataTableDialog = MutableStateFlow(false)
    val showCreateDataTableDialog: StateFlow<Boolean> = _showCreateDataTableDialog.asStateFlow()

    private val _activeDataTable = MutableStateFlow<DataTable?>(null)
    val activeDataTable: StateFlow<DataTable?> = _activeDataTable.asStateFlow()

    val dataTables: StateFlow<List<DataTable>> = dataTableRepository.observeTables()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeTableRows: StateFlow<List<DataTableRow>> = _activeDataTable.flatMapLatest { table ->
        if (table != null) dataTableRepository.observeRows(table.id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<WorkflowGroup>> = groupRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allVariables: StateFlow<List<WorkflowVariable>> = variableRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val groupVariables: StateFlow<List<WorkflowVariable>> = _selectedGroupId.flatMapLatest { id: String? ->
        if (id != null) variableRepository.observeForGroup(id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<FlowListUiState> = combine(
        flowRepository.observeSummaries(),
        runRepository.observeRecent(50),
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

    fun setTab(tab: DashboardTab) {
        _currentTab.value = tab
    }

    fun setVariableSearchQuery(query: String) {
        _variableSearchQuery.value = query
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

    fun openCreateVariableDialog() {
        _showCreateVariableDialog.value = true
    }

    fun closeCreateVariableDialog() {
        _showCreateVariableDialog.value = false
    }

    fun openMoveFlowDialog(flowId: String) {
        _movingFlowId.value = flowId
    }

    fun closeMoveFlowDialog() {
        _movingFlowId.value = null
    }

    fun createGroup(name: String, description: String = "", color: String = "amber") {
        viewModelScope.launch {
            val group = WorkflowGroup(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                description = description.trim(),
                color = color,
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

    fun saveVariable(key: String, value: String, groupId: String? = null) {
        viewModelScope.launch {
            variableRepository.save(
                WorkflowVariable(
                    id = UUID.randomUUID().toString(),
                    groupId = null, // Unified sovereign single-user
                    key = key.trim(),
                    value = value,
                )
            )
            closeCreateVariableDialog()
        }
    }

    fun deleteVariable(variableId: String) {
        viewModelScope.launch {
            variableRepository.delete(variableId)
        }
    }

    fun setCredentialSearchQuery(query: String) { _credentialSearchQuery.value = query }
    fun openCreateCredentialDialog() { _showCreateCredentialDialog.value = true }
    fun closeCreateCredentialDialog() { _showCreateCredentialDialog.value = false }

    fun saveCredential(type: String, name: String, fields: Map<String, String>) {
        viewModelScope.launch {
            credentialRepository.save(null, type, name.trim(), fields)
            closeCreateCredentialDialog()
        }
    }

    fun deleteCredential(id: String) {
        viewModelScope.launch {
            credentialRepository.delete(id)
        }
    }

    fun setDataTableSearchQuery(query: String) { _dataTableSearchQuery.value = query }
    fun openCreateDataTableDialog() { _showCreateDataTableDialog.value = true }
    fun closeCreateDataTableDialog() { _showCreateDataTableDialog.value = false }

    fun createDataTable(name: String, description: String, columns: List<DataTableColumn>) {
        viewModelScope.launch {
            dataTableRepository.createTable(name, description, columns)
            closeCreateDataTableDialog()
        }
    }

    fun deleteDataTable(id: String) {
        viewModelScope.launch {
            dataTableRepository.deleteTable(id)
            if (_activeDataTable.value?.id == id) {
                _activeDataTable.value = null
            }
        }
    }

    fun viewDataTable(table: DataTable) {
        _activeDataTable.value = table
    }

    fun closeDataTable() {
        _activeDataTable.value = null
    }

    fun insertTableRow(tableId: String, data: Map<String, Any?>) {
        viewModelScope.launch {
            dataTableRepository.insertRow(tableId, data)
        }
    }

    fun deleteTableRow(rowId: String) {
        viewModelScope.launch {
            dataTableRepository.deleteRow(rowId)
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

    fun duplicate(id: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            flowRepository.duplicate(id)?.let { onCreated(it.id) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { activation.delete(id) }
            runCatching { scheduler.cancel(id) }
            flowRepository.delete(id)
        }
    }

    fun toggleFavorite(id: String, favorite: Boolean) {
        viewModelScope.launch { flowRepository.setFavorite(id, favorite) }
    }

    fun pause(id: String) { viewModelScope.launch { activation.pause(id) } }
    fun resume(id: String) { viewModelScope.launch { activation.resume(id) } }
    fun retryNow(id: String) { viewModelScope.launch { activation.retryNow(id) } }
    fun unpublish(id: String) { viewModelScope.launch { activation.unpublish(id, confirmed = true) } }
}

data class FlowListUiState(
    val flows: List<FlowSummary> = emptyList(),
    val recentRuns: List<RunRecord> = emptyList(),
    val lastRunByFlow: Map<String, RunRecord> = emptyMap(),
) {
    val totalExecutions: Int get() = recentRuns.size
    val failedExecutions: Int get() = recentRuns.count { it.status == RunStatus.Failed }
    val failureRate: Int get() = if (recentRuns.isEmpty()) 0 else (failedExecutions * 100) / recentRuns.size
    val avgRunTimeFormatted: String get() {
        if (recentRuns.isEmpty()) return "0s"
        val totalMs = recentRuns.sumOf { ((it.finishedAt ?: it.startedAt) - it.startedAt).coerceAtLeast(0L) }
        val avgMs = totalMs / recentRuns.size
        return if (avgMs < 1000) "${avgMs}ms" else "${avgMs / 1000}s"
    }
}

@Composable
fun FlowListScreen(
    onOpenFlow: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrompt: (String) -> Unit = {},
    onOpenAiAssistant: (String?) -> Unit = {},
    onOpenAgentBuilder: (String?) -> Unit = {},
    viewModel: FlowListViewModel = hiltViewModel(),
) {
    var publishFlowId by remember { mutableStateOf<String?>(null) }
    PublishSheetEntry(publishFlowId, onDismiss = { publishFlowId = null }, onFix = { _ -> val id = publishFlowId; publishFlowId = null; id?.let(onOpenFlow) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val selectedGroupId by viewModel.selectedGroupId.collectAsStateWithLifecycle()
    val groupVariables by viewModel.groupVariables.collectAsStateWithLifecycle()
    val allVariables by viewModel.allVariables.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()
    val credentialSearchQuery by viewModel.credentialSearchQuery.collectAsStateWithLifecycle()
    val showCreateCredential by viewModel.showCreateCredentialDialog.collectAsStateWithLifecycle()
    val dataTables by viewModel.dataTables.collectAsStateWithLifecycle()
    val dataTableSearchQuery by viewModel.dataTableSearchQuery.collectAsStateWithLifecycle()
    val showCreateDataTable by viewModel.showCreateDataTableDialog.collectAsStateWithLifecycle()
    val activeDataTable by viewModel.activeDataTable.collectAsStateWithLifecycle()
    val activeTableRows by viewModel.activeTableRows.collectAsStateWithLifecycle()
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val variableSearchQuery by viewModel.variableSearchQuery.collectAsStateWithLifecycle()
    val creating by viewModel.isCreating.collectAsStateWithLifecycle()
    val showCreateGroup by viewModel.showCreateGroupDialog.collectAsStateWithLifecycle()
    val showVariables by viewModel.showVariablesDialog.collectAsStateWithLifecycle()
    val showCreateVariable by viewModel.showCreateVariableDialog.collectAsStateWithLifecycle()
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
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Overview", style = androidx.compose.material3.MaterialTheme.typography.displayLarge, color = colors.inkPrimary)
                        Text(
                            "All the workflows, credentials, variables and data tables in your workspace",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconPill("history", "Run history", onOpenHistory)
                    Spacer(Modifier.width(8.dp))
                    IconPill("settings", "Settings", onOpenSettings)
                }

                // Top Metrics Bar (n8n sovereign stats: Prod executions, Failed, Failure rate, Run time)
                DashboardMetricsOverview(
                    totalExecutions = state.totalExecutions,
                    failedExecutions = state.failedExecutions,
                    failureRate = state.failureRate,
                    runTimeFormatted = state.avgRunTimeFormatted,
                )

                Spacer(Modifier.height(8.dp))

                // Navigation Tabs
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item {
                        DashboardTabPill(
                            label = "Workflows (${state.flows.size})",
                            isSelected = currentTab == DashboardTab.Flows,
                            onClick = { viewModel.setTab(DashboardTab.Flows) },
                        )
                    }
                    item {
                        DashboardTabPill(
                            label = "Credentials (${credentials.size})",
                            isSelected = currentTab == DashboardTab.Credentials,
                            onClick = { viewModel.setTab(DashboardTab.Credentials) },
                        )
                    }
                    item {
                        DashboardTabPill(
                            label = "Variables (${allVariables.size})",
                            isSelected = currentTab == DashboardTab.Variables,
                            onClick = { viewModel.setTab(DashboardTab.Variables) },
                        )
                    }
                    item {
                        DashboardTabPill(
                            label = "Data tables (${dataTables.size})",
                            isSelected = currentTab == DashboardTab.DataTables,
                            onClick = { viewModel.setTab(DashboardTab.DataTables) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                when (currentTab) {
                    DashboardTab.Flows -> {
                        // Transfer & Templates Action Buttons
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = KaivoTheme.spacing.md, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HomeActionButton(
                                text = "Import",
                                icon = "download",
                                onClick = onImport,
                                modifier = Modifier.weight(1f),
                            )
                            HomeActionButton(
                                text = "Ask AI",
                                icon = "sparkles",
                                onClick = { onOpenAiAssistant(null) },
                                modifier = Modifier.weight(1.05f),
                            )
                            HomeActionButton(
                                text = "Build Agents",
                                icon = "flash",
                                onClick = { onOpenAgentBuilder(null) },
                                modifier = Modifier.weight(1.2f),
                            )
                        }

                        // Main Flow List or Empty State
                        if (state.flows.isEmpty()) {
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
                                items(state.flows, key = { it.id }) { flow ->
                                    FlowRowCard(
                                        flow = flow,
                                        groupName = null,
                                        lastRun = state.lastRunByFlow[flow.id],
                                        onOpen = { onOpenFlow(flow.id) },
                                        onExport = { onExport(flow.id) },
                                        onDelete = { viewModel.delete(flow.id) },
                                        onToggleFavorite = { viewModel.toggleFavorite(flow.id, !flow.isFavorite) },
                                        onPublish = { publishFlowId = flow.id },
                                        onPause = { viewModel.pause(flow.id) },
                                        onResume = { viewModel.resume(flow.id) },
                                        onRetry = { viewModel.retryNow(flow.id) },
                                        onUnpublish = { viewModel.unpublish(flow.id) },
                                    )
                                }
                            }

                            // Bottom Bar Action: compact, original-sized centered "+ New Flow"
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                GoldButton(
                                    text = if (creating) "Creating…" else "New Flow",
                                    icon = "add",
                                    enabled = !creating,
                                    onClick = { viewModel.createFlow(onOpenFlow) },
                                )
                            }
                        }
                    }

                    DashboardTab.Credentials -> {
                        CredentialsDashboardView(
                            credentials = credentials,
                            searchQuery = credentialSearchQuery,
                            onSearchChange = viewModel::setCredentialSearchQuery,
                            onCreateCredential = viewModel::openCreateCredentialDialog,
                            onDeleteCredential = viewModel::deleteCredential,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    DashboardTab.Variables -> {
                        VariablesDashboardView(
                            variables = allVariables,
                            searchQuery = variableSearchQuery,
                            onSearchChange = viewModel::setVariableSearchQuery,
                            onCreateVariable = viewModel::openCreateVariableDialog,
                            onDeleteVariable = viewModel::deleteVariable,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    DashboardTab.DataTables -> {
                        DataTablesDashboardView(
                            tables = dataTables,
                            searchQuery = dataTableSearchQuery,
                            onSearchChange = viewModel::setDataTableSearchQuery,
                            onCreateTable = viewModel::openCreateDataTableDialog,
                            onViewTable = viewModel::viewDataTable,
                            onDeleteTable = viewModel::deleteDataTable,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Dialogs
            if (showCreateCredential) {
                CreateCredentialDialog(
                    onDismiss = viewModel::closeCreateCredentialDialog,
                    onSave = viewModel::saveCredential,
                )
            }

            if (showCreateDataTable) {
                CreateDataTableDialog(
                    onDismiss = viewModel::closeCreateDataTableDialog,
                    onCreate = viewModel::createDataTable,
                )
            }

            activeDataTable?.let { table ->
                DataTableDetailSheet(
                    table = table,
                    rows = activeTableRows,
                    onAddRow = { data -> viewModel.insertTableRow(table.id, data) },
                    onDeleteRow = viewModel::deleteTableRow,
                    onDismiss = viewModel::closeDataTable,
                )
            }

            if (showCreateGroup) {
                CreateWorkflowGroupDialog(
                    onDismiss = viewModel::closeCreateGroupDialog,
                    onCreate = viewModel::createGroup,
                )
            }

            if (showCreateVariable) {
                CreateVariableDialog(
                    onSave = { key, value ->
                        viewModel.saveVariable(key, value)
                    },
                    onDismiss = viewModel::closeCreateVariableDialog,
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
    colorName: String? = null,
    onClick: () -> Unit,
) {
    val colors = KaivoTheme.colors
    val accentColor = colorName?.let { workflowGroupColor(it) } ?: colors.royalGold
    Box(
        Modifier
            .clip(KaivoShapes.chip)
            .background(if (isSelected) accentColor else colors.surfaceCard)
            .border(1.dp, if (isSelected) accentColor else colors.hairline, KaivoShapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (colorName != null && !isSelected) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = if (isSelected) colors.canvasVoid else colors.inkSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CreateWorkflowGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String) -> Unit,
) {
    val colors = KaivoTheme.colors
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("amber") }

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
                Spacer(Modifier.height(12.dp))
                Text("Group Color", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = colors.inkMuted)
                Spacer(Modifier.height(6.dp))
                val tones = listOf("amber", "amethyst", "emerald", "sapphire", "rose", "gold")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    tones.forEach { tone ->
                        val toneColor = workflowGroupColor(tone)
                        val isToneSelected = color.equals(tone, ignoreCase = true)
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(toneColor)
                                .border(if (isToneSelected) 2.5.dp else 1.dp, if (isToneSelected) Color.White else Color.Transparent, CircleShape)
                                .clickable { color = tone },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isToneSelected) {
                                Icon(KaivoIcons.icon("check"), null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name, description, color) },
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

internal fun workflowGroupColor(colorName: String): Color = when (colorName.lowercase()) {
    "amber" -> Color(0xFFF59E0B)
    "amethyst", "purple" -> Color(0xFF8B5CF6)
    "emerald", "green" -> Color(0xFF10B981)
    "sapphire", "blue" -> Color(0xFF3B82F6)
    "rose", "red" -> Color(0xFFF43F5E)
    "gold" -> Color(0xFFD4AF37)
    else -> Color(0xFFD4AF37)
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
                    "Group variables override global variables with the same key. Use {{ \$vars.KEY }} in expressions.",
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
                        Text("No variables in this group yet.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkMuted)
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
                                Spacer(Modifier.width(6.dp))
                                VariablesUsageBadge(keyName = variable.key)
                                Spacer(Modifier.width(6.dp))
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
                Text("Add Group Variable", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newKey,
                    onValueChange = { newKey = it.uppercase() },
                    label = { Text("Key (e.g. CLIENT_ID)") },
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
                        val trimmedKey = newKey.trim().uppercase()
                        if (trimmedKey.isNotBlank()) {
                            onAddVariable(trimmedKey, newValue)
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
fun VariablesUsageBadge(
    keyName: String,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val usageSyntax = "\$vars.$keyName"

    Box(
        modifier
            .clip(KaivoShapes.chip)
            .background(Color(0xFF064E3B).copy(alpha = 0.5f))
            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.4f), KaivoShapes.chip)
            .clickable {
                clipboardManager.setText(AnnotatedString(usageSyntax))
                Toast.makeText(context, "Copied \"$usageSyntax\" to clipboard", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                KaivoIcons.icon("clipboard"),
                contentDescription = "Copy syntax",
                tint = Color(0xFF10B981),
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                usageSyntax,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = Color(0xFF34D399),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DashboardMetricsOverview(
    totalExecutions: Int,
    failedExecutions: Int,
    failureRate: Int,
    runTimeFormatted: String,
) {
    val colors = KaivoTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricItem(title = "Executions", value = "$totalExecutions", modifier = Modifier.weight(1f))
        MetricItem(title = "Failed", value = "$failedExecutions", isError = failedExecutions > 0, modifier = Modifier.weight(1f))
        MetricItem(title = "Failure rate", value = "$failureRate%", modifier = Modifier.weight(1f))
        MetricItem(title = "Run time", value = runTimeFormatted, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MetricItem(
    title: String,
    value: String,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    Box(
        modifier
            .clip(KaivoShapes.chip)
            .background(colors.surfaceElevated.copy(alpha = 0.4f))
            .border(1.dp, colors.hairline, KaivoShapes.chip)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                value,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = if (isError) colors.statusError else colors.royalGold,
            )
        }
    }
}

@Composable
private fun DashboardTabPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    Box(
        modifier
            .clip(KaivoShapes.chip)
            .background(if (isSelected) colors.royalGold.copy(alpha = 0.15f) else colors.surfaceElevated.copy(alpha = 0.3f))
            .border(1.dp, if (isSelected) colors.royalGold else colors.hairline, KaivoShapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = if (isSelected) colors.royalGold else colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VariablesDashboardView(
    variables: List<WorkflowVariable>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onCreateVariable: () -> Unit,
    onDeleteVariable: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors

    val filtered = remember(variables, searchQuery) {
        if (searchQuery.isBlank()) variables
        else variables.filter {
            it.key.contains(searchQuery, ignoreCase = true) ||
                it.value.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Search & Action Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search variables…", style = androidx.compose.material3.MaterialTheme.typography.bodySmall) },
                singleLine = true,
                leadingIcon = {
                    Icon(KaivoIcons.icon("search"), contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(KaivoIcons.icon("close"), contentDescription = "Clear", tint = colors.inkMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                shape = KaivoShapes.chip,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.royalGold,
                    unfocusedBorderColor = colors.hairline,
                    focusedTextColor = colors.inkPrimary,
                    unfocusedTextColor = colors.inkPrimary,
                ),
                modifier = Modifier.weight(1f),
            )
            GoldButton(
                text = "New",
                icon = "add",
                onClick = onCreateVariable,
            )
        }

        Spacer(Modifier.height(4.dp))

        if (filtered.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(colors.royalGold.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(KaivoIcons.icon("variable"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (searchQuery.isNotBlank()) "No variables match \"$searchQuery\"" else "No variables yet",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Variables store environment constants and secrets accessed anywhere via {{ \$vars.KEY }}.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    GoldButton(
                        text = "Create variable",
                        icon = "add",
                        onClick = onCreateVariable,
                    )
                }
            }
        } else {
            // Table Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("KEY", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = colors.royalGold, modifier = Modifier.weight(1.4f))
                Text("VALUE", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = colors.inkMuted, modifier = Modifier.weight(1.4f))
                Text("SYNTAX", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = colors.inkMuted, modifier = Modifier.weight(2f))
                Spacer(Modifier.width(36.dp))
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(filtered, key = { it.id }) { variable ->
                    VariableRowItem(
                        variable = variable,
                        onDelete = { onDeleteVariable(variable.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VariableRowItem(
    variable: WorkflowVariable,
    onDelete: () -> Unit,
) {
    val colors = KaivoTheme.colors
    val valuePreview = remember(variable.value) {
        if (variable.value.length > 20) {
            "${variable.value.take(10)}…${variable.value.takeLast(4)}"
        } else {
            variable.value
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.stepCard)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Key column
            Column(Modifier.weight(1.4f)) {
                Text(
                    variable.key,
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = colors.royalGold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Value column
            Column(Modifier.weight(1.4f).padding(horizontal = 4.dp)) {
                Text(
                    valuePreview,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Syntax pill column
            Box(Modifier.weight(2f).padding(horizontal = 4.dp)) {
                VariablesUsageBadge(keyName = variable.key)
            }

            // Delete action
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    KaivoIcons.icon("delete"),
                    contentDescription = "Delete variable",
                    tint = colors.statusError.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun CreateVariableDialog(
    onSave: (key: String, value: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KaivoTheme.colors
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }

    fun validateAndSave() {
        val trimmedKey = key.trim().uppercase()
        if (trimmedKey.isEmpty()) {
            keyError = "Key is required"
            return
        }
        if (!trimmedKey.matches(Regex("^[A-Z0-9_]+$"))) {
            keyError = "Only letters, numbers, and underscores allowed"
            return
        }
        onSave(trimmedKey, value)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceAtelier,
        shape = KaivoShapes.stepCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(KaivoIcons.icon("variable"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create Variable", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Define a variable accessible in your workflows via {{ \$vars.KEY }}.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        key = it.uppercase()
                        keyError = null
                    },
                    label = { Text("Key (e.g. CLIENT_ID)") },
                    isError = keyError != null,
                    supportingText = keyError?.let { { Text(it, color = colors.statusError) } },
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
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value") },
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
                onClick = ::validateAndSave,
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
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPublish: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onUnpublish: () -> Unit,
) {
    val colors = KaivoTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(1.dp, if (flow.isFavorite) colors.royalGold.copy(alpha = 0.5f) else colors.hairline, KaivoShapes.stepCard)
            .clickable(onClick = onOpen)
            .padding(16.dp)
            .semantics { contentDescription = "${flow.name}, ${flow.stepCount} Steps" },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(KaivoShapes.medallion)
                    .background(Brush.radialGradient(listOf(colors.royalGold.copy(alpha = 0.3f), colors.royalGold.copy(alpha = 0.08f)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(KaivoIcons.icon("bolt"), null, tint = colors.royalGold, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        flow.name,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (flow.isFavorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            KaivoIcons.icon("bookmark"),
                            contentDescription = "Pinned",
                            tint = colors.royalGold,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${flow.stepCount} Steps",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                    if (flow.lifecycle != com.kaivoflow.core.engine.LifecycleState.Draft) {
                        Spacer(Modifier.width(8.dp))
                        LifecycleChip(flow)
                    }
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

            // 3-Dot Overflow Menu
            Box {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { showMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        KaivoIcons.icon("more"),
                        contentDescription = "Options",
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.hairline, KaivoShapes.chip),
                ) {
                    // 1. Lifecycle actions (spec 4.4)
                    @Composable
                    fun item(label: String, icon: String, gold: Boolean, action: () -> Unit) {
                        DropdownMenuItem(
                            text = { Text(label, color = if (gold) colors.royalGold else colors.inkPrimary, style = androidx.compose.material3.MaterialTheme.typography.labelMedium) },
                            leadingIcon = { Icon(KaivoIcons.icon(icon), contentDescription = null, tint = if (gold) colors.royalGold else colors.inkSecondary, modifier = Modifier.size(18.dp)) },
                            onClick = { showMenu = false; action() },
                        )
                    }
                    when (flow.lifecycle) {
                        LifecycleState.Draft -> item("Publish (Run 24/7)", "play", true, onPublish)
                        LifecycleState.Published -> {
                            if ((flow.updatedAt > flow.lifecycleSince)) item("Publish changes", "play", true, onPublish)
                            item("Pause", "pause", false, onPause)
                            item("Unpublish (Stop 24/7)", "pause", false, onUnpublish)
                        }
                        LifecycleState.Paused -> {
                            item("Resume", "play", true, onResume)
                            item("Unpublish (Stop 24/7)", "pause", false, onUnpublish)
                        }
                        LifecycleState.Error -> {
                            item("Fix and retry now", "play", true, onRetry)
                            item("Unpublish (Stop 24/7)", "pause", false, onUnpublish)
                        }
                    }

                    // 2. Pin / Unpin
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (flow.isFavorite) "Unpin" else "Pin",
                                color = colors.inkPrimary,
                                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                KaivoIcons.icon("bookmark"),
                                contentDescription = null,
                                tint = if (flow.isFavorite) colors.royalGold else colors.inkSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleFavorite()
                        },
                    )

                    // 3. Export
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Export",
                                color = colors.inkPrimary,
                                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                KaivoIcons.icon("download"),
                                contentDescription = null,
                                tint = colors.royalGold,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            showMenu = false
                            onExport()
                        },
                    )

                    // 4. Delete
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Delete",
                                color = colors.statusError,
                                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                KaivoIcons.icon("delete"),
                                contentDescription = null,
                                tint = colors.statusError,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = {
                            showMenu = false
                            showDeleteConfirm = true
                        },
                    )
                }
            }
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


/**
 * Lifecycle chip (spec 4.4): Published green with a Live dot, Paused amber, Error red.
 * "Changes not live" is a cheap approximation here (edited after the last publish); the publish
 * sheet runs the exact ChangeDetector comparison.
 */
@Composable
private fun LifecycleChip(flow: FlowSummary) {
    val now = System.currentTimeMillis()
    val timeFmt = remember { java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        when (flow.lifecycle) {
            LifecycleState.Published -> {
                StatusBadge("Live", BadgeKind.Success)
                if (flow.updatedAt > flow.lifecycleSince) StatusBadge("Changes not live", BadgeKind.Gold)
            }
            LifecycleState.Paused -> StatusBadge(flow.pausedUntil?.let { "Paused, resumes ${timeFmt.format(java.util.Date(it))}" } ?: "Paused", BadgeKind.Warning)
            LifecycleState.Error -> {
                StatusBadge("Failing", BadgeKind.Error)
                flow.nextRetryAt?.takeIf { it > now }?.let {
                    val mins = ((it - now) / 60_000L).coerceAtLeast(1)
                    StatusBadge("Retrying in ${mins} min", BadgeKind.Neutral)
                }
            }
            LifecycleState.Draft -> Unit
        }
    }
}

@Composable
private fun AgentsDashboardView(
    agents: List<CustomAgent>,
    onOpenAgentBuilder: (String?) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDuplicateAgent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "AI Agents",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.inkPrimary,
                    )
                    Text(
                        text = "Dedicated autonomous agents with triggers & tools",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )
                }

                Button(
                    onClick = { onOpenAgentBuilder(null) },
                    shape = KaivoShapes.chip,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.royalGold,
                        contentColor = Color.Black,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Icon(
                        KaivoIcons.icon("add"),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Build Agent",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        if (agents.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "🤖",
                            fontSize = 40.sp,
                        )
                        Text(
                            text = "No custom agents yet",
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = "Build dedicated autonomous agents that run schedules, search web, and use custom tools.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onOpenAgentBuilder(null) },
                            shape = KaivoShapes.chip,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.royalGold,
                                contentColor = Color.Black,
                            ),
                        ) {
                            Text("Build First Agent", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            items(agents, key = { it.id }) { agent ->
                AgentCard(
                    agent = agent,
                    onClick = { onOpenAgentBuilder(agent.id) },
                    onDelete = { onDeleteAgent(agent.id) },
                    onDuplicate = { onDuplicateAgent(agent.id) },
                )
            }
        }
    }
}

@Composable
private fun AgentCard(
    agent: CustomAgent,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    val colors = KaivoTheme.colors
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.medallion)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.medallion)
            .clickable { onClick() }
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Icon Badge
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(Color(agent.iconColorHex)),
                contentAlignment = Alignment.Center,
            ) {
                Text(agent.iconEmoji, fontSize = 24.sp)
            }

            // Details
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = agent.name,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.inkPrimary,
                    )
                    Box(
                        modifier = Modifier
                            .clip(KaivoShapes.chip)
                            .background(if (agent.isPublished) colors.statusSuccess.copy(alpha = 0.2f) else colors.royalGold.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (agent.isPublished) "Live" else "Draft",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = if (agent.isPublished) colors.statusSuccess else colors.royalGold,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Text(
                    text = "${agent.model} • ${agent.triggers.size} Trigger(s) • ${agent.tools.size} Tool(s)",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )
            }

            // Overflow Menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        KaivoIcons.icon("moreVert"),
                        contentDescription = "Options",
                        tint = colors.inkSecondary,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(colors.surfaceCard),
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit Agent", color = colors.inkPrimary) },
                        onClick = {
                            showMenu = false
                            onClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate", color = colors.inkPrimary) },
                        onClick = {
                            showMenu = false
                            onDuplicate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = colors.statusError) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeActionButton(
    text: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 42.dp)
            .clip(KaivoShapes.pill)
            .background(colors.surfaceElevated.copy(alpha = 0.55f))
            .border(1.dp, colors.hairline, KaivoShapes.pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = KaivoIcons.icon(icon),
            contentDescription = null,
            tint = colors.inkPrimary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp,
            ),
            color = colors.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
