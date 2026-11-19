package com.kaivoflow.feature.flows

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import com.kaivoflow.core.design.AnimatedStatusBadge
import com.kaivoflow.core.design.KaivoMotionTokens
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.MotionTokenValues
import com.kaivoflow.core.design.SuccessCheck
import com.kaivoflow.core.design.goldSweep
import com.kaivoflow.core.design.rememberKaivoHaptics
import com.kaivoflow.core.design.rememberShakeState
import com.kaivoflow.core.design.shake
import com.kaivoflow.core.design.staggeredEntrance
import com.kaivoflow.core.design.tweenSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SectionHeader
import com.kaivoflow.core.design.SheetDragHandle
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.engine.ActivationResult
import com.kaivoflow.core.engine.ActivationStatus
import com.kaivoflow.core.engine.PublishPreview
import com.kaivoflow.core.engine.ValidationIssue
import com.kaivoflow.core.engine.reliability.RegistrationRows
import com.kaivoflow.core.work.alwayson.ActivationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the sheet shows. Checklist first, then (after Publish) the per-Spark result. */
data class PublishUiState(
    val loading: Boolean = true,
    val preview: PublishPreview? = null,
    val busy: Boolean = false,
    val result: ActivationResult? = null,
    val acknowledged: Boolean = false,
)

@HiltViewModel
class PublishViewModel @Inject constructor(
    private val activation: ActivationManager,
) : ViewModel() {
    private val _state = MutableStateFlow(PublishUiState())
    val state: StateFlow<PublishUiState> = _state.asStateFlow()

    fun load(flowId: String) {
        _state.value = PublishUiState()
        viewModelScope.launch {
            val p = activation.preview(flowId)
            _state.value = PublishUiState(loading = false, preview = p)
        }
    }

    fun acknowledge() { _state.value = _state.value.copy(acknowledged = true) }

    fun publish(flowId: String) {
        val s = _state.value
        val changes = s.preview?.isPublished == true
        _state.value = s.copy(busy = true)
        viewModelScope.launch {
            val r = if (changes) activation.publishChanges(flowId, s.acknowledged) else activation.publish(flowId, s.acknowledged)
            val needsAck = r.status == ActivationStatus.NeedsAcknowledgement
            _state.value = _state.value.copy(busy = false, result = if (needsAck) null else r, preview = r.report?.let { s.preview?.copy(report = it) } ?: s.preview)
        }
    }
}

/**
 * Reusable publish sheet (spec 4.4). The Flow list uses it today; the canvas adopts it later through
 * [PublishSheetEntry]. [onFix] lets the host jump to the failing Step or a settings screen.
 */
@Composable
fun PublishSheet(
    flowId: String,
    onDismiss: () -> Unit,
    onFix: (ValidationIssue) -> Unit = {},
    viewModel: PublishViewModel = hiltViewModel(),
) {
    val colors = KaivoTheme.colors
    val state by viewModel.state.collectAsState()
    LaunchedEffect(flowId) { viewModel.load(flowId) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.surfaceAtelier, dragHandle = { SheetDragHandle(Modifier.padding(top = 12.dp, bottom = 8.dp)) }) {
        val result = state.result
        val preview = state.preview
        val phase = when {
            state.loading -> PublishPhase.Loading
            preview == null -> PublishPhase.Missing
            result != null && result.ok -> PublishPhase.Published
            else -> PublishPhase.Checklist
        }
        val policy = KaivoTheme.motionPolicy
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .animateContentSize(policy.tweenSpec(MotionTokenValues.STANDARD_MS, KaivoMotionTokens.flowing)),
        ) {
            // Checking, checklist and the Live result crossfade; the sheet height eases between them.
            Crossfade(
                targetState = phase,
                animationSpec = tween(policy.ms(MotionTokenValues.STANDARD_MS)),
                label = "publishPhase",
            ) { current ->
                Column(Modifier.fillMaxWidth()) {
                    when (current) {
                        PublishPhase.Loading -> Text("Checking this Flow…", color = colors.inkSecondary, style = MaterialTheme.typography.bodyMedium)
                        PublishPhase.Missing -> Text("This Flow no longer exists.", color = colors.inkSecondary)
                        PublishPhase.Published -> if (result != null) PublishedResult(result, onDismiss)
                        PublishPhase.Checklist -> if (preview != null) {
                            Checklist(state, preview, onFix, onAck = viewModel::acknowledge, onPublish = { viewModel.publish(flowId) }, result = result)
                        }
                    }
                }
            }
        }
    }
}

private enum class PublishPhase { Loading, Missing, Checklist, Published }

@Composable
private fun Checklist(
    state: PublishUiState,
    preview: PublishPreview,
    onFix: (ValidationIssue) -> Unit,
    onAck: () -> Unit,
    onPublish: () -> Unit,
    result: ActivationResult?,
) {
    val colors = KaivoTheme.colors
    val policy = KaivoTheme.motionPolicy
    val haptics = rememberKaivoHaptics()
    val shake = rememberShakeState()
    val report = preview.report
    val armFailed = result != null && result.status == ActivationStatus.ArmFailed
    // A failed arm shakes the sheet content once and gives a rejecting haptic.
    LaunchedEffect(armFailed) {
        if (armFailed) {
            haptics.reject()
            shake.shake(policy)
        }
    }
    Column(Modifier.shake(shake)) {
    Text(if (preview.isPublished) "Publish changes" else "Publish this Flow", style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
    Spacer(Modifier.height(4.dp))
    Text(
        if (report.errors.isEmpty()) "Ready to go live." else "Fix ${report.errors.size} thing${if (report.errors.size == 1) "" else "s"} before this can run on its own.",
        style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary,
    )
    if (report.errors.isNotEmpty()) {
        Spacer(Modifier.height(16.dp)); SectionHeader("Must fix")
        report.errors.forEachIndexed { i, issue -> Box(Modifier.staggeredEntrance(i, key = issue.message)) { IssueRow(issue, BadgeKind.Error, "Fix", onFix) } }
    }
    if (report.warnings.isNotEmpty()) {
        Spacer(Modifier.height(16.dp)); SectionHeader("Worth knowing")
        report.warnings.forEachIndexed { i, issue -> Box(Modifier.staggeredEntrance(i, key = issue.message)) { IssueRow(issue, BadgeKind.Warning, null, onFix) } }
    }
    if (result != null && result.status == ActivationStatus.ArmFailed) {
        Spacer(Modifier.height(16.dp)); SectionHeader("Could not start")
        SparkLines(result)
        Text("Nothing changed. The previous version keeps running.", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
    }
    Spacer(Modifier.height(20.dp))
    val needsAck = report.needsAcknowledgement && !state.acknowledged
    GoldButton(
        text = when {
            state.busy -> "Publishing…"
            needsAck -> "Publish anyway"
            preview.isPublished -> "Publish changes"
            else -> "Publish"
        },
        onClick = { if (needsAck) onAck() else onPublish() },
        enabled = report.canPublish && !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.pill)
            .goldSweep(trigger = if (state.busy) "busy" else null),
        icon = "bolt",
    )
    }
}

@Composable
private fun IssueRow(issue: ValidationIssue, kind: BadgeKind, fixLabel: String?, onFix: (ValidationIssue) -> Unit) {
    val colors = KaivoTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusBadge(if (kind == BadgeKind.Error) "Error" else "Note", kind)
        Column(Modifier.weight(1f)) {
            Text(issue.message, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
            issue.fixHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkMuted) }
        }
        if (fixLabel != null) GhostButton(fixLabel, onClick = { onFix(issue) })
    }
}

@Composable
private fun SparkLines(result: ActivationResult) {
    val colors = KaivoTheme.colors
    result.sparks.forEach { (id, st) ->
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedStatusBadge(if (st.isArmed) "Armed" else if (st.kind.name == "Pending") "Waiting" else "Needs attention", if (st.isArmed) BadgeKind.Success else if (st.kind.name == "Pending") BadgeKind.Neutral else BadgeKind.Error)
            Text(RegistrationRows.resultLine(st), style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PublishedResult(result: ActivationResult, onDone: () -> Unit) {
    val colors = KaivoTheme.colors
    val haptics = rememberKaivoHaptics()
    // The premium moment: the check draws itself, a gold sweep crosses the card, one confirm haptic.
    LaunchedEffect(Unit) { haptics.confirm() }
    Column(Modifier.fillMaxWidth().clip(KaivoShapes.stepCard).goldSweep(trigger = Unit)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SuccessCheck(diameter = 40.dp, color = colors.statusSuccess)
            Column {
                Text("Live", style = MaterialTheme.typography.titleLarge, color = colors.statusSuccess)
                Text("Live. KaivoFlow will keep this running in the background.", style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        SparkLines(result)
        Spacer(Modifier.height(20.dp))
        GoldButton("Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Callback entry for hosts that own their own navigation (the canvas). Hold `publishFlowId` in state,
 * set it from the Publish button, and call this when non-null.
 */
@Composable
fun PublishSheetEntry(publishFlowId: String?, onDismiss: () -> Unit, onFix: (ValidationIssue) -> Unit = {}) {
    if (publishFlowId != null) PublishSheet(publishFlowId, onDismiss, onFix)
}
