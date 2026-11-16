package com.kaivoflow.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.kaivoflow.core.data.ReliabilityEventEntity
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SectionHeader
import com.kaivoflow.core.design.SegmentedPill
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.engine.LifecycleState
import com.kaivoflow.core.engine.reliability.CheckGroup
import com.kaivoflow.core.engine.reliability.CheckResult
import com.kaivoflow.core.engine.reliability.CheckState
import com.kaivoflow.core.engine.reliability.DayOutcome
import com.kaivoflow.core.engine.reliability.FailureText
import com.kaivoflow.core.engine.reliability.FixAction
import com.kaivoflow.core.engine.reliability.SelfTestId
import com.kaivoflow.core.engine.reliability.SelfTestOutcome
import com.kaivoflow.core.engine.reliability.SelfTestState
import com.kaivoflow.core.work.alwayson.FixLauncher
import com.kaivoflow.core.work.alwayson.FlowHealth
import com.kaivoflow.core.work.alwayson.ReliabilityCenterRepository
import com.kaivoflow.core.work.alwayson.ReliabilityDump
import com.kaivoflow.core.work.alwayson.ReliabilitySnapshot
import com.kaivoflow.core.work.alwayson.SelfTestRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ReliabilityViewModel @Inject constructor(
    private val repo: ReliabilityCenterRepository,
    private val fixes: FixLauncher,
    private val selfTest: SelfTestRunner,
    private val dump: ReliabilityDump,
) : ViewModel() {
    private val _snapshot = MutableStateFlow<ReliabilitySnapshot?>(null)
    val snapshot: StateFlow<ReliabilitySnapshot?> = _snapshot.asStateFlow()
    private val _events = MutableStateFlow<List<ReliabilityEventEntity>>(emptyList())
    val events: StateFlow<List<ReliabilityEventEntity>> = _events.asStateFlow()
    val selfTestResults: StateFlow<Map<SelfTestId, SelfTestOutcome>> = selfTest.results
    val unacknowledged = repo.unacknowledged()

    fun refresh() {
        viewModelScope.launch {
            _snapshot.value = runCatching { repo.snapshot() }.getOrNull() ?: _snapshot.value
            _events.value = runCatching { repo.events() }.getOrDefault(_events.value)
        }
    }

    fun fix(context: Context, action: FixAction) {
        if (action == FixAction.RestartEngine) {
            viewModelScope.launch { fixes.restartEngine(); refresh() }
        } else if (action == FixAction.OemGuide) {
            fixes.launch(context, action)
        } else {
            fixes.launch(context, action)
        }
    }

    fun attestOem() { repo.attestOem(); refresh() }
    fun acknowledgeAll() { viewModelScope.launch { repo.acknowledgeAll(); refresh() } }
    fun runSelfTest() { selfTest.start(setOf(SelfTestId.ST1, SelfTestId.ST2, SelfTestId.ST4, SelfTestId.ST5, SelfTestId.ST6)) }

    /** Shares the dump as text (no FileProvider needed, nothing written outside app storage). */
    fun export(context: Context) {
        viewModelScope.launch {
            val json = runCatching { dump.build() }.getOrNull() ?: return@launch
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "KaivoFlow diagnostics").putExtra(Intent.EXTRA_TEXT, json)
            runCatching { context.startActivity(Intent.createChooser(send, "Export diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

@Composable
fun ReliabilityCenterScreen(onBack: () -> Unit, viewModel: ReliabilityViewModel = hiltViewModel()) {
    val colors = KaivoTheme.colors
    val context = LocalContext.current
    val snapshot by viewModel.snapshot.collectAsState()
    val events by viewModel.events.collectAsState()
    val selfTests by viewModel.selfTestResults.collectAsState()
    var tab by remember { mutableStateOf(0) }

    // Refresh on resume (returning from a settings screen) and every 30 s while visible.
    val owner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = object : DefaultLifecycleObserver { override fun onResume(o: LifecycleOwner) { viewModel.refresh() } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { while (true) { viewModel.refresh(); delay(30_000) } }

    Column(Modifier.fillMaxSize().background(colors.canvasVoid).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Reliability", style = MaterialTheme.typography.displayLarge, color = colors.inkPrimary)
        Spacer(Modifier.height(12.dp))
        SegmentedPill(listOf("Health", "Event log"), tab, { tab = it })
        Spacer(Modifier.height(16.dp))
        val s = snapshot
        if (s == null) {
            Text("Measuring…", color = colors.inkSecondary, style = MaterialTheme.typography.bodyMedium)
        } else if (tab == 0) {
            HealthTab(s, selfTests, viewModel, context)
        } else {
            EventsTab(events, viewModel)
        }
        Spacer(Modifier.height(24.dp))
        GhostButton("Back", onClick = onBack)
    }
}

private fun fmtTime(ms: Long?): String = if (ms == null) "never" else SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun HealthTab(s: ReliabilitySnapshot, selfTests: Map<SelfTestId, SelfTestOutcome>, vm: ReliabilityViewModel, context: Context) {
    val colors = KaivoTheme.colors
    val kind = when (s.score.band.name) { "Reliable" -> BadgeKind.Success; "MostlyReliable" -> BadgeKind.Gold; "AtRisk" -> BadgeKind.Warning; else -> BadgeKind.Error }
    Column(Modifier.fillMaxWidth().clip(KaivoShapes.stepCard).background(colors.surfaceCard).border(1.dp, colors.hairline, KaivoShapes.stepCard).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${s.score.score}", style = MaterialTheme.typography.displayLarge, color = colors.royalGold)
            Spacer(Modifier.width(12.dp))
            StatusBadge(s.score.band.label, kind)
        }
        s.score.capReason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusBadge("Engine ${s.engineState}", if (s.engineState.equals("running", true)) BadgeKind.Success else BadgeKind.Warning)
            StatusBadge("Heartbeat ${fmtTime(s.lastHeartbeatAt)}", BadgeKind.Neutral)
            StatusBadge("${s.publishedFlows} live", BadgeKind.Neutral)
        }
    }
    if (s.fixFirst.isNotEmpty()) {
        Spacer(Modifier.height(20.dp)); SectionHeader("Fix first")
        s.fixFirst.take(3).forEach { CheckRow(it, vm, context) }
    }
    CheckGroup.values().forEach { g ->
        val rows = s.checks.filter { it.group == g && it.state != CheckState.NotNeeded }
        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionHeader(when (g) { CheckGroup.Critical -> "Critical"; CheckGroup.Recommended -> "Recommended"; CheckGroup.Informational -> "Good to know" })
            rows.forEach { CheckRow(it, vm, context) }
        }
    }
    if (s.flowHealth.isNotEmpty()) {
        Spacer(Modifier.height(20.dp)); SectionHeader("Flow health", subtitle = "Last 14 days")
        s.flowHealth.forEach { FlowStrip(it) }
    }
    Spacer(Modifier.height(20.dp)); SectionHeader("Self-test", subtitle = "Checks alarms, inbox, webhook and listener on this phone")
    Spacer(Modifier.height(8.dp))
    selfTests.values.sortedBy { it.id }.forEach {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusBadge(it.state.name, when (it.state) { SelfTestState.Pass -> BadgeKind.Success; SelfTestState.Fail -> BadgeKind.Error; else -> BadgeKind.Neutral })
            Text("${it.id.title}: ${it.detail}", style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary, modifier = Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(8.dp))
    GoldButton("Run self-test", onClick = vm::runSelfTest, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    GhostButton("Export diagnostics", onClick = { vm.export(context) })
}

@Composable
private fun CheckRow(c: CheckResult, vm: ReliabilityViewModel, context: Context) {
    val colors = KaivoTheme.colors
    var open by remember { mutableStateOf(false) }
    val (label, kind) = when (c.state) {
        CheckState.Ok -> "OK" to BadgeKind.Success
        CheckState.Warn -> "Warning" to BadgeKind.Warning
        CheckState.Fail -> "Action needed" to BadgeKind.Error
        // Unverified never reads as green: OEM settings Android cannot report stay neutral until the user attests.
        CheckState.Unknown -> "Cannot verify" to BadgeKind.Neutral
        CheckState.NotNeeded -> "Not needed" to BadgeKind.Neutral
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(KaivoShapes.stepCard).background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.stepCard).clickable { open = !open }.padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.title, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, modifier = Modifier.weight(1f))
            StatusBadge(label, kind)
        }
        Spacer(Modifier.height(4.dp))
        Text(c.plain, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        if (open) Text("Evidence: ${c.evidence}", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted, modifier = Modifier.padding(top = 4.dp))
        if (c.fix != FixAction.None && c.state != CheckState.Ok) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(if (c.fix == FixAction.RestartEngine) "Restart engine" else "Fix", onClick = { vm.fix(context, c.fix) })
                if (c.state == CheckState.Unknown && c.fix == FixAction.OemAutostart) GhostButton("I did this", onClick = vm::attestOem)
            }
        }
    }
}

@Composable
private fun FlowStrip(h: FlowHealth) {
    val colors = KaivoTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(h.name, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, modifier = Modifier.weight(1f))
            h.onTimeRatio?.let { StatusBadge("${(it * 100).toInt()}% on time", if (it >= 0.95) BadgeKind.Success else BadgeKind.Warning) }
        }
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            h.strip.forEach { d ->
                androidx.compose.foundation.layout.Box(
                    Modifier.width(14.dp).height(14.dp).clip(KaivoShapes.chip).background(
                        when (d) {
                            DayOutcome.OnTime -> colors.statusSuccess
                            DayOutcome.Late -> colors.statusWarning
                            DayOutcome.Missed -> colors.statusError
                            DayOutcome.Idle -> colors.hairline
                        },
                    ),
                )
            }
        }
        val line = when {
            h.state == LifecycleState.Error -> FailureText.plain(h.errorCode) + " " + FailureText.retryText(h.retryAt, System.currentTimeMillis())
            h.nextRunAt != null -> "Next run ${fmtTime(h.nextRunAt)}"
            else -> "No scheduled run"
        }
        Text(line, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
    }
}

@Composable
private fun EventsTab(events: List<ReliabilityEventEntity>, vm: ReliabilityViewModel) {
    val colors = KaivoTheme.colors
    var onlyUnread by remember { mutableStateOf(false) }
    val shown = events.filter { !onlyUnread || !it.acknowledged }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton(if (onlyUnread) "Show all" else "Only new", onClick = { onlyUnread = !onlyUnread })
        GhostButton("Mark all read", onClick = vm::acknowledgeAll)
    }
    Spacer(Modifier.height(8.dp))
    if (shown.isEmpty()) Text("Nothing to report.", color = colors.inkSecondary, style = MaterialTheme.typography.bodyMedium)
    shown.forEach { e ->
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(KaivoShapes.stepCard).background(colors.surfaceCard).border(1.dp, colors.hairline, KaivoShapes.stepCard).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(e.kind.lowercase().replace('_', ' '), when (e.severity.lowercase()) { "error", "critical" -> BadgeKind.Error; "warning", "warn" -> BadgeKind.Warning; else -> BadgeKind.Neutral })
                Spacer(Modifier.width(8.dp))
                Text(fmtTime(e.at), style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
            }
            e.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}
