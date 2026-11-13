package com.kaivoflow.feature.canvas.sheets

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.ExpressionChip
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SectionHeader
import com.kaivoflow.core.design.SegmentedPill
import com.kaivoflow.core.design.SheetDragHandle
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.design.StepMedallion
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.feature.canvas.InspectorTab
import com.kaivoflow.feature.canvas.InspectorUiState
import com.kaivoflow.feature.canvas.StepNodeUiModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Step Inspector: Input / Configure / Output.
 *
 * The Configure tab is generated from [NodeSpec.params] — a new Step needs no UI work. Focusing a
 * text field raises the Expression Accessory Bar so `{{ $json }}`, `$now` and previous Step outputs
 * are one tap away instead of something the user has to memorise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepInspectorSheet(
    inspector: InspectorUiState,
    step: StepNodeUiModel,
    spec: NodeSpec?,
    params: Map<String, Any?>,
    previousStepNames: List<String>,
    onTab: (InspectorTab) -> Unit,
    onParamChange: (String, Any?) -> Unit,
    onRename: (String) -> Unit,
    onTestStep: () -> Unit,
    onFreezeOutput: () -> Unit,
    onToggleDisabled: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KaivoTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var focusedField by remember { mutableStateOf<String?>(null) }
    var name by remember(step.id) { mutableStateOf(step.name) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceAtelier,
        dragHandle = {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SheetDragHandle()
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepMedallion(iconKey = step.iconKey, tint = colors.categoryColor(step.category))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.name, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
                    Text(
                        spec?.subtitle ?: "This Step is not available on this build",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                }
                StatusBadge(if (step.isFrozen) "Frozen" else "Test", BadgeKind.Gold)
            }
            Spacer(Modifier.height(12.dp))

            SegmentedPill(
                options = InspectorTab.entries.map { it.label },
                selectedIndex = inspector.tab.ordinal,
                onSelect = { onTab(InspectorTab.entries[it]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))

            Box(Modifier.heightIn(min = 240.dp, max = 460.dp).verticalScroll(rememberScrollState())) {
                when (inspector.tab) {
                    InspectorTab.Input -> ItemsTable(
                        items = inspector.inputItems,
                        emptyText = "No items yet. Run the Flow, or test the Step above this one.",
                    )
                    InspectorTab.Configure -> ConfigureTab(
                        spec = spec,
                        params = params,
                        name = name,
                        onNameChange = {
                            name = it
                            onRename(it)
                        },
                        onParamChange = onParamChange,
                        onFocusChange = { key, focused -> focusedField = if (focused) key else null },
                        isDisabled = step.isDisabled,
                        onToggleDisabled = onToggleDisabled,
                    )
                    InspectorTab.Output -> OutputTab(
                        items = inspector.outputItems,
                        error = inspector.outputError,
                        isTesting = inspector.isTesting,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            // Expression accessory bar — appears only while a field is focused.
            if (focusedField != null) {
                ExpressionAccessoryBar(
                    field = focusedField!!,
                    previousStepNames = previousStepNames,
                    onInsert = { snippet -> onParamChange(focusedField!!, snippet) },
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton(
                    text = if (step.isFrozen) "Unfreeze" else "Freeze output",
                    icon = "save",
                    onClick = onFreezeOutput,
                )
                GhostButton(text = "Delete", icon = "delete", onClick = onDelete)
                Spacer(Modifier.weight(1f))
                GoldButton(
                    text = if (inspector.isTesting) "Testing…" else "Test Step",
                    icon = "play",
                    enabled = !inspector.isTesting,
                    onClick = onTestStep,
                )
            }
        }
    }
}

@Composable
private fun ConfigureTab(
    spec: NodeSpec?,
    params: Map<String, Any?>,
    name: String,
    onNameChange: (String) -> Unit,
    onParamChange: (String, Any?) -> Unit,
    onFocusChange: (String, Boolean) -> Unit,
    isDisabled: Boolean,
    onToggleDisabled: () -> Unit,
) {
    val colors = KaivoTheme.colors
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Step name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = KaivoShapes.chip,
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Switch this Step off", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                Text(
                    "Items pass straight through, so the rest of the Flow keeps working.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                )
            }
            Switch(checked = isDisabled, onCheckedChange = { onToggleDisabled() })
        }
        Spacer(Modifier.height(16.dp))

        val visible = spec?.params.orEmpty().filter { param -> isVisible(param, params) }
        visible.forEach { param ->
            ParamField(param = param, value = params[param.key], onChange = onParamChange, onFocusChange = onFocusChange)
            Spacer(Modifier.height(12.dp))
        }
        if (visible.isEmpty()) {
            Text(
                "This Step needs no settings.",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun isVisible(param: ParamSpec, params: Map<String, Any?>): Boolean {
    if (param.showWhen.isEmpty()) return true
    return param.showWhen.any { (key, allowed) ->
        val current = Values.asString(params[key])
        allowed.any { Values.asString(it) == current }
    }
}

@Composable
private fun ParamField(
    param: ParamSpec,
    value: Any?,
    onChange: (String, Any?) -> Unit,
    onFocusChange: (String, Boolean) -> Unit,
) {
    val colors = KaivoTheme.colors
    when (param.type) {
        ParamType.Boolean -> Row(
            Modifier.fillMaxWidth().clip(KaivoShapes.chip).clickable { onChange(param.key, !Values.asBoolean(value)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(param.label, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                if (param.hint.isNotBlank()) {
                    Text(param.hint, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                }
            }
            Switch(checked = Values.asBoolean(value), onCheckedChange = { onChange(param.key, it) })
        }

        ParamType.Select -> Column {
            Text(param.label, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(param.options.toList()) { option ->
                    val selected = Values.asString(value) == option.value
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(if (selected) colors.royalGold.copy(alpha = 0.2f) else colors.surfaceElevated)
                            .border(1.dp, if (selected) colors.royalGold.copy(alpha = 0.6f) else colors.hairline, CircleShape)
                            .clickable { onChange(param.key, option.value) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text(
                            option.label,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = if (selected) colors.royalGold else colors.inkSecondary,
                        )
                    }
                }
            }
        }

        ParamType.Multiline, ParamType.Json, ParamType.Code, ParamType.Expression, ParamType.KeyValue -> OutlinedTextField(
            value = Values.asString(value),
            onValueChange = { onChange(param.key, it) },
            label = { Text(param.label) },
            placeholder = { if (param.placeholder.isNotBlank()) Text(param.placeholder) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChange(param.key, it.isFocused) },
            minLines = 3,
            maxLines = 8,
            shape = KaivoShapes.chip,
            textStyle = if (param.type == ParamType.Json || param.type == ParamType.Code || param.type == ParamType.Expression) {
                androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(
                    fontFamily = KaivoTheme.type.monoFamily,
                    fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
                )
            } else androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            supportingText = if (param.hint.isNotBlank()) {
                { Text(param.hint) }
            } else null,
        )

        else -> OutlinedTextField(
            value = if (param.type == ParamType.Secret) Values.asString(value) else Values.asString(value),
            onValueChange = { onChange(param.key, it) },
            label = { Text(param.label) },
            placeholder = { if (param.placeholder.isNotBlank()) Text(param.placeholder) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChange(param.key, it.isFocused) },
            singleLine = true,
            shape = KaivoShapes.chip,
            keyboardOptions = if (param.type == ParamType.Number) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else KeyboardOptions.Default,
            supportingText = if (param.hint.isNotBlank()) ({ Text(param.hint) }) else null,
        )
    }
}

@Composable
private fun ExpressionAccessoryBar(
    field: String,
    previousStepNames: List<String>,
    onInsert: (String) -> Unit,
) {
    val colors = KaivoTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceElevated.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            "Insert into $field",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = colors.inkMuted,
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), state = rememberLazyListState()) {
            val snippets = buildList {
                add("{{ \$json. }}")
                add("{{ \$now }}")
                add("{{ \$today }}")
                add("{{ \$vars. }}")
                previousStepNames.forEach { add("{{ \$step(\"$it\") }}") }
            }
            items(snippets) { snippet ->
                ExpressionChip(expression = snippet, onClick = { onInsert(snippet) })
            }
        }
    }
}

@Composable
private fun ItemsTable(items: ImmutableList<FlowData>, emptyText: String) {
    val colors = KaivoTheme.colors
    if (items.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(KaivoIcons.icon("inbox"), null, tint = colors.inkMuted, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(emptyText, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        }
        return
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        SectionHeader(title = "${items.size} item${if (items.size == 1) "" else "s"}")
        Spacer(Modifier.height(8.dp))
        items.forEachIndexed { index, item ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(KaivoShapes.chip)
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.hairline, KaivoShapes.chip)
                    .padding(10.dp),
            ) {
                Text(
                    text = Values.stringify(Values.redact(item.json), pretty = true),
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = if (item.isError) colors.statusError else colors.inkSecondary,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun OutputTab(items: ImmutableList<FlowData>?, error: String?, isTesting: Boolean) {
    val colors = KaivoTheme.colors
    if (isTesting) {
        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(color = colors.royalGold, strokeWidth = 2.dp)
        }
        return
    }
    if (error != null) {
        Column(Modifier.padding(16.dp)) {
            StatusBadge("This Step failed", BadgeKind.Error)
            Spacer(Modifier.height(8.dp))
            Text(error, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.statusError)
        }
        return
    }
    ItemsTable(
        items = items ?: emptyList<FlowData>().toImmutableList(),
        emptyText = "Tap Test Step to see live output.",
    )
}
