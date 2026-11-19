package com.kaivoflow.feature.canvas.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.KaivoChip
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.model.ExprInputModes
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.feature.canvas.inspector.params.ExprFieldEnv
import com.kaivoflow.feature.canvas.inspector.params.ExpressionField
import com.kaivoflow.feature.canvas.inspector.params.FxPearl
import com.kaivoflow.feature.canvas.inspector.params.ParamInput
import com.kaivoflow.feature.canvas.inspector.state.PreviewState

/**
 * One text input for configuration that may be fixed or an expression (spec P3). Fixed mode is a
 * plain field on the shared surface; expression mode is the inspector's [ExpressionField] (chips,
 * `{{ }}` helpers, suggestions, undo, live preview), so the behaviour is the same in a node and out of it.
 *
 * Use it only where `ExprSurfaces.allows(surfaceId)` is true. Pass `scope = null` for a field that
 * must stay fixed; then no switch is shown and an expression stored by mistake is shown as text.
 * Secret values, chat boxes, search boxes and names never use this component.
 *
 * [value] is what is stored (`ParamValue`); the caller encodes it with `ExprFieldCodec` or `ParamValueCodec`.
 */
@Composable
fun KaivoInputField(
    value: ParamValue?,
    onValueChange: (ParamValue) -> Unit,
    label: String,
    fieldId: String,
    scope: ExpressionScopeProvider?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    multiline: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (multiline) 8 else 1,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    mono: Boolean = false,
    showLabel: Boolean = true,
) {
    val colors = KaivoTheme.colors
    val isExpr = scope != null && ExprInputModes.isExpression(value)
    val text = ExprInputModes.text(value)
    val path = remember(fieldId) { ParamPath(listOf(fieldId)) }
    val preview: PreviewState? = remember(text, scope, isExpr) { if (isExpr) scope?.preview(text) else null }
    var trayOpen by remember(fieldId) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        if (showLabel) Text(label, style = MaterialTheme.typography.labelMedium, color = colors.inkSecondary, modifier = Modifier.padding(bottom = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                if (isExpr && scope != null) {
                    val env = remember(scope, text, trayOpen) {
                        ExprFieldEnv(
                            assist = scope.assist,
                            scope = scope.scope,
                            previewOf = { preview },
                            onOpenMapper = scope.onOpenMapper ?: { trayOpen = !trayOpen },
                        )
                    }
                    ExpressionField(
                        path = path,
                        text = text,
                        onTextChange = { onValueChange(ExprInputModes.withText(value, it)) },
                        label = label,
                        env = env,
                        enabled = enabled,
                        multiline = multiline,
                        minLines = minLines,
                        maxLines = maxLines,
                        placeholder = placeholder,
                    )
                    if (trayOpen && scope.mapperItems.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            scope.mapperItems.forEach { item ->
                                KaivoChip(
                                    text = item.label,
                                    onClick = { onValueChange(ExprInputModes.withText(value, ExpressionInsert.append(text, item.snippet))) },
                                    description = "Insert ${item.label}",
                                )
                            }
                        }
                    }
                } else {
                    ParamInput(
                        value = text,
                        onValueChange = { onValueChange(ExprInputModes.withText(value, it)) },
                        label = label,
                        enabled = enabled,
                        singleLine = !multiline,
                        minLines = minLines,
                        maxLines = maxLines,
                        placeholder = placeholder,
                        keyboardOptions = keyboardOptions,
                        mono = mono,
                    )
                }
            }
            if (scope != null) {
                FxPearl(
                    isExpression = isExpr,
                    label = label,
                    enabled = enabled,
                    onClick = {
                        if (isExpr) onValueChange(ExprInputModes.toFixed(value, literalOf(preview)))
                        else onValueChange(ExprInputModes.toExpression(value))
                    },
                )
            }
        }
    }
}

/** The evaluated preview as the new fixed text, only when it is plain text. */
private fun literalOf(p: PreviewState?): String? {
    val v = p as? PreviewState.Value ?: return null
    val t = v.type.lowercase()
    return if ("string" in t || "text" in t) v.rendered else null
}
