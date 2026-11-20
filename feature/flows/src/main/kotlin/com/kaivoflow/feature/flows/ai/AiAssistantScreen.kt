package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.model.CredentialTypeInfo
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.model.CredentialItem
import com.kaivoflow.core.model.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(
    onBack: () -> Unit,
    onOpenCanvas: (flowId: String) -> Unit,
    viewModel: AiAssistantViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = KaivoTheme.colors
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }

    val isChatEmpty = state.messages.isEmpty() && state.currentFlow == null
    val targetFlow = state.activeProposedFlow ?: state.currentFlow

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvasVoid)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---------------- Top Bar ----------------
            TopBar(
                flow = state.currentFlow,
                providerConfig = state.providerConfig,
                savedCredentials = state.savedCredentials,
                onBack = onBack,
                onOpenCredentials = { viewModel.toggleSettingsSheet(true) },
                onOpenHistory = { viewModel.toggleHistorySheet(true) },
                onDetachFlow = { viewModel.detachFlow() },
            )

            // ---------------- Canvas Flow Switcher (Active when opened with flow context) ----------------
            if (state.currentFlow != null) {
                ViewModeSwitcher(
                    selectedMode = state.viewMode,
                    stepCount = targetFlow?.steps?.size ?: state.currentFlow?.steps?.size ?: 0,
                    onSelectMode = { viewModel.setViewMode(it) },
                )
            }

            if (state.currentFlow != null && state.viewMode == AssistantViewMode.CanvasPreview) {
                // ---------------- Side-by-Side Live Canvas View ----------------
                CanvasPreviewView(
                    flow = targetFlow ?: state.currentFlow!!,
                    baseFlow = state.currentFlow,
                    onApply = {
                        val flowToApply = targetFlow ?: state.currentFlow
                        if (flowToApply != null) {
                            viewModel.applyProposedFlow(flowToApply) { appliedId ->
                                onOpenCanvas(appliedId)
                            }
                        }
                    },
                    onBackToChat = { viewModel.setViewMode(AssistantViewMode.Chat) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )

                // Docked Bottom Input Bar under Live Canvas
                DockedBottomInputBar(
                    text = inputText,
                    onTextChange = { inputText = it },
                    onSend = {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    },
                    flowContext = state.currentFlow,
                    onDetachFlow = { viewModel.detachFlow() },
                    enabled = !state.isGenerating,
                    onOpenActionMenu = { viewModel.toggleActionMenuSheet(true) },
                    attachedSnippet = state.attachedContextSnippet,
                    onClearAttachedSnippet = { viewModel.clearAttachedContext() },
                    isGenerating = state.isGenerating,
                    onStop = { viewModel.stopGeneration() },
                )
            } else if (isChatEmpty) {
                // ---------------- Clean Floating Hero State (Screenshot 2) ----------------
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyStateCenteredPrompt(
                        text = inputText,
                        onTextChange = { inputText = it },
                        onSend = {
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        },
                        onSuggestionClick = { prompt ->
                            viewModel.sendMessage(prompt)
                        },
                        onOpenActionMenu = { viewModel.toggleActionMenuSheet(true) },
                    )
                }
            } else {
                // ---------------- Active Chat View (Screenshot 3) ----------------
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        val applied = state.lastApplied
                        val status = when {
                            applied != null && applied.messageId == msg.id -> if (applied.before != null) ProposalStatus.Applied else ProposalStatus.AppliedNoUndo
                            msg.flowProposal != null && msg.flowProposal == state.activeProposedFlow -> ProposalStatus.Pending
                            else -> ProposalStatus.Older
                        }
                        ChatMessageRow(
                            message = msg,
                            status = status,
                            onApply = { msg.flowProposal?.let { f -> viewModel.applyProposedFlow(f) { } } },
                            onUndo = { viewModel.undoLastApply() },
                            onOpenCanvas = { flowId -> onOpenCanvas(flowId) },
                        )
                    }

                    if (state.isGenerating) {
                        item {
                            GeneratingIndicator()
                        }
                    }
                }

                // ---------------- Docked Bottom Input Bar ----------------
                DockedBottomInputBar(
                    text = inputText,
                    onTextChange = { inputText = it },
                    onSend = {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    },
                    flowContext = state.currentFlow,
                    onDetachFlow = { viewModel.detachFlow() },
                    enabled = !state.isGenerating,
                    onOpenActionMenu = { viewModel.toggleActionMenuSheet(true) },
                    attachedSnippet = state.attachedContextSnippet,
                    onClearAttachedSnippet = { viewModel.clearAttachedContext() },
                    isGenerating = state.isGenerating,
                    onStop = { viewModel.stopGeneration() },
                )
            }
        }

        // ---------------- Approve / deny card for tools that act on the world ----------------
        AttachApprovalPresenter(viewModel.approvalRelay, inline = true)
        val pendingApproval by viewModel.approvalRelay.pending.collectAsStateWithLifecycle()
        pendingApproval?.let { request ->
            ApprovalCard(
                request = request,
                onApprove = { viewModel.approvalRelay.respond(true) },
                onDeny = { viewModel.approvalRelay.respond(false) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 84.dp),
            )
        }

        // ---------------- AI Preferences, Skills & Connectors Sheet (Tab-wise) ----------------
        if (state.showSettingsSheet) {
            AiSettingsSheet(
                credentials = state.savedCredentials,
                currentConfig = state.providerConfig,
                onSelectCredential = { cred -> viewModel.selectCredential(cred.id) },
                onSelectModel = { model -> viewModel.selectModel(model) },
                onDismiss = { viewModel.toggleSettingsSheet(false) },
                initialTabIndex = state.settingsInitialTabIndex,
            )
        }

        // ---------------- Action Menu Sheet (+ button) ----------------
        if (state.showActionMenuSheet) {
            AiActionMenuSheet(
                onDismiss = { viewModel.toggleActionMenuSheet(false) },
                onAttachFiles = {
                    viewModel.attachContextSnippet(
                        label = "Workflow Spec & Diagnostics",
                        content = "Attached workflow specifications, error traces, and execution context.",
                    )
                },
                onConnectServices = {
                    viewModel.openConnectServices()
                },
                onOpenPreferences = {
                    viewModel.openPreferences()
                },
            )
        }

        // ---------------- Chat History Sheet ----------------
        if (state.showHistorySheet) {
            ChatHistorySheet(
                sessions = state.savedSessions,
                activeSessionId = state.activeSessionId,
                onSelectSession = { session -> viewModel.loadSession(session) },
                onNewChat = { viewModel.newSession() },
                onDeleteSession = { sessionId -> viewModel.deleteSession(sessionId) },
                onDismiss = { viewModel.toggleHistorySheet(false) },
            )
        }
    }
}



@Composable
private fun ViewModeSwitcher(
    selectedMode: AssistantViewMode,
    stepCount: Int,
    onSelectMode: (AssistantViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Chat Assistant Tab
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(KaivoShapes.chip)
                .background(if (selectedMode == AssistantViewMode.Chat) colors.surfaceCard else colors.canvasVoid)
                .border(
                    1.dp,
                    if (selectedMode == AssistantViewMode.Chat) colors.royalGold else colors.hairline,
                    KaivoShapes.chip,
                )
                .clickable { onSelectMode(AssistantViewMode.Chat) }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    KaivoIcons.icon("sparkles"),
                    contentDescription = null,
                    tint = if (selectedMode == AssistantViewMode.Chat) colors.royalGold else colors.inkSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Chat Assistant",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = if (selectedMode == AssistantViewMode.Chat) FontWeight.Bold else FontWeight.Normal,
                    color = if (selectedMode == AssistantViewMode.Chat) colors.inkPrimary else colors.inkSecondary,
                )
            }
        }

        // Live Canvas Tab
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(KaivoShapes.chip)
                .background(if (selectedMode == AssistantViewMode.CanvasPreview) colors.surfaceCard else colors.canvasVoid)
                .border(
                    1.dp,
                    if (selectedMode == AssistantViewMode.CanvasPreview) colors.royalGold else colors.hairline,
                    KaivoShapes.chip,
                )
                .clickable { onSelectMode(AssistantViewMode.CanvasPreview) }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    KaivoIcons.icon("flow"),
                    contentDescription = null,
                    tint = if (selectedMode == AssistantViewMode.CanvasPreview) colors.royalGold else colors.inkSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Live Canvas ($stepCount)",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = if (selectedMode == AssistantViewMode.CanvasPreview) FontWeight.Bold else FontWeight.Normal,
                    color = if (selectedMode == AssistantViewMode.CanvasPreview) colors.inkPrimary else colors.inkSecondary,
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    flow: Flow?,
    providerConfig: AiProviderConfig,
    savedCredentials: List<CredentialItem>,
    onBack: () -> Unit,
    onOpenCredentials: () -> Unit,
    onOpenHistory: () -> Unit,
    onDetachFlow: () -> Unit,
) {
    val colors = KaivoTheme.colors
    val activeCredName = providerConfig.credentialName
        ?: savedCredentials.find { it.id == providerConfig.credentialId }?.name
        ?: providerConfig.provider.displayName

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(KaivoIcons.icon("back"), contentDescription = "Back", tint = colors.inkPrimary)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                KaivoIcons.icon("sparkles"),
                contentDescription = null,
                tint = colors.royalGold,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    text = "Ask AI",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.inkPrimary,
                )
                Text(
                    text = "${providerConfig.provider.displayName} · ${providerConfig.model}",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // AI Credential Selector button
        Box(
            Modifier
                .clip(KaivoShapes.chip)
                .background(colors.surfaceCard)
                .border(1.dp, colors.hairline, KaivoShapes.chip)
                .clickable { onOpenCredentials() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    KaivoIcons.icon("key"),
                    contentDescription = "AI Credential",
                    tint = colors.royalGold,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = activeCredName,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.inkPrimary,
                    maxLines = 1,
                )
                Icon(
                    KaivoIcons.icon("dropdown"),
                    contentDescription = null,
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        // History button at the far right corner
        IconButton(onClick = onOpenHistory) {
            Icon(
                KaivoIcons.icon("history"),
                contentDescription = "Chat history",
                tint = colors.inkSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun EmptyStateCenteredPrompt(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onOpenActionMenu: () -> Unit = {},
) {
    val colors = KaivoTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Centered Header (Screenshot 2: ✨ What do you want to automate?)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                KaivoIcons.icon("sparkles"),
                contentDescription = null,
                tint = colors.royalGold,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = "What do you want to automate?",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
            )
        }

        Spacer(Modifier.height(20.dp))

        // Floating Prompt Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            border = BorderStroke(1.dp, Color(0xFF2E2E36)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp),
                    textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                        color = colors.inkPrimary,
                    ),
                    cursorBrush = SolidColor(colors.royalGold),
                    decorationBox = { innerTextField ->
                        if (text.isEmpty()) {
                            Text(
                                text = "Tell me what to build or ask a question",
                                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                                color = colors.inkSecondary,
                            )
                        }
                        innerTextField()
                    },
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onOpenActionMenu,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            KaivoIcons.icon("add"),
                            contentDescription = "Add context or tools",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    IconButton(
                        onClick = { /* mic input */ },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            KaivoIcons.icon("mic"),
                            contentDescription = "Voice input",
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    val canSend = text.isNotBlank()
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (canSend) colors.royalGold else Color(0xFF2C2C34))
                            .clickable(enabled = canSend) { onSend() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            KaivoIcons.icon("forward"),
                            contentDescription = "Send",
                            tint = if (canSend) Color.Black else colors.inkSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Horizontal suggestion chips matching Screenshot 2
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val suggestions = listOf(
                "Score my leads" to "settings",
                "Process invoices" to "flow",
                "Schedule social posts" to "history",
                "Telegram support agent" to "sparkles",
                "Battery monitor alert" to "flash",
            )
            items(suggestions) { (title, iconName) ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E1E24))
                        .border(1.dp, Color(0xFF2E2E36), RoundedCornerShape(10.dp))
                        .clickable { onSuggestionClick(title) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            KaivoIcons.icon(iconName),
                            contentDescription = null,
                            tint = colors.inkSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = title,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            color = colors.inkPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageRow(
    message: AiChatMessage,
    status: ProposalStatus,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onOpenCanvas: (String) -> Unit,
) {
    val colors = KaivoTheme.colors
    val isUser = message.sender == MessageSender.User

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Icon(
                    KaivoIcons.icon("sparkles"),
                    contentDescription = null,
                    tint = colors.royalGold,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Assistant",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
            }

            // Collapsible reasoning trace matching screenshot
            if (message.thought != null) {
                AiThoughtBlock(thought = message.thought)
                Spacer(Modifier.height(4.dp))
            }
        }

        if (message.text.isNotBlank() || isUser) {
            Box(
                Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp,
                        )
                    )
                    .background(if (isUser) Color(0xFF24242C) else Color(0xFF1A1A20))
                    .border(
                        1.dp,
                        if (isUser) colors.royalGold.copy(alpha = 0.4f) else Color(0xFF2A2A32),
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp,
                        )
                    )
                    .padding(14.dp),
            ) {
                MarkdownChatText(
                    text = message.text,
                    isUser = isUser,
                )
            }
        }

        // Tool Execution Traces (Sovereign agent tools)
        if (message.toolCalls.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ToolCallsTraceCard(toolCalls = message.toolCalls)
        }

        // If message has a proposed flow, render the interactive Workflow Preview Card!
        if (message.flowProposal != null) {
            Spacer(Modifier.height(10.dp))
            WorkflowPreviewCard(
                flow = message.flowProposal,
                status = status,
                onApply = onApply,
                onUndo = onUndo,
                onOpenCanvas = { onOpenCanvas(message.flowProposal.id) },
            )
        }
    }
}

@Composable
private fun ToolCallsTraceCard(
    toolCalls: List<ToolExecution>,
) {
    val colors = KaivoTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF16161C))
            .border(1.dp, Color(0xFF262630), RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = KaivoIcons.icon("settings"),
                    contentDescription = null,
                    tint = colors.royalGold,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "🛠️ ${toolCalls.size} Workflow Tool Operations Executed",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.royalGold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (expanded) "Hide ▴" else "View Details ▾",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    toolCalls.forEach { tc ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = if (tc.isSuccess) "✓" else "✕",
                                color = if (tc.isSuccess) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = tc.toolName,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.inkPrimary,
                            )
                            Text(
                                text = "· ${tc.description}",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = colors.inkSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowPreviewCard(
    flow: Flow,
    status: ProposalStatus,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onOpenCanvas: () -> Unit,
) {
    val colors = KaivoTheme.colors
    Card(
        shape = KaivoShapes.medallion,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(colors.royalGold)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(colors.royalGold.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(KaivoIcons.icon("flow"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = flow.name,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.inkPrimary,
                    )
                    Text(
                        text = "${if (status == ProposalStatus.Pending) "Proposal, not applied yet" else "Workflow"} · ${flow.steps.size} Steps, ${flow.streams.size} Connections",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                    )
                }
            }

            // Steps visual timeline
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.canvasVoid)
                    .border(1.dp, colors.hairline, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                flow.steps.forEachIndexed { index, step ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (step.isTrigger) colors.royalGold.copy(alpha = 0.2f) else Color(0xFF24242C)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (step.isTrigger) colors.royalGold else colors.inkPrimary,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = step.name,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.inkPrimary,
                            )
                            Text(
                                text = step.type,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                color = colors.inkSecondary,
                            )
                        }
                    }
                    if (index < flow.steps.lastIndex) {
                        Box(
                            Modifier
                                .padding(start = 12.dp)
                                .width(2.dp)
                                .height(12.dp)
                                .background(colors.royalGold.copy(alpha = 0.5f))
                        )
                    }
                }
            }

            // Status line and actions. A proposal changes nothing until Apply; Undo puts the previous Flow back.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val applied = status == ProposalStatus.Applied || status == ProposalStatus.AppliedNoUndo
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = KaivoIcons.icon("checkCircle"),
                        contentDescription = null,
                        tint = if (applied) Color(0xFF10B981) else colors.royalGold,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = when (status) {
                            ProposalStatus.Pending -> "Not applied yet"
                            ProposalStatus.Applied, ProposalStatus.AppliedNoUndo -> "Applied"
                            ProposalStatus.Older -> "Older proposal"
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (applied) Color(0xFF10B981) else colors.royalGold,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status == ProposalStatus.Pending) {
                        Button(
                            onClick = onApply,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = Color.Black),
                            shape = KaivoShapes.chip,
                        ) {
                            Text("Apply", fontSize = 12.sp)
                        }
                    }
                    if (status == ProposalStatus.Applied) {
                        Button(
                            onClick = onUndo,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282834), contentColor = colors.inkPrimary),
                            shape = KaivoShapes.chip,
                        ) {
                            Text("Undo", fontSize = 12.sp)
                        }
                    }
                    if (applied) {
                        Button(
                            onClick = onOpenCanvas,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282834), contentColor = colors.inkPrimary),
                            shape = KaivoShapes.chip,
                        ) {
                            Text("Open Canvas ↗", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Where a proposed Flow stands in the chat. Only [Pending] can be applied. */
private enum class ProposalStatus { Pending, Applied, AppliedNoUndo, Older }

@Composable
private fun GeneratingIndicator() {
    Box(
        modifier = Modifier.padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        TinkeringPill()
    }
}

@Composable
private fun DockedBottomInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    flowContext: Flow?,
    onDetachFlow: () -> Unit,
    enabled: Boolean,
    onOpenActionMenu: () -> Unit = {},
    attachedSnippet: String? = null,
    onClearAttachedSnippet: () -> Unit = {},
    isGenerating: Boolean = false,
    onStop: () -> Unit = {},
) {
    val colors = KaivoTheme.colors
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
        border = BorderStroke(1.dp, Color(0xFF2E2E36)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Attached Flow chip inside bottom card (Screenshot 3 style)
            if (flowContext != null) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A34))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        KaivoIcons.icon("flow"),
                        contentDescription = null,
                        tint = colors.royalGold,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = flowContext.name,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = colors.inkPrimary,
                    )
                    Icon(
                        KaivoIcons.icon("close"),
                        contentDescription = "Detach flow",
                        tint = colors.inkSecondary,
                        modifier = Modifier
                            .size(12.dp)
                            .clickable { onDetachFlow() },
                    )
                }
            }

            // Attached context snippet chip
            if (!attachedSnippet.isNullOrBlank()) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A34))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        KaivoIcons.icon("attachment"),
                        contentDescription = null,
                        tint = colors.royalGold,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = attachedSnippet.take(35) + if (attachedSnippet.length > 35) "..." else "",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = colors.inkPrimary,
                    )
                    Icon(
                        KaivoIcons.icon("close"),
                        contentDescription = "Remove attachment",
                        tint = colors.inkSecondary,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onClearAttachedSnippet() },
                    )
                }
            }

            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                    color = colors.inkPrimary,
                ),
                cursorBrush = SolidColor(colors.royalGold),
                decorationBox = { innerTextField ->
                    if (text.isEmpty()) {
                        Text(
                            text = if (flowContext != null) "Ask anything about this workflow..." else "Ask anything...",
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = colors.inkSecondary,
                        )
                    }
                    innerTextField()
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onOpenActionMenu,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        KaivoIcons.icon("add"),
                        contentDescription = "Add context or tools",
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = { /* mic input */ },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        KaivoIcons.icon("mic"),
                        contentDescription = "Voice input",
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Spacer(Modifier.width(8.dp))

                val canSend = text.isNotBlank() && enabled
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (canSend || isGenerating) colors.royalGold else Color(0xFF2C2C34))
                        .clickable(enabled = canSend || isGenerating) { if (isGenerating) onStop() else onSend() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        KaivoIcons.icon(if (isGenerating) "stop" else "forward"),
                        contentDescription = if (isGenerating) "Stop" else "Send",
                        tint = if (canSend || isGenerating) Color.Black else colors.inkSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiCredentialPickerSheet(
    currentConfig: AiProviderConfig,
    savedCredentials: List<CredentialItem>,
    isDiscovering: Boolean,
    onSelectCredential: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KaivoTheme.colors
    val effectiveModels = currentConfig.effectiveModels

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.canvasVoid,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        KaivoIcons.icon("key"),
                        contentDescription = null,
                        tint = colors.royalGold,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = "AI Credentials",
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.inkPrimary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(KaivoIcons.icon("close"), contentDescription = "Close", tint = colors.inkSecondary)
                }
            }

            Text(
                text = "Select which AI credential account powers Ask AI. Create or edit credentials anytime from the Sovereign Credentials workspace.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
            )

            if (savedCredentials.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(KaivoShapes.stepCard)
                        .background(colors.surfaceCard)
                        .border(1.dp, colors.hairline, KaivoShapes.stepCard)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("🔑", fontSize = 28.sp)
                        Text(
                            text = "No AI Credentials Found",
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            text = "Add an OpenAI, Gemini, Claude, or Ollama credential from the Credentials tab in Overview to start using Ask AI.",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    savedCredentials.forEach { cred ->
                        val isSelected = cred.id == currentConfig.credentialId
                        val typeInfo = CredentialTypeInfo.find(cred.type)
                        val accentColor = typeInfo?.accentColor?.let { Color(it) } ?: colors.royalGold

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(KaivoShapes.stepCard)
                                .background(if (isSelected) colors.surfaceCard else colors.canvasVoid)
                                .border(
                                    1.dp,
                                    if (isSelected) colors.royalGold else colors.hairline,
                                    KaivoShapes.stepCard,
                                )
                                .clickable { if (!isSelected) onSelectCredential(cred.id) }
                                .padding(14.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(KaivoShapes.chip)
                                                .background(accentColor.copy(alpha = 0.15f))
                                                .border(1.dp, accentColor.copy(alpha = 0.35f), KaivoShapes.chip),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                KaivoIcons.icon(typeInfo?.iconKey ?: "key"),
                                                contentDescription = null,
                                                tint = accentColor,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }

                                        Column {
                                            Text(
                                                text = cred.name,
                                                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.inkPrimary,
                                            )
                                            Text(
                                                text = typeInfo?.displayName ?: cred.type,
                                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                                color = colors.inkSecondary,
                                            )
                                        }
                                    }

                                    // Selection Radio indicator
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) colors.royalGold else Color.Transparent)
                                            .border(
                                                2.dp,
                                                if (isSelected) colors.royalGold else colors.inkSecondary.copy(alpha = 0.5f),
                                                CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.Black),
                                            )
                                        }
                                    }
                                }

                                // If selected, show model selection
                                if (isSelected) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(KaivoShapes.chip)
                                            .background(colors.canvasVoid)
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = "Active Model",
                                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.royalGold,
                                            )
                                            if (isDiscovering) {
                                                Text(
                                                    text = "Discovering models...",
                                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                                    color = colors.inkSecondary,
                                                )
                                            }
                                        }

                                        Text(
                                            text = currentConfig.model,
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.inkPrimary,
                                        )

                                        if (effectiveModels.isNotEmpty()) {
                                            LazyRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.padding(top = 2.dp),
                                            ) {
                                                items(effectiveModels) { model ->
                                                    val isModelSelected = model == currentConfig.model
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(KaivoShapes.chip)
                                                            .background(if (isModelSelected) colors.royalGold else colors.surfaceCard)
                                                            .border(
                                                                1.dp,
                                                                if (isModelSelected) colors.royalGold else colors.hairline,
                                                                KaivoShapes.chip,
                                                            )
                                                            .clickable { onSelectModel(model) }
                                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                                    ) {
                                                        Text(
                                                            text = model,
                                                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                                            color = if (isModelSelected) Color.Black else colors.inkPrimary,
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
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onDismiss,
                shape = KaivoShapes.chip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.royalGold,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Done",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
