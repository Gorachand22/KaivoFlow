package com.kaivoflow.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaivoflow.core.data.CredentialRepository
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SectionHeader
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.model.CredentialRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CredentialsViewModel @Inject constructor(
    private val credentials: CredentialRepository,
) : ViewModel() {
    private val _refs = MutableStateFlow<List<CredentialRef>>(emptyList())
    val refs: StateFlow<List<CredentialRef>> = _refs.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _refs.value = runCatching { credentials.list() }.getOrDefault(emptyList()) }
    }

    fun save(id: String?, label: String, type: String, value: String) {
        viewModelScope.launch {
            runCatching {
                credentials.save(id, type, label.ifBlank { "API key" }, mapOf("apiKey" to value))
                _message.value = "Saved. The key is encrypted with this phone's Keystore."
            }.onFailure { error ->
                _message.value = error.message ?: "Could not save that credential."
            }
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            credentials.delete(id)
            refresh()
        }
    }
}

/**
 * Data Safety & Privacy Centre.
 *
 * Pillar 4 in one screen: it states the honest truth — automation data stays on the device, but any
 * Step that calls a third-party API sends that Step's data to that provider with the user's own key.
 * This is the screen the Play Console declaration must match.
 */
@Composable
fun PrivacyCentreScreen(onBack: () -> Unit) {
    val colors = KaivoTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvasVoid)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Privacy centre", style = androidx.compose.material3.MaterialTheme.typography.displayLarge, color = colors.inkPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "KaivoFlow has no KaivoFlow account and no KaivoFlow servers. These statements are checkable in the source.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(20.dp))

        PrivacyFact("Stays on this phone", listOf(
            "Flows, Step settings, variables and Run history — stored in a local database.",
            "Frozen (pinned) Step outputs.",
            "Saved files written by Device Steps.",
        ), BadgeKind.Success)

        Spacer(Modifier.height(12.dp))
        PrivacyFact("Leaves this phone only when a Step asks it to", listOf(
            "An HTTP, AI or SaaS Step sends that Step's data to the provider you configured (OpenAI, Google, Telegram, your own server…), using your own API key.",
            "Nothing is sent to KaivoFlow. There is no analytics SDK and no crash reporter in this build.",
        ), BadgeKind.Warning)

        Spacer(Modifier.height(12.dp))
        PrivacyFact("Never read", listOf(
            "When notification access is enabled, KaivoFlow receives notification contents to check them against enabled Notification Sparks. Matching data may be saved in Run history or sent to services configured in the Flow.",
            "When SMS access is enabled, incoming message data may be used by matching SMS Sparks. A Flow can send that data to a service you configure.",
            "Keystore-encrypted credentials cannot be read by other apps, and are excluded from backups.",
        ), BadgeKind.Neutral)

        Spacer(Modifier.height(20.dp))
        Text(
            "You can delete every Flow, Run and credential from this app at any time; nothing is held elsewhere to delete.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
    }
}

@Composable
private fun PrivacyFact(title: String, lines: List<String>, kind: BadgeKind) {
    val colors = KaivoTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.stepCard)
            .padding(16.dp),
    ) {
        StatusBadge(title, kind)
        Spacer(Modifier.height(10.dp))
        lines.forEach { line ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("•", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                Spacer(Modifier.width(6.dp))
                Text(line, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
            }
        }
    }
}

@Composable
fun CredentialsScreen(onBack: () -> Unit, viewModel: CredentialsViewModel = hiltViewModel()) {
    val refs by viewModel.refs.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val colors = KaivoTheme.colors
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvasVoid)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Credentials", style = androidx.compose.material3.MaterialTheme.typography.displayLarge, color = colors.inkPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Credentials are encrypted using Android Keystore. Hardware protection depends on your device.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(16.dp))

        refs.forEach { ref ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(KaivoShapes.chip)
                    .background(colors.surfaceCard)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ref.label, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                    Text(ref.type, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = colors.inkSecondary)
                }
                GhostButton(text = "Delete", onClick = { viewModel.delete(ref.id) })
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader(title = "Add a credential")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Name (e.g. OpenAI key)") },
            modifier = Modifier.fillMaxWidth(),
            shape = KaivoShapes.chip,
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("Secret value") },
            modifier = Modifier.fillMaxWidth(),
            shape = KaivoShapes.chip,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(12.dp))
        GoldButton(
            text = "Save credential",
            icon = "save",
            enabled = value.isNotBlank(),
            onClick = {
                viewModel.save(null, label, "apiKey", value)
                label = ""
                value = ""
            },
        )
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        }
        Spacer(Modifier.height(24.dp))
    }
}
