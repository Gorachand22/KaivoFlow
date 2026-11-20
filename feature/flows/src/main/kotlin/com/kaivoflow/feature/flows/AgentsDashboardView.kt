package com.kaivoflow.feature.flows

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.feature.flows.ai.agent.AgentSession
import com.kaivoflow.feature.flows.ai.agent.AgentLiveState
import com.kaivoflow.feature.flows.ai.agent.CustomAgent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sovereign Agents Tab View for KaivoFlow.
 * Displays all built and published custom agents with their status, model,
 * triggers, and execution history.
 */
@Composable
fun AgentsDashboardView(
    agents: List<CustomAgent>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onCreateAgent: () -> Unit,
    onOpenAgent: (String) -> Unit,
    onViewHistory: (CustomAgent) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDuplicateAgent: (String) -> Unit,
    onTogglePublish: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** What the Always-On engine says per agent id. An agent without an entry is not published. */
    liveStates: Map<String, AgentLiveState> = emptyMap(),
    onPause: (String) -> Unit = {},
    onResume: (String) -> Unit = {},
    onRetry: (String) -> Unit = {},
    onPublishChanges: (String) -> Unit = {},
) {
    val colors = KaivoTheme.colors
    val filtered = remember(agents, searchQuery) {
        if (searchQuery.isBlank()) agents
        else agents.filter { agent ->
            agent.name.contains(searchQuery, ignoreCase = true) ||
                agent.model.contains(searchQuery, ignoreCase = true) ||
                agent.instructions.contains(searchQuery, ignoreCase = true) ||
                agent.triggers.any { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Search bar & New Agent Action
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = {
                    Text("Search agents…", style = MaterialTheme.typography.bodySmall)
                },
                leadingIcon = {
                    Icon(
                        KaivoIcons.icon("search"),
                        contentDescription = null,
                        tint = colors.inkMuted,
                        modifier = Modifier.size(18.dp),
                    )
                },
                singleLine = true,
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
                onClick = onCreateAgent,
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
                        Icon(
                            KaivoIcons.icon("flash"),
                            contentDescription = null,
                            tint = colors.royalGold,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (searchQuery.isNotBlank()) "No agents match \"$searchQuery\"" else "No agents built yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Build sovereign AI agents with instructions, scheduled triggers, web capabilities, and tool execution.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    GoldButton(
                        text = "Build Agent",
                        icon = "add",
                        onClick = onCreateAgent,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(filtered, key = { it.id }) { agent ->
                    AgentCardItem(
                        agent = agent,
                        onClick = { onOpenAgent(agent.id) },
                        onViewHistory = { onViewHistory(agent) },
                        onDelete = { onDeleteAgent(agent.id) },
                        onDuplicate = { onDuplicateAgent(agent.id) },
                        onTogglePublish = { onTogglePublish(agent.id) },
                        liveState = liveStates[agent.id] ?: AgentLiveState.NotPublished,
                        onPause = { onPause(agent.id) },
                        onResume = { onResume(agent.id) },
                        onRetry = { onRetry(agent.id) },
                        onPublishChanges = { onPublishChanges(agent.id) },
                    )
                }
            }
        }
    }
}

/**
 * Agent Card representation matching KaivoFlow's Sovereign Card standards.
 */
@Composable
fun AgentCardItem(
    agent: CustomAgent,
    onClick: () -> Unit,
    onViewHistory: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onTogglePublish: () -> Unit,
    liveState: AgentLiveState = AgentLiveState.NotPublished,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onRetry: () -> Unit = {},
    onPublishChanges: () -> Unit = {},
) {
    val colors = KaivoTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val accentColor = remember(agent.iconColorHex) {
        Color(agent.iconColorHex)
    }

    val lastSession = agent.sessions.firstOrNull()

    Box(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(
                1.dp,
                if (liveState is AgentLiveState.Live) colors.royalGold.copy(alpha = 0.45f) else colors.hairline,
                KaivoShapes.stepCard,
            )
            .clickable { onClick() }
            .padding(14.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Agent Avatar Box
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(KaivoShapes.chip)
                        .background(accentColor.copy(alpha = 0.18f))
                        .border(1.dp, accentColor.copy(alpha = 0.4f), KaivoShapes.chip),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = agent.iconEmoji.ifBlank { "🤖" },
                        fontSize = 22.sp,
                    )
                }

                Spacer(Modifier.width(14.dp))

                // Title and Subtitle Info
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            agent.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // Status pill: always the engine's state for this agent, never a stored flag.
                        val (pillText, pillColor) = when (liveState) {
                            is AgentLiveState.Live -> (if (liveState.changesNotLive) "● Live · changes not live" else "● Live") to colors.statusSuccess
                            is AgentLiveState.Paused -> "Paused" to colors.royalGold
                            is AgentLiveState.Failing -> "Needs attention" to colors.statusError
                            AgentLiveState.NotPublished -> "Draft" to colors.inkSecondary
                        }
                        Box(
                            Modifier
                                .clip(KaivoShapes.chip)
                                .background(if (liveState == AgentLiveState.NotPublished) colors.surfaceElevated else pillColor.copy(alpha = 0.15f))
                                .border(1.dp, if (liveState == AgentLiveState.NotPublished) colors.hairline else pillColor.copy(alpha = 0.4f), KaivoShapes.chip)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                pillText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (liveState == AgentLiveState.NotPublished) FontWeight.Normal else FontWeight.Bold,
                                color = pillColor,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }

                    Spacer(Modifier.height(3.dp))

                    // Model & trigger details
                    val triggerSummary = agent.triggers.firstOrNull()?.name ?: "Manual Spark"
                    val skillsCountText = if (agent.skills.isNotEmpty()) " • ${agent.skills.size} skills" else ""
                    Text(
                        "${agent.model} • $triggerSummary$skillsCountText",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 3-Dots Menu
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            KaivoIcons.icon("more"),
                            contentDescription = "Options",
                            tint = colors.inkSecondary,
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier
                            .background(colors.surfaceElevated)
                            .border(1.dp, colors.hairline, KaivoShapes.chip),
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit Agent", color = colors.royalGold) },
                            leadingIcon = {
                                Icon(KaivoIcons.icon("create"), null, tint = colors.royalGold, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showMenu = false
                                onClick()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Execution History", color = colors.inkPrimary) },
                            leadingIcon = {
                                Icon(KaivoIcons.icon("history"), null, tint = colors.inkSecondary, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showMenu = false
                                onViewHistory()
                            },
                        )
                        val isLive = liveState != AgentLiveState.NotPublished
                        if (!isLive) {
                            DropdownMenuItem(
                                text = { Text("Publish (Run 24/7)", color = colors.inkPrimary) },
                                leadingIcon = { Icon(KaivoIcons.icon("play"), null, tint = colors.royalGold, modifier = Modifier.size(18.dp)) },
                                onClick = { showMenu = false; onTogglePublish() },
                            )
                        }
                        if (liveState is AgentLiveState.Live && liveState.changesNotLive) {
                            DropdownMenuItem(
                                text = { Text("Publish changes", color = colors.inkPrimary) },
                                leadingIcon = { Icon(KaivoIcons.icon("play"), null, tint = colors.royalGold, modifier = Modifier.size(18.dp)) },
                                onClick = { showMenu = false; onPublishChanges() },
                            )
                        }
                        if (liveState is AgentLiveState.Live) {
                            DropdownMenuItem(
                                text = { Text("Pause", color = colors.inkPrimary) },
                                leadingIcon = { Icon(KaivoIcons.icon("pause"), null, tint = colors.inkSecondary, modifier = Modifier.size(18.dp)) },
                                onClick = { showMenu = false; onPause() },
                            )
                        }
                        if (liveState is AgentLiveState.Paused) {
                            DropdownMenuItem(
                                text = { Text("Resume", color = colors.inkPrimary) },
                                leadingIcon = { Icon(KaivoIcons.icon("play"), null, tint = colors.royalGold, modifier = Modifier.size(18.dp)) },
                                onClick = { showMenu = false; onResume() },
                            )
                        }
                        if (liveState is AgentLiveState.Failing) {
                            DropdownMenuItem(
                                text = { Text("Retry now", color = colors.inkPrimary) },
                                leadingIcon = { Icon(KaivoIcons.icon("play"), null, tint = colors.royalGold, modifier = Modifier.size(18.dp)) },
                                onClick = { showMenu = false; onRetry() },
                            )
                        }
                        if (isLive) {
                            DropdownMenuItem(
                                text = { Text("Unpublish (Stop 24/7)", color = colors.inkPrimary) },
                                leadingIcon = { Icon(KaivoIcons.icon("pause"), null, tint = colors.inkSecondary, modifier = Modifier.size(18.dp)) },
                                onClick = { showMenu = false; onTogglePublish() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Duplicate Agent", color = colors.inkPrimary) },
                            leadingIcon = {
                                Icon(KaivoIcons.icon("copy"), null, tint = colors.inkSecondary, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showMenu = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = colors.statusError) },
                            leadingIcon = {
                                Icon(KaivoIcons.icon("delete"), null, tint = colors.statusError, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showMenu = false
                                showDeleteConfirm = true
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Bottom Execution Summary Row
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (lastSession != null) {
                    val isSuccess = lastSession.status.equals("Success", ignoreCase = true)
                    Text(
                        if (isSuccess) "✓ last run worked" else "✗ run issue detected",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSuccess) colors.statusSuccess else colors.statusError,
                    )
                } else {
                    Text(
                        "No runs recorded yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                    )
                }

                Spacer(Modifier.weight(1f))

                // History button pill
                Row(
                    modifier = Modifier
                        .clip(KaivoShapes.chip)
                        .background(colors.canvasVoid)
                        .border(1.dp, colors.hairline, KaivoShapes.chip)
                        .clickable { onViewHistory() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        KaivoIcons.icon("history"),
                        contentDescription = null,
                        tint = colors.royalGold,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        "${agent.sessions.size} runs",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                    )
                    if (lastSession != null) {
                        Text(
                            "• ${formatRelativeTime(lastSession.timestamp)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.inkMuted,
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Agent", color = colors.inkPrimary) },
            text = {
                Text(
                    "Are you sure you want to delete \"${agent.name}\"? ${if (liveState != AgentLiveState.NotPublished) "It will stop running first. " else ""}All instructions, trigger schedules, and execution history will be removed.",
                    color = colors.inkSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.statusError),
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = colors.inkSecondary)
                }
            },
            containerColor = colors.surfaceElevated,
        )
    }
}

/**
 * Agent Execution History Inspector Sheet.
 * Displays all recorded test runs and trigger invocations with full task prompts,
 * agent traces, durations, and output logs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentExecutionHistorySheet(
    agent: CustomAgent,
    onDismiss: () -> Unit,
    onOpenBuilder: () -> Unit,
) {
    val colors = KaivoTheme.colors
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceElevated,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 700.dp)
                .padding(16.dp),
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(KaivoShapes.chip)
                            .background(Color(agent.iconColorHex).copy(alpha = 0.2f))
                            .border(1.dp, Color(agent.iconColorHex).copy(alpha = 0.4f), KaivoShapes.chip),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(agent.iconEmoji.ifBlank { "🤖" }, fontSize = 18.sp)
                    }
                    Column {
                        Text(
                            "${agent.name} — Execution History",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            "${agent.sessions.size} runs • Model: ${agent.model}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(KaivoIcons.icon("close"), contentDescription = "Close", tint = colors.inkSecondary)
                }
            }

            Spacer(Modifier.height(14.dp))

            if (agent.sessions.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            KaivoIcons.icon("history"),
                            contentDescription = null,
                            tint = colors.royalGold,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No execution history yet",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.inkPrimary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "When this agent runs through triggers or test prompts, detailed step execution traces and agent responses will be logged here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(18.dp))
                        GoldButton(
                            text = "Open in Builder",
                            icon = "create",
                            onClick = {
                                onDismiss()
                                onOpenBuilder()
                            },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(agent.sessions, key = { it.id }) { session ->
                        val isSuccess = session.status.equals("Success", ignoreCase = true)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(KaivoShapes.stepCard)
                                .background(colors.surfaceCard)
                                .border(1.dp, colors.hairline, KaivoShapes.stepCard)
                                .padding(14.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Session Header Status Row
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Box(
                                            Modifier
                                                .clip(KaivoShapes.chip)
                                                .background(
                                                    if (isSuccess) colors.statusSuccess.copy(alpha = 0.15f)
                                                    else colors.statusError.copy(alpha = 0.15f),
                                                )
                                                .padding(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = if (isSuccess) "✓ SUCCESS" else "✗ ERROR",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSuccess) colors.statusSuccess else colors.statusError,
                                            )
                                        }
                                        Text(
                                            text = "${session.durationMs}ms",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.inkSecondary,
                                        )
                                    }
                                    Text(
                                        text = dateFormat.format(Date(session.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.inkMuted,
                                    )
                                }

                                // Prompt / Task Section
                                Column {
                                    Text(
                                        "TASK / PROMPT",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.royalGold,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(KaivoShapes.chip)
                                            .background(colors.canvasVoid)
                                            .border(1.dp, colors.hairline, KaivoShapes.chip)
                                            .padding(10.dp),
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                text = session.prompt,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = colors.inkPrimary,
                                            )
                                        }
                                    }
                                }

                                // Output / Response Section
                                Column {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            "AGENT RESPONSE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.royalGold,
                                        )
                                        Row(
                                            Modifier
                                                .clip(KaivoShapes.chip)
                                                .clickable {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("Agent Response", session.response))
                                                    Toast.makeText(context, "Copied response", Toast.LENGTH_SHORT).show()
                                                }
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                KaivoIcons.icon("copy"),
                                                contentDescription = "Copy",
                                                tint = colors.inkSecondary,
                                                modifier = Modifier.size(12.dp),
                                            )
                                            Text(
                                                "Copy",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.inkSecondary,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(KaivoShapes.chip)
                                            .background(colors.canvasVoid)
                                            .border(1.dp, colors.hairline, KaivoShapes.chip)
                                            .padding(10.dp),
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                text = session.response,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.inkPrimary,
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
