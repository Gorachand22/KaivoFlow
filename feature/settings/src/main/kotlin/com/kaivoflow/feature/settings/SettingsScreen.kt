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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SectionHeader
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.work.BatteryHealth
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings hub: battery health, privacy, and credentials.
 *
 * Pillar 4 lives here — the "Data Safety & Privacy Centre" tells the user exactly what leaves the
 * device, which is a superset of what the Play Console form must declare.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val batteryHealth: BatteryHealth,
) : ViewModel() {
    private val _checks = MutableStateFlow<List<BatteryHealth.Check>>(emptyList())
    val checks: StateFlow<List<BatteryHealth.Check>> = _checks.asStateFlow()

    private val _oem = MutableStateFlow(BatteryHealth.Oem.Other)
    val oem: StateFlow<BatteryHealth.Oem> = _oem.asStateFlow()

    fun refresh() {
        _oem.value = batteryHealth.oem()
        _checks.value = batteryHealth.checks()
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenCredentials: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val colors = KaivoTheme.colors
    val viewModel: SettingsViewModel = hiltViewModel()
    val checks by viewModel.checks.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvasVoid)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Settings", style = androidx.compose.material3.MaterialTheme.typography.displayLarge, color = colors.inkPrimary)
        Spacer(Modifier.height(16.dp))

        SectionHeader(title = "Battery health", subtitle = "Keep scheduled Flows alive on this phone")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            checks.forEach { check ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(KaivoShapes.stepCard)
                        .background(colors.surfaceCard)
                        .border(1.dp, colors.hairline, KaivoShapes.stepCard)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(check.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                            Spacer(Modifier.width(8.dp))
                            StatusBadge(if (check.ok) "OK" else "Action needed", if (check.ok) BadgeKind.Success else BadgeKind.Warning)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(check.explanation, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    }
                    if (!check.ok) {
                        Spacer(Modifier.width(8.dp))
                        GhostButton(
                            text = "Open",
                            onClick = {
                                // BatteryHealth already resolved the OEM intent, falling back to app settings.
                                check.action?.let { intent ->
                                    runCatching {
                                        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = "Privacy", subtitle = "Your automations stay on your phone")
        Spacer(Modifier.height(10.dp))
        SettingRow("Privacy centre", "What is stored, what leaves the device", "shield", onOpenPrivacy)
        SettingRow("Permissions", "Notifications, SMS and why each is needed", "security", onOpenPermissions)
        SettingRow("Credentials", "API keys, encrypted with the Android Keystore", "key", onOpenCredentials)
        SettingRow("Run history", "Every Run, with per-Step detail", "history", onOpenHistory)

        Spacer(Modifier.height(24.dp))
        Text(
            "KaivoFlow 1.0.0",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, icon: String, onClick: () -> Unit) {
    val colors = KaivoTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.stepCard)
            .androidClickable(onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(38.dp).clip(CircleShape).background(colors.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(KaivoIcons.icon(icon), null, tint = colors.royalGold, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        }
        Icon(KaivoIcons.icon("external"), null, tint = colors.inkMuted, modifier = Modifier.size(16.dp))
    }
}

private fun Modifier.androidClickable(onClick: () -> Unit): Modifier =
    this.then(androidx.compose.foundation.clickable(onClick = onClick))
