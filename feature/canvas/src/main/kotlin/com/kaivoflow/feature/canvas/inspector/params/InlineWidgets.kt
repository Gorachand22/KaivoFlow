package com.kaivoflow.feature.canvas.inspector.params

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamJson
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.ValueType
import com.kaivoflow.feature.canvas.inspector.mapper.DragPayload
import com.kaivoflow.feature.canvas.inspector.mapper.InsertOutcome
import com.kaivoflow.feature.canvas.inspector.mapper.MapperInsert
import com.kaivoflow.feature.canvas.inspector.mapper.mapDropTarget

/** Text typed for a typed value: numbers and JSON are stored as their type when they parse, else as text so nothing is lost. */
internal fun coerceFixed(type: ValueType, text: String): Any? {
    if (text.isEmpty()) return null
    return when (type) {
        ValueType.Number -> text.trim().toDoubleOrNull()?.let { NumberInput.toStored(it, false) } ?: text
        ValueType.List -> (ParamJson.parse(text) as? ParamJson.Parsed.Ok)?.value?.takeIf { it is List<*> } ?: text
        ValueType.Object -> (ParamJson.parse(text) as? ParamJson.Parsed.Ok)?.value?.takeIf { it is Map<*, *> } ?: text
        else -> text
    }
}

internal fun fixedDisplay(v: Any?): String = when (v) {
    null -> ""
    is String -> v
    is Map<*, *>, is List<*> -> Values.stringify(v)
    else -> Values.asString(v)
}

/**
 * A compact expression-capable value used inside rows (key-value, assignments, conditions, file and
 * resource fields): fixed input or expression field, with the pearl. It is its own drop target;
 * [onDrop] overrides what a drop does (the conditions builder sets the operator group from the type).
 */
@Composable
fun InlineValueField(
    path: ParamPath,
    label: String,
    value: ParamValue?,
    onChange: (ParamValue?) -> Unit,
    env: ExprFieldEnv?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    type: ValueType = ValueType.Text,
    allowExpression: Boolean = true,
    onDrop: ((DragPayload, Int, Int) -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    val isExpr = value is ParamValue.Expr
    val textDef = ParamDef(path.toString(), label, ParamKind.Text())
    val latest by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val drop: (DragPayload, Int, Int) -> Unit = onDrop ?: { p, s, e ->
        (MapperInsert.apply(textDef, latest, p.source, p.type, s, e) as? InsertOutcome.Set)?.let { change(it.value) }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f)) {
            when {
                isExpr -> ExpressionField(
                    path = path, text = (value as ParamValue.Expr).text, onTextChange = { onChange(ParamValue.Expr(it)) },
                    label = label, env = env, enabled = enabled, placeholder = placeholder, registerAsTarget = true, onDropped = drop,
                )
                type == ValueType.Boolean -> {
                    val on = (value as? ParamValue.Fixed)?.value.let { it == true || it?.toString()?.lowercase() == "true" }
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).mapDropTarget(path, true, label, drop)
                            .toggleable(value = on, enabled = enabled, role = Role.Switch) { onChange(ParamValue.Fixed(it)) }
                            .semantics { stateDescription = if (on) "On" else "Off" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                        Switch(checked = on, onCheckedChange = null, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = colors.royalGold, checkedThumbColor = colors.canvasVoid))
                    }
                }
                else -> ParamInput(
                    value = fixedDisplay((value as? ParamValue.Fixed)?.value),
                    onValueChange = { t -> onChange(coerceFixed(type, t)?.let { ParamValue.Fixed(it) }) },
                    label = label, enabled = enabled, placeholder = placeholder,
                    singleLine = type != ValueType.Object && type != ValueType.List,
                    keyboardOptions = KeyboardOptions(keyboardType = if (type == ValueType.Number) KeyboardType.Decimal else KeyboardType.Text),
                    modifier = Modifier.mapDropTarget(path, true, label, drop),
                )
            }
        }
        if (allowExpression) {
            Spacer(Modifier.width(2.dp))
            FxPearl(
                isExpression = isExpr, label = label, enabled = enabled,
                onClick = {
                    if (isExpr) onChange(ParamModes.toFixed(env?.literalOf(path)))
                    else onChange(ParamModes.toExpression(textDef, value))
                },
            )
        }
    }
}
