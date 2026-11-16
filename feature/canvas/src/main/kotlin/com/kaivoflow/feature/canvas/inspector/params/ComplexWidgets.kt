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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.BadgeKind
import com.kaivoflow.core.design.GhostButton
import com.kaivoflow.core.design.KaivoBottomSheet
import com.kaivoflow.core.design.KaivoChip
import com.kaivoflow.core.design.KaivoIconButton
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SearchField
import com.kaivoflow.core.design.SheetOptionRow
import com.kaivoflow.core.design.StatusBadge
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.CredentialRef
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamIssue
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.ParamScope
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.ParamValueCodec
import com.kaivoflow.core.model.ResourceMode
import com.kaivoflow.core.model.ValueType
import kotlinx.collections.immutable.persistentListOf

// T19: parameter widgets, batch 2. Container widgets write one ParamValue for the whole container; children keep the nested codec.

private val frameNoop: (ParamValue?) -> Unit = {}

/** Card chrome for a group of rows. */
@Composable
private fun JewelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = KaivoTheme.colors
    Column(modifier.fillMaxWidth().clip(KaivoShapes.chip).background(colors.surfaceCard).border(1.dp, colors.hairline, KaivoShapes.chip).padding(10.dp)) { content() }
}

/** Overflow menu of a row, with the same actions as TalkBack custom actions (a drag handle has no tap path). */
@Composable
private fun RowMenu(label: String, canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit, onDuplicate: (() -> Unit)?, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        KaivoIconButton("more", "More actions for $label", { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canUp) DropdownMenuItem(text = { Text("Move up") }, onClick = { open = false; onUp() })
            if (canDown) DropdownMenuItem(text = { Text("Move down") }, onClick = { open = false; onDown() })
            if (onDuplicate != null) DropdownMenuItem(text = { Text("Duplicate") }, onClick = { open = false; onDuplicate() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { open = false; onDelete() })
        }
    }
}

private fun rowActions(canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit, onDuplicate: (() -> Unit)?, onDelete: () -> Unit): List<CustomAccessibilityAction> =
    buildList {
        if (canUp) add(CustomAccessibilityAction("Move up") { onUp(); true })
        if (canDown) add(CustomAccessibilityAction("Move down") { onDown(); true })
        if (onDuplicate != null) add(CustomAccessibilityAction("Duplicate") { onDuplicate(); true })
        add(CustomAccessibilityAction("Delete") { onDelete(); true })
    }

// ---- Json and Code ----

@Composable
fun JsonParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    env: ParamFormEnv = ParamFormEnv(), path: ParamPath = ParamPath.of(def.key),
) {
    val k = def.kind as ParamKind.Json
    val colors = KaivoTheme.colors
    val isExpr = ParamModes.isExpression(def, value)
    val raw = (value ?: def.default)
    val text = when (val f = (raw as? ParamValue.Fixed)?.value) { null -> ""; is String -> f; else -> Values.stringify(f, pretty = true) }
    val status = if (isExpr) JsonModel.Status.Empty else JsonModel.status(text, k.expect)
    ParamFieldFrame(def, value, onChange, issues, modifier, path, togglePearl(def, value, onChange) { env.expr?.literalOf(path) }, dependsOnExpr, enabled) {
        if (isExpr) {
            ExpressionField(path, ParamModes.exprText(raw), { onChange(ParamValue.Expr(it)) }, def.label, env = env.expr, enabled = enabled, multiline = true, minLines = 4, issues = issues)
        } else Column {
            ParamInput(
                value = text, onValueChange = { onChange(if (it.isEmpty()) null else ParamValue.Fixed(it)) }, label = def.label, enabled = enabled,
                singleLine = false, minLines = 4, maxLines = 14, mono = true, placeholder = def.placeholder ?: "{ }",
                isError = status is JsonModel.Status.Invalid || status is JsonModel.Status.WrongShape,
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                JsonModel.helperSymbols.forEach { s -> KaivoChip(s, onClick = { onChange(ParamValue.Fixed(text + s)) }, enabled = enabled, description = "Insert $s") }
                KaivoChip("Format", onClick = { JsonModel.format(text)?.let { onChange(ParamValue.Fixed(it)) } }, enabled = enabled && status is JsonModel.Status.Valid, iconKey = "braces", description = "Format JSON")
            }
            val (msg, tint) = when (status) {
                JsonModel.Status.Empty -> "" to colors.inkMuted
                is JsonModel.Status.Valid -> "Valid JSON (${status.kind})" to colors.statusSuccess
                is JsonModel.Status.Invalid -> "${status.message}, line ${status.line}, column ${status.column}" to colors.statusError
                is JsonModel.Status.WrongShape -> "Must be a JSON ${status.expected}" to colors.statusError
            }
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(top = 4.dp).semantics { contentDescription = "JSON status: $msg" })
        }
    }
}

@Composable
fun CodeParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    path: ParamPath = ParamPath.of(def.key),
) {
    val colors = KaivoTheme.colors
    val text = ParamModes.fixedText(value ?: def.default)
    var full by remember { mutableStateOf(false) }
    val tooBig = text.length > CodeModel.MAX_CHARS
    val own = if (tooBig) listOf(ParamIssue(path, com.kaivoflow.core.model.ParamSeverity.Error, "code.size", "Code is longer than ${CodeModel.MAX_CHARS} characters.")) else emptyList()
    ParamFieldFrame(def, value, onChange, own + issues, modifier, path, null, dependsOnExpr, enabled) {
        Column {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    (1..CodeModel.lineCount(text)).joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = KaivoTheme.type.monoFamily), color = colors.inkMuted,
                    modifier = Modifier.padding(top = 20.dp, end = 8.dp, start = 4.dp).semantics { contentDescription = "" },
                )
                Box(Modifier.weight(1f)) {
                    ParamInput(
                        value = text, onValueChange = { onChange(if (it.isEmpty()) null else ParamValue.Fixed(it)) }, label = def.label, enabled = enabled,
                        singleLine = false, minLines = 6, maxLines = 24, mono = true, placeholder = def.placeholder,
                        isError = tooBig, trailing = { KaivoIconButton("unfoldMore", "Open full screen code editor", { full = true }) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                CodeModel.symbols.forEach { s ->
                    KaivoChip(if (s == "\t") "Tab" else s, onClick = { onChange(ParamValue.Fixed(CodeModel.insertSymbol(text, text.length, text.length, s).first)) }, enabled = enabled, description = "Insert ${if (s == "\t") "tab" else s}")
                }
            }
            Text("Code is never run while editing.", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        }
    }
    if (full) FixedTextDialog(def.label, text, false, path, null, onTextChange = { onChange(if (it.isEmpty()) null else ParamValue.Fixed(it)) }) { full = false }
}

// ---- Collection, Group, Repeater ----

@Composable
fun CollectionParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    env: ParamFormEnv = ParamFormEnv(), path: ParamPath = ParamPath.of(def.key), scope: ParamScope? = null,
) {
    val k = def.kind as ParamKind.Collection
    val map = ParamTree.asMap(value)
    val addable = FormModelBuilder.addableOptional(k, map)
    var open by remember { mutableStateOf(false) }
    ParamFieldFrame(def, value, onChange, emptyList(), modifier, path, null, dependsOnExpr, enabled) {
        JewelCard {
            val present = k.optional.filter { map.containsKey(it.key) }
            if (present.isEmpty()) Text("No optional fields added", style = MaterialTheme.typography.bodyMedium, color = KaivoTheme.colors.inkMuted, modifier = Modifier.padding(vertical = 8.dp))
            present.forEach { child ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) {
                        ParamFieldFor(
                            child, ParamTree.child(map, child.key), issues, { v -> onChange(collectionWith(map, child.key, v)) },
                            path.child(child.key), env, enabled, scope = scope,
                        )
                    }
                    KaivoIconButton("close", "Remove ${child.label}", { onChange(ParamTree.fixed(LinkedHashMap(map).also { it.remove(child.key) })) }, enabled = enabled)
                }
            }
            if (addable.isNotEmpty()) KaivoChip("Add field", onClick = { open = true }, iconKey = "add", enabled = enabled, description = "Add an optional field to ${def.label}")
        }
    }
    if (open) {
        var q by remember { mutableStateOf("") }
        KaivoBottomSheet(title = "Add field", onDismiss = { open = false }) {
            if (addable.size > 6) SearchField(q, { q = it }, placeholder = "Search fields")
            addable.filter { FormModelBuilder.matches(it, q.trim().lowercase()) }.forEach { d ->
                SheetOptionRow(d.label, selected = false, subtitle = d.hint, onClick = { onChange(ParamValue.Fixed(LinkedHashMap(map).also { m -> m[d.key] = ParamValueCodec.encode(d.default) })); open = false })
            }
        }
    }
}

/** Sets a child inside a collection; a null child value keeps the key (the field stays added, just empty). */
private fun collectionWith(map: Map<String, Any?>, key: String, v: ParamValue?): ParamValue =
    ParamValue.Fixed(LinkedHashMap(map).also { it[key] = ParamValueCodec.encode(v) })

@Composable
fun GroupParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    env: ParamFormEnv = ParamFormEnv(), path: ParamPath = ParamPath.of(def.key), scope: ParamScope? = null,
) {
    val k = def.kind as ParamKind.Group
    val map = ParamTree.asMap(value)
    ParamFieldFrame(def, value, onChange, emptyList(), modifier, path, null, dependsOnExpr, enabled) {
        JewelCard {
            val (childScope, decoded) = FormModelBuilder.childLevel(scope ?: ParamScope(emptyList(), emptyMap()), k.children, map)
            ParamFields(k.children, decoded, { key, v -> onChange(ParamTree.fixed(ParamTree.withChild(map, key, v))) }, path, env, issues, enabled, childScope)
        }
    }
}

@Composable
fun RepeaterParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    env: ParamFormEnv = ParamFormEnv(), path: ParamPath = ParamPath.of(def.key), scope: ParamScope? = null,
) {
    val k = def.kind as ParamKind.Repeater
    val rows = ParamTree.asRows(value)
    var collapsed by remember { mutableStateOf(setOf<Int>()) }
    fun emit(next: List<Map<String, Any?>>) = onChange(ParamTree.fixedRows(next))
    ParamFieldFrame(def, value, onChange, issues.filter { it.path == path }, modifier, path, null, dependsOnExpr, enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { i, row ->
                val summary = (k.itemLabelKey ?: k.children.firstOrNull()?.key)?.let { fixedDisplay((ParamTree.child(row, it) as? ParamValue.Fixed)?.value).ifEmpty { null } }
                val title = summary ?: "Item ${i + 1}"
                val canUp = i > 0; val canDown = i < rows.size - 1
                val up = { emit(RowEdits.move(rows, i, i - 1)) }
                val down = { emit(RowEdits.move(rows, i, i + 1)) }
                val dup: (() -> Unit)? = if (k.maxItems == null || rows.size < k.maxItems!!) ({ emit(RowEdits.duplicate(rows, i, k.maxItems)) }) else null
                val del = { emit(RowEdits.remove(rows, i, 0)) }
                JewelCard(Modifier.semantics { customActions = rowActions(canUp, canDown, up, down, dup, del) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button) { collapsed = if (i in collapsed) collapsed - i else collapsed + i }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(KaivoIcons.icon(if (i in collapsed) "chevronRight" else "expandMore"), null, tint = KaivoTheme.colors.inkSecondary)
                            Spacer(Modifier.width(6.dp))
                            Text(title, style = MaterialTheme.typography.titleSmall, color = KaivoTheme.colors.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        RowMenu(title, canUp, canDown, up, down, dup, del)
                    }
                    if (i !in collapsed) {
                        val (childScope, decoded) = FormModelBuilder.childLevel(scope ?: ParamScope(emptyList(), emptyMap()), k.children, row)
                        ParamFields(k.children, decoded, { key, v -> emit(rows.mapIndexed { j, r -> if (j == i) ParamTree.withChild(r, key, v) else r }) }, path.index(i), env, issues, enabled, childScope)
                    }
                }
            }
            val full = k.maxItems != null && rows.size >= k.maxItems!!
            KaivoChip("Add item", onClick = { emit(RowEdits.add(rows, emptyMap(), k.maxItems)) }, iconKey = "add", enabled = enabled && !full, description = "Add an item to ${def.label}")
        }
    }
}

// ---- key-value and assignments ----

@Composable
fun KeyValueParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    env: ParamFormEnv = ParamFormEnv(), path: ParamPath = ParamPath.of(def.key),
) {
    val rows = KeyValueModel.decode(value)
    val asMap = KeyValueModel.wasMap(value) || value == null
    fun emit(next: List<KeyValueRow>) = onChange(KeyValueModel.encode(next, asMap))
    ParamFieldFrame(def, value, onChange, issues.filter { it.path == path }, modifier, path, null, dependsOnExpr, enabled) {
        Column {
            rows.forEachIndexed { i, r ->
                val up = { emit(RowEdits.move(rows, i, i - 1)) }
                val down = { emit(RowEdits.move(rows, i, i + 1)) }
                val del = { emit(RowEdits.remove(rows, i)) }
                val rowIssue = issues.firstOrNull { it.path.segments.firstOrNull() == def.key && it.path.segments.getOrNull(1) == "[$i]" }
                Row(Modifier.fillMaxWidth().semantics { customActions = rowActions(i > 0, i < rows.size - 1, up, down, null, del) }, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        ParamInput(r.name, { n -> emit(rows.mapIndexed { j, x -> if (j == i) x.copy(name = n) else x }) }, "Name of row ${i + 1}", enabled = enabled, placeholder = "Name", isError = rowIssue != null)
                        Spacer(Modifier.height(4.dp))
                        InlineValueField(path.index(i).child("value"), "Value of row ${i + 1}", r.value, { v -> emit(rows.mapIndexed { j, x -> if (j == i) x.copy(value = v) else x }) }, env.expr, enabled = enabled, placeholder = "Value")
                    }
                    RowMenu(r.name.ifBlank { "row ${i + 1}" }, i > 0, i < rows.size - 1, up, down, null, del)
                }
                if (rowIssue != null) IssueLine(listOf(rowIssue), null)
                Spacer(Modifier.height(6.dp))
            }
            KaivoChip("Add row", onClick = { emit(RowEdits.add(rows, KeyValueRow("", null))) }, iconKey = "add", enabled = enabled, description = "Add a row to ${def.label}")
        }
    }
}

@Composable
fun AssignmentsParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    env: ParamFormEnv = ParamFormEnv(), path: ParamPath = ParamPath.of(def.key),
) {
    val k = def.kind as ParamKind.Assignments
    val rows = AssignmentsModel.decode(value)
    val bad = AssignmentsModel.invalidNames(rows)
    var typeSheet by remember { mutableStateOf<Int?>(null) }
    fun emit(next: List<AssignmentRow>) = onChange(AssignmentsModel.encode(next))
    ParamFieldFrame(def, value, onChange, issues.filter { it.path == path }, modifier, path, null, dependsOnExpr, enabled) {
        Column {
            rows.forEachIndexed { i, r ->
                val up = { emit(AssignmentsModel.move(rows, i, i - 1)) }
                val down = { emit(AssignmentsModel.move(rows, i, i + 1)) }
                val dup = { emit(AssignmentsModel.duplicate(rows, i)) }
                val del = { emit(AssignmentsModel.remove(rows, i)) }
                JewelCard(Modifier.padding(bottom = 6.dp).semantics { customActions = rowActions(i > 0, i < rows.size - 1, up, down, dup, del) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            ParamInput(r.name, { n -> emit(AssignmentsModel.update(rows, i) { it.copy(name = n) }) }, "Name of assignment ${i + 1}", enabled = enabled, placeholder = "name or a.b.c", isError = i in bad)
                        }
                        Spacer(Modifier.width(6.dp))
                        KaivoChip(r.type.name, onClick = { typeSheet = i }, trailingIconKey = "expandMore", enabled = enabled, description = "Type of assignment ${i + 1}: ${r.type.name}")
                        RowMenu(r.name.ifBlank { "assignment ${i + 1}" }, i > 0, i < rows.size - 1, up, down, dup, del)
                    }
                    if (i in bad) IssueLine(listOf(ParamIssue(path.index(i), com.kaivoflow.core.model.ParamSeverity.Error, "assignment.name", if (r.name.isBlank()) "Give this assignment a name." else "This name is used twice.")), null)
                    InlineValueField(path.index(i).child("value"), "Value of ${r.name.ifBlank { "assignment ${i + 1}" }}", r.value, { v -> emit(AssignmentsModel.update(rows, i) { it.copy(value = v) }) }, env.expr, enabled = enabled, placeholder = "Value", type = r.type)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                KaivoChip("Add assignment", onClick = { emit(AssignmentsModel.add(rows, AssignmentRow("", k.allowedTypes.firstOrNull { it == ValueType.Text } ?: k.allowedTypes.first()))) }, iconKey = "add", enabled = enabled, description = "Add an assignment")
                Spacer(Modifier.width(8.dp))
                Text("Drop a field here", style = MaterialTheme.typography.labelMedium, color = KaivoTheme.colors.inkMuted)
            }
        }
    }
    typeSheet?.let { idx ->
        val opts = ValueType.entries.filter { it in k.allowedTypes }.map { ParamOption(it.name, it.name) }
        ChoiceSheet("Type", opts, setOf(rows.getOrNull(idx)?.type?.name.orEmpty()), multi = false, searchable = false, allowUnset = false,
            onPick = { n -> n?.let { name -> emit(AssignmentsModel.update(rows, idx) { it.copy(type = ValueType.valueOf(name)) }) } }, onDismiss = { typeSheet = null })
    }
}

// ---- conditions ----

@Composable
fun ConditionsParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    env: ParamFormEnv = ParamFormEnv(), path: ParamPath = ParamPath.of(def.key),
) {
    val k = def.kind as ParamKind.Conditions
    val colors = KaivoTheme.colors
    val tree = ConditionsModel.decode(value)
    var sheet by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // rule id, true = operator sheet else type sheet
    fun emit(t: ConditionTree) = onChange(ConditionsModel.encode(t))

    @Composable
    fun GroupView(g: ConditionGroup, depth: Int) {
        Column(Modifier.fillMaxWidth().then(if (depth > 1) Modifier.padding(start = 8.dp) else Modifier)) {
            g.children.forEachIndexed { i, node ->
                if (i > 0) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        KaivoChip(g.combinator.label, onClick = { emit(ConditionsModel.toggleCombinator(tree, g.id)) }, selected = true, enabled = enabled, description = "Combine with ${g.combinator.label}, tap to switch")
                    }
                }
                when (node) {
                    is ConditionGroup -> JewelCard(Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Group", style = MaterialTheme.typography.labelLarge, color = colors.inkSecondary, modifier = Modifier.weight(1f))
                            KaivoIconButton("delete", "Delete group", { emit(ConditionsModel.remove(tree, node.id)) }, enabled = enabled)
                        }
                        GroupView(node, depth + 1)
                    }
                    is Rule -> RuleCard(tree, node, path.child(node.id), env, enabled, onTree = ::emit, onSheet = { sheet = node.id to it })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KaivoChip("Add condition", onClick = { emit(ConditionsModel.addRule(tree, g.id)) }, iconKey = "add", enabled = enabled, description = "Add a condition")
                if (k.allowNested && depth < ConditionsModel.MAX_DEPTH) KaivoChip("Add group", onClick = { emit(ConditionsModel.addGroup(tree, g.id, true)) }, iconKey = "add", enabled = enabled, description = "Add a nested group")
            }
        }
    }

    ParamFieldFrame(def, value, onChange, issues.filter { it.path == path || it.path == path.child("conditions") }, modifier, path, null, dependsOnExpr, enabled) {
        Column {
            GroupView(tree.root, 1)
            ToggleRow("Ignore case", tree.ignoreCase, enabled) { emit(tree.copy(ignoreCase = it)) }
            ToggleRow("Lenient types", tree.lenientTypes, enabled) { emit(tree.copy(lenientTypes = it)) }
            if (ConditionsModel.ruleCount(tree) > 0) Text(ConditionsModel.summary(tree), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
    sheet?.let { (id, isOp) ->
        val rule = ConditionsModel.find(tree, id) as? Rule
        if (rule != null) {
            if (isOp) {
                ChoiceSheet("Operator", ConditionOps.forType(rule.leftType).map { ParamOption(it.key, it.label) }, setOf(rule.op), false, false, false,
                    onPick = { op -> op?.let { emit(ConditionsModel.updateRule(tree, id) { r -> ConditionsModel.changeOperator(r, op) }) } }, onDismiss = { sheet = null })
            } else {
                ChoiceSheet("Type of the left value", OperandType.entries.map { ParamOption(it.name, it.label) }, setOf(rule.leftType.name), false, false, false,
                    onPick = { n -> n?.let { emit(ConditionsModel.updateRule(tree, id) { r -> ConditionsModel.changeType(r, OperandType.valueOf(n)).rule }) } }, onDismiss = { sheet = null })
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = KaivoTheme.colors
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).toggleable(on, enabled, Role.Switch, onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = colors.royalGold, checkedThumbColor = colors.canvasVoid))
    }
}

@Composable
private fun RuleCard(tree: ConditionTree, r: Rule, path: ParamPath, env: ParamFormEnv, enabled: Boolean, onTree: (ConditionTree) -> Unit, onSheet: (Boolean) -> Unit) {
    val def = r.def
    fun set(f: (Rule) -> Rule) = onTree(ConditionsModel.updateRule(tree, r.id, f))
    JewelCard(Modifier.padding(vertical = 4.dp)) {
        InlineValueField(
            path.child("left"), "Left value", r.left, { v -> set { it.copy(left = v) } }, env.expr, enabled = enabled, placeholder = "Drop a field or type a value",
            onDrop = { p, _, _ -> onTree(ConditionsModel.dropOnLeft(tree, r.id, p.reference, p.type)) },
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            KaivoChip(r.leftType.label, onClick = { onSheet(false) }, trailingIconKey = "expandMore", enabled = enabled, description = "Type of the left value: ${r.leftType.label}")
            KaivoChip(def.label, onClick = { onSheet(true) }, trailingIconKey = "expandMore", selected = true, enabled = enabled, description = "Operator: ${def.label}")
            KaivoIconButton("delete", "Delete condition", { onTree(ConditionsModel.remove(tree, r.id)) }, enabled = enabled)
        }
        if (def.args >= 1) InlineValueField(path.child("right"), if (def.args == 2) "From" else "Right value", r.right, { v -> set { it.copy(right = v) } }, env.expr, enabled = enabled, placeholder = "Value")
        if (def.args == 2) InlineValueField(path.child("right2"), "To", r.right2, { v -> set { it.copy(right2 = v) } }, env.expr, enabled = enabled, placeholder = "Value")
    }
}

// ---- file, resource, credential ----

@Composable
fun FileRefParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    env: ParamFormEnv = ParamFormEnv(), path: ParamPath = ParamPath.of(def.key),
) {
    val k = def.kind as ParamKind.FileRef
    val m = ParamTree.asMap(value)
    val device = m["mode"]?.toString()?.lowercase().let { it == "device" || it == "fromdevice" }
    fun put(key: String, v: ParamValue?) = onChange(ParamTree.fixed(ParamTree.withChild(m, key, v)))
    ParamFieldFrame(def, value, onChange, issues.filter { it.path == path || it.path.segments.firstOrNull() == def.key }, modifier, path, null, dependsOnExpr, enabled) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KaivoChip("From data", onClick = { onChange(ParamTree.fixed(ParamTree.withChild(m, "mode", ParamValue.Fixed("data")))) }, selected = !device, enabled = enabled)
                KaivoChip("From device", onClick = { onChange(ParamTree.fixed(ParamTree.withChild(m, "mode", ParamValue.Fixed("device")))) }, selected = device, enabled = enabled)
            }
            Spacer(Modifier.height(6.dp))
            if (!device) {
                InlineValueField(path.child("property"), "Binary property name", ParamTree.child(m, "property"), { put("property", it) }, env.expr, enabled = enabled, placeholder = "data")
                if (env.binaryProperties.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    env.binaryProperties.forEach { n -> KaivoChip(n, onClick = { put("property", ParamValue.Fixed(n)) }, iconKey = "attachFile", enabled = enabled, description = "Use binary property $n") }
                }
            } else {
                val name = m["name"]?.toString() ?: m["uri"]?.toString()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name ?: "No file chosen", style = MaterialTheme.typography.bodyMedium, color = if (name == null) KaivoTheme.colors.inkMuted else KaivoTheme.colors.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    GhostButton(if (name == null) "Pick file" else "Pick again", onClick = { env.onPickDeviceFile(path) })
                }
            }
            if (k.output) InlineValueField(path.child("fileName"), "File name", ParamTree.child(m, "fileName"), { put("fileName", it) }, env.expr, enabled = enabled, placeholder = "report-{{ \$today }}.csv")
        }
    }
}

@Composable
fun ResourceParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    env: ParamFormEnv = ParamFormEnv(), path: ParamPath = ParamPath.of(def.key),
) {
    val k = def.kind as ParamKind.ResourcePick
    val colors = KaivoTheme.colors
    val m = ParamTree.asMap(value)
    val mode = when (m["mode"]?.toString()?.lowercase()) { "url", "byurl" -> ResourceMode.ByUrl; "id", "byid" -> ResourceMode.ById; else -> k.modes.firstOrNull() ?: ResourceMode.ById }
    val modeKey = when (mode) { ResourceMode.FromList -> "list"; ResourceMode.ByUrl -> "url"; ResourceMode.ById -> "id" }
    val current = ParamTree.child(m, "value")
    var open by remember { mutableStateOf(false) }
    fun write(v: ParamValue?, name: String? = null) {
        var next = ParamTree.withChild(ParamTree.withChild(m, "mode", ParamValue.Fixed(modeKey)), "value", v)
        next = if (name != null) ParamTree.withChild(next, "cachedName", ParamValue.Fixed(name)) else next - "cachedName"
        onChange(ParamTree.fixed(next))
    }
    ParamFieldFrame(def, value, onChange, issues.filter { it.path.segments.firstOrNull() == def.key }, modifier, path, null, dependsOnExpr, enabled) {
        Column {
            if (k.modes.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                k.modes.forEach { md ->
                    val label = when (md) { ResourceMode.FromList -> "From list"; ResourceMode.ByUrl -> "By URL"; ResourceMode.ById -> "By ID" }
                    val key = when (md) { ResourceMode.FromList -> "list"; ResourceMode.ByUrl -> "url"; ResourceMode.ById -> "id" }
                    KaivoChip(label, onClick = { onChange(ParamTree.fixed(ParamTree.withChild(ParamTree.withChild(m, "mode", ParamValue.Fixed(key)), "value", null))) }, selected = md == mode, enabled = enabled)
                }
            }
            Spacer(Modifier.height(6.dp))
            when (mode) {
                ResourceMode.FromList -> {
                    val name = m["cachedName"]?.toString() ?: (current as? ParamValue.Fixed)?.value?.toString()
                    when (val st = env.resourcesFor(path)) {
                        ResourceListState.NeedsCredential -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Pick a credential first", style = MaterialTheme.typography.bodyMedium, color = colors.statusWarning, modifier = Modifier.weight(1f))
                            GhostButton("Pick credential", onClick = env.onAddCredential)
                        }
                        is ResourceListState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(st.message, style = MaterialTheme.typography.bodyMedium, color = colors.statusError, modifier = Modifier.weight(1f))
                            GhostButton("Retry", onClick = { env.onLoadResources(path, "") })
                        }
                        else -> FieldSurface(Modifier.clickable(enabled = enabled, role = Role.Button) { env.onLoadResources(path, ""); open = true }.semantics { contentDescription = "${def.label}, ${name ?: "none chosen"}, opens a list" }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name ?: "Choose from list", style = MaterialTheme.typography.bodyMedium, color = if (name == null) colors.inkMuted else colors.inkPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(KaivoIcons.icon("expandMore"), null, tint = colors.inkSecondary)
                            }
                        }
                    }
                }
                ResourceMode.ByUrl -> {
                    InlineValueField(path.child("value"), "URL", current, { write(it) }, env.expr, enabled = enabled, placeholder = "https://")
                    val id = k.urlPattern?.let { p -> runCatching { Regex(p).find((current as? ParamValue.Fixed)?.value?.toString().orEmpty())?.groupValues?.getOrNull(1) }.getOrNull() }
                    if (!id.isNullOrEmpty()) StatusBadge("ID: $id", BadgeKind.Gold)
                }
                ResourceMode.ById -> InlineValueField(path.child("value"), "ID", current, { write(it) }, env.expr, enabled = enabled, placeholder = "ID")
            }
        }
    }
    if (open) {
        var q by remember { mutableStateOf("") }
        val st = env.resourcesFor(path)
        KaivoBottomSheet(title = def.label, onDismiss = { open = false }) {
            SearchField(q, { q = it; env.onLoadResources(path, it) }, placeholder = "Search")
            when (st) {
                ResourceListState.Loading, ResourceListState.Idle -> Text("Loading", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted, modifier = Modifier.padding(16.dp))
                ResourceListState.Empty -> Text("Nothing found", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted, modifier = Modifier.padding(16.dp))
                is ResourceListState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.message, style = MaterialTheme.typography.bodyMedium, color = colors.statusError, modifier = Modifier.weight(1f).padding(16.dp))
                    GhostButton("Retry", onClick = { env.onLoadResources(path, q) })
                }
                ResourceListState.NeedsCredential -> Text("Pick a credential first", style = MaterialTheme.typography.bodyMedium, color = colors.statusWarning, modifier = Modifier.padding(16.dp))
                is ResourceListState.Ready -> st.items.forEach { it0 ->
                    SheetOptionRow(it0.name, selected = (current as? ParamValue.Fixed)?.value == it0.id, subtitle = it0.url, onClick = { write(ParamValue.Fixed(it0.id), it0.name); open = false })
                }
            }
        }
    }
}

/** Never lists secrets: only id, label and type. A deleted credential shows an error pill, not a blank. Expressions are not allowed here. */
@Composable
fun CredentialParamField(
    def: ParamDef, value: ParamValue?, issues: List<ParamIssue>, onChange: (ParamValue?) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, dependsOnExpr: Boolean = false,
    env: ParamFormEnv = ParamFormEnv(), path: ParamPath = ParamPath.of(def.key),
) {
    val colors = KaivoTheme.colors
    val creds: List<CredentialRef>? = env.credentials
    val id = (value as? ParamValue.Fixed)?.value?.toString().orEmpty()
    val selected = creds?.firstOrNull { it.id == id }
    // Only warn once the vault list has loaded, so a slow first load never flashes a false alarm.
    val missing = creds != null && id.isNotBlank() && selected == null
    var open by remember { mutableStateOf(false) }
    ParamFieldFrame(def, value, onChange, issues, modifier, path, null, dependsOnExpr, enabled) {
        Column {
            FieldSurface(Modifier.clickable(enabled = enabled, role = Role.Button) { open = true }.semantics { contentDescription = "${def.label}, ${selected?.label ?: if (missing) "credential missing" else "none selected"}, opens a list" }, isError = missing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(KaivoIcons.icon("key"), null, tint = colors.royalGold, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(selected?.label ?: if (id.isBlank()) "None selected" else "Missing credential", style = MaterialTheme.typography.bodyMedium, color = if (selected != null) colors.inkPrimary else colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (selected != null) Text(selected.type, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary, maxLines = 1)
                    }
                    Text("Change", style = MaterialTheme.typography.labelMedium, color = colors.royalGold)
                }
            }
            if (missing) StatusBadge("Credential no longer exists. Pick another.", BadgeKind.Error)
        }
    }
    if (open) {
        KaivoBottomSheet(title = def.label, onDismiss = { open = false }) {
            when {
                creds == null -> Text("Loading", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted, modifier = Modifier.padding(16.dp))
                else -> creds.forEach { c -> SheetOptionRow(c.label, selected = c.id == id, subtitle = c.type, onClick = { onChange(ParamValue.Fixed(c.id)); open = false }) }
            }
            SheetOptionRow("New credential", selected = false, onClick = { open = false; env.onAddCredential() })
            if (!def.required && id.isNotBlank()) SheetOptionRow("Clear", selected = false, onClick = { onChange(null); open = false })
        }
    }
}
