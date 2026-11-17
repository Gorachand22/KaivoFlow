package com.kaivoflow.feature.flows.ai.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.feature.flows.ai.AiProvider
import com.kaivoflow.feature.flows.ai.TinkeringPill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AgentBuilderScreen(
    viewModel: AgentBuilderViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = KaivoTheme.colors

    var showAddTriggerDialog by remember { mutableStateOf(false) }
    var showAddToolDialog by remember { mutableStateOf(false) }
    var showAddKnowledgeDialog by remember { mutableStateOf(false) }
    var showIconPickerDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = colors.canvasVoid,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            AgentBuilderTopBar(
                agent = state.agent,
                isSaving = state.isSaving,
                onBack = onNavigateBack,
                onTestClick = { viewModel.toggleTestSheet(true) },
                onSaveClick = { viewModel.saveAgent() },
                onPublishClick = { viewModel.publishAgent() },
            )

            // Agent Header (Icon & Title)
            AgentHeaderCard(
                agent = state.agent,
                onNameChange = viewModel::updateName,
                onIconClick = { showIconPickerDialog = true },
            )

            // Segmented Tab Selector
            AgentTabRow(
                selectedTab = state.currentTab,
                onTabSelected = viewModel::setTab,
            )

            // Content per active Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (state.currentTab) {
                    AgentBuilderTab.Build -> {
                        AgentBuildTabView(
                            agent = state.agent,
                            availableModels = state.providerConfig.effectiveModels,
                            onModelChange = { provider, model -> viewModel.updateModel(provider, model) },
                            onInstructionsChange = viewModel::updateInstructions,
                            onToggleTrigger = viewModel::toggleTrigger,
                            onDeleteTrigger = viewModel::removeTrigger,
                            onAddTrigger = { showAddTriggerDialog = true },
                            onToggleTool = viewModel::toggleTool,
                            onDeleteTool = viewModel::removeTool,
                            onAddTool = { showAddToolDialog = true },
                        )
                    }
                    AgentBuilderTab.Knowledge -> {
                        AgentKnowledgeTabView(
                            knowledge = state.agent.knowledge,
                            onAddKnowledge = { showAddKnowledgeDialog = true },
                            onDeleteKnowledge = viewModel::removeKnowledge,
                        )
                    }
                    AgentBuilderTab.Sessions -> {
                        AgentSessionsTabView(
                            sessions = state.agent.sessions,
                            onTestClick = { viewModel.toggleTestSheet(true) },
                        )
                    }
                    AgentBuilderTab.Settings -> {
                        AgentSettingsTabView(
                            settings = state.agent.settings,
                            onSettingsChange = viewModel::updateSettings,
                        )
                    }
                }
            }
        }
    }

    // Dialogs & Sheets
    if (state.showTestSheet) {
        AgentTestSheet(
            isTesting = state.isTesting,
            prompt = state.testPrompt,
            output = state.testSessionOutput,
            onPromptChange = viewModel::setTestPrompt,
            onRun = { viewModel.runTestAgent(state.testPrompt) },
            onDismiss = { viewModel.toggleTestSheet(false) },
        )
    }

    if (showAddTriggerDialog) {
        AddTriggerDialog(
            onDismiss = { showAddTriggerDialog = false },
            onAdd = { trigger ->
                viewModel.addTrigger(trigger)
                showAddTriggerDialog = false
            },
        )
    }

    if (showAddToolDialog) {
        AddToolDialog(
            onDismiss = { showAddToolDialog = false },
            onAdd = { tool ->
                viewModel.addTool(tool)
                showAddToolDialog = false
            },
        )
    }

    if (showAddKnowledgeDialog) {
        AddKnowledgeDialog(
            onDismiss = { showAddKnowledgeDialog = false },
            onAdd = { title, content ->
                viewModel.addKnowledge(title, content)
                showAddKnowledgeDialog = false
            },
        )
    }

    if (showIconPickerDialog) {
        IconPickerDialog(
            currentEmoji = state.agent.iconEmoji,
            currentColorHex = state.agent.iconColorHex,
            onDismiss = { showIconPickerDialog = false },
            onSelect = { emoji, colorHex ->
                viewModel.updateIcon(emoji, colorHex)
                showIconPickerDialog = false
            },
        )
    }
}

@Composable
private fun AgentBuilderTopBar(
    agent: CustomAgent,
    isSaving: Boolean,
    onBack: () -> Unit,
    onTestClick: () -> Unit,
    onSaveClick: () -> Unit,
    onPublishClick: () -> Unit,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                KaivoIcons.icon("arrowBack"),
                contentDescription = "Back",
                tint = colors.inkPrimary,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Test Agent Action
            OutlinedButton(
                onClick = onTestClick,
                shape = KaivoShapes.chip,
                border = BorderStroke(1.dp, colors.hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.inkPrimary),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    KaivoIcons.icon("flash"),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.royalGold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Test agent",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Save / Publish Action
            Button(
                onClick = if (agent.isPublished) onSaveClick else onPublishClick,
                shape = KaivoShapes.chip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.royalGold,
                    contentColor = Color.Black,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                enabled = !isSaving,
            ) {
                Text(
                    text = if (isSaving) "Saving..." else if (agent.isPublished) "Save" else "Publish",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AgentHeaderCard(
    agent: CustomAgent,
    onNameChange: (String) -> Unit,
    onIconClick: () -> Unit,
) {
    val colors = KaivoTheme.colors
    var isEditingName by remember { mutableStateOf(false) }
    var editNameText by remember(agent.name) { mutableStateOf(agent.name) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Icon Badge
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(agent.iconColorHex))
                .clickable { onIconClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = agent.iconEmoji,
                fontSize = 26.sp,
            )
        }

        // Agent Title
        Column(modifier = Modifier.weight(1f)) {
            if (isEditingName) {
                OutlinedTextField(
                    value = editNameText,
                    onValueChange = { editNameText = it },
                    trailingIcon = {
                        IconButton(onClick = {
                            onNameChange(editNameText.trim().ifBlank { "Agent" })
                            isEditingName = false
                        }) {
                            Icon(KaivoIcons.icon("check"), contentDescription = "Done", tint = colors.royalGold)
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.royalGold,
                        unfocusedBorderColor = colors.hairline,
                        focusedTextColor = colors.inkPrimary,
                        unfocusedTextColor = colors.inkPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isEditingName = true },
                ) {
                    Text(
                        text = agent.name,
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.inkPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        KaivoIcons.icon("edit"),
                        contentDescription = "Edit name",
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = "${agent.model} • Sovereign Agent",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
            }
        }
    }
}

@Composable
private fun AgentTabRow(
    selectedTab: AgentBuilderTab,
    onTabSelected: (AgentBuilderTab) -> Unit,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 0.5.dp, color = colors.hairline)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AgentBuilderTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Column(
                modifier = Modifier
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = tab.name,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) colors.royalGold else colors.inkSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .width(36.dp)
                        .background(if (isSelected) colors.royalGold else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun AgentBuildTabView(
    agent: CustomAgent,
    availableModels: List<String>,
    onModelChange: (AiProvider, String) -> Unit,
    onInstructionsChange: (String) -> Unit,
    onToggleTrigger: (String) -> Unit,
    onDeleteTrigger: (String) -> Unit,
    onAddTrigger: () -> Unit,
    onToggleTool: (String) -> Unit,
    onDeleteTool: (String) -> Unit,
    onAddTool: () -> Unit,
) {
    val colors = KaivoTheme.colors
    var showModelMenu by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Model & Instructions Card
        item {
            SectionContainer(title = "Model & instructions") {
                // Model Picker
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Model",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = " *",
                            color = colors.royalGold,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        )
                    }
                    Text(
                        text = "What model this agent uses",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )

                    Spacer(Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KaivoShapes.medallion)
                            .background(colors.surfaceCard)
                            .border(1.dp, colors.hairline, KaivoShapes.medallion)
                            .clickable { showModelMenu = true }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    KaivoIcons.icon("sparkles"),
                                    contentDescription = null,
                                    tint = colors.royalGold,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = agent.model.ifBlank { "Select Model" },
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.inkPrimary,
                                )
                            }
                            Icon(
                                KaivoIcons.icon("dropdown"),
                                contentDescription = null,
                                tint = colors.inkSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false },
                            modifier = Modifier.background(colors.surfaceCard),
                        ) {
                            availableModels.forEach { modelName ->
                                DropdownMenuItem(
                                    text = { Text(modelName, color = colors.inkPrimary) },
                                    onClick = {
                                        onModelChange(agent.provider, modelName)
                                        showModelMenu = false
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Instructions Editor
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Instructions",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = " *",
                            color = colors.royalGold,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        )
                    }
                    Text(
                        text = "What this agent should do",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )

                    Spacer(Modifier.height(4.dp))

                    OutlinedTextField(
                        value = agent.instructions,
                        onValueChange = onInstructionsChange,
                        placeholder = {
                            Text(
                                "Describe what this agent should do, its tone, persona, constraints, and output format...",
                                color = colors.inkSecondary,
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.royalGold,
                            unfocusedBorderColor = colors.hairline,
                            focusedTextColor = colors.inkPrimary,
                            unfocusedTextColor = colors.inkPrimary,
                            cursorColor = colors.royalGold,
                        ),
                        shape = KaivoShapes.medallion,
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Triggers Card
        item {
            SectionContainer(
                title = "Triggers",
                subtitle = "Where and when this agent runs",
                actionLabel = "+ Add trigger",
                onAction = onAddTrigger,
            ) {
                if (agent.triggers.isEmpty()) {
                    Text(
                        text = "No triggers attached yet. Tap '+ Add trigger' to schedule or listen for events.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        agent.triggers.forEach { trigger ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(KaivoShapes.chip)
                                    .background(colors.canvasVoid)
                                    .border(1.dp, colors.hairline, KaivoShapes.chip)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        KaivoIcons.icon(if (trigger.type == "schedule") "schedule" else "flash"),
                                        contentDescription = null,
                                        tint = colors.royalGold,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Column {
                                        Text(
                                            text = trigger.name,
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.inkPrimary,
                                        )
                                        if (trigger.description.isNotBlank()) {
                                            Text(
                                                text = trigger.description,
                                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                                color = colors.inkSecondary,
                                            )
                                        }
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = trigger.isEnabled,
                                        onCheckedChange = { onToggleTrigger(trigger.id) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = colors.royalGold,
                                            checkedTrackColor = colors.royalGold.copy(alpha = 0.4f),
                                        ),
                                    )
                                    IconButton(onClick = { onDeleteTrigger(trigger.id) }) {
                                        Icon(
                                            KaivoIcons.icon("delete"),
                                            contentDescription = "Delete",
                                            tint = colors.inkSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Tools / Capabilities Card
        item {
            SectionContainer(
                title = "Tools & Capabilities",
                subtitle = "Skills, workflows, and device actions this agent can invoke",
                actionLabel = "+ Add tool",
                onAction = onAddTool,
            ) {
                if (agent.tools.isEmpty()) {
                    Text(
                        text = "No tools attached. Agent will operate purely through language reasoning.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        agent.tools.forEach { tool ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(KaivoShapes.chip)
                                    .background(colors.canvasVoid)
                                    .border(1.dp, colors.hairline, KaivoShapes.chip)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        KaivoIcons.icon("flow"),
                                        contentDescription = null,
                                        tint = colors.royalGold,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Column {
                                        Text(
                                            text = tool.name,
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.inkPrimary,
                                        )
                                        if (tool.description.isNotBlank()) {
                                            Text(
                                                text = tool.description,
                                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                                color = colors.inkSecondary,
                                            )
                                        }
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = tool.isEnabled,
                                        onCheckedChange = { onToggleTool(tool.id) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = colors.royalGold,
                                            checkedTrackColor = colors.royalGold.copy(alpha = 0.4f),
                                        ),
                                    )
                                    IconButton(onClick = { onDeleteTool(tool.id) }) {
                                        Icon(
                                            KaivoIcons.icon("delete"),
                                            contentDescription = "Delete",
                                            tint = colors.inkSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentKnowledgeTabView(
    knowledge: List<AgentKnowledgeItem>,
    onAddKnowledge: () -> Unit,
    onDeleteKnowledge: (String) -> Unit,
) {
    val colors = KaivoTheme.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Knowledge base",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.inkPrimary,
                    )
                    Text(
                        text = "Context documents and user reference materials",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )
                }

                Button(
                    onClick = onAddKnowledge,
                    shape = KaivoShapes.chip,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.royalGold,
                        contentColor = Color.Black,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("+ Add document", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (knowledge.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No knowledge documents added yet.\nTap '+ Add document' to give this agent custom reference data.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                }
            }
        } else {
            items(knowledge) { item ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(KaivoShapes.medallion)
                        .background(colors.surfaceCard)
                        .border(1.dp, colors.hairline, KaivoShapes.medallion)
                        .padding(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = item.title,
                                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.inkPrimary,
                            )
                            IconButton(onClick = { onDeleteKnowledge(item.id) }) {
                                Icon(
                                    KaivoIcons.icon("delete"),
                                    contentDescription = "Delete",
                                    tint = colors.inkSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Text(
                            text = item.content,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                            maxLines = 4,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSessionsTabView(
    sessions: List<AgentSession>,
    onTestClick: () -> Unit,
) {
    val colors = KaivoTheme.colors
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Execution Sessions",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.inkPrimary,
                    )
                    Text(
                        text = "Recent test runs and trigger invocations",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
                Button(
                    onClick = onTestClick,
                    shape = KaivoShapes.chip,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.royalGold,
                        contentColor = Color.Black,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Test Run", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (sessions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No execution history yet. Tap 'Test Run' to try this agent.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                }
            }
        } else {
            items(sessions) { s ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(KaivoShapes.medallion)
                        .background(colors.surfaceCard)
                        .border(1.dp, colors.hairline, KaivoShapes.medallion)
                        .padding(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(KaivoShapes.chip)
                                        .background(colors.statusSuccess.copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = s.status,
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        color = colors.statusSuccess,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Text(
                                    text = "${s.durationMs}ms",
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    color = colors.inkSecondary,
                                )
                            }
                            Text(
                                text = dateFormat.format(Date(s.timestamp)),
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                color = colors.inkSecondary,
                            )
                        }

                        Text(
                            text = "Prompt: ${s.prompt}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = s.response,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                            maxLines = 5,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSettingsTabView(
    settings: AgentSettings,
    onSettingsChange: (AgentSettings) -> Unit,
) {
    val colors = KaivoTheme.colors
    var maxIter by remember(settings.maxIterations) { mutableFloatStateOf(settings.maxIterations.toFloat()) }
    var memoryDepth by remember(settings.memoryDepth) { mutableFloatStateOf(settings.memoryDepth.toFloat()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionContainer(title = "Reasoning & limits") {
                // Max iterations
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Max iterations",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = "${maxIter.toInt()} loops",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            color = colors.royalGold,
                        )
                    }
                    Text(
                        text = "Maximum number of agent reasoning loops per execution (1–100)",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )
                    Slider(
                        value = maxIter,
                        onValueChange = {
                            maxIter = it
                            onSettingsChange(settings.copy(maxIterations = it.toInt()))
                        },
                        valueRange = 1f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.royalGold,
                            activeTrackColor = colors.royalGold,
                        ),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Reasoning Effort
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Reasoning effort",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.inkPrimary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Low", "Medium", "High").forEach { level ->
                            val isSelected = settings.reasoningEffort.equals(level, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .clip(KaivoShapes.chip)
                                    .background(if (isSelected) colors.royalGold else colors.surfaceCard)
                                    .border(1.dp, if (isSelected) colors.royalGold else colors.hairline, KaivoShapes.chip)
                                    .clickable { onSettingsChange(settings.copy(reasoningEffort = level)) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = level,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.Black else colors.inkPrimary,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Memory depth
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Session memory depth",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = "${memoryDepth.toInt()} messages",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            color = colors.royalGold,
                        )
                    }
                    Slider(
                        value = memoryDepth,
                        onValueChange = {
                            memoryDepth = it
                            onSettingsChange(settings.copy(memoryDepth = it.toInt()))
                        },
                        valueRange = 1f..50f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.royalGold,
                            activeTrackColor = colors.royalGold,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionContainer(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = KaivoTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.medallion)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.medallion)
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = title,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.inkPrimary,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )
                    }
                }

                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) {
                        Text(
                            text = actionLabel,
                            color = colors.royalGold,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentTestSheet(
    isTesting: Boolean,
    prompt: String,
    output: String?,
    onPromptChange: (String) -> Unit,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KaivoTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.canvasVoid,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Test Agent",
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.inkPrimary,
                )
                IconButton(onClick = onDismiss) {
                    Icon(KaivoIcons.icon("close"), contentDescription = "Close", tint = colors.inkSecondary)
                }
            }

            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                placeholder = { Text("Enter a test task or message for this agent...", color = colors.inkSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.royalGold,
                    unfocusedBorderColor = colors.hairline,
                    focusedTextColor = colors.inkPrimary,
                    unfocusedTextColor = colors.inkPrimary,
                    cursorColor = colors.royalGold,
                ),
                shape = KaivoShapes.medallion,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            // Animated Tinkering Pill when testing!
            if (isTesting) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TinkeringPill(text = "Tinkering...")
                }
            } else {
                Button(
                    onClick = onRun,
                    enabled = prompt.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.royalGold,
                        contentColor = Color.Black,
                    ),
                    shape = KaivoShapes.chip,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Run Test",
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Output Display
            if (output != null) {
                Text(
                    text = "Agent Output:",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.royalGold,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(KaivoShapes.medallion)
                        .background(colors.surfaceCard)
                        .border(1.dp, colors.hairline, KaivoShapes.medallion)
                        .padding(14.dp),
                ) {
                    Text(
                        text = output,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = colors.inkPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddTriggerDialog(
    onDismiss: () -> Unit,
    onAdd: (AgentTrigger) -> Unit,
) {
    val colors = KaivoTheme.colors
    val triggerOptions = listOf(
        Triple("schedule", "Scheduled Spark", "Runs at custom interval or time (e.g. 9am daily)"),
        Triple("manual", "Manual Spark", "Runs whenever manually started or triggered from UI"),
        Triple("webhook", "Webhook Listener", "Triggers when incoming HTTP payload is received"),
        Triple("phone_event", "Phone Trigger", "Triggers on battery level, wifi state, or incoming SMS"),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Trigger", color = colors.inkPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                triggerOptions.forEach { (type, name, desc) ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KaivoShapes.medallion)
                            .background(colors.surfaceCard)
                            .border(1.dp, colors.hairline, KaivoShapes.medallion)
                            .clickable {
                                onAdd(AgentTrigger(type = type, name = name, description = desc))
                            }
                            .padding(12.dp),
                    ) {
                        Column {
                            Text(name, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
                            Text(desc, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.royalGold) }
        },
        containerColor = colors.canvasVoid,
    )
}

@Composable
private fun AddToolDialog(
    onDismiss: () -> Unit,
    onAdd: (AgentTool) -> Unit,
) {
    val colors = KaivoTheme.colors
    val toolOptions = listOf(
        Triple("web_search", "Web Search", "Searches the web for live articles, news, and queries"),
        Triple("workflow", "Execute Workflow", "Triggers an existing KaivoFlow canvas workflow"),
        Triple("code", "JavaScript Code", "Runs custom JavaScript transformations or math"),
        Triple("device", "Phone Actions", "Interacts with notifications, device storage, or clipboard"),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Tool or Capability", color = colors.inkPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                toolOptions.forEach { (type, name, desc) ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KaivoShapes.medallion)
                            .background(colors.surfaceCard)
                            .border(1.dp, colors.hairline, KaivoShapes.medallion)
                            .clickable {
                                onAdd(AgentTool(type = type, name = name, description = desc))
                            }
                            .padding(12.dp),
                    ) {
                        Column {
                            Text(name, fontWeight = FontWeight.Bold, color = colors.inkPrimary)
                            Text(desc, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.royalGold) }
        },
        containerColor = colors.canvasVoid,
    )
}

@Composable
private fun AddKnowledgeDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    val colors = KaivoTheme.colors
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Knowledge Document", color = colors.inkPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    placeholder = { Text("e.g. My News Preferences") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.royalGold,
                        unfocusedBorderColor = colors.hairline,
                        focusedTextColor = colors.inkPrimary,
                        unfocusedTextColor = colors.inkPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content / Notes") },
                    placeholder = { Text("Enter reference information, policies, or facts...") },
                    minLines = 4,
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
                onClick = { if (title.isNotBlank()) onAdd(title.trim(), content.trim()) },
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = Color.Black),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.inkSecondary) }
        },
        containerColor = colors.canvasVoid,
    )
}

@Composable
private fun IconPickerDialog(
    currentEmoji: String,
    currentColorHex: Long,
    onDismiss: () -> Unit,
    onSelect: (String, Long) -> Unit,
) {
    val colors = KaivoTheme.colors
    val emojis = listOf("☀️", "🤖", "⚡", "🧠", "📰", "🔍", "📈", "🛡️", "💬", "⚙️", "🚀", "📊")
    val palette = listOf(0xFFFF8A00, 0xFF4CAF50, 0xFF2196F3, 0xFF9C27B0, 0xFFE91E63, 0xFFFFC107)

    var selectedEmoji by remember { mutableStateOf(currentEmoji) }
    var selectedColor by remember { mutableStateOf(currentColorHex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Agent Icon & Color", color = colors.inkPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Choose Emoji:", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = colors.inkPrimary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(emojis) { emoji ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (emoji == selectedEmoji) colors.royalGold.copy(alpha = 0.3f) else colors.surfaceCard)
                                .clickable { selectedEmoji = emoji },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, fontSize = 20.sp)
                        }
                    }
                }

                Text("Choose Color:", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = colors.inkPrimary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(palette) { colorHex ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(colorHex))
                                .border(
                                    2.dp,
                                    if (colorHex == selectedColor) Color.White else Color.Transparent,
                                    CircleShape,
                                )
                                .clickable { selectedColor = colorHex },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSelect(selectedEmoji, selectedColor) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = Color.Black),
            ) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = colors.inkSecondary) }
        },
        containerColor = colors.canvasVoid,
    )
}
