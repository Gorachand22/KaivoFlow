package com.kaivoflow.feature.canvas.inspector.state

import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.expressions.assist.ReferenceBuilder
import com.kaivoflow.core.expressions.assist.ReferenceTarget
import com.kaivoflow.core.model.ExprMode
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.ParamScope
import com.kaivoflow.core.model.ParamValidator
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.ShowRules
import com.kaivoflow.core.model.ValidationContext
import com.kaivoflow.core.model.ValidationPhase
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

data class Reduction(val state: InspectorState, val effects: List<InspectorEffect> = emptyList())

/**
 * Pure transition function of the inspector: `(state, intent) -> (state, effects)`.
 * [exprSyntax] is the design-time expression syntax check handed to `ParamValidator`;
 * [searchDebounceMs] the delay before typed search text is applied.
 */
class InspectorReducer(
    private val searchDebounceMs: Long = 250,
    private val exprSyntax: ((String) -> String?)? = null,
) {
    fun reduce(s: InspectorState, intent: InspectorIntent): Reduction {
        if (s.readOnly && intent.mutates()) return Reduction(s)
        return when (intent) {
            is InspectorIntent.SelectPage -> Reduction(s.copy(page = intent.page))
            is InspectorIntent.SelectParamsTab -> Reduction(s.copy(paramsTab = intent.tab))
            InspectorIntent.Refresh -> reloadBoth(s)

            is InspectorIntent.ParamsLoaded -> paramsLoaded(s, intent)
            is InspectorIntent.EditParam -> edit(s, intent.key, intent.value, EditReason.User)
            is InspectorIntent.SwitchToExpression -> toExpression(s, intent.key)
            is InspectorIntent.SwitchToFixed -> toFixed(s, intent.key, intent.literal)
            InspectorIntent.UndoParamEdit -> undo(s)
            is InspectorIntent.FocusField -> Reduction(s.copy(focusedField = intent.path))
            InspectorIntent.JumpToNextIssue -> jump(s)
            is InspectorIntent.PreviewComputed -> {
                if (s.values[intent.key] is ParamValue.Expr) Reduction(s.copy(previews = (s.previews + (intent.key to intent.preview)).toImmutableMap())) else Reduction(s)
            }
            is InspectorIntent.SelectPreviewItem -> {
                val idx = intent.index.coerceAtLeast(0)
                val n = s.copy(previewItem = idx)
                Reduction(n, previewEffects(n))
            }

            is InspectorIntent.SetMapperVisible -> Reduction(s.copy(mapperVisible = intent.visible))
            is InspectorIntent.BeginDrag -> Reduction(s.copy(drag = DragState(
                source = intent.source,
                reference = ReferenceBuilder.build(intent.source, ReferenceTarget.Template),
                x = intent.x, y = intent.y, hoverTarget = null, eligibleTargets = eligibleTargets(s),
            )))
            is InspectorIntent.DragMoved -> {
                val d = s.drag ?: return Reduction(s)
                Reduction(s.copy(drag = d.copy(x = intent.x, y = intent.y, hoverTarget = intent.hover?.takeIf { it in d.eligibleTargets })))
            }
            is InspectorIntent.DragDropped -> {
                val d = s.drag ?: return Reduction(s)
                val cleared = s.copy(drag = null)
                val target = d.hoverTarget ?: return Reduction(cleared)
                insertReference(cleared, target, d.source, d.reference, intent.selStart, intent.selEnd, EditReason.Drop)
            }
            InspectorIntent.DragCancelled -> Reduction(s.copy(drag = null))
            is InspectorIntent.DragEdgeDwell -> if (s.drag != null) Reduction(s.copy(page = intent.page)) else Reduction(s)
            is InspectorIntent.InsertReference -> insertReference(
                s, intent.target, intent.source, ReferenceBuilder.build(intent.source, ReferenceTarget.Template),
                intent.selStart, intent.selEnd, EditReason.Insert,
            )

            is InspectorIntent.ParentsChanged -> parentsChanged(s, intent.parents)
            is InspectorIntent.SelectInputParent -> selectParent(s, intent.stepId)
            is InspectorIntent.RunsChanged -> Reduction(s.withPanel(intent.side, s.panel(intent.side).copy(runs = intent.runs.toImmutableList())))
            is InspectorIntent.SelectRun -> selectRun(s, intent.side, intent.run)
            is InspectorIntent.PortsChanged -> Reduction(s.withPanel(intent.side, s.panel(intent.side).copy(ports = intent.ports.toImmutableList())))
            is InspectorIntent.SelectPort -> selectPort(s, intent.side, intent.port)
            is InspectorIntent.SetViewMode -> setMode(s, intent.side, intent.mode)
            is InspectorIntent.SetPageRequest -> setPage(s, intent.side, intent.offset, intent.size)
            is InspectorIntent.SearchChanged -> {
                val p = s.panel(intent.side)
                if (p.search == intent.text) Reduction(s)
                else Reduction(s.withPanel(intent.side, p.copy(search = intent.text)), listOf(InspectorEffect.ScheduleSearchCommit(intent.side, intent.text, searchDebounceMs)))
            }
            is InspectorIntent.SearchCommitted -> {
                val p = s.panel(intent.side)
                val text = intent.text.trim()
                if (intent.text != p.search || text == p.appliedSearch) Reduction(s)
                else reload(s.withPanel(intent.side, p.copy(appliedSearch = text, page = p.page.copy(offset = 0))), intent.side)
            }
            is InspectorIntent.DataLoaded -> {
                val p = s.panel(intent.side)
                if (intent.token != p.loadToken) Reduction(s)
                else {
                    val mode = if (p.mode == DataViewMode.Binary && !intent.summary.hasBinary) DataViewMode.Table else p.mode
                    Reduction(s.withPanel(intent.side, p.copy(status = DataStatus.Loaded(intent.summary), mode = mode)))
                }
            }
            is InspectorIntent.DataFailed -> {
                val p = s.panel(intent.side)
                if (intent.token != p.loadToken) Reduction(s) else Reduction(s.withPanel(intent.side, p.copy(status = DataStatus.Failed(intent.message))))
            }
            is InspectorIntent.StepDataChanged -> stepDataChanged(s, intent.stepId)

            InspectorIntent.PinCurrent -> pin(s)
            InspectorIntent.Unpin -> if (s.pin != null) Reduction(s, listOf(InspectorEffect.Unpin)) else Reduction(s)
            is InspectorIntent.PinChanged -> {
                val n = s.copy(pin = intent.info)
                if (intent.info == null && s.output.run == RunSelection.Pinned) reload(n.withPanel(PanelSide.Output, n.output.copy(run = RunSelection.Latest, page = n.output.page.copy(offset = 0))), PanelSide.Output)
                else Reduction(n)
            }
            is InspectorIntent.TestStepRequested -> {
                if (s.test is TestState.Running) Reduction(s)
                else Reduction(s.copy(test = TestState.Running(listOf(s.stepId))), listOf(InspectorEffect.RunTest(s.stepId, intent.policy)))
            }
            is InspectorIntent.TestEventReceived -> testEvent(s, intent.event)
            InspectorIntent.CancelTest ->
                if (s.test is TestState.Running || s.test is TestState.NeedsParents) Reduction(s.copy(test = TestState.Idle), listOf(InspectorEffect.CancelTest)) else Reduction(s)
        }
    }

    private fun InspectorIntent.mutates(): Boolean = when (this) {
        is InspectorIntent.EditParam, is InspectorIntent.SwitchToExpression, is InspectorIntent.SwitchToFixed, InspectorIntent.UndoParamEdit,
        is InspectorIntent.FocusField, is InspectorIntent.SetMapperVisible, is InspectorIntent.BeginDrag, is InspectorIntent.InsertReference,
        InspectorIntent.PinCurrent, InspectorIntent.Unpin, is InspectorIntent.TestStepRequested, InspectorIntent.JumpToNextIssue -> true
        else -> false
    }

    // ---- parameters ----

    private fun paramsLoaded(s: InspectorState, i: InspectorIntent.ParamsLoaded): Reduction {
        val same = i.schema == s.schema && i.values == s.values
        if (same) {
            return if (i.credentialsFilled == s.credentialsFilled) Reduction(s) else Reduction(revalidate(s.copy(credentialsFilled = i.credentialsFilled)))
        }
        val n = revalidate(s.copy(
            schema = i.schema, values = i.values.toImmutableMap(), credentialsFilled = i.credentialsFilled,
            lastEdit = if (i.values == s.values) s.lastEdit else null,
            previews = s.previews.filterKeys { k -> i.values[k] is ParamValue.Expr && i.values[k] == s.values[k] }.toImmutableMap(),
        ))
        return Reduction(n, previewEffects(n).filter { e -> n.previews[(e as InspectorEffect.EvaluatePreview).key] == null })
    }

    private fun edit(s: InspectorState, key: String, value: ParamValue?, reason: EditReason): Reduction {
        if (s.schema.params.isNotEmpty() && s.schema.find(key) == null) return Reduction(s)
        val before = s.values[key]
        if (before == value) return Reduction(s)
        val values = (if (value == null) s.values - key else s.values + (key to value)).toImmutableMap()
        var previews = s.previews
        val effects = ArrayList<InspectorEffect>()
        effects += InspectorEffect.PersistParam(key, value)
        if (value is ParamValue.Expr) effects += InspectorEffect.EvaluatePreview(key, value.text, s.previewItem)
        else previews = (previews - key).toImmutableMap()
        val n = revalidate(s.copy(values = values, previews = previews, lastEdit = ParamEdit(key, before, value, reason)))
        return Reduction(n, effects)
    }

    private fun toExpression(s: InspectorState, key: String): Reduction {
        val def = s.schema.find(key) ?: return Reduction(s)
        if (def.expr == ExprMode.Never) return Reduction(s, listOf(InspectorEffect.Notify(InspectorMessage.ExpressionNotAllowed)))
        val cur = s.values[key] ?: def.default
        if (cur is ParamValue.Expr) return Reduction(s)
        return edit(s, key, ParamValue.Expr(expressionFor(def, (cur as? ParamValue.Fixed)?.value)), EditReason.Toggle)
    }

    private fun toFixed(s: InspectorState, key: String, literal: Any?): Reduction {
        val def = s.schema.find(key) ?: return Reduction(s)
        if (def.expr == ExprMode.Only) return Reduction(s, listOf(InspectorEffect.Notify(InspectorMessage.FixedNotAllowed)))
        if (s.values[key] !is ParamValue.Expr) return Reduction(s)
        return edit(s, key, literal?.let { ParamValue.Fixed(it) }, EditReason.Toggle)
    }

    /** Text kinds keep the literal as is; other kinds wrap it as a literal inside `{{ }}`. */
    private fun expressionFor(def: ParamDef, literal: Any?): String = when {
        literal == null -> ""
        def.kind is ParamKind.Text && literal is String -> literal
        literal is String -> "{{ ${ReferenceBuilder.quote(literal)} }}"
        literal is Number -> "{{ ${Values.asString(literal)} }}"
        literal is Boolean -> "{{ $literal }}"
        else -> "{{ ${Values.stringify(literal)} }}"
    }

    private fun undo(s: InspectorState): Reduction {
        val e = s.lastEdit ?: return Reduction(s)
        val values = (if (e.before == null) s.values - e.key else s.values + (e.key to e.before)).toImmutableMap()
        val effects = ArrayList<InspectorEffect>()
        effects += InspectorEffect.PersistParam(e.key, e.before)
        var previews = s.previews
        val b = e.before
        if (b is ParamValue.Expr) effects += InspectorEffect.EvaluatePreview(e.key, b.text, s.previewItem) else previews = (previews - e.key).toImmutableMap()
        return Reduction(revalidate(s.copy(values = values, previews = previews, lastEdit = null)), effects)
    }

    private fun previewEffects(s: InspectorState): List<InspectorEffect> =
        s.values.mapNotNull { (k, v) -> (v as? ParamValue.Expr)?.let { InspectorEffect.EvaluatePreview(k, it.text, s.previewItem) } }

    private fun revalidate(s: InspectorState): InspectorState {
        val ctx = ValidationContext(ValidationPhase.DesignTime, s.credentialsFilled, exprSyntax = exprSyntax)
        return s.copy(issues = ParamValidator.validate(s.schema, s.values, ctx).toImmutableList())
    }

    private fun jump(s: InspectorState): Reduction {
        if (s.issues.isEmpty()) return Reduction(s)
        val at = s.issues.indexOfFirst { it.path == s.focusedField }
        val next = s.issues[(at + 1) % s.issues.size]
        return Reduction(s.copy(focusedField = next.path))
    }

    // ---- mapping ----

    private val containerKinds: (ParamKind) -> Boolean = {
        it is ParamKind.Assignments || it is ParamKind.KeyValueList || it is ParamKind.Conditions ||
            it is ParamKind.Repeater || it is ParamKind.Group || it is ParamKind.Collection
    }

    private fun acceptsData(def: ParamDef): Boolean = def.expr != ExprMode.Never && when (def.kind) {
        is ParamKind.Code, ParamKind.Secret, is ParamKind.CredentialPick, is ParamKind.Notice -> false
        else -> true
    }

    private fun eligibleTargets(s: InspectorState): Set<ParamPath> {
        val scope = ParamScope(s.schema.params, s.values, null, s.credentialsFilled)
        return s.schema.params.filter { acceptsData(it) && ShowRules.evaluate(it.show, scope).visible }.map { ParamPath.of(it.key) }.toSet()
    }

    private fun insertReference(
        s: InspectorState, target: ParamPath, source: com.kaivoflow.core.expressions.assist.MapSource,
        reference: String, selStart: Int, selEnd: Int, reason: EditReason,
    ): Reduction {
        val key = target.segments.firstOrNull() ?: return Reduction(s)
        val def = s.schema.find(key)
        if (def == null || !acceptsData(def)) return Reduction(s, listOf(InspectorEffect.Notify(InspectorMessage.DropRejected)))
        if (containerKinds(def.kind)) return Reduction(s, listOf(InspectorEffect.CreateRowFromDrop(target, source, reference)))
        val cur = s.values[key] ?: def.default
        val hasCaret = selStart >= 0
        val next: ParamValue = when {
            cur is ParamValue.Expr -> {
                val at = if (hasCaret) selStart else cur.text.length
                ParamValue.Expr(com.kaivoflow.core.expressions.assist.ReferenceBuilder.insertInto(cur.text, at, if (hasCaret) selEnd else at, source).text)
            }
            def.kind is ParamKind.Json && hasCaret && (cur is ParamValue.Fixed && cur.value is String) -> {
                val text = cur.value as String
                ParamValue.Fixed(com.kaivoflow.core.expressions.assist.ReferenceBuilder.insertInto(text, selStart, selEnd, source).text)
            }
            hasCaret && cur is ParamValue.Fixed && cur.value is String && (cur.value as String).isNotEmpty() && def.kind is ParamKind.Text ->
                ParamValue.Expr(com.kaivoflow.core.expressions.assist.ReferenceBuilder.insertInto(cur.value as String, selStart, selEnd, source).text)
            else -> ParamValue.Expr(reference)
        }
        return edit(s, key, next, reason)
    }

    // ---- data panels ----

    private fun requestFor(s: InspectorState, side: PanelSide): DataRequest {
        val p = s.panel(side)
        return DataRequest(side, p.sourceStepId, p.run, p.port, p.page, p.appliedSearch)
    }

    private fun reload(s: InspectorState, side: PanelSide): Reduction {
        val p = s.panel(side)
        if (p.sourceStepId == null) return Reduction(s.withPanel(side, p.copy(status = DataStatus.NoConnection, loadToken = p.loadToken + 1)))
        val n = p.copy(status = DataStatus.Loading, loadToken = p.loadToken + 1)
        val ns = s.withPanel(side, n)
        return Reduction(ns, listOf(InspectorEffect.LoadData(requestFor(ns, side), n.loadToken)))
    }

    private fun reloadBoth(s: InspectorState): Reduction {
        val a = reload(s, PanelSide.Input)
        val b = reload(a.state, PanelSide.Output)
        return Reduction(b.state, a.effects + b.effects)
    }

    private fun parentsChanged(s: InspectorState, parents: List<SourceChip>): Reduction {
        val p = s.input
        val chips = parents.toImmutableList()
        val selected = p.sourceStepId?.takeIf { id -> parents.any { it.stepId == id } } ?: parents.firstOrNull()?.stepId
        if (selected == p.sourceStepId) {
            val updated = s.copy(input = p.copy(sources = chips))
            return if (selected == null && p.status !is DataStatus.NoConnection) reload(updated, PanelSide.Input) else Reduction(updated)
        }
        val reset = p.copy(sources = chips, sourceStepId = selected, port = 0, ports = persistentEmpty(), run = defaultRun(s), runs = persistentEmpty(), page = p.page.copy(offset = 0))
        return reload(s.copy(input = reset), PanelSide.Input)
    }

    private fun defaultRun(s: InspectorState): RunSelection = s.readOnlyRun?.let { RunSelection.Specific(it) } ?: RunSelection.Latest

    private fun selectParent(s: InspectorState, stepId: String): Reduction {
        val p = s.input
        if (stepId == p.sourceStepId) return Reduction(s)
        if (p.sources.isNotEmpty() && p.sources.none { it.stepId == stepId }) return Reduction(s)
        val reset = p.copy(sourceStepId = stepId, port = 0, ports = persistentEmpty(), run = defaultRun(s), runs = persistentEmpty(), page = p.page.copy(offset = 0))
        return reload(s.copy(input = reset), PanelSide.Input)
    }

    private fun selectRun(s: InspectorState, side: PanelSide, run: RunSelection): Reduction {
        val p = s.panel(side)
        if (run == p.run) return Reduction(s)
        return reload(s.withPanel(side, p.copy(run = run, page = p.page.copy(offset = 0))), side)
    }

    private fun selectPort(s: InspectorState, side: PanelSide, port: Int): Reduction {
        val p = s.panel(side)
        if (port == p.port || port < 0) return Reduction(s)
        return reload(s.withPanel(side, p.copy(port = port, page = p.page.copy(offset = 0))), side)
    }

    private fun setMode(s: InspectorState, side: PanelSide, mode: DataViewMode): Reduction {
        val p = s.panel(side)
        if (mode == DataViewMode.Binary && p.summary?.hasBinary != true) return Reduction(s)
        return Reduction(s.withPanel(side, p.copy(mode = mode)))
    }

    private fun setPage(s: InspectorState, side: PanelSide, offset: Int, size: Int): Reduction {
        val p = s.panel(side)
        val sz = if (size in PageRequest.Sizes) size else PageRequest.DEFAULT_SIZE
        val off = (offset.coerceAtLeast(0) / sz) * sz
        if (PageRequest(off, sz) == p.page) return Reduction(s)
        return reload(s.withPanel(side, p.copy(page = PageRequest(off, sz))), side)
    }

    private fun stepDataChanged(s: InspectorState, stepId: String): Reduction {
        var cur = s
        val effects = ArrayList<InspectorEffect>()
        if (stepId == s.stepId && s.output.run == RunSelection.Latest) { val r = reload(cur, PanelSide.Output); cur = r.state; effects += r.effects }
        if (stepId == s.input.sourceStepId && s.input.run == RunSelection.Latest) { val r = reload(cur, PanelSide.Input); cur = r.state; effects += r.effects }
        return Reduction(cur, effects)
    }

    // ---- pin and test ----

    private fun pin(s: InspectorState): Reduction {
        val summary = s.output.summary ?: return Reduction(s, listOf(InspectorEffect.Notify(InspectorMessage.NothingToPin)))
        if (summary.truncated) return Reduction(s, listOf(InspectorEffect.Notify(InspectorMessage.PinRefusedTruncated)))
        if (summary.itemCount == 0) return Reduction(s, listOf(InspectorEffect.Notify(InspectorMessage.NothingToPin)))
        return Reduction(s, listOf(InspectorEffect.PinCurrent(requestFor(s, PanelSide.Output))))
    }

    private fun testEvent(s: InspectorState, e: InspectorTestEvent): Reduction {
        if (s.test !is TestState.Running && s.test !is TestState.NeedsParents) return Reduction(s)
        return when (e) {
            is InspectorTestEvent.Progress -> Reduction(s.copy(test = TestState.Running(e.stepIds)))
            is InspectorTestEvent.NeedsParents -> Reduction(s.copy(test = TestState.NeedsParents(e.stepIds)))
            is InspectorTestEvent.Finished -> refreshAfterTest(s.copy(test = TestState.Finished(e.executionKey)))
            is InspectorTestEvent.Failed -> refreshAfterTest(s.copy(test = TestState.Failed(e.message, e.itemIndex, e.hasPartialData)))
        }
    }

    /** Show fresh data: both panels go back to the latest execution and reload. */
    private fun refreshAfterTest(s: InspectorState): Reduction {
        val inp = s.input.copy(run = RunSelection.Latest, page = s.input.page.copy(offset = 0))
        val out = s.output.copy(run = RunSelection.Latest, page = s.output.page.copy(offset = 0))
        return reloadBoth(s.copy(input = inp, output = out))
    }

    private fun persistentEmpty() = kotlinx.collections.immutable.persistentListOf<Nothing>()
}
