package com.kaivoflow.feature.flows

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.model.CredentialItem
import com.kaivoflow.core.model.CredentialTypeInfo
import com.kaivoflow.core.model.DataTable
import com.kaivoflow.core.model.DataTableColumn
import com.kaivoflow.core.model.DataTableRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ============================================================================
// Time / Date Format Helpers
// ============================================================================

fun formatRelativeTime(timeMs: Long): String {
    val diff = System.currentTimeMillis() - timeMs
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        diff < 60_000L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        days < 30L -> "${days}d ago"
        else -> "${days / 30}mo ago"
    }
}

fun formatSimpleDate(timeMs: Long): String {
    val date = Date(timeMs)
    val formatter = SimpleDateFormat("d MMMM", Locale.getDefault())
    return formatter.format(date)
}

// ============================================================================
// Credentials View
// ============================================================================

@Composable
fun CredentialsDashboardView(
    credentials: List<CredentialItem>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onCreateCredential: () -> Unit,
    onDeleteCredential: (String) -> Unit,
    onOpenCredential: ((CredentialItem) -> Unit)? = null,
    onRenameCredential: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    val filtered = remember(credentials, searchQuery) {
        if (searchQuery.isBlank()) credentials
        else credentials.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.type.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Search bar & Action button
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
                    Text("Search credentials…", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                },
                leadingIcon = {
                    Icon(KaivoIcons.icon("search"), contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
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
                onClick = onCreateCredential,
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
                        Icon(KaivoIcons.icon("key"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (searchQuery.isNotBlank()) "No credentials match \"$searchQuery\"" else "No credentials yet",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Store API keys and OAuth tokens for OpenAI, Claude, Gemini, Ollama, Telegram and external services securely in this phone's Keystore.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    GoldButton(
                        text = "Create credential",
                        icon = "add",
                        onClick = onCreateCredential,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(filtered, key = { it.id }) { cred ->
                    CredentialCardItem(
                        credential = cred,
                        onClick = { onOpenCredential?.invoke(cred) },
                        onDelete = { onDeleteCredential(cred.id) },
                        onRename = { newName -> onRenameCredential(cred.id, newName) },
                    )
                }
            }
        }
    }
}

@Composable
fun CredentialCardItem(
    credential: CredentialItem,
    onClick: () -> Unit = {},
    onDelete: () -> Unit,
    onRename: (String) -> Unit = {},
) {
    val colors = KaivoTheme.colors
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    val typeInfo = remember(credential.type) {
        CredentialTypeInfo.find(credential.type)
    }

    val accentColor = remember(typeInfo) {
        typeInfo?.accentColor?.let { Color(it) } ?: colors.royalGold
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.stepCard)
            .clickable { onClick() }
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Service Icon Box
            Box(
                Modifier
                    .size(44.dp)
                    .clip(KaivoShapes.chip)
                    .background(accentColor.copy(alpha = 0.15f))
                    .border(1.dp, accentColor.copy(alpha = 0.35f), KaivoShapes.chip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    KaivoIcons.icon(typeInfo?.iconKey ?: "key"),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            // Title and Subtitle
            Column(Modifier.weight(1f)) {
                Text(
                    credential.name,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${typeInfo?.displayName ?: credential.type} | Updated ${formatRelativeTime(credential.updatedAt)} | Created ${formatSimpleDate(credential.createdAt)}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            // Dependencies link count pill
            Box(
                Modifier
                    .clip(KaivoShapes.chip)
                    .background(colors.surfaceElevated.copy(alpha = 0.7f))
                    .border(1.dp, colors.hairline, KaivoShapes.chip)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "🔗",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        credential.usedInFlowCount.toString(),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                    )
                }
            }

            // 3-dots Menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(KaivoIcons.icon("more"), contentDescription = "Actions", tint = colors.inkSecondary)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(colors.surfaceElevated),
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit & Test Connection", color = colors.inkPrimary) },
                        leadingIcon = { Icon(KaivoIcons.icon("edit"), null, tint = colors.royalGold) },
                        onClick = {
                            showMenu = false
                            onClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename", color = colors.inkPrimary) },
                        leadingIcon = { Icon(KaivoIcons.icon("edit"), null, tint = colors.inkSecondary) },
                        onClick = {
                            showMenu = false
                            showRenameDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = colors.statusError) },
                        leadingIcon = { Icon(KaivoIcons.icon("delete"), null, tint = colors.statusError) },
                        onClick = {
                            showMenu = false
                            showDeleteConfirm = true
                        },
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = colors.surfaceAtelier,
            shape = KaivoShapes.stepCard,
            title = { Text("Delete Credential", color = colors.inkPrimary) },
            text = {
                Text(
                    "Are you sure you want to delete \"${credential.name}\"? Workflows using it will no longer be able to authenticate.",
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
                    shape = KaivoShapes.chip,
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = colors.inkSecondary)
                }
            },
        )
    }

    if (showRenameDialog) {
        RenameCredentialDialog(
            initialName = credential.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                onRename(newName)
            },
        )
    }
}

@Composable
fun RenameCredentialDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val colors = KaivoTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceAtelier,
        shape = KaivoShapes.stepCard,
        title = {
            Text(
                "Rename Credential",
                color = colors.inkPrimary,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter a new name for this credential account:",
                    color = colors.inkSecondary,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.royalGold,
                        unfocusedBorderColor = colors.hairline,
                        focusedTextColor = colors.inkPrimary,
                        unfocusedTextColor = colors.inkPrimary,
                    ),
                    shape = KaivoShapes.chip,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim())
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = Color.Black),
                shape = KaivoShapes.chip,
                enabled = name.isNotBlank(),
            ) {
                Text("Rename", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.inkSecondary)
            }
        },
    )
}

// ============================================================================
// Create Credential Dialog
// ============================================================================

@Composable
fun CreateCredentialDialog(
    existingCredentials: List<CredentialItem> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (type: String, name: String, fields: Map<String, String>) -> Unit,
) {
    val colors = KaivoTheme.colors

    fun nextReplicaName(typeInfo: CredentialTypeInfo): String {
        val prefix = when (typeInfo.type) {
            "openAiApi" -> "openai"
            "geminiApi" -> "gemini"
            "anthropicApi" -> "anthropic"
            "ollamaApi" -> "ollama"
            "telegramBot" -> "telegram"
            else -> typeInfo.type.removeSuffix("Api").lowercase()
        }
        val existingNames = existingCredentials.map { it.name.lowercase() }
        var count = 1
        while (existingNames.contains("${prefix}_$count")) {
            count++
        }
        return "${prefix}_$count"
    }

    var selectedType by remember { mutableStateOf(CredentialTypeInfo.OPENAI) }
    var name by remember { mutableStateOf(nextReplicaName(CredentialTypeInfo.OPENAI)) }
    val fieldValues = remember { mutableStateMapOf<String, String>() }
    var nameError by remember { mutableStateOf<String?>(null) }
    val secretVisibility = remember { mutableStateMapOf<String, Boolean>() }

    fun selectType(typeInfo: CredentialTypeInfo) {
        selectedType = typeInfo
        name = nextReplicaName(typeInfo)
        fieldValues.clear()
        secretVisibility.clear()
    }

    fun validateAndSave() {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            nameError = "Credential name is required"
            return
        }
        onSave(selectedType.type, trimmedName, fieldValues.toMap())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceAtelier,
        shape = KaivoShapes.stepCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(KaivoIcons.icon("key"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("New Credential", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Select service type:",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = colors.inkSecondary,
                )
                Spacer(Modifier.height(8.dp))

                // Service Type Selector Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(CredentialTypeInfo.ALL, key = { it.type }) { typeInfo ->
                        val isSelected = selectedType.type == typeInfo.type
                        val typeAccent = Color(typeInfo.accentColor)
                        Box(
                            Modifier
                                .clip(KaivoShapes.chip)
                                .background(if (isSelected) typeAccent.copy(alpha = 0.25f) else colors.surfaceElevated)
                                .border(1.dp, if (isSelected) typeAccent else colors.hairline, KaivoShapes.chip)
                                .clickable { selectType(typeInfo) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    KaivoIcons.icon(typeInfo.iconKey),
                                    contentDescription = null,
                                    tint = if (isSelected) typeAccent else colors.inkMuted,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    typeInfo.displayName.removeSuffix(" API"),
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) colors.inkPrimary else colors.inkSecondary,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = null
                    },
                    label = { Text("Credential Name") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it, color = colors.statusError) } },
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

                // Dynamic Service Fields
                selectedType.fields.forEach { field ->
                    val isSecret = field.isSecret
                    val isVisible = secretVisibility[field.key] ?: false
                    val value = fieldValues[field.key] ?: ""

                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { fieldValues[field.key] = it },
                        label = { Text(field.label) },
                        placeholder = { Text(field.placeholder, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        shape = KaivoShapes.chip,
                        visualTransformation = if (isSecret && !isVisible) PasswordVisualTransformation() else VisualTransformation.None,
                        trailingIcon = if (isSecret) {
                            {
                                IconButton(onClick = { secretVisibility[field.key] = !isVisible }) {
                                    Icon(
                                        KaivoIcons.icon(if (isVisible) "eyeOff" else "eye"),
                                        contentDescription = "Toggle visibility",
                                        tint = colors.inkMuted,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        } else null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.royalGold,
                            unfocusedBorderColor = colors.hairline,
                            focusedTextColor = colors.inkPrimary,
                            unfocusedTextColor = colors.inkPrimary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::validateAndSave,
                colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = colors.canvasVoid),
                shape = KaivoShapes.chip,
            ) {
                Text("Save Credential")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = colors.inkSecondary)) {
                Text("Cancel")
            }
        },
    )
}

// ============================================================================
// Data Tables View
// ============================================================================

@Composable
fun DataTablesDashboardView(
    tables: List<DataTable>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onCreateTable: () -> Unit,
    onViewTable: (DataTable) -> Unit,
    onDeleteTable: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    val filtered = remember(tables, searchQuery) {
        if (searchQuery.isBlank()) tables
        else tables.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Search bar & Action button
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
                    Text("Search data tables…", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                },
                leadingIcon = {
                    Icon(KaivoIcons.icon("search"), contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
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
                onClick = onCreateTable,
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
                        Icon(KaivoIcons.icon("table"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (searchQuery.isNotBlank()) "No data tables match \"$searchQuery\"" else "No data tables yet",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Create custom schemas with typed columns (String, Number, Boolean, Date) to store, query and update records across your workflow runs.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    GoldButton(
                        text = "Create data table",
                        icon = "add",
                        onClick = onCreateTable,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(filtered, key = { it.id }) { table ->
                    DataTableCardItem(
                        table = table,
                        onView = { onViewTable(table) },
                        onDelete = { onDeleteTable(table.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun DataTableCardItem(
    table: DataTable,
    onView: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = KaivoTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.stepCard)
            .clickable(onClick = onView)
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Table Icon
            Box(
                Modifier
                    .size(44.dp)
                    .clip(KaivoShapes.chip)
                    .background(colors.royalGold.copy(alpha = 0.15f))
                    .border(1.dp, colors.royalGold.copy(alpha = 0.35f), KaivoShapes.chip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    KaivoIcons.icon("table"),
                    contentDescription = null,
                    tint = colors.royalGold,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            // Name and details
            Column(Modifier.weight(1f)) {
                Text(
                    table.name,
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${table.rowCount} rows • ${table.columns.size} columns | Created ${formatSimpleDate(table.createdAt)}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            // 3-dots Menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(KaivoIcons.icon("more"), contentDescription = "Actions", tint = colors.inkSecondary)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(colors.surfaceElevated),
                ) {
                    DropdownMenuItem(
                        text = { Text("View Data", color = colors.inkPrimary) },
                        leadingIcon = { Icon(KaivoIcons.icon("table"), null, tint = colors.royalGold) },
                        onClick = {
                            showMenu = false
                            onView()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Table", color = colors.statusError) },
                        leadingIcon = { Icon(KaivoIcons.icon("delete"), null, tint = colors.statusError) },
                        onClick = {
                            showMenu = false
                            showDeleteConfirm = true
                        },
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = colors.surfaceAtelier,
            shape = KaivoShapes.stepCard,
            title = { Text("Delete Data Table", color = colors.inkPrimary) },
            text = {
                Text(
                    "Are you sure you want to delete table \"${table.name}\"? All rows in this table will be permanently deleted.",
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
                    shape = KaivoShapes.chip,
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = colors.inkSecondary)
                }
            },
        )
    }
}

// ============================================================================
// Create Data Table Dialog
// ============================================================================

@Composable
fun CreateDataTableDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, columns: List<DataTableColumn>) -> Unit,
) {
    val colors = KaivoTheme.colors
    var tableName by remember { mutableStateOf("") }
    var tableDesc by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var columns by remember {
        mutableStateOf(
            listOf(
                DataTableColumn(UUID.randomUUID().toString(), "id", "string"),
                DataTableColumn(UUID.randomUUID().toString(), "name", "string"),
                DataTableColumn(UUID.randomUUID().toString(), "status", "string"),
            )
        )
    }

    fun addColumn() {
        columns = columns + DataTableColumn(UUID.randomUUID().toString(), "column_${columns.size + 1}", "string")
    }

    fun updateColumnName(index: Int, newName: String) {
        columns = columns.toMutableList().apply {
            this[index] = this[index].copy(name = newName)
        }
    }

    fun updateColumnType(index: Int, newType: String) {
        columns = columns.toMutableList().apply {
            this[index] = this[index].copy(type = newType)
        }
    }

    fun removeColumn(index: Int) {
        if (columns.size > 1) {
            columns = columns.toMutableList().apply { removeAt(index) }
        }
    }

    fun validateAndCreate() {
        val trimmed = tableName.trim()
        if (trimmed.isEmpty()) {
            nameError = "Table name is required"
            return
        }
        onCreate(trimmed, tableDesc.trim(), columns)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceAtelier,
        shape = KaivoShapes.stepCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(KaivoIcons.icon("table"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("New Data Table", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = tableName,
                    onValueChange = {
                        tableName = it
                        nameError = null
                    },
                    label = { Text("Table Name (e.g. Customers)") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it, color = colors.statusError) } },
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
                    value = tableDesc,
                    onValueChange = { tableDesc = it },
                    label = { Text("Description (optional)") },
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

                Spacer(Modifier.height(14.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Columns (${columns.size})",
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        color = colors.royalGold,
                    )
                    TextButton(onClick = ::addColumn) {
                        Icon(KaivoIcons.icon("add"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Column", color = colors.royalGold)
                    }
                }

                Spacer(Modifier.height(6.dp))

                columns.forEachIndexed { index, col ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedTextField(
                            value = col.name,
                            onValueChange = { updateColumnName(index, it) },
                            singleLine = true,
                            shape = KaivoShapes.chip,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.royalGold,
                                unfocusedBorderColor = colors.hairline,
                                focusedTextColor = colors.inkPrimary,
                                unfocusedTextColor = colors.inkPrimary,
                            ),
                            modifier = Modifier.weight(1.5f),
                        )

                        // Column type selector chip
                        val types = listOf("string", "number", "boolean", "date")
                        var typeMenuOpen by remember { mutableStateOf(false) }
                        Box {
                            Box(
                                Modifier
                                    .clip(KaivoShapes.chip)
                                    .background(colors.surfaceElevated)
                                    .border(1.dp, colors.hairline, KaivoShapes.chip)
                                    .clickable { typeMenuOpen = true }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    col.type,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    color = colors.royalGold,
                                )
                            }
                            DropdownMenu(
                                expanded = typeMenuOpen,
                                onDismissRequest = { typeMenuOpen = false },
                                modifier = Modifier.background(colors.surfaceElevated),
                            ) {
                                types.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(t, color = colors.inkPrimary) },
                                        onClick = {
                                            updateColumnType(index, t)
                                            typeMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }

                        if (columns.size > 1) {
                            IconButton(onClick = { removeColumn(index) }, modifier = Modifier.size(32.dp)) {
                                Icon(KaivoIcons.icon("close"), contentDescription = "Remove", tint = colors.statusError, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::validateAndCreate,
                colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = colors.canvasVoid),
                shape = KaivoShapes.chip,
            ) {
                Text("Create Table")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = colors.inkSecondary)) {
                Text("Cancel")
            }
        },
    )
}

// ============================================================================
// Data Table Detail Sheet (View & Add Rows)
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataTableDetailSheet(
    table: DataTable,
    rows: List<DataTableRow>,
    onAddRow: (Map<String, Any?>) -> Unit,
    onDeleteRow: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KaivoTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAddRowDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceAtelier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        table.name,
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        color = colors.inkPrimary,
                    )
                    Text(
                        "${rows.size} rows • ${table.columns.size} columns",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
                GoldButton(
                    text = "Add row",
                    icon = "add",
                    onClick = { showAddRowDialog = true },
                )
            }

            Spacer(Modifier.height(14.dp))

            if (rows.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(KaivoIcons.icon("table"), contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No rows yet", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap 'Add row' or insert records from your flows.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkMuted)
                    }
                }
            } else {
                // Table header
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .clip(KaivoShapes.chip)
                        .background(colors.surfaceElevated)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(table.columns, key = { it.id }) { col ->
                        Text(
                            col.name.uppercase(),
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = colors.royalGold,
                            modifier = Modifier.width(100.dp),
                        )
                    }
                    item {
                        Spacer(Modifier.width(32.dp))
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Rows list
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(rows, key = { it.id }) { row ->
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
                                LazyRow(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(table.columns, key = { it.id }) { col ->
                                        val value = row.data[col.name]?.toString() ?: ""
                                        Text(
                                            value.ifBlank { "—" },
                                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            color = colors.inkPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.width(100.dp),
                                        )
                                    }
                                }
                                IconButton(onClick = { onDeleteRow(row.id) }, modifier = Modifier.size(28.dp)) {
                                    Icon(KaivoIcons.icon("delete"), contentDescription = "Delete row", tint = colors.statusError, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddRowDialog) {
        val newValues = remember { mutableStateMapOf<String, String>() }
        AlertDialog(
            onDismissRequest = { showAddRowDialog = false },
            containerColor = colors.surfaceAtelier,
            shape = KaivoShapes.stepCard,
            title = { Text("Add Row to ${table.name}", color = colors.inkPrimary) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    table.columns.forEach { col ->
                        val value = newValues[col.name] ?: ""
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = value,
                            onValueChange = { newValues[col.name] = it },
                            label = { Text("${col.name} (${col.type})") },
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
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = mutableMapOf<String, Any?>()
                        table.columns.forEach { col ->
                            val text = newValues[col.name]?.trim().orEmpty()
                            parsed[col.name] = when (col.type) {
                                "number" -> text.toDoubleOrNull() ?: 0.0
                                "boolean" -> text.equals("true", ignoreCase = true)
                                else -> text
                            }
                        }
                        onAddRow(parsed)
                        showAddRowDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = colors.canvasVoid),
                    shape = KaivoShapes.chip,
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRowDialog = false }) {
                    Text("Cancel", color = colors.inkSecondary)
                }
            },
        )
    }
}
