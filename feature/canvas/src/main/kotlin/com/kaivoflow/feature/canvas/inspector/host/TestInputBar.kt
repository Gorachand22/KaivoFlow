package com.kaivoflow.feature.canvas.inspector.host

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.KaivoChip
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.feature.canvas.inspector.logic.TestInputPolicy
import com.kaivoflow.feature.canvas.inspector.state.InputSource
import com.kaivoflow.feature.canvas.inspector.state.InspectorIntent
import com.kaivoflow.feature.canvas.inspector.state.InspectorState
import com.kaivoflow.feature.canvas.inspector.state.ParentPolicy

/** True when the Input pane should show the typed input instead of stored data (nothing was used yet). */
fun showsTypedInput(state: InspectorState): Boolean =
    !state.input.inbound && TestInputPolicy.effective(state.testInput.selection, state.testInput.options, state.isSpark).isText

/**
 * What Test Step will use as input, and the way to change it: source chips (Pinned, Last run, Last test, Manual), the JSON
 * editor with validation and "Sample from schema", the label of the input the last test really used, and the separate,
 * optional "Run earlier Steps first" action. The main Test Step button never runs earlier Steps.
 */
@Composable
fun TestInputBar(session: InspectorSession, state: InspectorState, hasParents: Boolean) {
    val colors = KaivoTheme.colors
    val ti = state.testInput
    val effective = TestInputPolicy.effective(ti.selection, ti.options, state.isSpark)
    val dispatch = session.holder::dispatch
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (state.isSpark) "Test with a sample event. Nothing is armed or published." else "Test input. Only this Step runs.",
            style = MaterialTheme.typography.labelMedium, color = colors.inkSecondary,
        )
        if (!state.isSpark) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (o in ti.options) {
                    KaivoChip("${o.source.label} ${o.itemCount}", { dispatch(InspectorIntent.SelectInputSource(o.source)) }, selected = effective == o.source, enabled = !state.readOnly)
                }
                KaivoChip(InputSource.Manual.label, { dispatch(InspectorIntent.SelectInputSource(InputSource.Manual)) }, selected = effective == InputSource.Manual, enabled = !state.readOnly)
            }
        }
        state.used?.let { u ->
            val counts = u.portCounts.entries.sortedBy { it.key }.joinToString(", ") { "port ${it.key + 1}: ${it.value}" }
            StatusBadge("Used: ${u.source.label}, ${u.itemsIn} in" + (if (counts.isNotEmpty()) ", $counts" else ""), BadgeKind.Neutral)
            u.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary) }
        }
        if (effective.isText && !state.input.inbound) {
            OutlinedTextField(
                value = ti.manualText,
                onValueChange = { dispatch(InspectorIntent.EditManualInput(it)) },
                enabled = !state.readOnly,
                label = { Text(if (state.isSpark) "Sample event (JSON)" else "Manual input (JSON)") },
                isError = ti.manualError != null,
                minLines = 4,
                maxLines = 12,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = KaivoTheme.type.monoFamily),
                modifier = Modifier.fillMaxWidth(),
            )
            ti.manualError?.let { e ->
                Text("Line ${e.line}, column ${e.column}: ${e.message}", style = MaterialTheme.typography.bodySmall, color = colors.statusError)
            }
            GhostButton(if (state.isSpark) "Reset sample" else "Sample from schema", { dispatch(InspectorIntent.FillSampleFromSchema) }, icon = "refresh")
        }
        if (hasParents && !state.isSpark && !state.readOnly) {
            Text("Parents without data stay untouched unless you choose this:", style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            GhostButton("Run earlier Steps first (optional)", { dispatch(InspectorIntent.TestStepRequested(ParentPolicy.RunThem)) }, icon = "play")
        }
    }
}
