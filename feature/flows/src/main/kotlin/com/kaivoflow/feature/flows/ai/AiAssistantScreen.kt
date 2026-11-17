package com.kaivoflow.feature.flows.ai

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

            if (isChatEmpty) {
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
                    // If flow context attached and no user messages sent yet
                    if (state.currentFlow != null && state.messages.size <= 1) {
                        item {
                            FlowContextHeroBanner(
                                flow = state.currentFlow!!,
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
                )
            }
        }

        // ---------------- Provider Settings Sheet ----------------
        if (state.showSettingsSheet) {
            AiProviderSettingsSheet(
                currentConfig = state.providerConfig,
                isDiscovering = state.isDiscoveringModels,
                discoveryMessage = state.modelDiscoveryMessage,
                isDiscoveryError = state.modelDiscoveryIsError,
                onDiscoverModels = { provider, baseUrl, apiKey ->
                    viewModel.discoverModels(provider, baseUrl, apiKey)
                },
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
private fun EmptyStateCenteredPrompt(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onSuggestionClick: (String) -> Unit,
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

        // Floating Prompt Card matching n8n Screenshot 2
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
                        onClick = { /* attachments or tools */ },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            KaivoIcons.icon("add"),
                            contentDescription = "Add context",
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
private fun FlowContextHeroBanner(
    flow: Flow,
    onSuggestionClick: (String) -> Unit,
) {
    val colors = KaivoTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Attached flow context banner (Screenshot 3 style)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(KaivoShapes.medallion)
                .background(Color(0xFF1E1E24))
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

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val flowSuggestions = listOf(
                "🔍 Diagnose & fix issues",
                "⚡ Add error notification",
                "📝 Explain how flow runs",
                "➕ Add JS transformation",
            )
            items(flowSuggestions) { suggestion ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E1E24))
                        .border(1.dp, Color(0xFF2E2E36), RoundedCornerShape(10.dp))
                        .clickable { onSuggestionClick(suggestion) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = suggestion,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = colors.inkPrimary,
                    )
                }
            }
        }
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
    Box(
        modifier = Modifier.padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        TinkeringPill(text = "Tinkering...")
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
                    onClick = { /* attachments / context */ },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        KaivoIcons.icon("add"),
                        contentDescription = "Add context",
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
                        .background(if (canSend) colors.royalGold else Color(0xFF2C2C34))
                        .clickable(enabled = canSend) { onSend() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        KaivoIcons.icon("forward"),
                        contentDescription = "Send",
                        tint = if (canSend) Color.Black else colors.inkSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiProviderSettingsSheet(
    currentConfig: AiProviderConfig,
    isDiscovering: Boolean,
    discoveryMessage: String?,
    isDiscoveryError: Boolean,
    onDiscoverModels: (AiProvider, String, String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (AiProviderConfig) -> Unit,
) {
    val colors = KaivoTheme.colors
    var selectedProvider by remember { mutableStateOf(currentConfig.provider) }
    var selectedModel by remember(currentConfig.model) { mutableStateOf(currentConfig.model) }
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var baseUrl by remember { mutableStateOf(currentConfig.baseUrl) }
    var showPassword by remember { mutableStateOf(false) }

    val effectiveModels = currentConfig.effectiveModels.ifEmpty { selectedProvider.availableModels }

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
                    text = "AI Provider & Model Settings",
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
                fontWeight = FontWeight.SemiBold,
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
                                baseUrl = provider.defaultBaseUrl
                                selectedModel = provider.defaultModel
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

            // Base URL field (supports custom OpenAI-compatible endpoints like NVIDIA NIM, local vLLM, etc.)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL / Endpoint") },
                placeholder = { Text("e.g. https://integrate.api.nvidia.com/v1") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.royalGold,
                    unfocusedBorderColor = colors.hairline,
                    focusedTextColor = colors.inkPrimary,
                    unfocusedTextColor = colors.inkPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // API Key field (optional for local/proxy endpoints)
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = {
                    Text(
                        if (selectedProvider.requiresApiKey) "API Key *"
                        else "API Key (Optional for local / proxies)"
                    )
                },
                placeholder = { Text("Enter your API key") },
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

            // Dynamic Discovery Button & Tinkering Pill
            if (isDiscovering) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TinkeringPill(text = "Tinkering & Discovering Models...")
                }
            } else {
                OutlinedButton(
                    onClick = { onDiscoverModels(selectedProvider, baseUrl, apiKey) },
                    shape = KaivoShapes.chip,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.royalGold.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.royalGold),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        KaivoIcons.icon("refresh"),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Connect & Discover Models",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Discovery status message
            if (discoveryMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(KaivoShapes.chip)
                        .background(if (isDiscoveryError) colors.statusError.copy(alpha = 0.15f) else colors.statusSuccess.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = discoveryMessage,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = if (isDiscoveryError) colors.statusError else colors.statusSuccess,
                    )
                }
            }

            // Model Selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Active Model",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.inkPrimary,
                )

                OutlinedTextField(
                    value = selectedModel,
                    onValueChange = { selectedModel = it },
                    label = { Text("Model ID") },
                    placeholder = { Text("e.g. meta/llama-3.3-70b-instruct or gpt-4o") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.royalGold,
                        unfocusedBorderColor = colors.hairline,
                        focusedTextColor = colors.inkPrimary,
                        unfocusedTextColor = colors.inkPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (effectiveModels.isNotEmpty()) {
                    Text(
                        text = "Available / Discovered Models:",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(effectiveModels) { model ->
                            val isSelected = model == selectedModel
                            Box(
                                Modifier
                                    .clip(KaivoShapes.chip)
                                    .background(if (isSelected) colors.royalGold else colors.surfaceCard)
                                    .border(1.dp, if (isSelected) colors.royalGold else colors.hairline, KaivoShapes.chip)
                                    .clickable { selectedModel = model }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = model,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) Color.Black else colors.inkPrimary,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    onSave(
                        AiProviderConfig(
                            provider = selectedProvider,
                            model = selectedModel.trim().ifBlank { selectedProvider.defaultModel },
                            apiKey = apiKey.trim(),
                            baseUrl = baseUrl.trim(),
                            discoveredModels = currentConfig.discoveredModels,
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = Color.Black),
                shape = KaivoShapes.chip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Save & Apply Settings",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
