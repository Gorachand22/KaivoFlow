package com.kaivoflow.feature.canvas.inspector.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.ErrorBlock
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.KaivoChip
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SectionHeader
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.feature.canvas.inspector.logic.AboutModel
import com.kaivoflow.feature.canvas.inspector.logic.OnErrorChoice
import com.kaivoflow.feature.canvas.inspector.logic.StepBehaviour
import com.kaivoflow.feature.canvas.inspector.state.InspectorIntent
import com.kaivoflow.feature.canvas.inspector.state.InspectorState
import com.kaivoflow.feature.canvas.inspector.state.ParentPolicy
import com.kaivoflow.feature.canvas.inspector.state.TestState

/** Test Step feedback: running, waiting for upstream data, or a failure with details. Shown above both data panels. */
@Composable
fun TestBanner(state: InspectorState, session: InspectorSession, node: StepNode) {
    val colors = KaivoTheme.colors
    val clipboard = LocalClipboardManager.current
    when (val t = state.test) {
        is TestState.Running -> Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge("Testing", BadgeKind.Gold)
            Spacer(Modifier.padding(4.dp))
            Text(if (t.stepIds.size > 1) "Running ${t.stepIds.size} Steps, ending with this one." else "Running this Step on real data.", style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
        is TestState.NeedsParents -> Column(Modifier.fillMaxWidth().padding(12.dp).clip(KaivoShapes.chip).background(colors.surfaceElevated).border(1.dp, colors.royalGold.copy(alpha = 0.4f), KaivoShapes.chip).padding(12.dp)) {
            Text("This Step has no input data yet", style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
            Text(
                "Run the earlier Steps to fill it, test with an empty item, or pin sample data on a parent Step.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Run earlier Steps", { session.holder.dispatch(InspectorIntent.TestStepRequested(ParentPolicy.RunThem)) }, icon = "play")
                GhostButton("Use empty input", { session.holder.dispatch(InspectorIntent.TestStepRequested(ParentPolicy.UsePinnedOrLast)) })
            }
            GhostButton("Cancel", { session.holder.dispatch(InspectorIntent.CancelTest) }, Modifier.padding(top = 4.dp))
        }
        is TestState.Failed -> {
            val where = t.itemIndex?.let { "Item ${it + 1}: " }.orEmpty()
            ErrorBlock(
                title = "\"${node.name}\" failed",
                message = where + t.message.substringBefore(" (line"),
                modifier = Modifier.padding(12.dp),
                details = buildString {
                    append(t.message)
                    t.itemIndex?.let { append("\nInput item: ${it + 1}") }
                    if (t.hasPartialData) append("\nSome items finished before the failure; see the Output panel.")
                },
                onCopyDetails = { clipboard.setText(AnnotatedString(t.message)) },
                actionLabel = "Test again",
                onAction = { session.holder.dispatch(InspectorIntent.TestStepRequested(ParentPolicy.Ask)) },
            )
        }
        else -> Unit
    }
}

/** Step-level notices above the form: past Run, disabled, problems to fix, and (when [showTest]) the Test Step result. */
@Composable
fun StepBanners(state: InspectorState, node: StepNode, session: InspectorSession, showTest: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.readOnly) StatusBadge("Viewing a past Run. Editing is off.", BadgeKind.Neutral)
        if (node.disabled) StatusBadge("This Step is off and is skipped in Runs.", BadgeKind.Neutral)
        if (state.issueCount > 0 && !state.readOnly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge("${state.issueCount} to fix", BadgeKind.Error)
                GhostButton("Next problem", { session.holder.dispatch(InspectorIntent.JumpToNextIssue) })
            }
        }
    }
    if (showTest) TestBanner(state, session, node)
}

@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = KaivoTheme.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).semantics(mergeDescendants = true) { toggleableState = ToggleableState(checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<String, T>>, selected: T, enabled: Boolean, onPick: (T) -> Unit) {
    val colors = KaivoTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = colors.inkMuted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (label, value) -> KaivoChip(label, onClick = { onPick(value) }, selected = value == selected, enabled = enabled) }
        }
    }
}

/**
 * Behaviour tab: error handling, retries, timeout and notes. It edits the fields StepNode already has
 * (onError, retry, timeoutMs, notes, disabled) through the pure [StepBehaviour] functions; the engine already honours them.
 * "Always output data", "Execute once" and "Show note on canvas" need new Step fields and engine support and are not offered yet.
 */
@Composable
fun BehaviourTab(node: StepNode, enabled: Boolean, onEdit: ((StepNode) -> StepNode) -> Unit) {
    val colors = KaivoTheme.colors
    val retryOn = StepBehaviour.retryOn(node)
    var notes by remember(node.id) { mutableStateOf(node.notes) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        SectionHeader("When something goes wrong")
        ChoiceRow("On error", OnErrorChoice.entries.map { it.label to it }, StepBehaviour.onErrorOf(node), enabled) { c -> onEdit { StepBehaviour.setOnError(it, c) } }
        Text(StepBehaviour.onErrorOf(node).hint, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        Spacer(Modifier.height(8.dp))
        SwitchRow("Retry on failure", if (retryOn) StepBehaviour.retrySummary(node.retry) else "Try once. Turn on for steps that are safe to repeat.", retryOn, enabled) { on -> onEdit { StepBehaviour.setRetryEnabled(it, on) } }
        if (retryOn) {
            ChoiceRow("Tries", listOf(2, 3, 5, 10).map { it.toString() to it }, node.retry.maxAttempts, enabled) { n -> onEdit { StepBehaviour.setMaxAttempts(it, n) } }
            ChoiceRow("Wait between tries", listOf("1 s" to 1_000L, "5 s" to 5_000L, "30 s" to 30_000L, "1 min" to 60_000L), node.retry.initialDelayMs, enabled) { ms -> onEdit { StepBehaviour.setWaitMs(it, ms) } }
            ChoiceRow("Wait grows", listOf("Stays the same" to 1.0, "Doubles" to 2.0, "Triples" to 3.0), node.retry.backoffMultiplier, enabled) { m -> onEdit { StepBehaviour.setBackoff(it, m) } }
        }
        Spacer(Modifier.height(8.dp))
        ChoiceRow(
            "Time limit",
            listOf("None" to null, "10 s" to 10L, "30 s" to 30L, "1 min" to 60L, "5 min" to 300L, "15 min" to 900L),
            StepBehaviour.timeoutSeconds(node), enabled,
        ) { s -> onEdit { StepBehaviour.setTimeoutSeconds(it, s) } }
        Spacer(Modifier.height(12.dp))
        SectionHeader("Notes")
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it; onEdit { n -> StepBehaviour.setNotes(n, it) } },
            enabled = enabled,
            label = { Text("What this Step is for") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** About tab: what the Step does, where it sits in the catalog and what each field means. */
@Composable
fun AboutTab(spec: NodeSpec?, schema: ParamSchema) {
    val colors = KaivoTheme.colors
    val m = AboutModel.build(spec, schema)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(m.title, style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
        Text(m.description, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        if (m.categoryPath.isNotBlank()) StatusBadge(m.categoryPath, BadgeKind.Neutral)
        m.lifecycleNote?.let { StatusBadge(it, BadgeKind.Gold) }
        m.permissionNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary) }
        if (m.outputs.size > 1) Text("Outputs: " + m.outputs.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        if (m.fields.isNotEmpty()) SectionHeader("Fields")
        m.fields.forEach { f ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(f.label + if (f.required) "  (required)" else "", style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                f.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
