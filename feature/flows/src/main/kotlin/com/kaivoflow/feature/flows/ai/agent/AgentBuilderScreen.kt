package com.kaivoflow.feature.flows.ai.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
    var showAddSkillDialog by remember { mutableStateOf(false) }
    var showIconPickerDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = colors.canvasVoid,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar with Test agent & Publish
            AgentBuilderTopBar(
                agent = state.agent,
                isSaving = state.isSaving,
                onBack = onNavigateBack,
                onTestClick = { viewModel.toggleTestSheet(true) },
                onSaveClick = { viewModel.saveAgent(onNavigateBack) },
                onPublishClick = { viewModel.publishAgent(onNavigateBack) },
            )

            // Agent Header (Icon & Editable Title)
            AgentHeaderCard(
                agent = state.agent,
                onNameChange = viewModel::updateName,
                onIconClick = { showIconPickerDialog = true },
                onTestClick = { viewModel.toggleTestSheet(true) },
            )

            // Tabs matching Screenshot: Build | Knowledge | Sessions | Settings
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
                            onAddSkill = { showAddSkillDialog = true },
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
                            agentModel = state.agent.model,
                            availableModels = state.providerConfig.effectiveModels,
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

    if (showAddSkillDialog) {
        AddSkillDialog(
            onDismiss = { showAddSkillDialog = false },
            onAdd = { skillName ->
                viewModel.addTool(
                    AgentTool(
                        type = "skill",
                        name = skillName,
                        description = "Custom specialized agent skill",
                        isEnabled = true,
                    )
                )
                showAddSkillDialog = false
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
            .padding(horizontal = 14.dp, vertical = 8.dp),
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
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Test Agent Action (Flask/Beaker style)
            OutlinedButton(
                onClick = onTestClick,
                shape = KaivoShapes.chip,
                border = BorderStroke(1.dp, colors.hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.inkPrimary),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "🧪 Test agent",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.inkPrimary,
                )
            }

            // Publish Action
            Button(
                onClick = if (agent.isPublished) onSaveClick else onPublishClick,
                shape = KaivoShapes.chip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.royalGold,
                    contentColor = Color.Black,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                enabled = !isSaving,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (isSaving) "Saving…" else if (agent.isPublished) "Save" else "Publish",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        KaivoIcons.icon("dropdown"),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.Black,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentHeaderCard(
    agent: CustomAgent,
    onNameChange: (String) -> Unit,
    onIconClick: () -> Unit,
    onTestClick: () -> Unit,
) {
    val colors = KaivoTheme.colors
    var isEditingName by remember { mutableStateOf(false) }
    var editNameText by remember(agent.name) { mutableStateOf(agent.name) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Orange Sun/Sparkle Badge matching Screenshot
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(agent.iconColorHex))
                .clickable { onIconClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = agent.iconEmoji,
                fontSize = 24.sp,
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
        horizontalArrangement = Arrangement.spacedBy(20.dp),
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
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.White else colors.inkSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .width(42.dp)
                        .background(if (isSelected) Color.White else Color.Transparent),
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
    onAddSkill: () -> Unit,
) {
    val colors = KaivoTheme.colors
    var showModelMenu by remember { mutableStateOf(false) }
    var webSearchEnabled by remember { mutableStateOf(false) }
    var crossSessionMemory by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Section 1: Model & instructions (matching Screenshot 1)
        item {
            CardSection(title = "Model & instructions") {
                // Model Field
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Model",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkPrimary,
                        )
                        Text(text = " *", color = colors.royalGold, fontWeight = FontWeight.Bold)
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
                            .padding(horizontal = 14.dp, vertical = 11.dp),
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
                                Box(
                                    modifier = Modifier
                                        .clip(KaivoShapes.chip)
                                        .background(colors.canvasVoid)
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = agent.provider.displayName,
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        color = colors.inkSecondary,
                                    )
                                }
                            }
                            Icon(
                                KaivoIcons.icon("dropdown"),
                                contentDescription = null,
                                tint = colors.inkSecondary,
                                modifier = Modifier.size(16.dp),
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

                // Instructions Field
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Instructions",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkPrimary,
                        )
                        Text(text = " *", color = colors.royalGold, fontWeight = FontWeight.Bold)
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
                                "Describe what this agent should do, its tone, constraints, and output format...",
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

        // Section 2: Skills (matching Screenshot 1)
        item {
            CardSection(
                title = "Skills",
                subtitle = "How this agent handles specific tasks",
            ) {
                OutlinedButton(
                    onClick = onAddSkill,
                    shape = KaivoShapes.chip,
                    border = BorderStroke(1.dp, colors.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.inkPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("+ Add skill", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Section 3: Triggers (matching Screenshot 1)
        item {
            CardSection(
                title = "Triggers",
                subtitle = "Where and when this agent runs",
                headerBadge = "🧪",
            ) {
                OutlinedButton(
                    onClick = onAddTrigger,
                    shape = KaivoShapes.chip,
                    border = BorderStroke(1.dp, colors.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.inkPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("+ Add channel", fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Schedules",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.inkPrimary,
                    )

                    // Morning news brief schedule pill
                    agent.triggers.forEach { trigger ->
                        Row(
                            modifier = Modifier
                                .clip(KaivoShapes.chip)
                                .background(colors.canvasVoid)
                                .border(1.dp, colors.hairline, KaivoShapes.chip)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                KaivoIcons.icon("schedule"),
                                contentDescription = null,
                                tint = colors.royalGold,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = trigger.name,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                color = colors.inkPrimary,
                            )
                            Icon(
                                KaivoIcons.icon("close"),
                                contentDescription = "Remove",
                                tint = colors.inkSecondary,
                                modifier = Modifier
                                    .size(12.dp)
                                    .clickable { onDeleteTrigger(trigger.id) },
                            )
                        }
                    }

                    IconButton(
                        onClick = onAddTrigger,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(KaivoIcons.icon("add"), contentDescription = "Add schedule", tint = colors.inkSecondary)
                    }
                }
            }
        }

        // Section 4: Capabilities (matching Screenshot 1)
        item {
            CardSection(
                title = "Capabilities",
                subtitle = "What this agent can do and how it does it",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onAddTool,
                        shape = KaivoShapes.chip,
                        border = BorderStroke(1.dp, colors.hairline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.inkPrimary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("+ Add tool", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onAddTool,
                        shape = KaivoShapes.chip,
                        border = BorderStroke(1.dp, colors.hairline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.inkPrimary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("+ Add workflow", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onAddTool,
                        shape = KaivoShapes.chip,
                        border = BorderStroke(1.dp, colors.hairline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.inkPrimary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("+ Add sub-agent", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Web search capability toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(KaivoShapes.chip)
                        .background(colors.canvasVoid)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Web search",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = "Let the model search the web when it needs up-to-date information",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(KaivoShapes.chip)
                            .background(if (webSearchEnabled) colors.royalGold else colors.surfaceCard)
                            .border(1.dp, colors.hairline, KaivoShapes.chip)
                            .clickable { webSearchEnabled = !webSearchEnabled }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (webSearchEnabled) "On" else "Off",
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (webSearchEnabled) Color.Black else colors.inkPrimary,
                            )
                            Icon(
                                KaivoIcons.icon("dropdown"),
                                contentDescription = null,
                                tint = if (webSearchEnabled) Color.Black else colors.inkSecondary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
        }

        // Section 5: Session memory (matching Screenshot 1)
        item {
            CardSection(
                title = "Session memory",
                headerBadge = "Always on",
            ) {
                Text(
                    text = "Remembers the conversation within the current session",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cross-session memory",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = "Remembers useful details about each user across sessions, such as their preferences, decisions, and previous work",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )
                    }
                    Switch(
                        checked = crossSessionMemory,
                        onCheckedChange = { crossSessionMemory = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.royalGold,
                            checkedTrackColor = colors.royalGold.copy(alpha = 0.4f),
                        ),
                    )
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
    var advancedExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Card 1: Knowledge base (matching Screenshot 2)
        item {
            CardSection(
                title = "Knowledge base",
                subtitle = "Sovereign files uploaded to this agent so it can search and read their contents when answering",
            ) {
                // Dashed border upload area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .drawBehind {
                            drawRoundRect(
                                color = colors.hairline,
                                cornerRadius = CornerRadius(12.dp.toPx()),
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), 0f),
                                ),
                            )
                        }
                        .padding(vertical = 32.dp, horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            KaivoIcons.icon("folder"),
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            text = "No files uploaded",
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = "Upload CSV, PDF, Markdown, or TXT files this agent can search and read",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )
                        Spacer(Modifier.height(6.dp))

                        Button(
                            onClick = onAddKnowledge,
                            shape = KaivoShapes.chip,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text("Upload file", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // If user added knowledge items, display them below
                if (knowledge.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        knowledge.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(KaivoShapes.chip)
                                    .background(colors.canvasVoid)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
                                    Text(item.content, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkSecondary, maxLines = 1)
                                }
                                IconButton(onClick = { onDeleteKnowledge(item.id) }) {
                                    Icon(KaivoIcons.icon("delete"), contentDescription = "Delete", tint = colors.inkSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Card 2: Memory (matching Screenshot 2)
        item {
            CardSection(
                title = "Memory",
                subtitle = "What this agent remembers about each user across sessions",
            ) {
                Text(
                    text = "No memories stored yet.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                )
            }
        }

        // Card 3: Advanced Accordion (matching Screenshot 2)
        item {
            CardSection(
                title = "Advanced",
                headerBadge = if (advancedExpanded) "▲" else "▼",
                onHeaderClick = { advancedExpanded = !advancedExpanded },
            ) {
                AnimatedVisibility(
                    visible = advancedExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Vector indexing: HNSW cosine distance on device.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )
                        Text(
                            text = "Chunk size: 512 tokens with 64 token overlap.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
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
                        text = "Session History",
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
                    Text("🧪 Test Run", fontWeight = FontWeight.Bold)
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
                        text = "No execution history yet. Tap '🧪 Test Run' to try this agent.",
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
    agentModel: String,
    availableModels: List<String>,
    onSettingsChange: (AgentSettings) -> Unit,
) {
    val colors = KaivoTheme.colors
    var reasoningEnabled by remember { mutableStateOf(false) }
    var promptCachingEnabled by remember { mutableStateOf(true) }
    var customModelRouting by remember { mutableStateOf(false) }
    var availableInMcp by remember { mutableStateOf(false) }
    var maxParallelSubAgents by remember { mutableStateOf("10") }
    var toolConcurrency by remember { mutableStateOf("5") }
    var maxIterations by remember { mutableStateOf(settings.maxIterations.toString()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Card 1: Monthly usage (matching Screenshot 3)
        item {
            CardSection(
                title = "Monthly usage",
                headerBadge = "No monthly budget",
            ) {
                SettingRowAction(
                    label = "Monthly budget",
                    subtitle = "Maximum this agent can spend across all sessions each month",
                    actionText = "Not set >",
                    onClick = {},
                )
                SettingRowAction(
                    label = "Cost cap per session",
                    subtitle = "Maximum a single session can spend",
                    actionText = "Not set >",
                    onClick = {},
                )
            }
        }

        // Card 2: Sub-agents (matching Screenshot 3)
        item {
            CardSection(
                title = "Sub-agents",
                subtitle = "Set how many sub-agents can run at once and which models they use",
            ) {
                SettingInputRow(
                    label = "Max parallel sub-agents",
                    subtitle = "How many sub-agents this agent can run at the same time (1-20)",
                    value = maxParallelSubAgents,
                    onValueChange = { maxParallelSubAgents = it },
                )
                SettingToggleRow(
                    label = "Custom model routing",
                    subtitle = "Use different models for low, medium, or high difficulty tasks",
                    checked = customModelRouting,
                    onCheckedChange = { customModelRouting = it },
                )
            }
        }

        // Card 3: Available in MCP (matching Screenshot 3)
        item {
            CardSection(title = "Available in MCP") {
                SettingToggleRow(
                    label = "Make this agent visible to AI Agents through local MCP",
                    subtitle = "",
                    checked = availableInMcp,
                    onCheckedChange = { availableInMcp = it },
                )
            }
        }

        // Card 4: Advanced (matching Screenshot 3)
        item {
            CardSection(
                title = "Advanced",
                subtitle = "Configure memory and tune how this agent runs",
            ) {
                // Memory model
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Memory model",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.inkPrimary,
                    )
                    Text(
                        text = "Choose the model that creates, reviews, and retrieves memories. Uses the agent model by default",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KaivoShapes.chip)
                            .background(colors.canvasVoid)
                            .border(1.dp, colors.hairline, KaivoShapes.chip)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = agentModel.ifBlank { "Default Agent Model" },
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = colors.inkPrimary,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                SettingToggleRow(
                    label = "Reasoning",
                    subtitle = "Let the model reason before responding",
                    checked = reasoningEnabled,
                    onCheckedChange = { reasoningEnabled = it },
                )

                SettingToggleRow(
                    label = "Prompt caching",
                    subtitle = "Cache repeated parts of the prompt to reduce latency and cost",
                    checked = promptCachingEnabled,
                    onCheckedChange = { promptCachingEnabled = it },
                )

                SettingInputRow(
                    label = "Tool call concurrency",
                    subtitle = "How many tool calls the agent can run in parallel",
                    value = toolConcurrency,
                    onValueChange = { toolConcurrency = it },
                )

                SettingInputRow(
                    label = "Max iterations",
                    subtitle = "Maximum number of agent loop iterations per run (1–200)",
                    value = maxIterations,
                    onValueChange = {
                        maxIterations = it
                        it.toIntOrNull()?.let { iter -> onSettingsChange(settings.copy(maxIterations = iter)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun CardSection(
    title: String,
    subtitle: String? = null,
    headerBadge: String? = null,
    onHeaderClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = KaivoTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.medallion)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.medallion)
            .clickable(enabled = onHeaderClick != null) { onHeaderClick?.invoke() }
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
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

                if (headerBadge != null) {
                    Box(
                        modifier = Modifier
                            .clip(KaivoShapes.chip)
                            .background(colors.canvasVoid)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = headerBadge,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = colors.inkSecondary,
                        )
                    }
                }
            }

            content()
        }
    }
}

@Composable
private fun SettingRowAction(
    label: String,
    subtitle: String,
    actionText: String,
    onClick: () -> Unit,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
        Text(actionText, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = colors.inkSecondary)
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.royalGold,
                checkedTrackColor = colors.royalGold.copy(alpha = 0.4f),
            ),
        )
    }
}

@Composable
private fun SettingInputRow(
    label: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            shape = KaivoShapes.chip,
            modifier = Modifier.width(70.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.royalGold,
                unfocusedBorderColor = colors.hairline,
                focusedTextColor = colors.inkPrimary,
                unfocusedTextColor = colors.inkPrimary,
            ),
        )
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
                    text = "🧪 Test Agent",
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
                placeholder = { Text("Enter a test query or scenario for this agent...", color = colors.inkSecondary) },
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
        Triple("schedule", "Daily News Brief Schedule", "Every morning at 9am"),
        Triple("manual", "Manual Spark", "Runs whenever manually started from app"),
        Triple("webhook", "Webhook Listener", "Triggers when incoming HTTP payload is received"),
        Triple("phone_event", "Phone Trigger", "Triggers on battery level, wifi state, or incoming SMS"),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Channel / Trigger", color = colors.inkPrimary) },
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
        Triple("workflow", "Execute KaivoFlow Workflow", "Triggers an existing canvas automation flow"),
        Triple("code", "JavaScript Code Transformation", "Runs sandboxed custom data transformations"),
        Triple("device", "Phone Actions", "Interacts with phone notifications, clipboard, or SMS"),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Capability / Tool", color = colors.inkPrimary) },
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
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    val colors = KaivoTheme.colors
    val skillPresets = listOf(
        "Headline Summarizer & Deduplicator",
        "Fact Checker & Citation Extractor",
        "Multi-language Translator",
        "Executive Briefing Formatter",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Agent Skill", color = colors.inkPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                skillPresets.forEach { name ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KaivoShapes.medallion)
                            .background(colors.surfaceCard)
                            .border(1.dp, colors.hairline, KaivoShapes.medallion)
                            .clickable { onAdd(name) }
                            .padding(12.dp),
                    ) {
                        Text(name, fontWeight = FontWeight.SemiBold, color = colors.inkPrimary)
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
        title = { Text("Upload / Add Knowledge Document", color = colors.inkPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Document Title") },
                    placeholder = { Text("e.g. News Guidelines / Topics") },
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
                    label = { Text("Document Content / Markdown") },
                    placeholder = { Text("Paste document text, facts, or instructions for retrieval...") },
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
                Text("Save Document")
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
