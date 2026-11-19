package com.kaivoflow.feature.canvas.inspector.logic

import com.kaivoflow.core.engine.stepdata.ItemPage
import com.kaivoflow.core.engine.stepdata.PinReader
import com.kaivoflow.core.engine.stepdata.PinSource
import com.kaivoflow.core.engine.stepdata.PinnedData
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.OnErrorPolicy
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.RetryPolicy
import com.kaivoflow.core.model.SchemaInference
import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.core.model.SchemaType
import com.kaivoflow.core.model.StepCategory
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.feature.canvas.inspector.panels.PanelDataSource
import com.kaivoflow.feature.canvas.inspector.state.DEFAULT_MANUAL_INPUT
import com.kaivoflow.feature.canvas.inspector.state.DataRequest
import com.kaivoflow.feature.canvas.inspector.state.InputOption
import com.kaivoflow.feature.canvas.inspector.state.InputSource
import com.kaivoflow.feature.canvas.inspector.state.InspectorPage
import com.kaivoflow.feature.canvas.inspector.state.PageRequest
import com.kaivoflow.feature.canvas.inspector.state.PanelSide
import com.kaivoflow.feature.canvas.inspector.state.ParentPolicy
import com.kaivoflow.feature.canvas.inspector.state.PreviewState
import com.kaivoflow.feature.canvas.inspector.state.RunSelection
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorLogicTest {
    // ---- layout ----
    @Test fun layoutChoice() {
        assertEquals(InspectorLayoutKind.PhonePortrait, InspectorLayouts.choose(360, 800))
        assertEquals(InspectorLayoutKind.PhoneLandscape, InspectorLayouts.choose(800, 360))
        assertEquals(InspectorLayoutKind.ThreePane, InspectorLayouts.choose(840, 1280))
        assertEquals(InspectorLayoutKind.ThreePane, InspectorLayouts.choose(1280, 800))
        assertEquals(InspectorLayoutKind.PhoneLandscape, InspectorLayouts.choose(500, 300))
    }

    @Test fun paneWidthsHonourMinimums() {
        val w = PaneWidths.initial(1280f)
        assertEquals(1280f, w.total, 0.01f)
        assertTrue(w.input >= 280f && w.params >= 280f && w.output >= 280f)
        val dragged = w.drag(0, -500f)
        assertEquals(280f, dragged.input, 0.01f)
        assertEquals(w.total, dragged.total, 0.01f)
        val right = w.drag(1, 5000f)
        assertEquals(280f, right.output, 0.01f)
        assertEquals(280f, w.drag(1, -5000f).params, 0.01f)
        assertEquals(w.total, right.total, 0.01f)
    }

    @Test fun narrowWindowCollapsesOutputThenInput() {
        val a = PaneWidths.initial(700f)
        assertEquals(0f, a.output, 0f); assertTrue(a.input >= 280f)
        val b = PaneWidths.initial(500f)
        assertEquals(0f, b.output, 0f); assertEquals(0f, b.input, 0f); assertEquals(500f, b.params, 0.01f)
        assertEquals(a, a.drag(1, 50f)) // collapsed pane has no divider
    }

    @Test fun rescaleKeepsTotalAndMin() {
        val r = PaneWidths.rescale(PaneWidths.initial(1280f), 900f)
        assertEquals(900f, r.total, 0.01f)
        assertTrue(r.input >= 280f && r.params >= 280f && r.output >= 280f)
    }

    @Test fun pageNavigation() {
        assertEquals(InspectorPage.Parameters, InspectorPages.next(InspectorPage.Input))
        assertEquals(InspectorPage.Output, InspectorPages.next(InspectorPage.Output))
        assertEquals(InspectorPage.Input, InspectorPages.previous(InspectorPage.Input))
        assertEquals(InspectorPage.Output, InspectorPages.afterSwipe(InspectorPage.Parameters, -200f, 100f))
        assertEquals(InspectorPage.Input, InspectorPages.afterSwipe(InspectorPage.Parameters, 200f, 100f))
        assertEquals(InspectorPage.Parameters, InspectorPages.afterSwipe(InspectorPage.Parameters, 40f, 100f))
    }

    // ---- behaviour ----
    private fun step() = StepNode(id = "s", type = "t", name = "Step")

    @Test fun onErrorMapping() {
        assertEquals(OnErrorChoice.Stop, StepBehaviour.onErrorOf(step()))
        assertEquals(OnErrorChoice.Stop, StepBehaviour.onErrorOf(step().copy(onError = OnErrorPolicy.Retry)))
        assertEquals(OnErrorPolicy.ErrorFlow, StepBehaviour.setOnError(step(), OnErrorChoice.ErrorOutput).onError)
        val legacy = StepBehaviour.setOnError(step().copy(onError = OnErrorPolicy.Retry), OnErrorChoice.Continue)
        assertEquals(OnErrorPolicy.Continue, legacy.onError)
        assertTrue(legacy.retry.enabled)
        assertTrue(StepBehaviour.retryOn(step().copy(onError = OnErrorPolicy.Retry)))
        val off = StepBehaviour.setRetryEnabled(step().copy(onError = OnErrorPolicy.Retry, retry = RetryPolicy(enabled = true)), false)
        assertEquals(OnErrorPolicy.Stop, off.onError); assertEquals(false, off.retry.enabled)
    }

    @Test fun retryFieldsAreClamped() {
        var s = StepBehaviour.setMaxAttempts(step(), 99)
        assertEquals(10, s.retry.maxAttempts)
        assertEquals(2, StepBehaviour.setMaxAttempts(s, 0).retry.maxAttempts)
        s = StepBehaviour.setWaitMs(s, 999_999)
        assertEquals(300_000L, s.retry.initialDelayMs)
        assertTrue(s.retry.maxDelayMs >= s.retry.initialDelayMs)
        assertEquals(5.0, StepBehaviour.setBackoff(s, 50.0).retry.backoffMultiplier, 0.0)
        assertEquals(1.0, StepBehaviour.setBackoff(s, -1.0).retry.backoffMultiplier, 0.0)
        assertEquals("Up to 3 tries, 1 s between the first two, growing x2 each time.", StepBehaviour.retrySummary(RetryPolicy()))
    }

    @Test fun timeoutNotesRenameDisable() {
        assertEquals(30_000L, StepBehaviour.setTimeoutSeconds(step(), 30).timeoutMs)
        assertEquals(30L, StepBehaviour.timeoutSeconds(StepBehaviour.setTimeoutSeconds(step(), 30)))
        assertNull(StepBehaviour.setTimeoutSeconds(step().copy(timeoutMs = 5), 0).timeoutMs)
        assertNull(StepBehaviour.setTimeoutSeconds(step().copy(timeoutMs = 5), null).timeoutMs)
        assertEquals(3_600_000L, StepBehaviour.setTimeoutSeconds(step(), 99_999).timeoutMs)
        assertEquals("hello", StepBehaviour.setNotes(step(), "hello").notes)
        assertEquals("Step", StepBehaviour.rename(step(), "  ").name)
        assertEquals("New", StepBehaviour.rename(step(), " New ").name)
        assertTrue(StepBehaviour.setDisabled(step(), true).disabled)
    }

    @Test fun aboutModel() {
        val spec = NodeSpec(id = "t", title = "Thing", category = StepCategory.Device, subtitle = "Does a thing", iconKey = "x", categoryPath = "")
        val schema = ParamSchema(1, persistentListOf(ParamDef("a", "Alpha", ParamKind.Bool, required = true, hint = "Pick")))
        val m = AboutModel.build(spec, schema)
        assertEquals("Does a thing", m.description)
        assertEquals("Device", m.categoryPath)
        assertEquals(listOf("Output"), m.outputs)
        assertEquals(listOf(AboutField("Alpha", "Pick", true)), m.fields)
        assertEquals("Unknown Step", AboutModel.build(null, ParamSchema.Empty).title)
    }

    // ---- test planner ----
    private val graph = mapOf("c" to listOf(ParentEdge("b")), "b" to listOf(ParentEdge("a")), "a" to emptyList())
    private fun plan(policy: ParentPolicy, have: Set<String>, step: String = "c") =
        TestPlanner.plan(step, { graph[it].orEmpty() }, { it in have }, policy)

    @Test fun defaultPolicyNeverRunsParents() {
        assertEquals(TestPlan.RunNow, plan(ParentPolicy.OnlyThis, emptySet()))
        assertEquals(TestPlan.RunNow, plan(ParentPolicy.OnlyThis, setOf("b")))
        assertEquals(TestPlan.RunNow, plan(ParentPolicy.OnlyThis, emptySet(), step = "a"))
        assertEquals(TestPlan.RunNow, plan(ParentPolicy.RunThem, setOf("b")))
    }

    // ---- test input ----
    private fun opt(s: InputSource, n: Int = 1) = InputOption(s, n)

    @Test fun inputOrderIsPinnedThenLastRunThenLastTestThenManual() {
        val all = listOf(opt(InputSource.LastTest), opt(InputSource.LastRun), opt(InputSource.Pinned))
        assertEquals(InputSource.Pinned, TestInputPolicy.auto(all, false))
        assertEquals(InputSource.LastRun, TestInputPolicy.auto(all.drop(2).take(0) + all.take(2), false))
        assertEquals(InputSource.LastTest, TestInputPolicy.auto(listOf(opt(InputSource.LastTest)), false))
        assertEquals(InputSource.Manual, TestInputPolicy.auto(emptyList(), false))
        assertEquals(InputSource.Manual, TestInputPolicy.auto(listOf(opt(InputSource.Pinned, 0)), false))
        assertEquals(InputSource.Sample, TestInputPolicy.auto(all, true))
    }

    @Test fun pickIsHonouredOnlyWhenAvailable() {
        val o = listOf(opt(InputSource.LastRun))
        assertEquals(InputSource.LastRun, TestInputPolicy.effective(InputSource.LastRun, o, false))
        assertEquals(InputSource.LastRun, TestInputPolicy.effective(InputSource.Pinned, o, false))
        assertEquals(InputSource.Manual, TestInputPolicy.effective(InputSource.Manual, o, false))
        assertEquals(InputSource.Sample, TestInputPolicy.effective(InputSource.Pinned, o, true))
    }

    @Test fun manualInputValidatesWithPosition() {
        assertTrue(ManualInput.parse("[{\"a\": 1}, {\"b\": 2}]") is ManualInput.Parsed.Items)
        assertEquals(1, (ManualInput.parse("{\"a\": 1}") as ManualInput.Parsed.Items).items.size)
        val bad = ManualInput.errorOf("[\n  {\"a\": }\n]")!!
        assertEquals(2, bad.line)
        assertTrue(ManualInput.errorOf("") != null && ManualInput.errorOf("")!!.message.startsWith("Nothing to test with"))
        assertEquals("Item 1 must be an object.", ManualInput.errorOf("[1]")!!.message)
    }

    @Test fun sampleFromSchemaFollowsInferredShape() {
        val items = listOf(Item(json = mapOf("name" to "Ada", "age" to 36L, "ok" to true, "tags" to listOf("x"), "who" to mapOf("id" to 7L))))
        val text = SampleFromSchema.generate(SchemaInference.infer(items.asSequence()))
        val parsed = (ManualInput.parse(text) as ManualInput.Parsed.Items).items.single().json
        assertEquals("Ada", parsed["name"]); assertEquals(36L, parsed["age"]); assertEquals(true, parsed["ok"])
        assertEquals(listOf("x"), parsed["tags"]); assertEquals(mapOf("id" to 7L), parsed["who"])
        assertEquals(DEFAULT_MANUAL_INPUT, SampleFromSchema.generate(null))
    }

    @Test fun sideEffectStepsAreFlagged() {
        assertEquals("sends an SMS", TestRisks.of("device.sms", emptyMap())!!.label)
        assertTrue(TestRisks.of("net.http", mapOf("method" to ParamValue.Fixed("POST"))) != null)
        assertTrue(TestRisks.of("net.http", mapOf("method" to ParamValue.Expr("{{ \$json.m }}"))) != null)
        assertNull(TestRisks.of("net.http", mapOf("method" to ParamValue.Fixed("get"))))
        assertNull(TestRisks.of("net.http", emptyMap()))
        assertNull(TestRisks.of("data.set", emptyMap()))
        assertNull(TestRisks.of("spark.sms", emptyMap()))
    }

    @Test fun plannerRunsMissingAncestorsFirst() {
        assertEquals(TestPlan.RunInOrder(listOf("a", "b", "c")), plan(ParentPolicy.RunThem, emptySet()))
        assertEquals(TestPlan.RunInOrder(listOf("b", "c")), plan(ParentPolicy.RunThem, setOf("a")))
    }

    @Test fun plannerSurvivesCycles() {
        val cyc = mapOf("a" to listOf(ParentEdge("b")), "b" to listOf(ParentEdge("a")))
        val p = TestPlanner.plan("a", { cyc[it].orEmpty() }, { false }, ParentPolicy.RunThem)
        assertTrue(p is TestPlan.RunInOrder && p.steps.last() == "a" && p.steps.size <= 3)
    }

    // ---- error surface ----
    @Test fun errorSpotFromMessage() {
        val values = mapOf<String, ParamValue?>("url" to ParamValue.Expr("{{ \$json.nme }}"), "n" to ParamValue.Fixed("x"))
        val spot = ErrorSurface.locate("Unknown field nme (line 1, column 10)", values)!!
        assertEquals("url", spot.key)
        assertEquals(9, spot.start)
        assertEquals(12, spot.end)
        assertEquals("Unknown field nme", spot.detail)
        val p = ErrorSurface.asPreview(spot)
        assertEquals(9, p.start)
    }

    @Test fun errorSpotNeedsAUniqueOrNamedField() {
        val two = mapOf<String, ParamValue?>("a" to ParamValue.Expr("{{ 1 }}"), "b" to ParamValue.Expr("{{ x }}"))
        assertNull(ErrorSurface.locate("bad (line 1, column 3)", two))
        assertEquals("b", ErrorSurface.locate("Parameter \"b\" failed: bad (line 1, column 4)", two)!!.key)
        assertNull(ErrorSurface.locate("no position here", two))
        assertNull(ErrorSurface.locate("bad (line 1, column 3)", mapOf("a" to ParamValue.Fixed("x"))))
        assertNull(ErrorSurface.locate("bad (line 5, column 1)", mapOf("a" to ParamValue.Expr("{{ 1 }}"))))
    }

    @Test fun multiLineOffsets() {
        assertEquals(5, ErrorSurface.offsetOf("ab\ncd\nef", 2, 3))
        assertEquals(6, ErrorSurface.offsetOf("ab\ncd\nef", 3, 1))
        assertNull(ErrorSurface.offsetOf("ab", 0, 1))
    }

    // ---- pinned source ----
    private class Empty : PanelDataSource {
        override suspend fun page(request: DataRequest) = ItemPage.Empty
        override suspend fun schema(request: DataRequest) = SchemaNode(SchemaType.Object)
    }

    @Test fun pinnedSourcePagesAndSearches() = runBlocking {
        val items = (0 until 7).map { Item(mapOf("name" to "n$it", "k" to if (it % 2 == 0) "even" else "odd")) }
        val pins = PinReader { _, id -> if (id == "s") PinnedData(listOf(items), 10, 1, PinSource.Json) else null }
        val src = PinAwarePanelSource(Empty(), pins, "f")
        fun req(offset: Int = 0, size: Int = 3, search: String = "", run: RunSelection = RunSelection.Pinned) =
            DataRequest(PanelSide.Output, "s", run, 0, PageRequest(offset, size), search)
        val first = src.page(req())
        assertEquals(3, first.items.size); assertEquals(7, first.totalStored); assertEquals(0, first.offset)
        val last = src.page(req(offset = 6))
        assertEquals(listOf(6), last.originalIndices)
        val even = src.page(req(search = "EVEN"))
        assertEquals(4, even.totalMatching); assertEquals(listOf(0, 2, 4), even.originalIndices)
        assertEquals(ItemPage.Empty, src.page(req(run = RunSelection.Latest)))
        assertEquals(0, src.page(DataRequest(PanelSide.Output, "other", RunSelection.Pinned, 0, PageRequest(), "")).items.size)
        assertTrue(src.schema(req()).fields.isNotEmpty())
    }

    // ---- credentials and messages ----
    @Test fun credentialFillMatchesVaultEntries() {
        val schema = ParamSchema(1, persistentListOf(ParamDef("credential", "Credential", ParamKind.CredentialPick()), ParamDef("x", "X", ParamKind.Bool)))
        val refs = listOf(com.kaivoflow.core.model.CredentialRef("id1", "api", "Main"))
        assertEquals(setOf("credential"), CredentialFill.filled(schema, mapOf("credential" to ParamValue.Fixed("id1")), refs))
        assertEquals(emptySet<String>(), CredentialFill.filled(schema, mapOf("credential" to ParamValue.Fixed("gone")), refs))
        assertEquals(emptySet<String>(), CredentialFill.filled(schema, mapOf("credential" to ParamValue.Fixed("id1")), null))
        assertEquals(emptySet<String>(), CredentialFill.filled(schema, emptyMap(), refs))
    }

    @Test fun everyMessageHasText() {
        com.kaivoflow.feature.canvas.inspector.state.InspectorMessage.entries.forEach { assertTrue(InspectorMessages.text(it).isNotBlank()) }
    }
}
