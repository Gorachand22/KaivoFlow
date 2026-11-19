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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme

/**
 * The '+' Action Menu Sheet for Ask AI chat input.
 * Clean, modern menu providing:
 * 1. Attach files (Workflow JSON, schema, or error logs)
 * 2. Connect services (Sovereign Credentials picker / AI providers)
 * 3. Preferences (AI directives, temperature, reasoning & autonomy limits)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiActionMenuSheet(
    onAttachFiles: () -> Unit,
    onConnectServices: () -> Unit,
    onOpenPreferences: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceCard,
        dragHandle = null,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Assistant Actions",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            // 1. Attach files
            ActionMenuItem(
                icon = Icons.Filled.AttachFile,
                title = "Attach files",
                subtitle = "Include workflow JSON, schema specs, or error logs",
                accentColor = colors.royalGold,
                onClick = {
                    onDismiss()
                    onAttachFiles()
                },
            )

            // 2. Connect services
            ActionMenuItem(
                icon = Icons.Filled.Power,
                title = "Connect services",
                subtitle = "Manage sovereign AI providers, skills & connectors",
                accentColor = Color(0xFF3B82F6),
                onClick = {
                    onDismiss()
                    onConnectServices()
                },
            )

            // 3. Preferences
            ActionMenuItem(
                icon = Icons.Filled.Tune,
                title = "Preferences",
                subtitle = "Behavioral directives, temperature, and agent autonomy limits",
                accentColor = Color(0xFFA855F7),
                onClick = {
                    onDismiss()
                    onOpenPreferences()
                },
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActionMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(KaivoShapes.stepCard)
            .background(colors.canvasVoid)
            .border(1.dp, colors.hairline, KaivoShapes.stepCard)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(KaivoShapes.chip)
                    .background(accentColor.copy(alpha = 0.15f))
                    .border(1.dp, accentColor.copy(alpha = 0.35f), KaivoShapes.chip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.inkPrimary,
                )
                Text(
                    text = subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                )
            }
        }
    }
}
