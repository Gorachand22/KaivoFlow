package com.kaivoflow.feature.flows.ai

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
                onBack = onBack,
                onOpenSettings = { viewModel.toggleSettingsSheet(true) },
                onClearChat = { viewModel.clearChat() },
                onDetachFlow = { viewModel.detachFlow() },
            )

            // ---------------- Chat Content ----------------
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // If only 1 greeting message exists, show quick action suggestion chips
                if (state.messages.size <= 1) {
                    item {
                        EmptyStateGreeting(
                            flow = state.currentFlow,
                            onSuggestionClick = { prompt -> viewModel.sendMessage(prompt) },
                        )
                    }
                }

                items(state.messages, key = { it.id }) { msg ->
                    ChatMessageRow(
                        message = msg,
                        onApplyFlow = { flow -> viewModel.applyProposedFlow(flow, onOpenCanvas) },
                    )
                }

                if (state.isGenerating) {
                    item {
                        GeneratingIndicator()
                    }
                }
            }

            // ---------------- Input Bar ----------------
            BottomInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                },
                flowContext = state.currentFlow,
                onDetachFlow = { viewModel.detachFlow() },
                enabled = !state.isGenerating,
            )
        }

        // ---------------- Provider Settings Sheet ----------------
        if (state.showSettingsSheet) {
            AiProviderSettingsSheet(
                currentConfig = state.providerConfig,
                onDismiss = { viewModel.toggleSettingsSheet(false) },
                onSave = { newConfig -> viewModel.saveProviderConfig(newConfig) },
            )
        }
    }
}

@Composable
private fun TopBar(
    flow: Flow?,
    providerConfig: AiProviderConfig,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearChat: () -> Unit,
    onDetachFlow: () -> Unit,
) {
    val colors = KaivoTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                )
            }
        }

        // Provider configuration button
        Box(
            Modifier
                .clip(KaivoShapes.chip)
                .background(colors.surfaceCard)
                .border(1.dp, colors.hairline, KaivoShapes.chip)
                .clickable { onOpenSettings() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    KaivoIcons.icon("settings"),
                    contentDescription = "Settings",
                    tint = colors.inkSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Provider",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
            }
        }

        IconButton(onClick = onClearChat) {
            Icon(KaivoIcons.icon("refresh"), contentDescription = "Clear Chat", tint = colors.inkSecondary)
        }
    }
}

@Composable
private fun EmptyStateGreeting(
    flow: Flow?,
    onSuggestionClick: (String) -> Unit,
) {
    val colors = KaivoTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (flow != null) {
            // Attached flow context banner (Screenshot 3 style)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(KaivoShapes.medallion)
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.royalGold.copy(alpha = 0.35f), KaivoShapes.medallion)
                    .padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(KaivoIcons.icon("flow"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(18.dp))
                        Text(
                            text = flow.name,
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.royalGold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${flow.steps.size} Steps",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = colors.inkSecondary,
                        )
                    }
                    Text(
                        text = "I see you're working on the \"${flow.name}\" workflow. What would you like to do with it — make changes, debug an issue, configure steps, or something else?",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = colors.inkPrimary,
                    )
                }
            }

            Text(
                text = "Quick actions for this workflow:",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = colors.inkSecondary,
                modifier = Modifier.align(Alignment.Start),
            )

            val flowSuggestions = listOf(
                "🔍 Diagnose & fix validation issues",
                "⚡ Add error notification handler",
                "📝 Explain how this flow executes",
                "➕ Add a JavaScript code transformation",
            )

            flowSuggestions.forEach { suggestion ->
                SuggestionPill(text = suggestion, onClick = { onSuggestionClick(suggestion) })
            }
        } else {
            // General builder mode (Screenshot 2 style)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Icon(
                    KaivoIcons.icon("sparkles"),
                    contentDescription = null,
                    tint = colors.royalGold,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    text = "What do you want to automate?",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.inkPrimary,
                )
                Text(
                    text = "Tell me what to build or ask a question",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                )
            }

            Spacer(Modifier.height(12.dp))

            val generalSuggestions = listOf(
                "🔔 Notify me when phone battery drops below 20%",
                "🌐 Incoming webhook that triggers a notification",
                "⏱️ Run a schedule every morning at 9 AM",
                "💬 Auto-respond to incoming SMS messages",
            )

            generalSuggestions.forEach { suggestion ->
                SuggestionPill(text = suggestion, onClick = { onSuggestionClick(suggestion) })
            }
        }
    }
}

@Composable
private fun SuggestionPill(
    text: String,
    onClick: () -> Unit,
) {
    val colors = KaivoTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.medallion)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.medallion)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = text,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = colors.inkPrimary,
        )
    }
}

@Composable
private fun ChatMessageRow(
    message: AiChatMessage,
    onApplyFlow: (Flow) -> Unit,
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
        }

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
                .background(if (isUser) colors.surfaceCard else colors.surfaceCard)
                .border(
                    1.dp,
                    if (isUser) colors.royalGold.copy(alpha = 0.4f) else colors.hairline,
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    )
                )
                .padding(14.dp),
        ) {
            Text(
                text = message.text,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = colors.inkPrimary,
                lineHeight = 20.sp,
            )
        }

        // If message has a proposed flow, render the interactive Workflow Preview Card!
        if (message.flowProposal != null) {
            Spacer(Modifier.height(10.dp))
            WorkflowPreviewCard(
                flow = message.flowProposal,
                onApply = { onApplyFlow(message.flowProposal) },
            )
        }
    }
}

@Composable
private fun WorkflowPreviewCard(
    flow: Flow,
    onApply: () -> Unit,
) {
    val colors = KaivoTheme.colors
    Card(
        shape = KaivoShapes.medallion,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(colors.royalGold)),
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
                        text = "Proposed Workflow · ${flow.steps.size} Steps, ${flow.streams.size} Connections",
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
                                .background(if (step.isTrigger) colors.royalGold.copy(alpha = 0.2f) else colors.surfaceCard),
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

            // Apply Button
            Button(
                onClick = onApply,
                colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = Color.Black),
                shape = KaivoShapes.chip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(KaivoIcons.icon("check"), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Apply to Canvas",
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneratingIndicator() {
    val colors = KaivoTheme.colors
    Row(
        Modifier
            .clip(KaivoShapes.chip)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.chip)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = colors.royalGold,
            strokeWidth = 2.dp,
        )
        Text(
            text = "AI is thinking & designing...",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
        )
    }
}

@Composable
private fun BottomInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    flowContext: Flow?,
    onDetachFlow: () -> Unit,
    enabled: Boolean,
) {
    val colors = KaivoTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.canvasVoid)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Flow Context Badge (Screenshot 3 style)
        if (flowContext != null) {
            Row(
                Modifier
                    .clip(KaivoShapes.chip)
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.royalGold.copy(alpha = 0.35f), KaivoShapes.chip)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    KaivoIcons.icon("flow"),
                    contentDescription = null,
                    tint = colors.royalGold,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = flowContext.name,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = colors.royalGold,
                )
                Icon(
                    KaivoIcons.icon("close"),
                    contentDescription = "Detach context",
                    tint = colors.inkSecondary,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { onDetachFlow() },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        if (flowContext != null) "Ask anything about this workflow..."
                        else "Tell me what to build or ask a question...",
                        color = colors.inkSecondary,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
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
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )

            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (text.isNotBlank() && enabled) colors.royalGold else colors.surfaceCard)
                    .clickable(enabled = text.isNotBlank() && enabled) { onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    KaivoIcons.icon("forward"),
                    contentDescription = "Send",
                    tint = if (text.isNotBlank() && enabled) Color.Black else colors.inkSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiProviderSettingsSheet(
    currentConfig: AiProviderConfig,
    onDismiss: () -> Unit,
    onSave: (AiProviderConfig) -> Unit,
) {
    val colors = KaivoTheme.colors
    var selectedProvider by remember { mutableStateOf(currentConfig.provider) }
    var selectedModel by remember { mutableStateOf(currentConfig.model) }
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var baseUrl by remember { mutableStateOf(currentConfig.baseUrl) }
    var showPassword by remember { mutableStateOf(false) }

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
                Text(
                    text = "AI Provider Settings",
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.inkPrimary,
                )
                IconButton(onClick = onDismiss) {
                    Icon(KaivoIcons.icon("close"), contentDescription = "Close", tint = colors.inkSecondary)
                }
            }

            Text(
                text = "Choose your AI intelligence provider. Your API key is encrypted on your device using Android Keystore and never proxied through any server.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
            )

            // Provider selection chips
            Text(
                text = "Provider",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = colors.inkPrimary,
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AiProvider.entries) { provider ->
                    val isSelected = provider == selectedProvider
                    Box(
                        Modifier
                            .clip(KaivoShapes.chip)
                            .background(if (isSelected) colors.royalGold else colors.surfaceCard)
                            .border(1.dp, if (isSelected) colors.royalGold else colors.hairline, KaivoShapes.chip)
                            .clickable {
                                selectedProvider = provider
                                selectedModel = provider.defaultModel
                                baseUrl = provider.defaultBaseUrl
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = provider.displayName,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.Black else colors.inkPrimary,
                        )
                    }
                }
            }

            // Model selection chips
            Text(
                text = "Model",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = colors.inkPrimary,
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(selectedProvider.availableModels) { model ->
                    val isSelected = model == selectedModel
                    Box(
                        Modifier
                            .clip(KaivoShapes.chip)
                            .background(if (isSelected) colors.royalGold else colors.surfaceCard)
                            .border(1.dp, if (isSelected) colors.royalGold else colors.hairline, KaivoShapes.chip)
                            .clickable { selectedModel = model }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = model,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = if (isSelected) Color.Black else colors.inkPrimary,
                        )
                    }
                }
            }

            // API Key field (if provider requires key)
            if (selectedProvider.requiresApiKey) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("Enter your ${selectedProvider.displayName} API Key") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                KaivoIcons.icon(if (showPassword) "visibilityOff" else "visibility"),
                                contentDescription = "Toggle visibility",
                                tint = colors.inkSecondary,
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.royalGold,
                        unfocusedBorderColor = colors.hairline,
                        focusedTextColor = colors.inkPrimary,
                        unfocusedTextColor = colors.inkPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Base URL field
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL / Endpoint") },
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
                    onSave(
                        AiProviderConfig(
                            provider = selectedProvider,
                            model = selectedModel,
                            apiKey = apiKey.trim(),
                            baseUrl = baseUrl.trim(),
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = Color.Black),
                shape = KaivoShapes.chip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Save & Connect",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
