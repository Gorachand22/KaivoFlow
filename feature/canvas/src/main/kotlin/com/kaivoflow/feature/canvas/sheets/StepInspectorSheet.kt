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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.expressions.FallbackExpressionEvaluator
import kotlinx.coroutines.delay
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
import com.kaivoflow.feature.canvas.StreamEdgeUiModel
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
    connectedStreams: List<StreamEdgeUiModel> = emptyList(),
    allSteps: List<StepNodeUiModel> = emptyList(),
    vars: Map<String, Any?> = emptyMap(),
    onTab: (InspectorTab) -> Unit,
    onParamChange: (String, Any?) -> Unit,
    onRename: (String) -> Unit,
    onDisconnectStream: (StreamEdgeUiModel) -> Unit = {},
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

    val liveScope = remember(vars, inspector.inputItems) {
        val inputFirst = inspector.inputItems.firstOrNull()?.json ?: emptyMap()
        val envMap = buildMap<String, Any?> {
            runCatching { putAll(System.getenv()) }
            put("OS", "Android")
            put("PLATFORM", "KaivoFlow")
            put("TIMEZONE", java.time.ZoneId.systemDefault().id)
            put("DEVICE", android.os.Build.MODEL)
            put("USER", "SingleUser")
        }
        mapOf(
            "vars" to vars,
            "env" to envMap,
            "json" to inputFirst,
            "items" to inspector.inputItems.map { it.json },
            "item" to inputFirst,
            "now" to java.time.Instant.now().toString(),
            "today" to java.time.LocalDate.now().toString(),
        )
    }

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
                if (step.isFrozen) {
                    StatusBadge("❄ Frozen", BadgeKind.Gold)
                } else if (inspector.isTesting) {
                    StatusBadge("Testing…", BadgeKind.Gold)
                }
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
                    InspectorTab.Input -> NodeDataViewer(
                        items = inspector.inputItems,
                        emptyText = "No items yet. Run the Flow, or test the Step above this one.",
                        onCopyExpression = { expr ->
                            focusedField?.let { onParamChange(it, expr) }
                        },
                    )
                    InspectorTab.Configure -> ConfigureTab(
                        spec = spec,
                        params = params,
                        name = name,
                        stepId = step.id,
                        connectedStreams = connectedStreams,
                        allSteps = allSteps,
                        scope = liveScope,
                        onNameChange = {
                            name = it
                            onRename(it)
                        },
                        onParamChange = onParamChange,
                        onFocusChange = { key, focused -> focusedField = if (focused) key else null },
                        onDisconnectStream = onDisconnectStream,
                        isDisabled = step.isDisabled,
                        onToggleDisabled = onToggleDisabled,
                    )
                    InspectorTab.Output -> OutputTab(
                        items = inspector.outputItems,
                        error = inspector.outputError,
                        isTesting = inspector.isTesting,
                        onCopyExpression = { expr ->
                            focusedField?.let { onParamChange(it, expr) }
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            // Expression accessory bar — appears only while a field is focused.
            if (focusedField != null) {
                ExpressionAccessoryBar(
                    field = focusedField!!,
                    currentValue = params[focusedField]?.toString().orEmpty(),
                    scope = liveScope,
                    previousStepNames = previousStepNames,
                    onInsert = { snippet -> onParamChange(focusedField!!, snippet) },
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GhostButton(
                        text = if (step.isFrozen) "Unfreeze" else "Freeze",
                        icon = "save",
                        onClick = onFreezeOutput,
                    )
                    GhostButton(
                        text = "Delete",
                        icon = "delete",
                        onClick = onDelete,
                    )
                }
                GoldButton(
                    text = if (inspector.isTesting) "Running…" else "Run",
                    icon = "play",
                    compact = true,
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
    stepId: String,
    connectedStreams: List<StreamEdgeUiModel>,
    allSteps: List<StepNodeUiModel>,
    scope: Map<String, Any?>,
    onNameChange: (String) -> Unit,
    onParamChange: (String, Any?) -> Unit,
    onFocusChange: (String, Boolean) -> Unit,
    onDisconnectStream: (StreamEdgeUiModel) -> Unit,
    isDisabled: Boolean,
    onToggleDisabled: () -> Unit,
) {
    val colors = KaivoTheme.colors
    val incoming = connectedStreams.filter { it.toStepId == stepId }
    val outgoing = connectedStreams.filter { it.fromStepId == stepId }

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

        // Connections section
        if (incoming.isNotEmpty() || outgoing.isNotEmpty()) {
            Text("Connected Streams", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                incoming.forEach { edge ->
                    val sourceName = allSteps.firstOrNull { it.id == edge.fromStepId }?.name ?: "Previous Step"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(KaivoShapes.chip)
                            .background(colors.surfaceElevated)
                            .border(1.dp, colors.hairline, KaivoShapes.chip)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(KaivoIcons.icon("route"), contentDescription = null, tint = colors.royalGold, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("From: $sourceName", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { onDisconnectStream(edge) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(KaivoIcons.icon("disconnect"), contentDescription = "Disconnect Stream", tint = colors.velvetRose, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                outgoing.forEach { edge ->
                    val targetName = allSteps.firstOrNull { it.id == edge.toStepId }?.name ?: "Next Step"
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(KaivoShapes.chip)
                            .background(colors.surfaceElevated)
                            .border(1.dp, colors.hairline, KaivoShapes.chip)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(KaivoIcons.icon("route"), contentDescription = null, tint = colors.stepTransform, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("To: $targetName", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { onDisconnectStream(edge) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(KaivoIcons.icon("disconnect"), contentDescription = "Disconnect Stream", tint = colors.velvetRose, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

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
            ParamField(
                param = param,
                value = params[param.key],
                scope = scope,
                onChange = onParamChange,
                onFocusChange = onFocusChange,
            )
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

private data class EvaluationPreview(
    val value: String?,
    val isError: Boolean = false,
    val isWarning: Boolean = false,
)

private fun evaluatePreview(raw: String, scope: Map<String, Any?>): EvaluationPreview {
    val trimmed = raw.trim()
    if (!trimmed.contains("{{")) return EvaluationPreview(null)
    val inside = trimmed.substringAfter("{{").substringBefore("}}").trim()
    if (inside.endsWith(".") || inside == "$" || inside.isEmpty()) {
        return EvaluationPreview("…waiting for property name", isWarning = true)
    }
    return try {
        val evaluator = FallbackExpressionEvaluator()
        val result = evaluator.interpolate(trimmed, scope)
        if (result == null) {
            EvaluationPreview("null / undefined", isWarning = true)
        } else {
            EvaluationPreview(result.toString())
        }
    } catch (e: Exception) {
        EvaluationPreview(e.message?.take(50) ?: "Syntax error", isError = true)
    }
}

@Composable
private fun ExpressionPreviewBadge(preview: EvaluationPreview) {
    val colors = KaivoTheme.colors
    Row(
        Modifier
            .padding(top = 4.dp)
            .clip(KaivoShapes.pill)
            .background(
                if (preview.isError) colors.statusError.copy(alpha = 0.12f)
                else if (preview.isWarning) colors.royalGold.copy(alpha = 0.1f)
                else colors.surfaceElevated
            )
            .border(
                1.dp,
                if (preview.isError) colors.statusError.copy(alpha = 0.4f)
                else if (preview.isWarning) colors.royalGold.copy(alpha = 0.3f)
                else colors.royalGold.copy(alpha = 0.45f),
                KaivoShapes.pill
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (preview.isError) "⚠ ${preview.value}" else if (preview.isWarning) "✦ ${preview.value}" else "✦ Result: ${preview.value}",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(fontFamily = KaivoTheme.type.monoFamily),
            color = if (preview.isError) colors.statusError else if (preview.isWarning) colors.inkMuted else colors.royalGold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ParamField(
    param: ParamSpec,
    value: Any?,
    scope: Map<String, Any?>,
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

        ParamType.Multiline, ParamType.Json, ParamType.Code, ParamType.Expression, ParamType.KeyValue -> {
            val strVal = Values.asString(value)
            val preview = remember(strVal, scope) {
                if (strVal.contains("{{")) evaluatePreview(strVal, scope) else null
            }

            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = strVal,
                    onValueChange = { onChange(param.key, it) },
                    label = { Text(param.label) },
                    placeholder = { if (param.placeholder.isNotBlank()) Text(param.placeholder) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onFocusChange(param.key, it.isFocused) },
                    minLines = 3,
                    maxLines = 8,
                    shape = KaivoShapes.chip,
                    textStyle = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(
                        fontFamily = KaivoTheme.type.monoFamily,
                        fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                    supportingText = if (param.hint.isNotBlank()) {
                        { Text(param.hint) }
                    } else null,
                )
                if (preview?.value != null) {
                    ExpressionPreviewBadge(preview)
                }
            }
        }

        else -> {
            val strVal = Values.asString(value)
            val preview = remember(strVal, scope) {
                if (strVal.contains("{{")) evaluatePreview(strVal, scope) else null
            }

            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = strVal,
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
                if (preview?.value != null) {
                    ExpressionPreviewBadge(preview)
                }
            }
        }
    }
}

private data class ExpressionSuggestion(
    val label: String,
    val detail: String? = null,
    val snippet: String,
)

@Composable
private fun ExpressionAccessoryBar(
    field: String,
    currentValue: String,
    scope: Map<String, Any?>,
    previousStepNames: List<String>,
    onInsert: (String) -> Unit,
) {
    val colors = KaivoTheme.colors
    val trimmed = currentValue.trim()
    val preview = remember(currentValue, scope) {
        if (currentValue.contains("{{")) evaluatePreview(currentValue, scope) else null
    }

    val varsMap = (scope["vars"] as? Map<*, *>)?.keys?.mapNotNull { it?.toString() } ?: emptyList()
    val envMap = (scope["env"] as? Map<*, *>)?.keys?.mapNotNull { it?.toString() } ?: emptyList()
    val jsonMap = (scope["json"] as? Map<*, *>)?.keys?.mapNotNull { it?.toString() } ?: emptyList()

    val suggestions = remember(currentValue, varsMap, envMap, jsonMap, previousStepNames) {
        when {
            trimmed.contains("\$vars.") || trimmed.endsWith("\$vars") -> {
                varsMap.map { key ->
                    val resolvedVal = (scope["vars"] as? Map<*, *>)?.get(key)?.toString()?.take(25)
                    val varPattern = Regex("""\$\{'\$'}vars\.\w*""")
                    ExpressionSuggestion(
                        label = key,
                        detail = resolvedVal,
                        snippet = if (trimmed.contains("{{") && !trimmed.contains("}}")) {
                            trimmed.replace(varPattern, "\$vars.$key") + "}}"
                        } else if (trimmed.contains("\$vars.")) {
                            trimmed.replace(varPattern, "\$vars.$key")
                        } else {
                            "{{ \$vars.$key }}"
                        }
                    )
                }
            }
            trimmed.contains("\$env.") || trimmed.endsWith("\$env") -> {
                envMap.map { key ->
                    val resolvedVal = (scope["env"] as? Map<*, *>)?.get(key)?.toString()?.take(25)
                    val envPattern = Regex("""\$\{'\$'}env\.\w*""")
                    ExpressionSuggestion(
                        label = key,
                        detail = resolvedVal,
                        snippet = if (trimmed.contains("{{") && !trimmed.contains("}}")) {
                            trimmed.replace(envPattern, "\$env.$key") + "}}"
                        } else if (trimmed.contains("\$env.")) {
                            trimmed.replace(envPattern, "\$env.$key")
                        } else {
                            "{{ \$env.$key }}"
                        }
                    )
                }
            }
            trimmed.contains("\$json.") || trimmed.endsWith("\$json") -> {
                jsonMap.map { key ->
                    val resolvedVal = (scope["json"] as? Map<*, *>)?.get(key)?.toString()?.take(25)
                    val jsonPattern = Regex("""\$\{'\$'}json\.\w*""")
                    ExpressionSuggestion(
                        label = key,
                        detail = resolvedVal,
                        snippet = if (trimmed.contains("{{") && !trimmed.contains("}}")) {
                            trimmed.replace(jsonPattern, "\$json.$key") + "}}"
                        } else if (trimmed.contains("\$json.")) {
                            trimmed.replace(jsonPattern, "\$json.$key")
                        } else {
                            "{{ \$json.$key }}"
                        }
                    )
                }
            }
            trimmed.contains("\$step") -> {
                previousStepNames.map { stepName ->
                    ExpressionSuggestion(
                        label = stepName,
                        detail = "Step output",
                        snippet = "{{ \$step(\"$stepName\") }}"
                    )
                }
            }
            else -> {
                listOf(
                    ExpressionSuggestion("{{ \$vars. }}", "${varsMap.size} vars", "{{ \$vars. }}"),
                    ExpressionSuggestion("{{ \$env. }}", "${envMap.size} env keys", "{{ \$env. }}"),
                    ExpressionSuggestion("{{ \$json. }}", "${jsonMap.size} fields", "{{ \$json. }}"),
                    ExpressionSuggestion("{{ \$now }}", "Current time", "{{ \$now }}"),
                    ExpressionSuggestion("{{ \$today }}", "Today's date", "{{ \$today }}"),
                ) + previousStepNames.map {
                    ExpressionSuggestion("{{ \$step(\"$it\") }}", "Output from $it", "{{ \$step(\"$it\") }}")
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceElevated.copy(alpha = 0.95f))
            .border(1.dp, colors.hairline)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                when {
                    trimmed.contains("\$vars.") -> "Variables (tap to complete):"
                    trimmed.contains("\$env.") -> "Environment (tap to complete):"
                    trimmed.contains("\$json.") -> "Input fields (tap to complete):"
                    else -> "Expressions for $field:"
                },
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = colors.inkMuted,
            )
            if (preview?.value != null) {
                Box(
                    Modifier
                        .clip(KaivoShapes.pill)
                        .background(
                            if (preview.isError) colors.statusError.copy(alpha = 0.15f)
                            else if (preview.isWarning) colors.royalGold.copy(alpha = 0.12f)
                            else colors.royalGold.copy(alpha = 0.2f)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (preview.isError) "⚠ ${preview.value}" else "✦ ${preview.value}",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(fontFamily = KaivoTheme.type.monoFamily),
                        color = if (preview.isError) colors.statusError else colors.royalGold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), state = rememberLazyListState()) {
            items(suggestions) { item ->
                Box(
                    modifier = Modifier
                        .clip(KaivoShapes.chip)
                        .background(colors.surfaceElevated)
                        .border(1.dp, colors.imperialAmethyst.copy(alpha = 0.45f), KaivoShapes.chip)
                        .clickable { onInsert(item.snippet) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.label,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(
                                fontFamily = KaivoTheme.type.monoFamily,
                            ),
                            color = colors.royalGold,
                        )
                        if (item.detail != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "= ${item.detail}",
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                color = colors.inkMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

enum class DataViewMode { Table, Json, Schema }

@Composable
private fun NodeDataViewer(
    items: ImmutableList<FlowData>,
    emptyText: String,
    onCopyExpression: ((String) -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    val clipboardManager = LocalClipboardManager.current
    var viewMode by remember { mutableStateOf(DataViewMode.Table) }
    var copiedSnippet by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedSnippet) {
        if (copiedSnippet != null) {
            delay(2000)
            copiedSnippet = null
        }
    }

    if (items.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(KaivoIcons.icon("inbox"), null, tint = colors.inkMuted, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(emptyText, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        }
        return
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        // Top status and view mode bar (n8n style)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(colors.statusSuccess.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(KaivoIcons.icon("check"), null, tint = colors.statusSuccess, modifier = Modifier.size(11.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "${items.size} item${if (items.size == 1) "" else "s"}",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = colors.inkPrimary,
                )
                if (copiedSnippet != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Copied $copiedSnippet",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = colors.royalGold,
                    )
                }
            }

            // View mode pill: Table | JSON | Schema
            Row(
                Modifier
                    .clip(KaivoShapes.pill)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.hairline, KaivoShapes.pill)
                    .padding(2.dp),
            ) {
                DataViewMode.entries.forEach { mode ->
                    val isSelected = viewMode == mode
                    Box(
                        Modifier
                            .clip(KaivoShapes.pill)
                            .background(if (isSelected) colors.royalGold.copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { viewMode = mode }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = mode.name,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = if (isSelected) colors.royalGold else colors.inkMuted,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        when (viewMode) {
            DataViewMode.Table -> TableView(
                items = items,
                onKeyClick = { key ->
                    val expr = "{{ \$json.$key }}"
                    clipboardManager.setText(AnnotatedString(expr))
                    copiedSnippet = expr
                    onCopyExpression?.invoke(expr)
                },
            )
            DataViewMode.Json -> JsonView(
                items = items,
                onCopy = { json ->
                    clipboardManager.setText(AnnotatedString(json))
                    copiedSnippet = "JSON"
                },
            )
            DataViewMode.Schema -> SchemaView(
                items = items,
                onFieldClick = { field ->
                    val expr = "{{ \$json.$field }}"
                    clipboardManager.setText(AnnotatedString(expr))
                    copiedSnippet = expr
                    onCopyExpression?.invoke(expr)
                },
            )
        }
    }
}

@Composable
private fun TableView(
    items: ImmutableList<FlowData>,
    onKeyClick: (String) -> Unit,
) {
    val colors = KaivoTheme.colors
    val allKeys = remember(items) {
        val keys = linkedSetOf<String>()
        items.forEach { keys.addAll(it.json.keys) }
        keys.toList()
    }

    if (allKeys.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(KaivoShapes.chip)
                .background(colors.surfaceCard)
                .padding(14.dp),
        ) {
            Text("Empty object {}", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
        }
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.chip)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.chip)
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Column {
                // Header row
                Row(
                    Modifier
                        .background(colors.surfaceElevated.copy(alpha = 0.6f))
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    Text(
                        "#",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                        modifier = Modifier.width(32.dp),
                    )
                    allKeys.forEach { key ->
                        Box(
                            Modifier
                                .width(140.dp)
                                .clickable { onKeyClick(key) }
                                .padding(horizontal = 6.dp),
                        ) {
                            Text(
                                text = key,
                                style = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = KaivoTheme.type.monoFamily,
                                ),
                                color = colors.royalGold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Data rows
                items.forEachIndexed { index, item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (index % 2 == 0) colors.surfaceCard else colors.surfaceElevated.copy(alpha = 0.25f))
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = colors.inkMuted,
                            modifier = Modifier.width(32.dp),
                        )
                        allKeys.forEach { key ->
                            val value = item.json[key]
                            val display = when (value) {
                                null -> "null"
                                is Map<*, *> -> "{…} (${value.size})"
                                is List<*> -> "[…] (${value.size})"
                                is String -> value
                                else -> value.toString()
                            }
                            Box(
                                Modifier
                                    .width(140.dp)
                                    .clickable { onKeyClick(key) }
                                    .padding(horizontal = 6.dp),
                            ) {
                                Text(
                                    text = display,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = if (value == null) colors.inkMuted else colors.inkPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JsonView(
    items: ImmutableList<FlowData>,
    onCopy: (String) -> Unit,
) {
    val colors = KaivoTheme.colors
    val fullJson = remember(items) {
        val payload = if (items.size == 1) items.first().json else items.map { it.json }
        Values.stringify(Values.redact(payload), pretty = true)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.chip)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.chip)
            .padding(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "JSON Payload",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
            )
            Box(
                Modifier
                    .clip(KaivoShapes.pill)
                    .background(colors.surfaceElevated)
                    .clickable { onCopy(fullJson) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(KaivoIcons.icon("copy"), null, tint = colors.royalGold, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy JSON", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = colors.royalGold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = fullJson,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(
                fontFamily = KaivoTheme.type.monoFamily,
                fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
            ),
            color = colors.inkSecondary,
        )
    }
}

@Composable
private fun SchemaView(
    items: ImmutableList<FlowData>,
    onFieldClick: (String) -> Unit,
) {
    val colors = KaivoTheme.colors
    val schemaEntries = remember(items) {
        val fields = linkedMapOf<String, Pair<String, String>>()
        items.forEach { item ->
            item.json.forEach { (key, value) ->
                val type = when (value) {
                    null -> "null"
                    is String -> "string"
                    is Number -> "number"
                    is Boolean -> "boolean"
                    is Map<*, *> -> "object"
                    is List<*> -> "array"
                    else -> value::class.java.simpleName.lowercase()
                }
                val sample = when (value) {
                    null -> "null"
                    is Map<*, *> -> "{…} ${value.size} keys"
                    is List<*> -> "[…] ${value.size} items"
                    else -> value.toString().take(40)
                }
                fields[key] = type to sample
            }
        }
        fields.toList()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(KaivoShapes.chip)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.chip)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        schemaEntries.forEach { (key, typeAndSample) ->
            val (type, sample) = typeAndSample
            val badgeColor = when (type) {
                "string" -> colors.statusSuccess
                "number" -> colors.royalGold
                "boolean" -> colors.imperialAmethyst
                "object" -> colors.categoryColor(com.kaivoflow.core.model.StepCategory.Ai)
                "array" -> colors.categoryColor(com.kaivoflow.core.model.StepCategory.Device)
                else -> colors.inkMuted
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onFieldClick(key) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        text = key,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(
                            fontFamily = KaivoTheme.type.monoFamily,
                        ),
                        color = colors.inkPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(badgeColor.copy(alpha = 0.15f))
                            .border(1.dp, badgeColor.copy(alpha = 0.4f), CircleShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(type, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = badgeColor)
                    }
                }
                Text(
                    text = sample,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OutputTab(
    items: ImmutableList<FlowData>?,
    error: String?,
    isTesting: Boolean,
    onCopyExpression: ((String) -> Unit)? = null,
) {
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
    NodeDataViewer(
        items = items ?: emptyList<FlowData>().toImmutableList(),
        emptyText = "Tap Test Step to see live output.",
        onCopyExpression = onCopyExpression,
    )
}
