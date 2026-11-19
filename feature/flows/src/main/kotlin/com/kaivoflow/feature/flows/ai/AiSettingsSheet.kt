package com.kaivoflow.feature.flows.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaivoflow.core.ai.skills.AiSkill
import com.kaivoflow.core.ai.skills.BuiltInSkills
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.model.CredentialItem
import java.util.UUID

data class AiUserPreference(
    val id: String = UUID.randomUUID().toString(),
    val category: String, // e.g. "Workflow", "Agent", "Assistant"
    val content: String,
    val isDeletable: Boolean = true,
)

/**
 * Modern 4-tab settings and capabilities sheet for Ask AI:
 * 1. Providers & Models
 * 2. Skills (enable/disable, add custom skills, delete)
 * 3. Connectors (Browser Automation with Connect -> Configure -> Toggle lifecycle)
 * 4. Preferences (AI Behavioral directives, temperature sampling, auto-fix)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsSheet(
    credentials: List<CredentialItem>,
    currentConfig: AiProviderConfig,
    onSelectCredential: (CredentialItem) -> Unit,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit,
    initialTabIndex: Int = 0,
) {
    val colors = KaivoTheme.colors
    var selectedTabIndex by remember { mutableIntStateOf(initialTabIndex.coerceIn(0, 3)) }
    val tabs = listOf("Providers", "Skills", "Connectors", "Preferences")

    // In-memory skills state initialized with default built-in skills
    val skillsList = remember {
        mutableStateListOf<AiSkill>().apply {
            addAll(BuiltInSkills.defaultSkills)
        }
    }

    // Connectors state: Browser Automation
    var isBrowserConnected by remember { mutableStateOf(false) }
    var isBrowserEnabled by remember { mutableStateOf(true) }
    var browserEndpoint by remember { mutableStateOf("http://localhost:9222") }
    var showBrowserConfigDialog by remember { mutableStateOf(false) }

    // User AI Preferences state
    val preferencesList = remember {
        mutableStateListOf(
            AiUserPreference(
                id = "pref-1",
                category = "Workflow",
                content = "Always use descriptive node labels and explicit error handling.",
                isDeletable = false,
            ),
            AiUserPreference(
                id = "pref-2",
                category = "Agent",
                content = "Ask user confirmation before modifying existing steps or sensitive device capabilities.",
                isDeletable = false,
            ),
            AiUserPreference(
                id = "pref-3",
                category = "Assistant",
                content = "Provide concise step breakdowns, and keep parameter rationale clean.",
                isDeletable = false,
            ),
        )
    }
    var showAddPreferenceDialog by remember { mutableStateOf(false) }
    var temperature by remember { mutableFloatStateOf(0.2f) }
    var autoFixConnections by remember { mutableStateOf(true) }
    var maxReasoningSteps by remember { mutableIntStateOf(10) }

    var showAddSkillDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.canvasVoid,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        KaivoIcons.icon("sparkle"),
                        contentDescription = null,
                        tint = colors.royalGold,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "AI Preferences & Skills",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = "Configure models, active skills, and connectors",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        KaivoIcons.icon("x"),
                        contentDescription = "Close",
                        tint = colors.inkSecondary,
                    )
                }
            }

            // Tab Navigation Row
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = colors.surfaceCard,
                contentColor = colors.royalGold,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                        color = colors.royalGold,
                        height = 2.dp,
                    )
                },
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTabIndex == index) colors.royalGold else colors.inkSecondary,
                            )
                        },
                    )
                }
            }

            // Tab Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                when (selectedTabIndex) {
                    0 -> ProvidersTabContent(
                        credentials = credentials,
                        currentConfig = currentConfig,
                        onSelectCredential = onSelectCredential,
                        onSelectModel = onSelectModel,
                    )
                    1 -> SkillsTabContent(
                        skills = skillsList,
                        onToggleSkill = { skillId, isEnabled ->
                            val index = skillsList.indexOfFirst { it.id == skillId }
                            if (index != -1) {
                                skillsList[index] = skillsList[index].copy(isEnabled = isEnabled)
                            }
                        },
                        onAddSkillClick = { showAddSkillDialog = true },
                        onDeleteSkill = { skillId ->
                            skillsList.removeAll { it.id == skillId }
                        },
                    )
                    2 -> ConnectorsTabContent(
                        isBrowserConnected = isBrowserConnected,
                        isBrowserEnabled = isBrowserEnabled,
                        browserEndpoint = browserEndpoint,
                        onConnectClick = { showBrowserConfigDialog = true },
                        onToggleEnabled = { isBrowserEnabled = it },
                        onDisconnect = {
                            isBrowserConnected = false
                            isBrowserEnabled = false
                        },
                    )
                    3 -> PreferencesTabContent(
                        preferences = preferencesList,
                        temperature = temperature,
                        onTemperatureChange = { temperature = it },
                        maxReasoningSteps = maxReasoningSteps,
                        onMaxStepsChange = { maxReasoningSteps = it },
                        autoFixConnections = autoFixConnections,
                        onToggleAutoFix = { autoFixConnections = it },
                        onAddPreferenceClick = { showAddPreferenceDialog = true },
                        onDeletePreference = { prefId ->
                            preferencesList.removeAll { it.id == prefId }
                        },
                    )
                }
            }
        }
    }

    // Dialog for adding a custom skill
    if (showAddSkillDialog) {
        AddSkillDialog(
            onDismiss = { showAddSkillDialog = false },
            onAdd = { id, name, desc, instructions ->
                skillsList.add(
                    AiSkill(
                        id = id.lowercase().replace(" ", "-"),
                        name = name,
                        description = desc,
                        instructions = instructions,
                        isBuiltIn = false,
                        isEnabled = true,
                    )
                )
                showAddSkillDialog = false
            },
        )
    }

    // Dialog for configuring and connecting Browser Automation
    if (showBrowserConfigDialog) {
        ConfigureBrowserDialog(
            currentEndpoint = browserEndpoint,
            onDismiss = { showBrowserConfigDialog = false },
            onSaveAndConnect = { endpoint ->
                browserEndpoint = endpoint
                isBrowserConnected = true
                isBrowserEnabled = true
                showBrowserConfigDialog = false
            },
        )
    }

    // Dialog for adding a custom user preference
    if (showAddPreferenceDialog) {
        AddPreferenceDialog(
            onDismiss = { showAddPreferenceDialog = false },
            onAdd = { category, content ->
                preferencesList.add(
                    AiUserPreference(
                        category = category,
                        content = content,
                        isDeletable = true,
                    )
                )
                showAddPreferenceDialog = false
            },
        )
    }
}

// ============================================================================
// TAB 1: PROVIDERS & MODELS
// ============================================================================

@Composable
private fun ProvidersTabContent(
    credentials: List<CredentialItem>,
    currentConfig: AiProviderConfig,
    onSelectCredential: (CredentialItem) -> Unit,
    onSelectModel: (String) -> Unit,
) {
    val colors = KaivoTheme.colors

    Text(
        text = "Select which AI credential powers Ask AI. Create credentials anytime from the Sovereign Credentials workspace.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.inkSecondary,
        modifier = Modifier.padding(bottom = 14.dp),
    )

    if (credentials.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "No Sovereign Credentials Found",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.inkPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Head to Credentials workspace to add your OpenAI, NVIDIA, Groq, Gemini, Claude, or local Ollama credentials.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )
            }
        }
    } else {
        credentials.forEach { cred ->
            val isSelected = cred.id == currentConfig.credentialId
            val borderColor = if (isSelected) colors.royalGold else colors.hairline

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable { onSelectCredential(cred) },
                colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.canvasVoid)
                                    .border(1.dp, colors.hairline, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    KaivoIcons.icon("sparkle"),
                                    contentDescription = null,
                                    tint = if (isSelected) colors.royalGold else colors.inkSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = cred.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.inkPrimary,
                                )
                                Text(
                                    text = cred.type,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.inkSecondary,
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (isSelected) 6.dp else 2.dp,
                                    color = if (isSelected) colors.royalGold else colors.inkSecondary.copy(alpha = 0.5f),
                                    shape = CircleShape,
                                )
                                .background(colors.surfaceCard),
                        )
                    }

                    if (isSelected) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Active Model",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.royalGold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = currentConfig.model,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkPrimary,
                        )

                        val availableModels = currentConfig.discoveredModels.ifEmpty {
                            listOf(
                                currentConfig.model,
                                "bigcode/starcoder2-15b",
                                "google/gemma-3-12b-it",
                            ).distinct()
                        }

                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(availableModels) { modelName ->
                                val isModelActive = modelName == currentConfig.model
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isModelActive) colors.royalGold.copy(alpha = 0.2f)
                                            else colors.canvasVoid,
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isModelActive) colors.royalGold else colors.hairline,
                                            shape = RoundedCornerShape(8.dp),
                                        )
                                        .clickable { onSelectModel(modelName) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = modelName,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        fontWeight = if (isModelActive) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isModelActive) colors.royalGold else colors.inkPrimary,
                                        maxLines = 1,
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

// ============================================================================
// TAB 2: SKILLS
// ============================================================================

@Composable
private fun SkillsTabContent(
    skills: List<AiSkill>,
    onToggleSkill: (String, Boolean) -> Unit,
    onAddSkillClick: () -> Unit,
    onDeleteSkill: (String) -> Unit,
) {
    val colors = KaivoTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Active Agent Skills (${skills.count { it.isEnabled }}/${skills.size})",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
            )
            Text(
                text = "Skills guide the agent's reasoning and execution strategy.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
            )
        }

        Button(
            onClick = onAddSkillClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.surfaceCard,
                contentColor = colors.royalGold,
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.border(1.dp, colors.hairline, RoundedCornerShape(8.dp)),
        ) {
            Text("+ Add", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
    }

    Spacer(Modifier.height(14.dp))

    skills.forEach { skill ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .border(1.dp, colors.hairline, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = skill.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.inkPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (skill.isBuiltIn) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.royalGold.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "Built-in",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.royalGold,
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = skill.isEnabled,
                            onCheckedChange = { onToggleSkill(skill.id, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.canvasVoid,
                                checkedTrackColor = colors.royalGold,
                                uncheckedThumbColor = colors.inkSecondary,
                                uncheckedTrackColor = colors.canvasVoid,
                            ),
                        )
                        if (!skill.isBuiltIn) {
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { onDeleteSkill(skill.id) }) {
                                Icon(
                                    KaivoIcons.icon("trash"),
                                    contentDescription = "Delete skill",
                                    tint = colors.statusError,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )
            }
        }
    }
}

// ============================================================================
// TAB 3: CONNECTORS (BROWSER AUTOMATION)
// ============================================================================

@Composable
private fun ConnectorsTabContent(
    isBrowserConnected: Boolean,
    isBrowserEnabled: Boolean,
    browserEndpoint: String,
    onConnectClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = KaivoTheme.colors

    Text(
        text = "External automation connectors and environment bridges available for Ask AI.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.inkSecondary,
        modifier = Modifier.padding(bottom = 16.dp),
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isBrowserConnected && isBrowserEnabled) 1.5.dp else 1.dp,
                color = if (isBrowserConnected && isBrowserEnabled) Color(0xFF10B981) else colors.hairline,
                shape = RoundedCornerShape(14.dp),
            ),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Language,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Browser Automation",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.inkPrimary,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isBrowserConnected) Color(0xFF10B981).copy(alpha = 0.15f)
                                    else colors.canvasVoid,
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = if (isBrowserConnected) "Connected (DevTools / MCP)" else "Not Configured",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = if (isBrowserConnected) Color(0xFF10B981) else colors.inkSecondary,
                            )
                        }
                    }
                }

                if (isBrowserConnected) {
                    Switch(
                        checked = isBrowserEnabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.canvasVoid,
                            checkedTrackColor = Color(0xFF10B981),
                            uncheckedThumbColor = colors.inkSecondary,
                            uncheckedTrackColor = colors.canvasVoid,
                        ),
                    )
                } else {
                    Button(
                        onClick = onConnectClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.royalGold,
                            contentColor = colors.canvasVoid,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = "Connect",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "Enables AI to navigate web applications, inspect live DOM trees, and extract online documentation.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
            )

            if (isBrowserConnected) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Endpoint: $browserEndpoint",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                    )
                    Row {
                        Text(
                            text = "Configure",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.royalGold,
                            modifier = Modifier
                                .clickable { onConnectClick() }
                                .padding(4.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Disconnect",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.statusError,
                            modifier = Modifier
                                .clickable { onDisconnect() }
                                .padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 4: PREFERENCES (AI DIRECTIVES & EXECUTION RULES)
// ============================================================================

@Composable
private fun PreferencesTabContent(
    preferences: List<AiUserPreference>,
    temperature: Float,
    onTemperatureChange: (Float) -> Unit,
    maxReasoningSteps: Int,
    onMaxStepsChange: (Int) -> Unit,
    autoFixConnections: Boolean,
    onToggleAutoFix: (Boolean) -> Unit,
    onAddPreferenceClick: () -> Unit,
    onDeletePreference: (String) -> Unit,
) {
    val colors = KaivoTheme.colors

    // Section 1: AI Directives Header & List
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "AI Behavioral Directives (${preferences.size})",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
            )
            Text(
                text = "Direct instructions guiding Ask AI decisions & output style.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
            )
        }

        Button(
            onClick = onAddPreferenceClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.surfaceCard,
                contentColor = colors.royalGold,
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.border(1.dp, colors.hairline, RoundedCornerShape(8.dp)),
        ) {
            Text("+ Add", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
    }

    Spacer(Modifier.height(12.dp))

    preferences.forEach { pref ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .border(1.dp, colors.hairline, RoundedCornerShape(10.dp)),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
            shape = RoundedCornerShape(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.royalGold.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = pref.category,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = colors.royalGold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pref.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkPrimary,
                    )
                }

                if (pref.isDeletable) {
                    IconButton(onClick = { onDeletePreference(pref.id) }) {
                        Icon(
                            KaivoIcons.icon("trash"),
                            contentDescription = "Delete preference",
                            tint = colors.statusError,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))

    // Section 2: Runtime Execution Controls
    Text(
        text = "Runtime Agent Controls",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = colors.inkPrimary,
    )
    Spacer(Modifier.height(10.dp))

    // Temperature Sampling
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.hairline, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Model Temperature & Creativity",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.inkPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    0.2f to "Deterministic (0.2)",
                    0.5f to "Balanced (0.5)",
                    0.7f to "Creative (0.7)",
                ).forEach { (t, label) ->
                    val isSelected = kotlin.math.abs(temperature - t) < 0.05f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) colors.royalGold.copy(alpha = 0.2f)
                                else colors.canvasVoid
                            )
                            .border(
                                1.dp,
                                if (isSelected) colors.royalGold else colors.hairline,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { onTemperatureChange(t) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) colors.royalGold else colors.inkPrimary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Max Reasoning Steps
            Text(
                text = "Max Reasoning Steps",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.inkPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(5 to "5 Steps", 10 to "10 Steps (Default)", 15 to "15 Steps").forEach { (steps, label) ->
                    val isSelected = maxReasoningSteps == steps
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) colors.royalGold.copy(alpha = 0.2f)
                                else colors.canvasVoid
                            )
                            .border(
                                1.dp,
                                if (isSelected) colors.royalGold else colors.hairline,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { onMaxStepsChange(steps) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) colors.royalGold else colors.inkPrimary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Auto-Fix Connections Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Self-Healing Auto-Fix",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.inkPrimary,
                    )
                    Text(
                        text = "Automatically repairs missing parameters and broken connections in flow proposals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
                Switch(
                    checked = autoFixConnections,
                    onCheckedChange = onToggleAutoFix,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.canvasVoid,
                        checkedTrackColor = colors.royalGold,
                        uncheckedThumbColor = colors.inkSecondary,
                        uncheckedTrackColor = colors.canvasVoid,
                    ),
                )
            }
        }
    }
}

// ============================================================================
// DIALOGS
// ============================================================================

@Composable
private fun AddSkillDialog(
    onDismiss: () -> Unit,
    onAdd: (id: String, name: String, desc: String, instructions: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Agent Skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Skill Name") },
                    placeholder = { Text("e.g. SQL Query Generator") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Short Description") },
                    placeholder = { Text("What this skill specializes in") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("System Instructions") },
                    placeholder = { Text("Detailed prompt rules for the agent") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && instructions.isNotBlank()) {
                        onAdd(name, name, desc, instructions)
                    }
                },
                enabled = name.isNotBlank() && instructions.isNotBlank(),
            ) {
                Text("Add Skill")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ConfigureBrowserDialog(
    currentEndpoint: String,
    onDismiss: () -> Unit,
    onSaveAndConnect: (endpoint: String) -> Unit,
) {
    var endpoint by remember { mutableStateOf(currentEndpoint) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Browser Automation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Configure Chrome DevTools MCP or Headless WebView endpoint to grant Ask AI live web browsing powers.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Remote Endpoint / Port") },
                    placeholder = { Text("http://localhost:9222") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (endpoint.isNotBlank()) {
                        onSaveAndConnect(endpoint)
                    }
                },
                enabled = endpoint.isNotBlank(),
            ) {
                Text("Save & Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AddPreferenceDialog(
    onDismiss: () -> Unit,
    onAdd: (category: String, content: String) -> Unit,
) {
    var category by remember { mutableStateOf("Workflow") }
    var content by remember { mutableStateOf("") }
    val categories = listOf("Workflow", "Agent", "Assistant", "General")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add AI Directive / Preference") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { cat ->
                        val isSelected = cat == category
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { category = cat }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = cat,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Directive Text") },
                    placeholder = { Text("e.g. Always format error alerts with red icons") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (content.isNotBlank()) {
                        onAdd(category, content)
                    }
                },
                enabled = content.isNotBlank(),
            ) {
                Text("Save Preference")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
