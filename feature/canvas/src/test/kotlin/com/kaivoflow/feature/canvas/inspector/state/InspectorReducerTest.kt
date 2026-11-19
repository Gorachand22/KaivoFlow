package com.kaivoflow.feature.canvas.inspector.state

import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.expressions.assist.SourceKind
import com.kaivoflow.core.model.ExprMode
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.ParamRule
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.SchemaPath
import com.kaivoflow.core.model.ShowWhen
import com.kaivoflow.feature.canvas.inspector.logic.TestRisks
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorReducerTest {
    private val reducer = InspectorReducer(searchDebounceMs = 250)
    private val schema = ParamSchema(
        version = 1,
        params = listOf(
            ParamDef("url", "URL", ParamKind.Text(), required = true),
            ParamDef("count", "Count", ParamKind.Number()),
            ParamDef("flag", "Flag", ParamKind.Bool),
            ParamDef("token", "Token", ParamKind.Secret, expr = ExprMode.Never),
            ParamDef("body", "Body", ParamKind.Code(), expr = ExprMode.Never),
            ParamDef("only", "Only", ParamKind.Text(), expr = ExprMode.Only),
            ParamDef("fields", "Fields", ParamKind.Assignments()),
            ParamDef("hidden", "Hidden", ParamKind.Text(), show = ShowWhen.Eq("flag", true)),
            ParamDef("json", "Json", ParamKind.Json()),
        ).toImmutableList(),
    )
    private val src = MapSource("s1", true, SchemaPath.of("user", "name"), SourceKind.Json)

    private fun opened(replay: String? = null): InspectorState {
        var s = InspectorState.initial("me", replay)
        s = reducer.reduce(s, InspectorIntent.ParamsLoaded(schema, emptyMap())).state
        s = reducer.reduce(s, InspectorIntent.ParentsChanged(listOf(SourceChip("p1", "Parent 1"), SourceChip("p2", "Parent 2")))).state
        return s
    }

    private fun InspectorState.apply(i: InspectorIntent) = reducer.reduce(this, i)
    private fun loaded(s: InspectorState, side: PanelSide, summary: DataSummary = DataSummary(10, 10, hasBinary = true)): InspectorState =
        s.apply(InspectorIntent.DataLoaded(side, s.panel(side).loadToken, summary)).state

    // ---- data panels ----

    @Test fun openingWithoutParentShowsNoConnectionAndLoadsOutput() {
        val r = InspectorState.initial("me").apply(InspectorIntent.Refresh)
        assertEquals(DataStatus.NoConnection, r.state.input.status)
        assertEquals(DataStatus.Loading, r.state.output.status)
        val load = r.effects.filterIsInstance<InspectorEffect.LoadData>().single()
        assertEquals("me", load.request.stepId); assertEquals(PanelSide.Output, load.request.side)
    }

    @Test fun parentsChangedPicksFirstParentAndLoads() {
        val r = InspectorState.initial("me").apply(InspectorIntent.ParentsChanged(listOf(SourceChip("p1", "A"))))
        assertEquals("p1", r.state.input.sourceStepId)
        assertEquals("p1", (r.effects.single() as InspectorEffect.LoadData).request.stepId)
        val again = r.state.apply(InspectorIntent.ParentsChanged(listOf(SourceChip("p1", "A"), SourceChip("p2", "B"))))
        assertTrue(again.effects.isEmpty()); assertEquals(2, again.state.input.sources.size)
        val gone = again.state.apply(InspectorIntent.ParentsChanged(listOf(SourceChip("p2", "B"))))
        assertEquals("p2", gone.state.input.sourceStepId); assertEquals(1, gone.effects.size)
        val none = gone.state.apply(InspectorIntent.ParentsChanged(emptyList()))
        assertNull(none.state.input.sourceStepId); assertEquals(DataStatus.NoConnection, none.state.input.status)
    }

    @Test fun selectingParentReloadsInputAndResetsPortRunAndPaging() {
        var s = opened()
        s = s.apply(InspectorIntent.PortsChanged(PanelSide.Input, listOf(PortChip(0), PortChip(1)))).state
        s = s.apply(InspectorIntent.SelectPort(PanelSide.Input, 1)).state
        s = s.apply(InspectorIntent.SelectRun(PanelSide.Input, RunSelection.Specific("r9"))).state
        s = s.apply(InspectorIntent.SetPageRequest(PanelSide.Input, 50, 25)).state
        val r = s.apply(InspectorIntent.SelectInputParent("p2"))
        val p = r.state.input
        assertEquals("p2", p.sourceStepId); assertEquals(0, p.port); assertEquals(RunSelection.Latest, p.run); assertEquals(0, p.page.offset)
        assertTrue(p.ports.isEmpty())
        val load = r.effects.single() as InspectorEffect.LoadData
        assertEquals("p2", load.request.stepId)
        assertTrue(s.apply(InspectorIntent.SelectInputParent("ghost")).effects.isEmpty())
        assertTrue(s.apply(InspectorIntent.SelectInputParent("p1")).effects.isEmpty())
    }

    @Test fun changingRunResetsPaging() {
        var s = opened()
        s = s.apply(InspectorIntent.SetPageRequest(PanelSide.Output, 75, 25)).state
        assertEquals(75, s.output.page.offset)
        val r = s.apply(InspectorIntent.SelectRun(PanelSide.Output, RunSelection.Specific("r2", 1)))
        assertEquals(0, r.state.output.page.offset)
        assertEquals(RunSelection.Specific("r2", 1), (r.effects.single() as InspectorEffect.LoadData).request.run)
        assertTrue(r.state.apply(InspectorIntent.SelectRun(PanelSide.Output, RunSelection.Specific("r2", 1))).effects.isEmpty())
    }

    @Test fun portSwitchKeepsModeAndResetsOffset() {
        var s = opened()
        s = s.apply(InspectorIntent.SetViewMode(PanelSide.Output, DataViewMode.Json)).state
        s = s.apply(InspectorIntent.SetPageRequest(PanelSide.Output, 50, 25)).state
        val r = s.apply(InspectorIntent.SelectPort(PanelSide.Output, 1))
        assertEquals(DataViewMode.Json, r.state.output.mode); assertEquals(0, r.state.output.page.offset); assertEquals(1, r.state.output.port)
        assertEquals(1, (r.effects.single() as InspectorEffect.LoadData).request.port)
        assertTrue(s.apply(InspectorIntent.SelectPort(PanelSide.Output, -1)).effects.isEmpty())
    }

    @Test fun pagingNormalisesOffsetAndSize() {
        val s = opened()
        assertEquals(50, s.apply(InspectorIntent.SetPageRequest(PanelSide.Output, 60, 25)).state.output.page.offset)
        val big = s.apply(InspectorIntent.SetPageRequest(PanelSide.Output, 0, 100))
        assertEquals(100, big.state.output.page.size)
        assertEquals(PageRequest.DEFAULT_SIZE, s.apply(InspectorIntent.SetPageRequest(PanelSide.Output, 0, 33)).state.output.page.size)
        assertTrue(s.apply(InspectorIntent.SetPageRequest(PanelSide.Output, 0, 25)).effects.isEmpty())
    }

    @Test fun searchDebouncesThenResetsPage() {
        var s = opened()
        s = s.apply(InspectorIntent.SetPageRequest(PanelSide.Output, 50, 25)).state
        val a = s.apply(InspectorIntent.SearchChanged(PanelSide.Output, "ab"))
        assertEquals("ab", a.state.output.search); assertEquals("", a.state.output.appliedSearch)
        assertEquals(InspectorEffect.ScheduleSearchCommit(PanelSide.Output, "ab", 250), a.effects.single())
        assertEquals(50, a.state.output.page.offset)
        val stale = a.state.apply(InspectorIntent.SearchChanged(PanelSide.Output, "abc")).state
        assertTrue(stale.apply(InspectorIntent.SearchCommitted(PanelSide.Output, "ab")).effects.isEmpty())
        val c = stale.apply(InspectorIntent.SearchCommitted(PanelSide.Output, "abc"))
        assertEquals("abc", c.state.output.appliedSearch); assertEquals(0, c.state.output.page.offset)
        assertEquals("abc", (c.effects.single() as InspectorEffect.LoadData).request.search)
        assertTrue(c.state.apply(InspectorIntent.SearchChanged(PanelSide.Output, "abc")).effects.isEmpty())
        assertTrue(c.state.apply(InspectorIntent.SearchCommitted(PanelSide.Output, "abc")).effects.isEmpty())
    }

    @Test fun lateDataResultsAreIgnored() {
        val s = opened()
        val first = s.output.loadToken
        val s2 = s.apply(InspectorIntent.SelectPort(PanelSide.Output, 1)).state
        val stale = s2.apply(InspectorIntent.DataLoaded(PanelSide.Output, first, DataSummary(1, 1))).state
        assertEquals(DataStatus.Loading, stale.output.status)
        val ok = loaded(s2, PanelSide.Output)
        assertTrue(ok.output.status is DataStatus.Loaded)
        assertEquals(DataStatus.Loading, s2.apply(InspectorIntent.DataFailed(PanelSide.Output, first, "x")).state.output.status)
        assertEquals(DataStatus.Failed("boom"), s2.apply(InspectorIntent.DataFailed(PanelSide.Output, s2.output.loadToken, "boom")).state.output.status)
    }

    @Test fun binaryModeNeedsBinaryData() {
        var s = opened()
        assertEquals(DataViewMode.Table, s.apply(InspectorIntent.SetViewMode(PanelSide.Output, DataViewMode.Binary)).state.output.mode)
        s = loaded(s, PanelSide.Output)
        s = s.apply(InspectorIntent.SetViewMode(PanelSide.Output, DataViewMode.Binary)).state
        assertEquals(DataViewMode.Binary, s.output.mode)
        val s2 = s.apply(InspectorIntent.SelectPort(PanelSide.Output, 1)).state
        val noBin = s2.apply(InspectorIntent.DataLoaded(PanelSide.Output, s2.output.loadToken, DataSummary(2, 2, hasBinary = false))).state
        assertEquals(DataViewMode.Table, noBin.output.mode)
    }

    @Test fun stepDataChangedReloadsLatestOnly() {
        var s = opened()
        val r = s.apply(InspectorIntent.StepDataChanged("me"))
        assertEquals(PanelSide.Output, (r.effects.filterIsInstance<InspectorEffect.LoadData>().single()).request.side)
        assertEquals(1, s.apply(InspectorIntent.StepDataChanged("p1")).effects.filterIsInstance<InspectorEffect.LoadData>().size)
        assertTrue(s.apply(InspectorIntent.StepDataChanged("other")).effects.isEmpty())
        s = s.apply(InspectorIntent.SelectRun(PanelSide.Output, RunSelection.Specific("old"))).state
        assertTrue(s.apply(InspectorIntent.StepDataChanged("me")).effects.isEmpty())
    }

    @Test fun pageAndTabSelection() {
        val s = opened()
        assertEquals(InspectorPage.Output, s.apply(InspectorIntent.SelectPage(InspectorPage.Output)).state.page)
        assertEquals(ParamsTab.About, s.apply(InspectorIntent.SelectParamsTab(ParamsTab.About)).state.paramsTab)
        val runs = s.apply(InspectorIntent.RunsChanged(PanelSide.Output, listOf(RunChip("r1", 5)))).state
        assertEquals(1, runs.output.runs.size)
    }

    // ---- parameters ----

    @Test fun editStoresValuePersistsAndUndoRestores() {
        val s = opened()
        val r = s.apply(InspectorIntent.EditParam("url", ParamValue.Fixed("http://a")))
        assertEquals(ParamValue.Fixed("http://a"), r.state.values["url"])
        assertEquals(InspectorEffect.PersistParam("url", ParamValue.Fixed("http://a")), r.effects.single())
        assertTrue(r.state.canUndo)
        val e2 = r.state.apply(InspectorIntent.EditParam("url", ParamValue.Fixed("http://b")))
        val u = e2.state.apply(InspectorIntent.UndoParamEdit)
        assertEquals(ParamValue.Fixed("http://a"), u.state.values["url"])
        assertEquals(InspectorEffect.PersistParam("url", ParamValue.Fixed("http://a")), u.effects.single())
        assertNull(u.state.lastEdit)
        assertTrue(u.state.apply(InspectorIntent.UndoParamEdit).effects.isEmpty())
        val back = r.state.apply(InspectorIntent.UndoParamEdit)
        assertFalse(back.state.values.containsKey("url"))
        assertTrue(r.state.apply(InspectorIntent.EditParam("url", ParamValue.Fixed("http://a"))).effects.isEmpty())
        assertTrue(s.apply(InspectorIntent.EditParam("nokey", ParamValue.Fixed(1))).effects.isEmpty())
    }

    @Test fun validationIssuesFollowEdits() {
        val s = opened()
        assertTrue(s.issues.any { it.path == ParamPath.of("url") })
        val fixed = s.apply(InspectorIntent.EditParam("url", ParamValue.Fixed("http://a"))).state
        assertTrue(fixed.issues.none { it.path == ParamPath.of("url") })
        val withSyntax = InspectorReducer(exprSyntax = { if (it.contains("(")) "bad" else null })
        var t = InspectorState.initial("me")
        t = withSyntax.reduce(t, InspectorIntent.ParamsLoaded(schema, emptyMap())).state
        t = withSyntax.reduce(t, InspectorIntent.EditParam("url", ParamValue.Expr("{{ f( }}"))).state
        assertTrue(t.issues.any { it.path == ParamPath.of("url") })
        assertEquals(t.issues.size, t.issueCount)
    }

    @Test fun jumpToNextIssueCycles() {
        val s = opened().apply(InspectorIntent.EditParam("count", ParamValue.Fixed("abc"))).state
        assertTrue(s.issues.size >= 2)
        val a = s.apply(InspectorIntent.JumpToNextIssue).state
        assertEquals(s.issues[0].path, a.focusedField)
        val b = a.apply(InspectorIntent.JumpToNextIssue).state
        assertEquals(s.issues[1].path, b.focusedField)
        assertNull(InspectorState.initial("x").apply(InspectorIntent.JumpToNextIssue).state.focusedField)
    }

    @Test fun toggleToExpressionKeepsLiteralAndSchedulesPreview() {
        var s = opened().apply(InspectorIntent.EditParam("url", ParamValue.Fixed("http://a"))).state
        val r = s.apply(InspectorIntent.SwitchToExpression("url"))
        assertEquals(ParamValue.Expr("http://a"), r.state.values["url"])
        assertTrue(r.effects.any { it is InspectorEffect.EvaluatePreview && it.key == "url" })
        assertEquals(EditReason.Toggle, r.state.lastEdit!!.reason)
        val n = opened().apply(InspectorIntent.EditParam("count", ParamValue.Fixed(5.0))).state.apply(InspectorIntent.SwitchToExpression("count"))
        assertEquals(ParamValue.Expr("{{ 5 }}"), n.state.values["count"])
        val b = opened().apply(InspectorIntent.EditParam("flag", ParamValue.Fixed(true))).state.apply(InspectorIntent.SwitchToExpression("flag"))
        assertEquals(ParamValue.Expr("{{ true }}"), b.state.values["flag"])
        val j = opened().apply(InspectorIntent.EditParam("json", ParamValue.Fixed("hi"))).state.apply(InspectorIntent.SwitchToExpression("json"))
        assertEquals(ParamValue.Expr("{{ \"hi\" }}"), j.state.values["json"])
        assertTrue(r.state.apply(InspectorIntent.SwitchToExpression("url")).effects.isEmpty())
        val back = r.state.apply(InspectorIntent.SwitchToFixed("url", "http://evaluated"))
        assertEquals(ParamValue.Fixed("http://evaluated"), back.state.values["url"])
        assertTrue(back.state.previews.isEmpty())
        val cleared = r.state.apply(InspectorIntent.SwitchToFixed("url", null))
        assertFalse(cleared.state.values.containsKey("url"))
        val undone = back.state.apply(InspectorIntent.UndoParamEdit).state
        assertEquals(ParamValue.Expr("http://a"), undone.values["url"])
    }

    @Test fun toggleRulesForNeverAndOnly() {
        val s = opened()
        assertEquals(InspectorEffect.Notify(InspectorMessage.ExpressionNotAllowed), s.apply(InspectorIntent.SwitchToExpression("token")).effects.single())
        val o = s.apply(InspectorIntent.EditParam("only", ParamValue.Expr("{{ 1 }}"))).state
        assertEquals(InspectorEffect.Notify(InspectorMessage.FixedNotAllowed), o.apply(InspectorIntent.SwitchToFixed("only", "x")).effects.single())
        assertTrue(s.apply(InspectorIntent.SwitchToFixed("url", "x")).effects.isEmpty())
        assertTrue(s.apply(InspectorIntent.SwitchToExpression("zzz")).effects.isEmpty())
    }

    @Test fun previewResultsAreKeptOnlyForExpressions() {
        var s = opened().apply(InspectorIntent.EditParam("url", ParamValue.Expr("{{ 1 }}"))).state
        s = s.apply(InspectorIntent.PreviewComputed("url", PreviewState.Value("1", "number"))).state
        assertEquals(PreviewState.Value("1", "number"), s.previews["url"])
        val fixed = s.apply(InspectorIntent.EditParam("url", ParamValue.Fixed("x"))).state
        assertTrue(fixed.previews.isEmpty())
        assertTrue(fixed.apply(InspectorIntent.PreviewComputed("url", PreviewState.Value("1", "number"))).state.previews.isEmpty())
        val item = s.apply(InspectorIntent.SelectPreviewItem(3))
        assertEquals(3, item.state.previewItem)
        assertEquals(InspectorEffect.EvaluatePreview("url", "{{ 1 }}", 3), item.effects.single())
        assertEquals(0, s.apply(InspectorIntent.SelectPreviewItem(-4)).state.previewItem)
    }

    @Test fun echoOfPersistedValuesKeepsUndo() {
        val s = opened().apply(InspectorIntent.EditParam("url", ParamValue.Fixed("a"))).state
        val echo = s.apply(InspectorIntent.ParamsLoaded(schema, mapOf("url" to ParamValue.Fixed("a"))))
        assertEquals(s, echo.state); assertTrue(echo.effects.isEmpty())
        val external = s.apply(InspectorIntent.ParamsLoaded(schema, mapOf("url" to ParamValue.Fixed("zzz"))))
        assertNull(external.state.lastEdit)
        val creds = s.apply(InspectorIntent.ParamsLoaded(schema, mapOf("url" to ParamValue.Fixed("a")), setOf("slot")))
        assertEquals(setOf("slot"), creds.state.credentialsFilled); assertNotNull(creds.state.lastEdit)
        val withExpr = opened().apply(InspectorIntent.ParamsLoaded(schema, mapOf("url" to ParamValue.Expr("{{ 1 }}"))))
        assertTrue(withExpr.effects.any { it is InspectorEffect.EvaluatePreview })
    }

    @Test fun focusAndMapperTray() {
        val s = opened()
        assertEquals(ParamPath.of("url"), s.apply(InspectorIntent.FocusField(ParamPath.of("url"))).state.focusedField)
        assertTrue(s.apply(InspectorIntent.SetMapperVisible(true)).state.mapperVisible)
    }

    // ---- drag and insert ----

    @Test fun dragEligibilityExcludesNeverAndHidden() {
        val s = opened().apply(InspectorIntent.BeginDrag(src, 1f, 2f)).state
        val d = s.drag!!
        assertEquals("{{ \$json.user.name }}", d.reference)
        assertTrue(ParamPath.of("url") in d.eligibleTargets && ParamPath.of("fields") in d.eligibleTargets)
        assertFalse(ParamPath.of("token") in d.eligibleTargets)
        assertFalse(ParamPath.of("body") in d.eligibleTargets)
        assertFalse(ParamPath.of("hidden") in d.eligibleTargets)
        val shown = opened().apply(InspectorIntent.EditParam("flag", ParamValue.Fixed(true))).state.apply(InspectorIntent.BeginDrag(src)).state
        assertTrue(ParamPath.of("hidden") in shown.drag!!.eligibleTargets)
    }

    @Test fun dropOnFixedFieldFlipsToExpressionWithUndo() {
        var s = opened().apply(InspectorIntent.EditParam("url", ParamValue.Fixed("old"))).state
        s = s.apply(InspectorIntent.BeginDrag(src)).state
        s = s.apply(InspectorIntent.DragMoved(5f, 6f, ParamPath.of("url"))).state
        assertEquals(ParamPath.of("url"), s.drag!!.hoverTarget)
        val r = s.apply(InspectorIntent.DragDropped())
        assertNull(r.state.drag)
        assertEquals(ParamValue.Expr("{{ \$json.user.name }}"), r.state.values["url"])
        assertEquals(EditReason.Drop, r.state.lastEdit!!.reason)
        assertEquals(ParamValue.Fixed("old"), r.state.lastEdit!!.before)
        assertEquals(ParamValue.Fixed("old"), r.state.apply(InspectorIntent.UndoParamEdit).state.values["url"])
    }

    @Test fun dropOnExpressionFieldInsertsAtCaret() {
        var s = opened().apply(InspectorIntent.EditParam("url", ParamValue.Expr("Hi  there"))).state
        s = s.apply(InspectorIntent.BeginDrag(src)).state.apply(InspectorIntent.DragMoved(0f, 0f, ParamPath.of("url"))).state
        val r = s.apply(InspectorIntent.DragDropped(selStart = 3, selEnd = 3))
        assertEquals(ParamValue.Expr("Hi {{ \$json.user.name }} there"), r.state.values["url"])
        var t = opened().apply(InspectorIntent.EditParam("url", ParamValue.Expr("{{ 1 +  }}"))).state
        val i = t.apply(InspectorIntent.InsertReference(ParamPath.of("url"), src, 7, 7))
        assertEquals(ParamValue.Expr("{{ 1 + \$json.user.name }}"), i.state.values["url"])
    }

    @Test fun fixedTextWithCaretKeepsSurroundingText() {
        val s = opened().apply(InspectorIntent.EditParam("url", ParamValue.Fixed("Hello, !"))).state
        val r = s.apply(InspectorIntent.InsertReference(ParamPath.of("url"), src, 7, 7))
        assertEquals(ParamValue.Expr("Hello, {{ \$json.user.name }}!"), r.state.values["url"])
        val j = opened().apply(InspectorIntent.EditParam("json", ParamValue.Fixed("{\"a\": \"\"}"))).state
            .apply(InspectorIntent.InsertReference(ParamPath.of("json"), src, 7, 7))
        assertEquals(ParamValue.Fixed("{\"a\": \"{{ \$json.user.name }}\"}"), j.state.values["json"])
    }

    @Test fun tapToInsertEqualsDrag() {
        val base = opened()
        val tap = base.apply(InspectorIntent.InsertReference(ParamPath.of("count"), src)).state.values["count"]
        val drag = base.apply(InspectorIntent.BeginDrag(src)).state.apply(InspectorIntent.DragMoved(0f, 0f, ParamPath.of("count"))).state
            .apply(InspectorIntent.DragDropped()).state.values["count"]
        assertEquals(drag, tap)
        assertEquals(EditReason.Insert, base.apply(InspectorIntent.InsertReference(ParamPath.of("count"), src)).state.lastEdit!!.reason)
    }

    @Test fun dropOnNeverFieldIsRejected() {
        val s = opened()
        val r = s.apply(InspectorIntent.InsertReference(ParamPath.of("token"), src))
        assertEquals(InspectorEffect.Notify(InspectorMessage.DropRejected), r.effects.single())
        assertEquals(s.values, r.state.values)
        val drag = s.apply(InspectorIntent.BeginDrag(src)).state.apply(InspectorIntent.DragMoved(0f, 0f, ParamPath.of("token"))).state
        assertNull(drag.drag!!.hoverTarget)
        val drop = drag.apply(InspectorIntent.DragDropped())
        assertTrue(drop.effects.isEmpty()); assertNull(drop.state.drag)
        assertEquals(InspectorEffect.Notify(InspectorMessage.DropRejected), s.apply(InspectorIntent.InsertReference(ParamPath.of("nope"), src)).effects.single())
    }

    @Test fun dropOnContainerAsksTheWidget() {
        val r = opened().apply(InspectorIntent.InsertReference(ParamPath.of("fields"), src))
        val e = r.effects.single() as InspectorEffect.CreateRowFromDrop
        assertEquals("{{ \$json.user.name }}", e.reference); assertEquals(src, e.source)
        assertTrue(r.state.values.isEmpty())
    }

    @Test fun dragCancelAndEdgeDwell() {
        val s = opened().apply(InspectorIntent.BeginDrag(src)).state
        assertEquals(InspectorPage.Parameters, s.apply(InspectorIntent.DragEdgeDwell(InspectorPage.Parameters)).state.page)
        assertEquals(InspectorPage.Input, s.apply(InspectorIntent.DragEdgeDwell(InspectorPage.Input)).state.page)
        assertNull(s.apply(InspectorIntent.DragCancelled).state.drag)
        assertEquals(InspectorPage.Parameters, opened().apply(InspectorIntent.DragEdgeDwell(InspectorPage.Output)).state.page)
        assertEquals(opened(), opened().apply(InspectorIntent.DragMoved(1f, 1f, null)).state)
        assertEquals(opened(), opened().apply(InspectorIntent.DragDropped()).state)
    }

    // ---- pin ----

    @Test fun pinRules() {
        var s = opened()
        assertEquals(InspectorEffect.Notify(InspectorMessage.NothingToPin), s.apply(InspectorIntent.PinCurrent).effects.single())
        val trunc = loaded(s, PanelSide.Output, DataSummary(1000, 200, truncated = true))
        assertEquals(InspectorEffect.Notify(InspectorMessage.PinRefusedTruncated), trunc.apply(InspectorIntent.PinCurrent).effects.single())
        assertEquals(InspectorEffect.Notify(InspectorMessage.NothingToPin), loaded(s, PanelSide.Output, DataSummary(0, 0)).apply(InspectorIntent.PinCurrent).effects.single())
        s = loaded(s, PanelSide.Output)
        val p = s.apply(InspectorIntent.PinCurrent)
        assertEquals("me", (p.effects.single() as InspectorEffect.PinCurrent).request.stepId)
        val pinned = s.apply(InspectorIntent.PinChanged(PinInfo(10, 2048, 5))).state
        assertEquals(PinInfo(10, 2048, 5), pinned.pin)
        assertEquals(InspectorEffect.Unpin, pinned.apply(InspectorIntent.Unpin).effects.single())
        assertTrue(s.apply(InspectorIntent.Unpin).effects.isEmpty())
        val showingPin = pinned.apply(InspectorIntent.SelectRun(PanelSide.Output, RunSelection.Pinned)).state
        val unpinned = showingPin.apply(InspectorIntent.PinChanged(null))
        assertEquals(RunSelection.Latest, unpinned.state.output.run); assertEquals(1, unpinned.effects.size)
    }

    // ---- test step ----

    private val summary = TestSummary("test-1", InputSource.Manual, itemsIn = 2, itemsOut = 3, portCounts = mapOf(0 to 1, 1 to 2), durationMs = 12)

    @Test fun testEventSequenceRunningToFinished() {
        var s = opened()
        val start = s.apply(InspectorIntent.TestStepRequested())
        assertEquals(TestState.Running(listOf("me")), start.state.test)
        val req = (start.effects.single() as InspectorEffect.RunTest).request
        assertEquals("me", req.stepId); assertEquals(ParentPolicy.OnlyThis, req.policy)
        s = start.state
        // a second tap while running is ignored
        assertTrue(s.apply(InspectorIntent.TestStepRequested()).effects.isEmpty())
        assertEquals(s, s.apply(InspectorIntent.TestStepRequested()).state)
        s = s.apply(InspectorIntent.TestEventReceived(InspectorTestEvent.Progress(listOf("p1", "me")))).state
        assertEquals(TestState.Running(listOf("p1", "me")), s.test)
        s = s.apply(InspectorIntent.SelectRun(PanelSide.Output, RunSelection.Specific("old"))).state
        val done = s.apply(InspectorIntent.TestEventReceived(InspectorTestEvent.Finished("test-1", summary)))
        assertEquals(TestState.Finished("test-1", summary), done.state.test)
        assertEquals(RunSelection.Latest, done.state.output.run)
        assertEquals(2, done.effects.filterIsInstance<InspectorEffect.LoadData>().size)
        assertTrue(done.state.apply(InspectorIntent.TestEventReceived(InspectorTestEvent.Failed("late"))).effects.isEmpty())
    }

    @Test fun finishedTestShowsExactlyTheUsedInputAndPortCounts() {
        val s = opened().copy(output = opened().output.copy(ports = persistentListOf(PortChip(0, "true"), PortChip(1, "false"))))
        val done = s.apply(InspectorIntent.TestStepRequested()).state.apply(InspectorIntent.TestEventReceived(InspectorTestEvent.Finished("test-1", summary)))
        val inp = done.state.input
        assertTrue(inp.inbound); assertEquals("me", inp.sourceStepId); assertEquals(RunSelection.Specific("test-1"), inp.run)
        val req = done.effects.filterIsInstance<InspectorEffect.LoadData>().first { it.request.side == PanelSide.Input }.request
        assertTrue(req.inbound); assertEquals("me", req.stepId)
        assertEquals(listOf(1, 2), done.state.output.ports.map { it.itemCount })
        assertEquals(summary, done.state.used)
        assertTrue(done.effects.contains(InspectorEffect.LoadInputCatalog))
        // choosing a parent again leaves the used-input view
        val back = done.state.apply(InspectorIntent.SelectInputParent("p2")).state.input
        assertFalse(back.inbound); assertEquals("p2", back.sourceStepId)
    }

    @Test fun failedTestKeepsErrorAndReloadsPartialData() {
        val s = opened().apply(InspectorIntent.TestStepRequested()).state
        val f = s.apply(InspectorIntent.TestEventReceived(InspectorTestEvent.Failed("boom", 2, true, summary)))
        assertEquals(TestState.Failed("boom", 2, true, summary), f.state.test)
        assertTrue(f.effects.isNotEmpty())
        assertEquals(TestState.Running(listOf("me")), f.state.apply(InspectorIntent.TestStepRequested()).state.test)
    }

    @Test fun cancelTestReturnsToIdleAndIgnoresLateEvents() {
        val running = opened().apply(InspectorIntent.TestStepRequested()).state
        val c = running.apply(InspectorIntent.CancelTest)
        assertEquals(TestState.Idle, c.state.test)
        assertEquals(InspectorEffect.CancelTest, c.effects.single())
        assertTrue(c.state.apply(InspectorIntent.TestEventReceived(InspectorTestEvent.Finished("late"))).effects.isEmpty())
        assertEquals(TestState.Idle, c.state.apply(InspectorIntent.TestEventReceived(InspectorTestEvent.Finished("late"))).state.test)
        assertTrue(c.state.apply(InspectorIntent.CancelTest).effects.isEmpty())
        val waiting = running.apply(InspectorIntent.TestEventReceived(InspectorTestEvent.NeedsConfirm(listOf("sends an SMS")))).state
        assertEquals(TestState.Idle, waiting.apply(InspectorIntent.CancelTest).state.test)
    }

    @Test fun mainButtonNeverAsksForParentsAndAlwaysStartsOnlyThisStep() {
        // no parent data at all: still runs, with the manual input
        val r = opened().apply(InspectorIntent.TestStepRequested())
        val req = (r.effects.single() as InspectorEffect.RunTest).request
        assertEquals(ParentPolicy.OnlyThis, req.policy)
        assertNull(req.source)
        assertEquals(DEFAULT_MANUAL_INPUT, req.manualText)
    }

    @Test fun invalidManualInputBlocksTheTestAndPointsToTheInputPage() {
        var s = opened().apply(InspectorIntent.EditManualInput("[{\"a\": }]")).state
        assertNotNull(s.testInput.manualError)
        val r = s.apply(InspectorIntent.TestStepRequested())
        assertTrue(r.effects.isEmpty()); assertEquals(TestState.Idle, r.state.test); assertEquals(InspectorPage.Input, r.state.page)
        s = s.apply(InspectorIntent.EditManualInput("[{\"a\": 1}]")).state
        assertNull(s.testInput.manualError)
        assertEquals("[{\"a\": 1}]", ((s.apply(InspectorIntent.TestStepRequested()).effects.single()) as InspectorEffect.RunTest).request.manualText)
    }

    @Test fun pickedSourceIsSentAndCatalogDrivesTheDefaultText() {
        var s = opened().apply(InspectorIntent.InputCatalogLoaded(InputCatalogInfo(listOf(InputOption(InputSource.LastRun, 3)), "[{\"id\": 0}]"))).state
        assertEquals("[{\"id\": 0}]", s.testInput.manualText)
        s = s.apply(InspectorIntent.SelectInputSource(InputSource.Manual)).state
        assertEquals(InputSource.Manual, (s.apply(InspectorIntent.TestStepRequested()).effects.single() as InspectorEffect.RunTest).request.source)
        // typed text is never overwritten by a later catalog
        s = s.apply(InspectorIntent.EditManualInput("[{\"mine\": 1}]")).state
        s = s.apply(InspectorIntent.InputCatalogLoaded(InputCatalogInfo(emptyList(), "[{\"other\": 1}]"))).state
        assertEquals("[{\"mine\": 1}]", s.testInput.manualText)
        assertEquals("[{\"other\": 1}]", s.apply(InspectorIntent.FillSampleFromSchema).state.testInput.manualText)
        // a pick that vanished falls back to automatic
        val gone = opened().apply(InspectorIntent.InputCatalogLoaded(InputCatalogInfo(listOf(InputOption(InputSource.Pinned, 1))))).state
            .apply(InspectorIntent.SelectInputSource(InputSource.Pinned)).state
            .apply(InspectorIntent.InputCatalogLoaded(InputCatalogInfo(emptyList()))).state
        assertNull(gone.testInput.selection)
    }

    @Test fun pickingPinnedOrLastTestPointsTheInputPanelAtThoseItems() {
        val s = opened().apply(InspectorIntent.InputCatalogLoaded(InputCatalogInfo(listOf(
            InputOption(InputSource.Pinned, 2), InputOption(InputSource.LastTest, 1, "test-9"))))).state
        val pinned = s.apply(InspectorIntent.SelectInputSource(InputSource.Pinned))
        assertEquals(RunSelection.Pinned, pinned.state.input.run); assertEquals("p1", pinned.state.input.sourceStepId)
        val lastTest = s.apply(InspectorIntent.SelectInputSource(InputSource.LastTest))
        assertTrue(lastTest.state.input.inbound); assertEquals(RunSelection.Specific("test-9"), lastTest.state.input.run)
    }

    @Test fun sideEffectStepNeedsConfirmationBeforeItRuns() {
        val sms = InspectorState.initial("me", null, "device.sms")
        val first = reducer.reduce(sms, InspectorIntent.TestStepRequested())
        assertEquals(TestState.NeedsConfirm(listOf("sends an SMS")), first.state.test)
        assertTrue(first.effects.isEmpty())
        val ok = reducer.reduce(first.state, InspectorIntent.TestStepRequested(confirmed = true))
        assertEquals(TestState.Running(listOf("me")), ok.state.test)
        assertTrue((ok.effects.single() as InspectorEffect.RunTest).request.confirmed)
        // a plain data Step does not ask
        assertTrue(opened().apply(InspectorIntent.TestStepRequested()).effects.single() is InspectorEffect.RunTest)
    }

    @Test fun sparkAlwaysTestsWithItsSample() {
        val spark = InspectorState.initial("sp", null, "spark.sms")
        val r = reducer.reduce(spark, InspectorIntent.InputCatalogLoaded(InputCatalogInfo(emptyList(), "[{\"from\": \"+1\"}]")))
        assertEquals("[{\"from\": \"+1\"}]", r.state.testInput.manualText)
        val t = reducer.reduce(r.state, InspectorIntent.TestStepRequested())
        assertEquals("[{\"from\": \"+1\"}]", (t.effects.single() as InspectorEffect.RunTest).request.manualText)
        assertNull(TestRisks.of("spark.sms", emptyMap()))
    }

    @Test fun refusedEventShowsMessage() {
        val s = opened().apply(InspectorIntent.TestStepRequested()).state
        assertEquals(TestState.Refused("off"), s.apply(InspectorIntent.TestEventReceived(InspectorTestEvent.Refused("off"))).state.test)
    }

    // ---- replay ----

    @Test fun replayIsReadOnly() {
        val s = opened(replay = "run-7")
        assertTrue(s.readOnly); assertEquals(InspectorPage.Output, s.page)
        assertEquals(RunSelection.Specific("run-7"), s.output.run); assertEquals(RunSelection.Specific("run-7"), s.input.run)
        val blocked = listOf(
            InspectorIntent.EditParam("url", ParamValue.Fixed("x")), InspectorIntent.SwitchToExpression("url"), InspectorIntent.UndoParamEdit,
            InspectorIntent.FocusField(ParamPath.of("url")), InspectorIntent.SetMapperVisible(true), InspectorIntent.BeginDrag(src),
            InspectorIntent.InsertReference(ParamPath.of("url"), src), InspectorIntent.PinCurrent, InspectorIntent.Unpin,
            InspectorIntent.TestStepRequested(), InspectorIntent.JumpToNextIssue,
        )
        for (i in blocked) { val r = s.apply(i); assertEquals(i.toString(), s, r.state); assertTrue(r.effects.isEmpty()) }
        assertEquals(InspectorPage.Input, s.apply(InspectorIntent.SelectPage(InspectorPage.Input)).state.page)
        assertEquals(DataViewMode.Json, s.apply(InspectorIntent.SetViewMode(PanelSide.Output, DataViewMode.Json)).state.output.mode)
        val parent = s.apply(InspectorIntent.SelectInputParent("p2")).state
        assertEquals(RunSelection.Specific("run-7"), parent.input.run)
        assertFalse(s.canUndo)
    }

    @Test fun stateHelpers() {
        val s = persistentListOf(1)
        assertEquals(1, s.size)
        val st = InspectorState.initial("a")
        assertEquals(st.input, st.panel(PanelSide.Input)); assertEquals(0, st.issueCount)
    }
}
