package com.kaivoflow.feature.canvas.inspector.params

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.KaivoBottomSheet
import com.kaivoflow.core.design.KaivoChip
import com.kaivoflow.core.design.KaivoIconButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SearchField
import com.kaivoflow.core.design.SegmentOption
import com.kaivoflow.core.design.SegmentedSwitch
import com.kaivoflow.core.design.SheetOptionRow
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.NoticeTone
import com.kaivoflow.core.model.OptionSource
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamIssue
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.ParamRule
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.TextFormat
import com.kaivoflow.core.model.DateTimeMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

// T17: parameter widgets, batch 1. Each is stateless: ParamDef, stored ParamValue, issues at its path,
// callbacks. Where the field can be an expression, the pearl switches and ExpressionField takes over.

/** Shared arguments, so ParamForm can call every widget alike. */
private fun fixedOf(def: ParamDef, value: ParamValue?): Any? = ((value ?: def.default) as? ParamValue.Fixed)?.value

@Composable
private fun ExprBody(
    def: ParamDef, value: ParamValue?, onChange: (ParamValue?) -> Unit, issues: List<ParamIssue>,
    path: ParamPath, env: ExprFieldEnv?, enabled: Boolean, multiline: Boolean = false, onFull: (() -> Unit)? = null,
) {
    ExpressionField(
        path = path, text = ParamModes.exprText(value ?: def.default), onTextChange = { onChange(ParamValue.Expr(it)) },
        label = def.label, env = env, enabled = enabled, multiline = multiline, minLines = if (multiline) 3 else 1,
        placeholder = def.placeholder, issues = issues, onOpenFullScreen = onFull,
    )
}

@Composable
fun TextParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    expr: ExprFieldEnv? = null, path: ParamPath = ParamPath.of(def.key),
) {
    val kind = def.kind as ParamKind.Text
    val isExpr = ParamModes.isExpression(def, value)
    val maxLen = def.rules.filterIsInstance<ParamRule.MaxLength>().firstOrNull()?.n
    var full by remember { mutableStateOf(false) }
    val text = ParamModes.fixedText(value ?: def.default)
    ParamFieldFrame(
        def, value, onChange, issues, modifier, path, togglePearl(def, value, onChange) { expr?.literalOf(path) }, dependsOnExpr, enabled,
        counter = if (maxLen != null && !isExpr) "${text.length}/$maxLen" else null,
    ) {
        if (isExpr) {
            ExprBody(def, value, onChange, issues, path, expr, enabled, kind.multiline, onFull = { full = true })
        } else {
            val kb = when (kind.format) {
                TextFormat.Url -> KeyboardType.Uri
                TextFormat.Email -> KeyboardType.Email
                TextFormat.Phone -> KeyboardType.Phone
                else -> KeyboardType.Text
            }
            ParamInput(
                value = text,
                onValueChange = { raw ->
                    // Smart quotes are sanitised only for Url and Path (spec 2.1).
                    val t = if (kind.format == TextFormat.Url || kind.format == TextFormat.Path) raw.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'') else raw
                    onChange(if (t.isEmpty()) null else ParamValue.Fixed(t))
                },
                label = def.label, enabled = enabled, singleLine = !kind.multiline, minLines = if (kind.multiline) 3 else 1,
                maxLines = if (kind.multiline) 8 else 1, placeholder = def.placeholder,
                keyboardOptions = KeyboardOptions(keyboardType = kb),
                isError = issues.any { it.severity == com.kaivoflow.core.model.ParamSeverity.Error },
                trailing = if (kind.multiline) ({ KaivoIconButton("unfoldMore", "Open full screen editor for ${def.label}", { full = true }) }) else null,
            )
        }
    }
    if (full) {
        FixedTextDialog(def.label, text, isExpr, path, expr, onTextChange = { onChange(if (isExpr) ParamValue.Expr(it) else if (it.isEmpty()) null else ParamValue.Fixed(it)) }) { full = false }
    }
}

/** Full-screen editor for multiline text; the expression variant is the shared expression editor. */
@Composable
internal fun FixedTextDialog(title: String, text: String, isExpr: Boolean, path: ParamPath, env: ExprFieldEnv?, onTextChange: (String) -> Unit, onDismiss: () -> Unit) {
    if (isExpr) {
        ExpressionEditorDialog(path, title, text, onTextChange, onDismiss, env)
        return
    }
    val colors = KaivoTheme.colors
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().background(colors.canvasVoid).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                GhostButton("Done", onClick = onDismiss)
            }
            ParamInput(value = text, onValueChange = onTextChange, label = title, singleLine = false, minLines = 10, maxLines = 24)
        }
    }
}

@Composable
fun NumberParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    expr: ExprFieldEnv? = null, path: ParamPath = ParamPath.of(def.key),
) {
    val k = def.kind as ParamKind.Number
    val isExpr = ParamModes.isExpression(def, value)
    val stored: Double? = fixedOf(def, value)?.let { Values.asDouble(it) }
    var text by remember { mutableStateOf(NumberInput.format(fixedOf(def, value))) }
    var typeError by remember { mutableStateOf<String?>(null) }
    // Outside changes (stepper, undo, drop) replace the text; typing a partial number such as "1." does not.
    LaunchedEffect(stored) {
        val shown = (NumberInput.parse(text, k.integer) as? NumberInput.Parsed.Ok)?.value
        if (shown != stored) { text = NumberInput.format(fixedOf(def, value)); typeError = null }
    }
    val latestStored by rememberUpdatedState(stored)
    fun commit(d: Double) { onChange(ParamValue.Fixed(NumberInput.toStored(d, k.integer))) }
    val own = typeError?.let { listOf(ParamIssue(path, com.kaivoflow.core.model.ParamSeverity.Error, "type.number", it)) }.orEmpty()
    ParamFieldFrame(def, value, onChange, own + issues, modifier, path, togglePearl(def, value, onChange) { expr?.literalOf(path) }, dependsOnExpr, enabled) {
        if (isExpr) ExprBody(def, value, onChange, issues, path, expr, enabled)
        else Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RepeatStepButton("remove", "Decrease ${def.label}", enabled) {
                    commit(NumberInput.step(latestStored, -1, k.step, k.min, k.max, k.integer))
                }
                Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    ParamInput(
                        value = text,
                        onValueChange = { t ->
                            text = t
                            when (val p = NumberInput.parse(t, k.integer)) {
                                NumberInput.Parsed.Empty -> { typeError = null; onChange(null) }
                                is NumberInput.Parsed.Ok -> { typeError = null; commit(p.value) }
                                is NumberInput.Parsed.Invalid -> typeError = p.reason
                            }
                        },
                        label = def.label, enabled = enabled, placeholder = def.placeholder, mono = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if (k.integer && (k.min ?: -1.0) >= 0.0) KeyboardType.Number else KeyboardType.Decimal),
                        isError = typeError != null,
                        trailing = k.unit?.let { u -> ({ Text(u, style = MaterialTheme.typography.labelLarge, color = KaivoTheme.colors.inkSecondary) }) },
                    )
                }
                RepeatStepButton("add", "Increase ${def.label}", enabled) {
                    commit(NumberInput.step(latestStored, 1, k.step, k.min, k.max, k.integer))
                }
            }
            if (NumberInput.sliderEligible(k.min, k.max, k.step, k.integer)) {
                val lo = k.min!!.toFloat(); val hi = k.max!!.toFloat()
                val s = (k.step ?: 1.0).let { if (it <= 0) 1.0 else it }
                Slider(
                    value = (stored ?: k.min!!).toFloat().coerceIn(lo, hi),
                    onValueChange = { v ->
                        val snapped = k.min!! + Math.rint((v - lo) / s) * s
                        commit(snapped.coerceIn(k.min!!, k.max!!))
                    },
                    valueRange = lo..hi, enabled = enabled,
                    colors = SliderDefaults.colors(thumbColor = KaivoTheme.colors.royalGold, activeTrackColor = KaivoTheme.colors.royalGold, inactiveTrackColor = KaivoTheme.colors.hairline),
                    modifier = Modifier.semantics { contentDescription = "${def.label} slider" },
                )
            }
        }
    }
}

/** 48dp stepper with long-press auto repeat. TalkBack gets a plain click action, since a held press is not accessible. */
@Composable
private fun RepeatStepButton(iconKey: String, description: String, enabled: Boolean, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(KaivoTheme.colors.surfaceElevated.copy(alpha = 0.6f))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    step()
                    coroutineScope {
                        val job = launch { delay(400); var n = 0; while (true) { step(); delay(NumberInput.repeatDelayMs(n)); n++ } }
                        tryAwaitRelease()
                        job.cancel()
                    }
                })
            }
            .semantics { contentDescription = description; role = Role.Button; onClick(description) { if (enabled) step(); enabled } },
        contentAlignment = Alignment.Center,
    ) { Icon(KaivoIcons.icon(iconKey), null, tint = KaivoTheme.colors.inkSecondary, modifier = Modifier.size(20.dp)) }
}

@Composable
fun BooleanParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    expr: ExprFieldEnv? = null, path: ParamPath = ParamPath.of(def.key),
) {
    val colors = KaivoTheme.colors
    val isExpr = ParamModes.isExpression(def, value)
    val on = (fixedOf(def, value) as? Boolean) ?: (fixedOf(def, value)?.toString()?.lowercase() == "true")
    ParamFieldFrame(def, value, onChange, issues, modifier, path, togglePearl(def, value, onChange) { expr?.literalOf(path) }, dependsOnExpr, enabled, showLabel = isExpr) {
        if (isExpr) ExprBody(def, value, onChange, issues, path, expr, enabled)
        else Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .toggleable(value = on, enabled = enabled, role = Role.Switch) { onChange(ParamValue.Fixed(it)) }
                .semantics { stateDescription = if (on) "On" else "Off" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(def.label, style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = on, onCheckedChange = null, enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.royalGold, checkedThumbColor = colors.canvasVoid, uncheckedTrackColor = colors.surfaceElevated, uncheckedThumbColor = colors.inkSecondary),
            )
        }
    }
}

/** Options of a Choice: static, or loaded through the engine with the states loading, empty, failed. */
sealed interface OptionsState {
    data class Ready(val options: List<ParamOption>) : OptionsState
    data object Loading : OptionsState
    data object Empty : OptionsState
    data class Failed(val message: String) : OptionsState
}

private fun optionsOf(def: ParamDef, src: OptionSource, dynamic: OptionsState?): OptionsState = when (src) {
    is OptionSource.Static -> OptionsState.Ready(src.options)
    is OptionSource.Dynamic -> dynamic ?: OptionsState.Loading
}

@Composable
fun ChoiceParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    expr: ExprFieldEnv? = null, path: ParamPath = ParamPath.of(def.key),
    dynamicOptions: OptionsState? = null, onRetry: () -> Unit = {},
) {
    val k = def.kind as ParamKind.Choice
    val colors = KaivoTheme.colors
    val isExpr = ParamModes.isExpression(def, value)
    val state = optionsOf(def, k.options, dynamicOptions)
    val options = (state as? OptionsState.Ready)?.options.orEmpty()
    val current = fixedOf(def, value)?.toString()
    val stale = state is OptionsState.Ready && ChoiceModel.isStale(options, current)
    val staleIssue = if (stale) listOf(ParamIssue(path, com.kaivoflow.core.model.ParamSeverity.Error, "choice.unknown", "\"$current\" is no longer available. Pick another option.")) else emptyList()
    var open by remember { mutableStateOf(false) }
    val layout = ChoiceModel.layout(options.size, k.searchable)
    ParamFieldFrame(def, value, onChange, staleIssue + issues, modifier, path, togglePearl(def, value, onChange) { expr?.literalOf(path) }, dependsOnExpr, enabled) {
        when {
            isExpr -> ExprBody(def, value, onChange, issues, path, expr, enabled)
            state is OptionsState.Loading -> Text("Loading options", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted, modifier = Modifier.defaultMinSize(minHeight = 48.dp).padding(12.dp))
            state is OptionsState.Empty -> Text("No options available", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted, modifier = Modifier.defaultMinSize(minHeight = 48.dp).padding(12.dp))
            state is OptionsState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = colors.statusError, modifier = Modifier.weight(1f).padding(12.dp))
                GhostButton("Retry", onClick = onRetry)
            }
            layout == ChoiceLayout.Segmented && !stale -> SegmentedSwitch(
                options = options.map { SegmentOption(it.label) },
                selectedIndex = options.indexOfFirst { it.value == current },
                onSelect = { onChange(ParamValue.Fixed(options[it].value)) },
            )
            else -> {
                FieldSurface(Modifier.clickable(enabled = enabled, role = Role.Button) { open = true }.semantics { contentDescription = "${def.label}, ${ChoiceModel.labelOf(options, current) ?: current ?: "not set"}, opens a list" }, isError = stale) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ChoiceModel.labelOf(options, current) ?: current ?: (def.placeholder ?: "Select"),
                            style = MaterialTheme.typography.bodyMedium, color = if (current == null) colors.inkMuted else colors.inkPrimary,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Icon(KaivoIcons.icon("expandMore"), null, tint = colors.inkSecondary)
                    }
                }
            }
        }
    }
    if (open) {
        ChoiceSheet(def.label, options, selected = setOf(current.orEmpty()), multi = false, searchable = layout == ChoiceLayout.SearchSheet, allowUnset = !def.required,
            onPick = { v -> onChange(if (v == null) null else ParamValue.Fixed(v)) }, onDismiss = { open = false })
    }
}

/** Sheet with optional search; single or multi select. Selecting a single option closes the sheet. */
@Composable
internal fun ChoiceSheet(
    title: String, options: List<ParamOption>, selected: Set<String>, multi: Boolean, searchable: Boolean, allowUnset: Boolean,
    onPick: (String?) -> Unit, onDismiss: () -> Unit, extra: (@Composable () -> Unit)? = null,
) {
    var q by remember { mutableStateOf("") }
    val shown = ChoiceModel.filter(options, q)
    KaivoBottomSheet(title = title, onDismiss = onDismiss) {
        if (searchable) { SearchField(q, { q = it }, placeholder = "Search options"); Spacer(Modifier.size(8.dp)) }
        extra?.invoke()
        if (allowUnset && !multi) SheetOptionRow("Not set", selected = selected.all { it.isEmpty() }, onClick = { onPick(null); onDismiss() })
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            shown.forEach { o ->
                SheetOptionRow(o.label, selected = o.value in selected, onClick = { onPick(o.value); if (!multi) onDismiss() })
            }
            if (shown.isEmpty()) Text("Nothing matches", style = MaterialTheme.typography.bodyMedium, color = KaivoTheme.colors.inkMuted, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
fun MultiChoiceParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    expr: ExprFieldEnv? = null, path: ParamPath = ParamPath.of(def.key),
    dynamicOptions: OptionsState? = null,
) {
    val k = def.kind as ParamKind.MultiChoice
    val isExpr = ParamModes.isExpression(def, value)
    val options = (optionsOf(def, k.options, dynamicOptions) as? OptionsState.Ready)?.options.orEmpty()
    val selected = ((fixedOf(def, value) as? List<*>)?.map { it.toString() }).orEmpty()
    var open by remember { mutableStateOf(false) }
    ParamFieldFrame(def, value, onChange, issues, modifier, path, togglePearl(def, value, onChange) { expr?.literalOf(path) }, dependsOnExpr, enabled) {
        if (isExpr) ExprBody(def, value, onChange, issues, path, expr, enabled)
        else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            selected.forEach { v ->
                KaivoChip(ChoiceModel.labelOf(options, v) ?: "$v (gone)", onClick = { onChange(ChoiceModel.stored(ChoiceModel.toggle(selected, v, k.max))?.let { ParamValue.Fixed(it) }) }, selected = true, trailingIconKey = "close", enabled = enabled, description = "Remove ${ChoiceModel.labelOf(options, v) ?: v}")
            }
            KaivoChip(if (selected.isEmpty()) "Choose" else "Add", onClick = { open = true }, iconKey = "add", enabled = enabled, description = "Choose ${def.label}")
        }
    }
    if (open) {
        ChoiceSheet(def.label, options, selected.toSet(), multi = true, searchable = options.size > 8, allowUnset = false,
            onPick = { v -> if (v != null) onChange(ChoiceModel.stored(ChoiceModel.toggle(selected, v, k.max))?.let { ParamValue.Fixed(it) }) },
            onDismiss = { open = false },
            extra = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KaivoChip("Select all", onClick = { onChange(ChoiceModel.stored(ChoiceModel.selectAll(options, k.max))?.let { ParamValue.Fixed(it) }) })
                    KaivoChip("Clear", onClick = { onChange(null) })
                }
            })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    expr: ExprFieldEnv? = null, path: ParamPath = ParamPath.of(def.key),
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val k = def.kind as ParamKind.DateTime
    val colors = KaivoTheme.colors
    val isExpr = ParamModes.isExpression(def, value)
    val text = ParamModes.fixedText(value ?: def.default)
    val parts = DateTimeModel.parse(text, k.mode, zoneId)
    var step by remember { mutableStateOf(0) } // 0 closed, 1 date, 2 time, 3 relative
    var pickedDay by remember { mutableStateOf(DateTimeModel.today(zoneId)) }
    ParamFieldFrame(def, value, onChange, issues, modifier, path, togglePearl(def, value, onChange) { expr?.literalOf(path) }, dependsOnExpr, enabled) {
        if (isExpr) ExprBody(def, value, onChange, issues, path, expr, enabled)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            FieldSurface(Modifier.weight(1f).clickable(enabled = enabled, role = Role.Button) { step = if (k.mode == DateTimeMode.Time) 2 else 1 }.semantics { contentDescription = "${def.label}, ${text.ifEmpty { "not set" }}, opens a picker" }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(KaivoIcons.icon(if (k.mode == DateTimeMode.Time) "clock" else "calendar"), null, tint = colors.royalGold, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text.ifEmpty { def.placeholder ?: "Select" }, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = KaivoTheme.type.monoFamily), color = if (text.isEmpty()) colors.inkMuted else colors.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(6.dp))
            if (k.mode != DateTimeMode.Date) StatusBadge(zoneId.id, BadgeKind.Neutral)
            KaivoChip("Relative", onClick = { step = 3 }, enabled = enabled, description = "Set relative to now")
        }
    }
    if (step == 1) {
        val st = rememberDatePickerState(initialSelectedDateMillis = DateTimeModel.pickerMillis(parts?.epochDay ?: pickedDay))
        DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = {
                TextButton(onClick = {
                    val day = st.selectedDateMillis?.let { DateTimeModel.epochDayOf(it) } ?: pickedDay
                    pickedDay = day
                    if (k.mode == DateTimeMode.Date) { onChange(ParamValue.Fixed(DateTimeModel.compose(day, 0, 0, k.mode, zoneId))); step = 0 } else step = 2
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel") } },
        ) { DatePicker(state = st) }
    }
    if (step == 2) {
        val ts = rememberTimePickerState(parts?.hour ?: 9, parts?.minute ?: 0, is24Hour = true)
        androidx.compose.ui.window.Dialog(onDismissRequest = { step = 0 }) {
            Column(Modifier.clip(KaivoShapes.stepCard).background(colors.surfaceAtelier).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = ts)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { step = 0 }) { Text("Cancel") }
                    TextButton(onClick = {
                        val day = parts?.epochDay ?: pickedDay
                        onChange(ParamValue.Fixed(DateTimeModel.compose(day, ts.hour, ts.minute, k.mode, zoneId))); step = 0
                    }) { Text("OK") }
                }
            }
        }
    }
    if (step == 3) {
        var amount by remember { mutableStateOf(1) }
        var unit by remember { mutableStateOf("days") }
        var sign by remember { mutableStateOf(1) }
        KaivoBottomSheet(title = "Relative to now", onDismiss = { step = 0 }) {
            SegmentedSwitch(listOf(SegmentOption("After now"), SegmentOption("Before now")), if (sign > 0) 0 else 1, { sign = if (it == 0) 1 else -1 })
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RepeatStepButton("remove", "Decrease amount", true) { if (amount > 0) amount-- }
                Text("$amount", style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary, modifier = Modifier.padding(horizontal = 16.dp))
                RepeatStepButton("add", "Increase amount", true) { amount++ }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DateTimeModel.relativeUnits.forEach { u -> KaivoChip(u, onClick = { unit = u }, selected = unit == u) }
            }
            Spacer(Modifier.size(12.dp))
            com.kaivoflow.core.design.GoldButton("Use", onClick = { onChange(ParamValue.Expr(DateTimeModel.relativeExpression(sign * amount, unit))); step = 0 })
        }
    }
}

@Composable
fun ColorParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    expr: ExprFieldEnv? = null, path: ParamPath = ParamPath.of(def.key),
    recents: List<String> = emptyList(),
) {
    val colors = KaivoTheme.colors
    val isExpr = ParamModes.isExpression(def, value)
    val text = ParamModes.fixedText(value ?: def.default)
    val swatches = listOf(colors.royalGold, colors.royalGoldDeep, colors.velvetRose, colors.imperialAmethyst, colors.statusSuccess, colors.statusWarning, colors.statusError, colors.inkPrimary)
        .map { ColorHex.fromArgb(it.toArgb()) } + recents
    val bad = text.isNotEmpty() && ColorHex.normalize(text) == null
    val own = if (bad) listOf(ParamIssue(path, com.kaivoflow.core.model.ParamSeverity.Error, "color.hex", "Use a hex color such as #D4AF37.")) else emptyList()
    ParamFieldFrame(def, value, onChange, own + issues, modifier, path, togglePearl(def, value, onChange) { expr?.literalOf(path) }, dependsOnExpr, enabled) {
        if (isExpr) ExprBody(def, value, onChange, issues, path, expr, enabled)
        else Column {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                swatches.forEach { hex ->
                    val argb = ColorHex.toArgb(hex) ?: return@forEach
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button) { onChange(ParamValue.Fixed(hex)) }
                        .semantics { contentDescription = "Color $hex" + if (ColorHex.normalize(text) == hex) ", selected" else "" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(argb)).border(if (ColorHex.normalize(text) == hex) 2.dp else 1.dp, if (ColorHex.normalize(text) == hex) colors.royalGold else colors.hairline, CircleShape))
                    }
                }
            }
            ParamInput(
                value = text, onValueChange = { onChange(if (it.isEmpty()) null else ParamValue.Fixed(it)) }, label = def.label, enabled = enabled, mono = true,
                placeholder = def.placeholder ?: "#RRGGBB", isError = bad,
                leading = { ColorHex.toArgb(text)?.let { Box(Modifier.size(20.dp).clip(CircleShape).background(Color(it))) } },
            )
        }
    }
}

@Composable
fun NoticeParamField(
    def: ParamDef, modifier: Modifier = Modifier, onAction: (String) -> Unit = {},
) {
    val k = def.kind as ParamKind.Notice
    val colors = KaivoTheme.colors
    val tint = when (k.tone) { NoticeTone.Info -> colors.royalGold; NoticeTone.Warning -> colors.statusWarning; NoticeTone.Danger -> colors.statusError }
    val tone = when (k.tone) { NoticeTone.Info -> "Note"; NoticeTone.Warning -> "Warning"; NoticeTone.Danger -> "Important" }
    val body = def.hint ?: def.label
    val annotated: AnnotatedString = buildAnnotatedString {
        NoticeMarkup.pieces(body).forEach { p -> if (p.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p.text) } else append(p.text) }
    }
    Row(
        modifier.fillMaxWidth().padding(bottom = 8.dp).clip(KaivoShapes.chip).background(tint.copy(alpha = 0.10f)).border(1.dp, tint.copy(alpha = 0.4f), KaivoShapes.chip).padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$tone: ${body.replace("**", "")}" },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(KaivoIcons.icon(if (k.tone == NoticeTone.Info) "info" else "error"), null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (def.hint != null && def.label.isNotBlank()) Text(def.label, style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
            Text(annotated, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary)
            k.action?.let { a -> GhostButton(a.label, onClick = { onAction(a.id) }) }
        }
    }
}

/** Masked field. The value never reaches previews, semantics text or copy menus; reveal hides itself after 10 s and when the app stops. */
@Composable
fun SecretParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    path: ParamPath = ParamPath.of(def.key),
) {
    var revealed by remember { mutableStateOf(false) }
    val text = ParamModes.fixedText(value ?: def.default)
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(revealed) { if (revealed) { delay(10_000); revealed = false } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) revealed = false }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val withHint = def.copy(hint = def.hint ?: "Stored in plain text on this device. Prefer a credential when you can.")
    ParamFieldFrame(withHint, value, onChange, issues, modifier, path, null, dependsOnExpr, enabled) {
        ParamInput(
            value = text, onValueChange = { onChange(if (it.isEmpty()) null else ParamValue.Fixed(it)) },
            label = def.label + if (text.isEmpty()) ", empty" else ", has a value, hidden", enabled = enabled, mono = true,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.semantics { password() },
            trailing = {
                Row {
                    KaivoIconButton("copy", "Paste into ${def.label}", { clipboard.getText()?.text?.let { onChange(ParamValue.Fixed(it)) } }, enabled = enabled)
                    KaivoIconButton("eye", if (revealed) "Hide ${def.label}" else "Show ${def.label}", { revealed = !revealed }, enabled = enabled)
                }
            },
        )
    }
}
