package com.kaivoflow.feature.canvas.inspector.params

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamIssue
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.ParamSeverity
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.feature.canvas.inspector.mapper.DragPayload
import com.kaivoflow.feature.canvas.inspector.mapper.DropHint
import com.kaivoflow.feature.canvas.inspector.mapper.InsertOutcome
import com.kaivoflow.feature.canvas.inspector.mapper.LocalDragMap
import com.kaivoflow.feature.canvas.inspector.mapper.MapperInsert
import com.kaivoflow.feature.canvas.inspector.mapper.mapDropTarget

// Shared chrome of every parameter widget: label row, fixed/expression pearl, hint, issues, drop target.
// All widgets are stateless: they take the ParamDef, the stored ParamValue, the issues at their path and callbacks.

/** Errors first, then warnings. */
internal fun List<ParamIssue>.leading(): ParamIssue? =
    firstOrNull { it.severity == ParamSeverity.Error } ?: firstOrNull()

/** The trailing two-state pearl: `Fixed` (muted) and `fx` (gold). 48dp target; the state is spoken, not only coloured. */
@Composable
fun FxPearl(
    isExpression: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = KaivoTheme.colors
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Switch, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics {
                contentDescription = "$label value mode"
                stateDescription = if (isExpression) "Expression" else "Fixed"
            },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (isExpression) colors.royalGold else colors.inkMuted
        Box(
            Modifier
                .clip(KaivoShapes.pill)
                .background(if (isExpression) colors.royalGold.copy(alpha = 0.16f) else colors.surfaceElevated.copy(alpha = 0.7f))
                .border(1.dp, if (isExpression) colors.royalGold.copy(alpha = 0.6f) else colors.hairline, KaivoShapes.pill)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                if (isExpression) "fx" else "Fixed",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = if (isExpression) KaivoTheme.type.monoFamily else KaivoTheme.type.bodyFamily),
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** Surface of an input: gold hairline for focus and expression mode, rose for errors. */
@Composable
fun FieldSurface(
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    isExpression: Boolean = false,
    focused: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = KaivoTheme.colors
    val border = when {
        isError -> colors.statusError
        focused || isExpression -> colors.royalGold.copy(alpha = if (focused) 0.9f else 0.55f)
        else -> colors.hairline
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(KaivoShapes.chip)
            .background(colors.surfaceElevated.copy(alpha = 0.6f))
            .border(if (focused) 2.dp else 1.dp, border, KaivoShapes.chip)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

/** A plain-string input on the shared surface. Number, Text, Secret, Color, Code and Json build on it. */
@Composable
fun ParamInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 8,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    mono: Boolean = false,
    isError: Boolean = false,
    isExpression: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    var focused by remember { mutableStateOf(false) }
    val style = MaterialTheme.typography.bodyMedium.copy(
        color = colors.inkPrimary,
        fontFamily = if (mono) KaivoTheme.type.monoFamily else KaivoTheme.type.bodyFamily,
    )
    FieldSurface(modifier, isError = isError, isExpression = isExpression, focused = focused, enabled = enabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(8.dp)) }
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    textStyle = style,
                    cursorBrush = SolidColor(colors.royalGold),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    visualTransformation = visualTransformation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
                        .semantics { contentDescription = label },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty() && placeholder != null) {
                                Text(placeholder, style = style.copy(color = colors.inkMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            inner()
                        }
                    },
                )
            }
            if (trailing != null) { Spacer(Modifier.width(4.dp)); trailing() }
        }
    }
}

/** One line under a field: the leading error or warning (with an icon, never colour alone), else the hint. */
@Composable
fun IssueLine(issues: List<ParamIssue>, hint: String?, modifier: Modifier = Modifier) {
    val colors = KaivoTheme.colors
    val issue = issues.leading()
    if (issue != null) {
        val isError = issue.severity == ParamSeverity.Error
        val tint = if (isError) colors.statusError else colors.statusWarning
        Row(
            modifier = modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top,
        ) {
            Icon(KaivoIcons.icon(if (isError) "error" else "info"), contentDescription = if (isError) "Error" else "Warning", tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(issue.message, style = MaterialTheme.typography.bodySmall, color = tint)
        }
    } else if (!hint.isNullOrBlank()) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, modifier = modifier.padding(top = 4.dp))
    }
}

/** Builds the drop handler of a field: the same `MapperInsert` the tap-to-insert path uses, so both give the same text. */
@Composable
fun rememberFieldDrop(
    def: ParamDef,
    path: ParamPath,
    value: ParamValue?,
    onChange: (ParamValue?) -> Unit,
): (DragPayload, Int, Int) -> Unit {
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onChange)
    val controller = LocalDragMap.current
    return remember(def, path, controller) {
        { payload, selStart, selEnd ->
            when (val o = MapperInsert.apply(def, latestValue, payload.source, payload.type, selStart, selEnd)) {
                is InsertOutcome.Set -> latestChange(o.value)
                InsertOutcome.Rejected -> controller?.reject(path)
            }
        }
    }
}

/**
 * Label row (label, required mark, `fx` marker for expression-only fields, pearl), the widget, the
 * drop highlight, then hint and issues. The whole frame is a drop target; fields that cannot take
 * data (code, secret, credential, notice) register as rejecting so they dim during a drag.
 */
@Composable
fun ParamFieldFrame(
    def: ParamDef,
    value: ParamValue?,
    onChange: (ParamValue?) -> Unit,
    issues: List<ParamIssue>,
    modifier: Modifier = Modifier,
    path: ParamPath = ParamPath.of(def.key),
    onTogglePearl: (() -> Unit)? = null,
    dependsOnExpr: Boolean = false,
    enabled: Boolean = true,
    counter: String? = null,
    showLabel: Boolean = true,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = KaivoTheme.colors
    val isExpression = ParamModes.isExpression(def, value)
    val onDrop = rememberFieldDrop(def, path, value, onChange)
    val message = issues.leading()?.takeIf { it.severity == ParamSeverity.Error }?.message
    Column(
        modifier = modifier
            .fillMaxWidth()
            .mapDropTarget(path = path, accepts = MapperInsert.acceptsData(def), label = def.label, onDrop = onDrop)
            .semantics { if (message != null) error(message) },
    ) {
        if (showLabel) {
            Row(
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = if (onTogglePearl != null) 48.dp else 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    def.label.ifBlank { def.key },
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).semantics {
                        contentDescription = def.label.ifBlank { def.key } + if (def.required) ", required" else ""
                    },
                )
                if (def.required) {
                    Spacer(Modifier.width(4.dp))
                    Text("*", style = MaterialTheme.typography.titleSmall, color = colors.royalGold, modifier = Modifier.clearAndSetSemantics { })
                }
                if (dependsOnExpr) {
                    Spacer(Modifier.width(8.dp))
                    StatusBadge("depends on expression", BadgeKind.Neutral)
                }
                Spacer(Modifier.weight(1f))
                if (ParamModes.isExprOnly(def)) {
                    Text("fx", style = MaterialTheme.typography.labelMedium.copy(fontFamily = KaivoTheme.type.monoFamily), color = colors.royalGold, modifier = Modifier.padding(horizontal = 8.dp))
                }
                if (onTogglePearl != null) {
                    FxPearl(isExpression = isExpression, onClick = onTogglePearl, label = def.label.ifBlank { def.key }, enabled = enabled)
                }
            }
        }
        Box {
            content()
            DropHint(path)
        }
        below?.invoke(this)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) { IssueLine(issues, def.hint) }
            if (counter != null) {
                Spacer(Modifier.width(8.dp))
                Text(counter, style = MaterialTheme.typography.labelMedium.copy(fontFamily = KaivoTheme.type.monoFamily), color = colors.inkMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** What to do about a toggle tap, for any widget that shows the pearl. */
fun togglePearl(def: ParamDef, value: ParamValue?, onChange: (ParamValue?) -> Unit, evaluatedLiteral: () -> Any? = { null }): (() -> Unit)? {
    if (!ParamModes.showsPearl(def)) return null
    return {
        val current = value ?: def.default
        if (current is ParamValue.Expr) onChange(ParamModes.toFixed(evaluatedLiteral()))
        else onChange(ParamModes.toExpression(def, current))
    }
}
