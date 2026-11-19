package com.kaivoflow.feature.flows.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaivoflow.core.ai.ApprovalRelay
import com.kaivoflow.core.ai.ApprovalRequest
import com.kaivoflow.core.ai.ToolRisk
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme

/**
 * The approve/deny card for a tool that acts on the world (audit 8.4). It shows what will happen, where, and with
 * which arguments (already redacted by `:core:ai`). A destructive tool also asks the person to type its name, and
 * is never approved on its own. Reasoned, not compiled here: only API patterns already used in this module.
 */
@Composable
fun ApprovalCard(
    request: ApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    var typed by remember(request) { mutableStateOf("") }
    val canApprove = !request.requiresTypedConfirmation || typed.trim() == request.toolName
    Card(
        shape = KaivoShapes.medallion,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
        border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(colors.royalGold)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (request.risk == ToolRisk.Destructive) "The assistant wants to delete or overwrite something" else "The assistant wants to act on your phone",
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
            )
            Text(request.toolName, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.royalGold)
            if (request.description.isNotBlank()) {
                Text(request.description, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
            if (request.target.isNotBlank()) {
                Text("Target: ${request.target}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.inkPrimary)
            }
            request.arguments.entries.take(6).forEach { (k, v) ->
                Text("$k: ${v.toString().take(160)}", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            }
            if (request.requiresTypedConfirmation) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text("Type ${request.toolName} to allow") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDeny, shape = KaivoShapes.chip, modifier = Modifier.weight(1f)) { Text("Deny") }
                Button(
                    onClick = onApprove,
                    enabled = canApprove,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.royalGold, contentColor = Color.Black),
                    shape = KaivoShapes.chip,
                    modifier = Modifier.weight(1f),
                ) { Text("Allow once") }
            }
        }
    }
}

/** Tells the relay a screen can show cards while this composable is on screen. Without one, requests are denied at once. */
@Composable
fun AttachApprovalPresenter(relay: ApprovalRelay, inline: Boolean) {
    DisposableEffect(relay, inline) {
        val detach = relay.attach(inline)
        onDispose { detach() }
    }
}

/**
 * A dialog for approvals that start where no chat is open (a Test Step or hand-started Run on the canvas). Hidden
 * while a chat screen shows the card itself. Host it once at the root of the app.
 */
@Composable
fun ApprovalDialogHost(relay: ApprovalRelay) {
    AttachApprovalPresenter(relay, inline = false)
    val pending by relay.pending.collectAsStateWithLifecycle()
    val inline by relay.inlinePresenters.collectAsStateWithLifecycle()
    val request = pending
    if (request != null && inline == 0) {
        AlertDialog(
            onDismissRequest = { relay.respond(false) },
            title = { Text("Allow this action?") },
            text = {
                ApprovalCard(request, onApprove = { relay.respond(true) }, onDeny = { relay.respond(false) })
            },
            confirmButton = {},
        )
    }
}
