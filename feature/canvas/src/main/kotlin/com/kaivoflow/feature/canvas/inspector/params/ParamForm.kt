package com.kaivoflow.feature.canvas.inspector.params

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.CountPill
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SearchField
import com.kaivoflow.core.model.ExprMode
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamIssue
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.ParamScope
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.feature.canvas.inspector.mapper.dragMapViewport
import com.kaivoflow.feature.canvas.inspector.state.EditReason
import com.kaivoflow.feature.canvas.inspector.state.ParamEdit
import kotlinx.coroutines.delay

/** Draws one parameter with the widget for its kind. Scalars get the issues at their path, containers the issues below it. */
@Composable
fun ParamFieldFor(
    def: ParamDef,
    value: ParamValue?,
    issues: List<ParamIssue>,
    onChange: (ParamValue?) -> Unit,
    path: ParamPath,
    env: ParamFormEnv,
    enabled: Boolean,
    dependsOnExpr: Boolean = false,
    scope: ParamScope? = null,
    modifier: Modifier = Modifier,
) {
    val own = issues.filter { it.path == path }
    val below = issues.filter { FormModelBuilder.startsWith(it.path, path) }
    val expr = env.expr
    when (def.kind) {
        is ParamKind.Text -> TextParamField(def, value, own, onChange, modifier, enabled, dependsOnExpr, expr, path)
        is ParamKind.Number -> NumberParamField(def, value, own, onChange, modifier, enabled, dependsOnExpr, expr, path)
        ParamKind.Bool -> BooleanParamField(def, value, own, onChange, modifier, enabled, dependsOnExpr, expr, path)
        is ParamKind.Choice -> ChoiceParamField(def, value, own, onChange, modifier, enabled, dependsOnExpr, expr, path, env.optionsFor(path), { env.onRetryOptions(path) })
        is ParamKind.MultiChoice -> MultiChoiceParamField(def, value, own, onChange, modifier, enabled, dependsOnExpr, expr, path, env.optionsFor(path))
        is ParamKind.DateTime -> DateTimeParamField(def, value, own, onChange, modifier, enabled, dependsOnExpr, expr, path)
        ParamKind.Color -> ColorParamField(def, value, own, onChange, modifier, enabled, dependsOnExpr, expr, path, env.recentColors)
        is ParamKind.Notice -> NoticeParamField(def, modifier, env.onNoticeAction)
        ParamKind.Secret -> SecretParamField(def, value, own, onChange, modifier, enabled, dependsOnExpr, path)
        is ParamKind.Json -> JsonParamField(def, value, own, onChange, modifier, enabled, dependsOnExpr, env, path)
        is ParamKind.Code -> CodeParamField(def, value, own, onChange, modifier, enabled, dependsOnExpr, path)
        is ParamKind.Collection -> CollectionParamField(def, value, below, onChange, modifier, enabled, dependsOnExpr, env, path, scope)
        is ParamKind.Group -> GroupParamField(def, value, below, onChange, modifier, enabled, dependsOnExpr, env, path, scope)
        is ParamKind.Repeater -> RepeaterParamField(def, value, below, onChange, modifier, enabled, dependsOnExpr, env, path, scope)
        ParamKind.KeyValueList -> KeyValueParamField(def, value, below, onChange, modifier, enabled, dependsOnExpr, env, path)
        is ParamKind.Assignments -> AssignmentsParamField(def, value, below, onChange, modifier, enabled, dependsOnExpr, env, path)
        is ParamKind.Conditions -> ConditionsParamField(def, value, below, onChange, modifier, enabled, dependsOnExpr, env, path)
        is ParamKind.FileRef -> FileRefParamField(def, value, below, onChange, modifier, enabled, dependsOnExpr, env, path)
        is ParamKind.ResourcePick -> ResourceParamField(def, value, below, onChange, modifier, enabled, dependsOnExpr, env, path)
        is ParamKind.CredentialPick -> CredentialParamField(def, value, own, onChange, modifier, enabled, dependsOnExpr, env, path)
    }
}

/**
 * One level of a form (root, group, or repeater row), recursive through the container widgets.
 * Show rules hide fields (their stored values stay); advanced fields sit under a collapsed
 * "More options" row with a count of changed values.
 */
@Composable
fun ParamFields(
    defs: List<ParamDef>,
    values: Map<String, ParamValue?>,
    onChild: (String, ParamValue?) -> Unit,
    at: ParamPath,
    env: ParamFormEnv,
    issues: List<ParamIssue>,
    enabled: Boolean,
    scope: ParamScope,
    search: String = "",
) {
    val model = FormModelBuilder.buildLevel(defs, values, scope, at, issues, search)
    var moreOpen by remember(at) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        model.primary.forEach { f -> FieldItem(f, onChild, env, issues, enabled, scope) }
        if (model.advanced.isNotEmpty()) {
            val colors = KaivoTheme.colors
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(KaivoShapes.chip)
                    .clickable(role = Role.Button) { moreOpen = !moreOpen }
                    .semantics { stateDescription = if (moreOpen) "Expanded" else "Collapsed"; contentDescription = "More options, ${model.advancedChanged} changed" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(KaivoIcons.icon(if (moreOpen) "expandMore" else "chevronRight"), null, tint = colors.inkSecondary)
                Spacer(Modifier.width(6.dp))
                Text("More options", style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary)
                if (model.advancedChanged > 0) { Spacer(Modifier.width(8.dp)); CountPill(model.advancedChanged.toString(), description = "${model.advancedChanged} changed") }
            }
            if (moreOpen || search.isNotBlank()) model.advanced.forEach { f -> FieldItem(f, onChild, env, issues, enabled, scope) }
        }
    }
}

@Composable
private fun FieldItem(f: FormField, onChild: (String, ParamValue?) -> Unit, env: ParamFormEnv, issues: List<ParamIssue>, enabled: Boolean, scope: ParamScope) {
    ParamFieldFor(f.def, f.value, issues, { v -> onChild(f.def.key, v) }, f.path, env, enabled, f.dependsOnExpr, scope)
}

/**
 * The whole parameters form for a Step (spec P2 2 and 3). Credential slots come first, then the
 * fields. Edits are reported per top-level key; nested edits are folded into the container's value
 * here, so the inspector's single-level undo covers them. [lastEdit] feeds the undo bar:
 * "Switched to expression", "Inserted reference", "Removed row".
 */
@Composable
fun ParamForm(
    schema: ParamSchema,
    values: Map<String, ParamValue?>,
    issues: List<ParamIssue>,
    onChange: (key: String, value: ParamValue?) -> Unit,
    modifier: Modifier = Modifier,
    env: ParamFormEnv = ParamFormEnv(),
    credentialsFilled: Set<String> = emptySet(),
    enabled: Boolean = true,
    lastEdit: ParamEdit? = null,
    onUndo: () -> Unit = {},
    scrollable: Boolean = true,
) {
    var search by remember { mutableStateOf("") }
    var dismissed by remember { mutableStateOf<ParamEdit?>(null) }
    val scope = remember(schema, values, credentialsFilled) { ParamScope(schema.params, values, null, credentialsFilled) }
    val model = FormModelBuilder.build(schema, values, issues, credentialsFilled, search)
    val scroll = rememberScrollState()
    val message = lastEdit?.let { e ->
        UndoText.describe(e.before, e.after) ?: if (e.reason == EditReason.Drop || e.reason == EditReason.Insert) UndoText.describeDrop() else null
    }
    LaunchedEffect(lastEdit) { if (lastEdit != null) { delay(6000); dismissed = lastEdit } }
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().then(if (scrollable) Modifier.dragMapViewport(scroll).verticalScroll(scroll) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = if (message != null) 56.dp else 0.dp),
        ) {
            if (FormModelBuilder.offersSearch(schema)) {
                SearchField(search, { search = it }, placeholder = "Search parameters")
                Spacer(Modifier.padding(4.dp))
            }
            model.credentialSlots.forEach { slot ->
                val def = ParamDef(slot.key, slot.label, ParamKind.CredentialPick(slot.accepts), required = slot.required, expr = ExprMode.Never)
                ParamFieldFor(def, values[slot.key], issues, { v -> onChange(slot.key, v) }, ParamPath.of(slot.key), env, enabled, scope = scope)
            }
            ParamFields(schema.params, values, onChange, ParamPath.Root, env, issues, enabled, scope, search)
        }
        if (message != null && lastEdit != null && lastEdit !== dismissed) {
            val colors = KaivoTheme.colors
            Row(
                Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().clip(KaivoShapes.chip)
                    .background(colors.surfaceElevated).border(1.dp, colors.royalGold.copy(alpha = 0.5f), KaivoShapes.chip)
                    .padding(start = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                GhostButton("Undo", onClick = { dismissed = lastEdit; onUndo() })
            }
        }
    }
}
