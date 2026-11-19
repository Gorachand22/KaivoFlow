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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.model.CredentialItem
import com.kaivoflow.core.model.ExprFieldCodec
import com.kaivoflow.core.model.WorkflowVariable
import com.kaivoflow.feature.canvas.input.KaivoInputField
import com.kaivoflow.core.model.CredentialTypeInfo
import kotlinx.coroutines.launch

enum class CredentialEditTab {
    Connection, Details
}

sealed class ConnectionTestState {
    object Idle : ConnectionTestState()
    object Testing : ConnectionTestState()
    data class Success(val message: String = "Connection tested successfully") : ConnectionTestState()
    data class Error(val error: String) : ConnectionTestState()
}

/**
 * Sovereign Credential Edit Modal modeled after the reference architecture.
 * Supports:
 * - Connection tab: real connectivity test, API key masking with eye toggle, Base URL & presets
 * - Details tab: Created timestamp, Last modified timestamp, sovereign ID with copy
 * - Editable name, Saved badge, delete & test retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialEditSheet(
    credential: CredentialItem,
    initialFields: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (name: String, fields: Map<String, String>) -> Unit,
    onDelete: (id: String) -> Unit,
    onTestConnection: suspend (type: String, fields: Map<String, String>) -> Result<List<String>>,
    modifier: Modifier = Modifier,
    variables: List<WorkflowVariable> = emptyList(),
) {
    val colors = KaivoTheme.colors
    // Spec P3: only non-secret fields take expressions, against the global scope ($vars, $now).
    val exprScope = remember(variables) { variableExprScope(variables) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var currentTab by remember { mutableStateOf(CredentialEditTab.Connection) }
    var credentialName by remember { mutableStateOf(credential.name) }
    var isEditingName by remember { mutableStateOf(false) }
    val fields = remember { mutableStateMapOf<String, String>().apply { putAll(initialFields) } }
    val secretVisibility = remember { mutableStateMapOf<String, Boolean>() }
    var testState by remember { mutableStateOf<ConnectionTestState>(ConnectionTestState.Idle) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isSavedDirty by remember { mutableStateOf(false) }

    val typeInfo = remember(credential.type) {
        CredentialTypeInfo.find(credential.type) ?: CredentialTypeInfo.OPENAI
    }
    val accentColor = remember(typeInfo) { Color(typeInfo.accentColor) }

    fun runTest() {
        scope.launch {
            testState = ConnectionTestState.Testing
            val sanitized = fields.mapValues { (k, v) ->
                if (k == "apiKey" || k == "token" || k == "baseUrl") v.trim() else v
            }
            val res = onTestConnection(credential.type, sanitized)
            testState = if (res.isSuccess) {
                val models = res.getOrNull().orEmpty()
                val summary = if (models.isNotEmpty()) "Connection tested successfully (${models.size} models discovered)"
                else "Connection tested successfully"
                ConnectionTestState.Success(summary)
            } else {
                val err = res.exceptionOrNull()?.message ?: "Unknown connectivity error"
                ConnectionTestState.Error(err)
            }
        }
    }

    // Auto-test on first open if fields contain an API key or base URL
    LaunchedEffect(Unit) {
        if (fields["apiKey"]?.isNotBlank() == true || fields["baseUrl"]?.isNotBlank() == true) {
            runTest()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceCard,
        dragHandle = null,
        modifier = modifier.imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            // ---------------- Top Bar ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    // Type Icon
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(KaivoShapes.chip)
                            .background(accentColor.copy(alpha = 0.15f))
                            .border(1.dp, accentColor.copy(alpha = 0.35f), KaivoShapes.chip),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            KaivoIcons.icon(typeInfo.iconKey),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (isEditingName) {
                                BasicTextField(
                                    value = credentialName,
                                    onValueChange = {
                                        credentialName = it
                                        isSavedDirty = true
                                    },
                                    textStyle = androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(
                                        color = colors.inkPrimary,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    cursorBrush = SolidColor(colors.royalGold),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { isEditingName = false },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(KaivoIcons.icon("check"), contentDescription = "Done", tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { isEditingName = true },
                                ) {
                                    Text(
                                        text = credentialName,
                                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.inkPrimary,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        KaivoIcons.icon("edit"),
                                        contentDescription = "Rename",
                                        tint = colors.royalGold,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = typeInfo.displayName,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = colors.inkSecondary,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Saved Indicator Pill
                    Box(
                        modifier = Modifier
                            .clip(KaivoShapes.chip)
                            .background(if (isSavedDirty) colors.royalGold.copy(alpha = 0.2f) else colors.surfaceElevated)
                            .border(1.dp, if (isSavedDirty) colors.royalGold else colors.hairline, KaivoShapes.chip)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (isSavedDirty) "Unsaved" else "Saved",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSavedDirty) colors.royalGold else colors.inkSecondary,
                        )
                    }

                    // Delete button
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(KaivoIcons.icon("delete"), contentDescription = "Delete", tint = colors.statusError, modifier = Modifier.size(18.dp))
                    }

                    // Close button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(KaivoIcons.icon("close"), contentDescription = "Close", tint = colors.inkSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // ---------------- Tab Selector ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(KaivoShapes.chip)
                        .background(if (currentTab == CredentialEditTab.Connection) colors.surfaceElevated else colors.canvasVoid)
                        .border(1.dp, if (currentTab == CredentialEditTab.Connection) colors.royalGold else colors.hairline, KaivoShapes.chip)
                        .clickable { currentTab = CredentialEditTab.Connection }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Connection",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = if (currentTab == CredentialEditTab.Connection) FontWeight.Bold else FontWeight.Normal,
                        color = if (currentTab == CredentialEditTab.Connection) colors.inkPrimary else colors.inkSecondary,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(KaivoShapes.chip)
                        .background(if (currentTab == CredentialEditTab.Details) colors.surfaceElevated else colors.canvasVoid)
                        .border(1.dp, if (currentTab == CredentialEditTab.Details) colors.royalGold else colors.hairline, KaivoShapes.chip)
                        .clickable { currentTab = CredentialEditTab.Details }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Details",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = if (currentTab == CredentialEditTab.Details) FontWeight.Bold else FontWeight.Normal,
                        color = if (currentTab == CredentialEditTab.Details) colors.inkPrimary else colors.inkSecondary,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ---------------- Tab Content ----------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (currentTab) {
                    CredentialEditTab.Connection -> {
                        // Docs Help Banner
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E1E26))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "Need help filling out these fields? Configured keys remain sovereign & encrypted on device.",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = colors.inkSecondary,
                            )
                        }

                        // Connection Test Status Box
                        when (val state = testState) {
                            ConnectionTestState.Idle -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.surfaceElevated)
                                        .border(1.dp, colors.hairline, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = "Connection not tested yet",
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                            color = colors.inkSecondary,
                                        )
                                        OutlinedButton(
                                            onClick = ::runTest,
                                            shape = KaivoShapes.chip,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        ) {
                                            Text("Test Connection", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            ConnectionTestState.Testing -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.royalGold.copy(alpha = 0.12f))
                                        .border(1.dp, colors.royalGold.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = colors.royalGold,
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            text = "Testing connection...",
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.royalGold,
                                        )
                                    }
                                }
                            }
                            is ConnectionTestState.Success -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0F382A))
                                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
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
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Icon(
                                                KaivoIcons.icon("checkCircle"),
                                                contentDescription = null,
                                                tint = Color(0xFF10B981),
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Text(
                                                text = state.message,
                                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF34D399),
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = ::runTest,
                                            shape = KaivoShapes.chip,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF34D399)),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.6f)),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        ) {
                                            Text("Retry", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            is ConnectionTestState.Error -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF3B1818))
                                        .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
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
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Icon(
                                                KaivoIcons.icon("error"),
                                                contentDescription = null,
                                                tint = Color(0xFFEF4444),
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Column {
                                                Text(
                                                    text = "Connection test failed",
                                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFFF87171),
                                                )
                                                Text(
                                                    text = state.error,
                                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                                    color = colors.inkSecondary,
                                                    maxLines = 2,
                                                )
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = ::runTest,
                                            shape = KaivoShapes.chip,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171)),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        ) {
                                            Text("Retry", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        // Form Fields
                        typeInfo.fields.forEach { spec ->
                            val isSecret = spec.isSecret
                            val isVisible = secretVisibility[spec.key] ?: false
                            val value = fields[spec.key].orEmpty()

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = spec.label,
                                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.inkPrimary,
                                    )
                                    if (spec.required) {
                                        Text(text = " *", color = colors.royalGold, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (!isSecret) {
                                    KaivoInputField(
                                        value = ExprFieldCodec.decode(value),
                                        onValueChange = {
                                            fields[spec.key] = ExprFieldCodec.encode(it)
                                            isSavedDirty = true
                                        },
                                        label = spec.label,
                                        fieldId = "credential.field." + spec.key,
                                        scope = exprScope,
                                        placeholder = spec.placeholder,
                                        showLabel = false,
                                    )
                                } else {
                                    OutlinedTextField(
                                        value = value,
                                        onValueChange = {
                                            fields[spec.key] = it
                                            isSavedDirty = true
                                        },
                                        placeholder = { Text(spec.placeholder, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) },
                                        singleLine = true,
                                        shape = KaivoShapes.chip,
                                        visualTransformation = if (isSecret && !isVisible) PasswordVisualTransformation() else VisualTransformation.None,
                                        trailingIcon = if (isSecret) {
                                            {
                                                IconButton(onClick = { secretVisibility[spec.key] = !isVisible }) {
                                                    Icon(
                                                        KaivoIcons.icon(if (isVisible) "eyeOff" else "eye"),
                                                        contentDescription = "Toggle visibility",
                                                        tint = if (isVisible) colors.royalGold else colors.inkMuted,
                                                        modifier = Modifier.size(18.dp),
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
                        }
                    }

                    CredentialEditTab.Details -> {
                        // Details Tab: Created, Last modified
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceElevated)
                                .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "Created",
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        color = colors.inkSecondary,
                                    )
                                    Text(
                                        text = formatRelativeTime(credential.createdAt),
                                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.inkPrimary,
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "Last modified",
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        color = colors.inkSecondary,
                                    )
                                    Text(
                                        text = formatRelativeTime(credential.updatedAt),
                                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.inkPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---------------- Save Action Button ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = KaivoShapes.chip,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel", color = colors.inkSecondary)
                }

                Button(
                    onClick = {
                        isSaving = true
                        val sanitized = fields.mapValues { (k, v) ->
                            if (k == "apiKey" || k == "token" || k == "baseUrl") v.trim() else v
                        }
                        onSave(credentialName.trim(), sanitized)
                        isSaving = false
                        isSavedDirty = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = Color.Black),
                    shape = KaivoShapes.chip,
                    enabled = !isSaving && credentialName.isNotBlank(),
                    modifier = Modifier.weight(1.5f),
                ) {
                    Text("Save & Apply", fontWeight = FontWeight.Bold)
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
                    "Are you sure you want to delete \"${credentialName}\"? Agents and workflows connected to this credential will stop working.",
                    color = colors.inkSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(credential.id)
                        onDismiss()
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
