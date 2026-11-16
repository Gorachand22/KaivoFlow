package com.kaivoflow.feature.canvas.inspector.params

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.CountPill
import com.kaivoflow.core.design.GoldButton
import com.kaivoflow.core.design.KaivoChip
import com.kaivoflow.core.design.KaivoIconButton
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.expressions.assist.Completion
import com.kaivoflow.core.expressions.assist.ExprDiagnostic
import com.kaivoflow.core.expressions.assist.ExpressionAssist
import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.expressions.assist.ReferenceBuilder
import com.kaivoflow.core.expressions.assist.SchemaScope
import com.kaivoflow.core.model.ParamIssue
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.feature.canvas.inspector.mapper.DragPayload
import com.kaivoflow.feature.canvas.inspector.mapper.DropHint
import com.kaivoflow.feature.canvas.inspector.mapper.LocalDragMap
import com.kaivoflow.feature.canvas.inspector.mapper.mapDropTarget
import com.kaivoflow.feature.canvas.inspector.state.PreviewState
import kotlinx.coroutines.delay

/**
 * What an expression field needs from its host: the assist for suggestions and diagnostics, the
 * shape of upstream data, the preview results by field path, and a few callbacks. A host that has
 * none of it can pass null and still gets chips, the accessory bar and undo.
 */
@Stable
class ExprFieldEnv(
    val assist: ExpressionAssist? = null,
    val scope: SchemaScope = SchemaScope(),
    val previewOf: (ParamPath) -> PreviewState? = { null },
    val onFocus: (ParamPath?) -> Unit = {},
    val onOpenMapper: () -> Unit = {},
    val itemIndex: Int = 0,
    val itemCount: Int = 0,
    val onSelectItem: (Int) -> Unit = {},
) {
    /** The evaluated preview as a literal, offered when switching back to a fixed value. Only simple types qualify. */
    fun literalOf(path: ParamPath): Any? {
        val v = previewOf(path) as? PreviewState.Value ?: return null
        val t = v.type.lowercase()
        return when {
            "number" in t -> v.rendered.toDoubleOrNull()?.let { NumberInput.toStored(it, false) }
            "bool" in t -> v.rendered.toBooleanStrictOrNull()
            "string" in t || "text" in t -> v.rendered
            else -> null
        }
    }
}

/** Colours of the three chip states, taken from tokens. */
private class ChipPalette(val resolved: androidx.compose.ui.graphics.Color, val absent: androidx.compose.ui.graphics.Color, val error: androidx.compose.ui.graphics.Color)

/** Chips are drawn over the real text with identity offsets, so the cursor, IME composition and paste behave normally. */
private class ExprHighlight(
    private val diagnostics: List<ExprDiagnostic>,
    private val palette: ChipPalette,
    private val mono: androidx.compose.ui.text.font.FontFamily,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val b = AnnotatedString.Builder(text)
        for (c in ExprFieldModel.chips(text.text, diagnostics)) {
            if (c.end > text.length || c.start >= c.end) continue
            val tint = when (c.state) { ChipState.Resolved -> palette.resolved; ChipState.Absent -> palette.absent; ChipState.Error -> palette.error }
            b.addStyle(SpanStyle(color = tint, background = tint.copy(alpha = 0.16f), fontFamily = mono, fontWeight = FontWeight.Medium), c.start, c.end)
        }
        for (r in ExprFieldModel.errorRanges(text.text, PreviewLine.None, diagnostics)) {
            if (r.last + 1 <= text.length) b.addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = palette.error), r.first, r.last + 1)
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/**
 * The expression text field (spec P2 3.6 to 3.8): the stored text is the visible text; `{{ }}`
 * parts are tinted as chips (gold resolves, amber absent, rose error); a chip row below lets a tap
 * select a reference to edit its path; the accessory bar offers `{{ }}`, quotes, brackets, undo and
 * redo, the Mapper tray, and a strip of suggestions from `ExpressionAssist.complete`; the preview
 * line shows value and type, a pending note, or the error with the exact span underlined.
 * Backspace right after a chip deletes it whole as one undo step.
 *
 * [registerAsTarget] makes the field its own drop target (for fields nested in rows, where the
 * outer frame is not the target); [onDropped] then does the insertion.
 */
@Composable
fun ExpressionField(
    path: ParamPath,
    text: String,
    onTextChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    env: ExprFieldEnv? = null,
    enabled: Boolean = true,
    multiline: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (multiline) 8 else 1,
    placeholder: String? = null,
    issues: List<ParamIssue> = emptyList(),
    showPreview: Boolean = true,
    showChipRow: Boolean = true,
    registerAsTarget: Boolean = false,
    onDropped: ((DragPayload, Int, Int) -> Unit)? = null,
    onOpenFullScreen: (() -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    val controller = LocalDragMap.current
    val focusRequester = remember { FocusRequester() }
    var tfv by remember(path) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    var focused by remember { mutableStateOf(false) }
    val history = remember(path) { EditHistory() }
    var historyVersion by remember(path) { mutableStateOf(0) }

    // An outside change (undo bar, drop, tap-to-insert, mode switch) replaces the text; typing never reaches here.
    LaunchedEffect(text) {
        if (tfv.text != text) tfv = TextFieldValue(text, TextRange(text.length))
    }
    LaunchedEffect(tfv.selection, controller) { controller?.reportSelection(path, tfv.selection.start, tfv.selection.end) }
    DisposableEffect(controller, path) {
        onDispose {
            controller?.clearSelection(path)
            if (controller?.focused == path) controller.reportFocus(null)
        }
    }

    val diagnostics: List<ExprDiagnostic> = remember(tfv.text, env?.assist, env?.scope) {
        runCatching { env?.assist?.diagnostics(tfv.text, env.scope) }.getOrNull().orEmpty()
    }
    val chips = remember(tfv.text, diagnostics) { ExprFieldModel.chips(tfv.text, diagnostics) }
    val line = remember(tfv.text, diagnostics, env?.previewOf) { ExprFieldModel.previewLine(env?.previewOf?.invoke(path), diagnostics) }
    val suggestions: List<Completion> = remember(tfv.text, tfv.selection, focused, env?.assist, env?.scope) {
        if (!focused || env?.assist == null || !tfv.selection.collapsed || !ExprFieldModel.suggestionsApply(tfv.text, tfv.selection.start)) emptyList()
        else runCatching { env.assist.complete(tfv.text, tfv.selection.start, env.scope) }.getOrNull().orEmpty().take(12)
    }
    val palette = ChipPalette(colors.royalGold, colors.statusWarning, colors.statusError)
    val mono = KaivoTheme.type.monoFamily
    val transformation = remember(diagnostics, palette.resolved, palette.absent, palette.error, mono) { ExprHighlight(diagnostics, palette, mono) }
    val hasError = line is PreviewLine.Error || issues.any { it.severity == com.kaivoflow.core.model.ParamSeverity.Error }

    fun apply(next: TextFieldValue, atomic: Boolean) {
        val old = tfv
        if (next.text != old.text) {
            history.record(EditHistory.Snapshot(old.text, old.selection.start), System.currentTimeMillis(), atomic)
            historyVersion++
        }
        tfv = next
        if (next.text != old.text) onTextChange(next.text)
    }

    fun edit(e: com.kaivoflow.core.expressions.assist.TextEdit) = apply(TextFieldValue(e.text, TextRange(e.caret)), atomic = true)

    val style = MaterialTheme.typography.bodyMedium.copy(color = colors.inkPrimary, fontFamily = mono)
    val ownDrop = onDropped
    val frameModifier = if (registerAsTarget && ownDrop != null) {
        Modifier.mapDropTarget(path = path, accepts = true, label = label, onDrop = ownDrop)
    } else Modifier

    Column(modifier.fillMaxWidth().then(frameModifier)) {
        FieldSurface(isError = hasError, isExpression = true, focused = focused, enabled = enabled, minHeight = 48.dp) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        value = tfv,
                        onValueChange = { next ->
                            val old = tfv
                            val backspace = next.text.length == old.text.length - 1 && old.selection.collapsed && next.selection.collapsed &&
                                next.selection.start == old.selection.start - 1
                            val chipDelete = if (backspace) ExprFieldModel.deleteChipBefore(old.text, old.selection.start) else null
                            if (chipDelete != null) edit(chipDelete) else apply(next, atomic = false)
                        },
                        enabled = enabled,
                        singleLine = !multiline,
                        minLines = minLines,
                        maxLines = maxLines,
                        textStyle = style,
                        cursorBrush = SolidColor(colors.royalGold),
                        visualTransformation = transformation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged {
                                focused = it.isFocused
                                if (it.isFocused) { controller?.reportFocus(path); env?.onFocus?.invoke(path) }
                            }
                            .semantics {
                                // Chips are announced with their full text; the text itself stays the editable node.
                                contentDescription = label + ", expression" + if (chips.isNotEmpty()) ", " + chips.joinToString("; ") { it.description } else ""
                            },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (tfv.text.isEmpty() && placeholder != null) {
                                    Text(placeholder, style = style.copy(color = colors.inkMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                inner()
                            }
                        },
                    )
                }
                if (onOpenFullScreen != null) KaivoIconButton("unfoldMore", "Open full screen editor for $label", onOpenFullScreen)
            }
            DropHint(path)
        }

        if (showChipRow && chips.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.forEach { chip ->
                    val tint = when (chip.state) { ChipState.Resolved -> colors.royalGold; ChipState.Absent -> colors.statusWarning; ChipState.Error -> colors.statusError }
                    Box(
                        Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(KaivoShapes.chip)
                            .clickable(enabled = enabled) {
                                val (a, b) = ExprFieldModel.selectionForChip(chip)
                                tfv = tfv.copy(selection = TextRange(a, b))
                                focusRequester.requestFocus()
                            }
                            .semantics { contentDescription = chip.description + ", " + when (chip.state) { ChipState.Resolved -> "found"; ChipState.Absent -> "not in upstream data"; ChipState.Error -> "error" } + ", tap to edit reference" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            chip.label,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = mono),
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(KaivoShapes.chip)
                                .background(tint.copy(alpha = 0.14f))
                                .border(1.dp, tint.copy(alpha = 0.4f), KaivoShapes.chip)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }

        if (showPreview) {
            ExprPreviewLine(
                line = line,
                itemIndex = env?.itemIndex ?: 0,
                itemCount = env?.itemCount ?: 0,
                onSelectItem = env?.onSelectItem,
                onClick = onOpenFullScreen,
            )
        }

        if (focused || suggestions.isNotEmpty()) {
            ExprAccessoryBar(
                suggestions = suggestions,
                canUndo = historyVersion >= 0 && history.canUndo,
                canRedo = history.canRedo,
                onSuggestion = { c -> edit(ExprFieldModel.applyCompletion(tfv.text, c)) },
                onBraces = { edit(ExprFieldModel.insertBraces(tfv.text, tfv.selection.start, tfv.selection.end)) },
                onPair = { o, c -> edit(ExprFieldModel.insertPair(tfv.text, tfv.selection.start, tfv.selection.end, o, c)) },
                onUndo = {
                    history.undo(EditHistory.Snapshot(tfv.text, tfv.selection.start))?.let { s ->
                        tfv = TextFieldValue(s.text, TextRange(s.caret.coerceIn(0, s.text.length))); historyVersion++; onTextChange(s.text)
                    }
                },
                onRedo = {
                    history.redo(EditHistory.Snapshot(tfv.text, tfv.selection.start))?.let { s ->
                        tfv = TextFieldValue(s.text, TextRange(s.caret.coerceIn(0, s.text.length))); historyVersion++; onTextChange(s.text)
                    }
                },
                onOpenMapper = env?.onOpenMapper,
            )
        }
    }
}

/** Under every expression field: value and type, `no data yet`, or the error. A polite live region, so a changed result is announced once typing stops. */
@Composable
fun ExprPreviewLine(
    line: PreviewLine,
    modifier: Modifier = Modifier,
    itemIndex: Int = 0,
    itemCount: Int = 0,
    onSelectItem: ((Int) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    if (line is PreviewLine.None) return
    val tint = when (line) { is PreviewLine.Error -> colors.statusError; is PreviewLine.Pending -> colors.inkMuted; else -> colors.inkSecondary }
    val spoken = when (line) {
        is PreviewLine.Value -> "Preview: ${line.rendered}, ${line.type}"
        is PreviewLine.Pending -> "Preview: ${line.message}"
        is PreviewLine.Error -> "Preview error: ${line.message}"
        PreviewLine.None -> ""
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = spoken; liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (line) {
            is PreviewLine.Value -> {
                Text("=", style = MaterialTheme.typography.labelLarge, color = colors.inkMuted)
                Spacer(Modifier.width(6.dp))
                Text(line.rendered, style = MaterialTheme.typography.labelLarge.copy(fontFamily = KaivoTheme.type.monoFamily), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                CountPill(line.type, kind = BadgeKind.Neutral)
            }
            is PreviewLine.Pending -> Text(line.message, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            is PreviewLine.Error -> Text(line.message, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            PreviewLine.None -> Unit
        }
        Spacer(Modifier.weight(1f))
        if (itemCount > 1 && onSelectItem != null) {
            KaivoChip(
                text = "item ${itemIndex + 1} of $itemCount",
                onClick = { onSelectItem((itemIndex + 1) % itemCount) },
                description = "Preview item ${itemIndex + 1} of $itemCount, tap for the next item",
            )
        }
    }
}

/** Above the keyboard: the suggestion strip first, then the format helpers. Each item is a 48dp target. */
@Composable
fun ExprAccessoryBar(
    suggestions: List<Completion>,
    canUndo: Boolean,
    canRedo: Boolean,
    onSuggestion: (Completion) -> Unit,
    onBraces: () -> Unit,
    onPair: (Char, Char) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOpenMapper: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = KaivoTheme.colors
    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (suggestions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { c ->
                    KaivoChip(
                        text = c.label,
                        onClick = { onSuggestion(c) },
                        description = "Insert ${c.label}" + (c.help?.let { ", $it" } ?: "") + (c.sample?.let { ", sample $it" } ?: ""),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(colors.surfaceAtelier, KaivoShapes.chip),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onOpenMapper != null) KaivoChip("Fields", onClick = onOpenMapper, iconKey = "table", description = "Open the Mapper tray")
            KaivoChip("{{ }}", onClick = onBraces, description = "Insert double braces")
            KaivoChip("\" \"", onClick = { onPair('"', '"') }, description = "Insert quotes")
            KaivoChip("[ ]", onClick = { onPair('[', ']') }, description = "Insert square brackets")
            KaivoChip("( )", onClick = { onPair('(', ')') }, description = "Insert parentheses")
            KaivoIconButton("undo", "Undo", onUndo, enabled = canUndo)
            KaivoIconButton("redo", "Redo", onRedo, enabled = canRedo)
        }
    }
}

/**
 * The full-screen editor: the field on top with chips, a result panel (type, pretty value, copy),
 * and an optional Mapper tray below. [tray] receives an `insert` function that puts a reference at
 * this field's caret, so a tap in the tray lands here and the tray stays open for more.
 */
@Composable
fun ExpressionEditorDialog(
    path: ParamPath,
    title: String,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    env: ExprFieldEnv?,
    modifier: Modifier = Modifier,
    tray: (@Composable (insert: (MapSource) -> Unit) -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    val controller = LocalDragMap.current
    val latestText by rememberUpdatedState(text)
    var trayOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val line = remember(text, env?.previewOf) { ExprFieldModel.previewLine(env?.previewOf?.invoke(path)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(colors.canvasVoid)
                .statusBarsPadding()
                .imePadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                KaivoIconButton("close", "Close editor", onDismiss)
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                GoldButton("Done", onClick = onDismiss, compact = true)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                ExpressionField(
                    path = path,
                    text = text,
                    onTextChange = onTextChange,
                    label = title,
                    env = env?.let { ExprFieldEnv(it.assist, it.scope, it.previewOf, it.onFocus, onOpenMapper = { trayOpen = !trayOpen }, it.itemIndex, it.itemCount, it.onSelectItem) }
                        ?: ExprFieldEnv(onOpenMapper = { trayOpen = !trayOpen }),
                    multiline = true,
                    minLines = 5,
                    maxLines = 14,
                    showPreview = false,
                )
                Spacer(Modifier.height(12.dp))
                Text("Result", style = MaterialTheme.typography.labelMedium, color = colors.inkMuted)
                val resultText = when (line) {
                    is PreviewLine.Value -> line.rendered
                    is PreviewLine.Pending -> line.message
                    is PreviewLine.Error -> line.message
                    PreviewLine.None -> "Nothing to show yet"
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(KaivoShapes.chip).background(colors.surfaceElevated.copy(alpha = 0.6f)).padding(12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        if (line is PreviewLine.Value) CountPill(line.type, kind = BadgeKind.Neutral)
                        Text(
                            resultText,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = KaivoTheme.type.monoFamily),
                            color = if (line is PreviewLine.Error) colors.statusError else colors.inkPrimary,
                        )
                    }
                    if (line is PreviewLine.Value) KaivoIconButton("copy", "Copy result", { clipboard.setText(AnnotatedString(line.rendered)) })
                }
            }
            if (tray != null && trayOpen) {
                Box(Modifier.fillMaxWidth().height(320.dp).background(colors.surfaceAtelier)) {
                    tray { source ->
                        val (s, e) = controller?.selectionOf(path) ?: (-1 to -1)
                        val cur = latestText
                        val a = if (s < 0) cur.length else s
                        val b = if (e < 0) cur.length else e
                        onTextChange(ReferenceBuilder.insertInto(cur, a, b, source).text)
                    }
                }
            }
        }
    }
}
