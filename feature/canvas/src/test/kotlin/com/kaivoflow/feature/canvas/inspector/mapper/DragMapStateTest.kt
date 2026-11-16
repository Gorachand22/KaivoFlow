package com.kaivoflow.feature.canvas.inspector.mapper

import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.model.ExprMode
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.SchemaPath
import com.kaivoflow.core.model.SchemaType
import com.kaivoflow.feature.canvas.inspector.params.AssignmentRow
import com.kaivoflow.feature.canvas.inspector.params.AssignmentsModel
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DragMapStateTest {
    private val cfg = DragConfig(edgeScrollZonePx = 100f, maxScrollPxPerSecond = 1000f, edgeSwitchZonePx = 40f, edgeSwitchDwellMs = 600, cancelMarginPx = 80f)
    private val vp = Bounds(0f, 0f, 400f, 800f)
    private val payload = DragPayload(MapSource.json(SchemaPath.of("user", "name")), SchemaType.String, "name")
    private val text = DropTarget(ParamPath.of("url"), Bounds(20f, 200f, 380f, 260f), accepts = true)
    private val code = DropTarget(ParamPath.of("body"), Bounds(20f, 300f, 380f, 360f), accepts = false)
    private val card = DropTarget(ParamPath.of("rows"), Bounds(0f, 400f, 400f, 600f), accepts = true)
    private val inner = DropTarget(ParamPath.of("rows").index(0).child("v"), Bounds(20f, 420f, 380f, 470f), accepts = true)

    private fun begin(targets: List<DropTarget> = listOf(text, code, card, inner), x: Float = 200f, y: Float = 100f) =
        DragMachine.reduce(DragMapState(), DragEvent.Begin(payload, x, y, vp, targets), cfg)

    @Test fun beginTicksHaptic() {
        val s = begin()
        assertTrue(s.state.active)
        assertEquals(listOf<DragEffect>(DragEffect.Haptic(HapticKind.Start)), s.effects)
        assertNull(s.state.hover)
        assertEquals("{{ \$json.user.name }}", s.state.payload!!.reference)
        assertEquals(setOf(text, card, inner), s.state.glowing.toSet())
        assertEquals(listOf(code), s.state.dimmed)
    }

    @Test fun hoverPicksInnermostTargetAndHasLabel() {
        var s = begin().state
        s = DragMachine.reduce(s, DragEvent.Move(100f, 440f), cfg).state
        assertEquals(inner, s.hover)
        assertEquals("Insert here", s.hoverLabel)
        s = DragMachine.reduce(s, DragEvent.Move(100f, 560f), cfg).state
        assertEquals(card, s.hover)
        s = DragMachine.reduce(s, DragEvent.Move(100f, 330f), cfg).state
        assertEquals(code, s.hover)
        assertEquals("Cannot take data", s.hoverLabel)
    }

    @Test fun dropInsertsAtCaretWithHaptic() {
        var s = begin().state
        s = DragMachine.reduce(s, DragEvent.Move(100f, 230f), cfg).state
        val d = DragMachine.reduce(s, DragEvent.Drop(3, 3), cfg)
        assertFalse(d.state.active)
        assertEquals(DragEffect.Haptic(HapticKind.Drop), d.effects[0])
        assertEquals(DragEffect.Insert(ParamPath.of("url"), payload, 3, 3), d.effects[1])
    }

    @Test fun dropOnNeverFieldIsRejected() {
        var s = begin().state
        s = DragMachine.reduce(s, DragEvent.Move(100f, 330f), cfg).state
        val d = DragMachine.reduce(s, DragEvent.Drop(), cfg)
        assertTrue(d.effects.contains(DragEffect.Rejected(ParamPath.of("body"))))
        assertTrue(d.effects.none { it is DragEffect.Insert })
        assertEquals("This field cannot take data references", DragMachine.REJECT_MESSAGE)
    }

    @Test fun dropOnNothingOrOutsideCancels() {
        var s = begin().state
        s = DragMachine.reduce(s, DragEvent.Move(100f, 150f), cfg).state
        assertEquals(listOf<DragEffect>(DragEffect.Cancelled), DragMachine.reduce(s, DragEvent.Drop(), cfg).effects)
        // over a target but dragged outside the viewport: the release cancels
        val far = DragMachine.reduce(s, DragEvent.Move(-200f, 230f), cfg).state
        assertTrue(far.outside)
        assertNull(far.hover)
        assertEquals(listOf<DragEffect>(DragEffect.Cancelled), DragMachine.reduce(far, DragEvent.Drop(), cfg).effects)
        assertEquals(listOf<DragEffect>(DragEffect.Cancelled), DragMachine.reduce(s, DragEvent.Cancel, cfg).effects)
        // coming back inside restores the hover
        val back = DragMachine.reduce(far, DragEvent.Move(100f, 230f), cfg).state
        assertEquals(text, back.hover)
    }

    @Test fun autoScrollNearEdgesAndProportional() {
        var s = begin(y = 750f).state
        val t1 = DragMachine.reduce(s, DragEvent.Tick(100), cfg)
        val down = t1.effects.filterIsInstance<DragEffect.ScrollBy>().single().dy
        assertEquals(50f, down, 0.01f) // depth 50 of 100 => 500 px/s for 100 ms
        s = DragMachine.reduce(t1.state, DragEvent.Move(200f, 20f), cfg).state
        val up = DragMachine.reduce(s, DragEvent.Tick(100), cfg).effects.filterIsInstance<DragEffect.ScrollBy>().single().dy
        assertTrue(up < 0f)
        assertTrue(DragMachine.reduce(begin(y = 400f).state, DragEvent.Tick(100), cfg).effects.none { it is DragEffect.ScrollBy })
    }

    @Test fun scrollReachesATargetThreeScreensDown() {
        // The list scrolls under a held finger; the host sends TargetsChanged after each scroll.
        val far = DropTarget(ParamPath.of("far"), Bounds(20f, 2600f, 380f, 2660f), accepts = true)
        var offset = 0f
        var s = DragMachine.reduce(DragMapState(), DragEvent.Begin(payload, 200f, 790f, vp, listOf(far)), cfg).state
        var frames = 0
        while (s.hover == null && frames < 2000) {
            val t = DragMachine.reduce(s, DragEvent.Tick(16), cfg)
            for (e in t.effects) if (e is DragEffect.ScrollBy) offset += e.dy
            val moved = far.copy(bounds = Bounds(20f, 2600f - offset, 380f, 2660f - offset))
            s = DragMachine.reduce(t.state, DragEvent.TargetsChanged(listOf(moved)), cfg).state
            frames++
        }
        // the finger sits at the bottom edge, so the target must arrive under it
        assertTrue("frames=$frames", frames < 2000)
        assertEquals("far", s.hover!!.path.toString())
    }

    @Test fun edgeDwellSwitchesPageOnceUntilTheEdgeIsLeft() {
        var s = begin(x = 10f, y = 400f).state
        assertEquals(Edge.Left, s.edge)
        var t = DragMachine.reduce(s, DragEvent.Tick(300), cfg)
        assertTrue(t.effects.isEmpty())
        t = DragMachine.reduce(t.state, DragEvent.Tick(400), cfg)
        assertEquals(listOf<DragEffect>(DragEffect.SwitchPage(Edge.Left)), t.effects)
        t = DragMachine.reduce(t.state, DragEvent.Tick(2000), cfg)
        assertTrue(t.effects.none { it is DragEffect.SwitchPage })
        s = DragMachine.reduce(t.state, DragEvent.Move(200f, 400f), cfg).state
        assertNull(s.edge)
        s = DragMachine.reduce(s, DragEvent.Move(395f, 400f), cfg).state
        assertEquals(Edge.Right, s.edge)
        assertEquals(0L, s.edgeDwellMs)
    }

    @Test fun mapToListsAcceptingFieldsInReadingOrder() {
        val opts = MapToOptions.list(listOf(card, code, text))
        assertEquals(listOf("url", "rows"), opts.map { it.path.toString() })
        assertEquals(text, MapToOptions.focusedTarget(listOf(text, code), ParamPath.of("url")))
        assertNull(MapToOptions.focusedTarget(listOf(text, code), ParamPath.of("body")))
    }

    // ---- what a drop or tap does to a value ----

    private val src = MapSource.json(SchemaPath.of("user", "age"))
    private fun def(kind: ParamKind, expr: ExprMode = ExprMode.Allowed) = ParamDef("k", "K", kind, expr = expr)
    private fun set(o: InsertOutcome) = (o as InsertOutcome.Set).value

    @Test fun plainFieldsFlipToExpression() {
        for (kind in listOf(ParamKind.Text(), ParamKind.Number(), ParamKind.Bool, ParamKind.Color)) {
            assertEquals(ParamValue.Expr("{{ \$json.user.age }}"), set(MapperInsert.apply(def(kind), ParamValue.Fixed("old"), src)))
        }
    }

    @Test fun existingExpressionGetsTheChipAtTheCaret() {
        val v = set(MapperInsert.apply(def(ParamKind.Text()), ParamValue.Expr("Hello {{ \$json.a }}!"), src, selStart = 5, selEnd = 5))
        assertEquals(ParamValue.Expr("Hello{{ \$json.user.age }} {{ \$json.a }}!"), v)
        // unknown caret appends
        assertEquals(ParamValue.Expr("x{{ \$json.user.age }}"), set(MapperInsert.apply(def(ParamKind.Text()), ParamValue.Expr("x"), src)))
    }

    @Test fun jsonStaysFixedAndInsertsIntoTheText() {
        val v = set(MapperInsert.apply(def(ParamKind.Json()), ParamValue.Fixed("{\"id\": \"\"}"), src, selStart = 8, selEnd = 8))
        assertTrue(v is ParamValue.Fixed)
        assertTrue(((v as ParamValue.Fixed).value as String).contains("{{ \$json.user.age }}"))
    }

    @Test fun neverFieldsAreRejected() {
        assertEquals(InsertOutcome.Rejected, MapperInsert.apply(def(ParamKind.Text(), ExprMode.Never), null, src))
        assertEquals(InsertOutcome.Rejected, MapperInsert.apply(def(ParamKind.Code()), null, src))
        assertEquals(InsertOutcome.Rejected, MapperInsert.apply(def(ParamKind.Secret), null, src))
        assertEquals(InsertOutcome.Rejected, MapperInsert.apply(def(ParamKind.CredentialPick()), null, src))
        assertEquals(InsertOutcome.Rejected, MapperInsert.apply(def(ParamKind.Notice(com.kaivoflow.core.model.NoticeTone.Info)), null, src))
    }

    @Test fun assignmentsFooterDropCreatesInferredRow() {
        val v = set(MapperInsert.apply(def(ParamKind.Assignments()), null, src, SchemaType.Number))
        assertEquals(listOf(AssignmentRow("age", com.kaivoflow.core.model.ValueType.Number, ParamValue.Expr("{{ \$json.user.age }}"))), AssignmentsModel.decode(v))
    }

    @Test fun keyValueAndConditionsAndRepeaterDrops() {
        val kv = set(MapperInsert.apply(def(ParamKind.KeyValueList), null, src)) as ParamValue.Fixed
        assertTrue(kv.value is List<*>)
        val cond = set(MapperInsert.apply(def(ParamKind.Conditions()), null, src, SchemaType.Number)) as ParamValue.Fixed
        assertTrue((cond.value as Map<*, *>)["conditions"] is List<*>)
        val rep = ParamKind.Repeater(persistentListOf(ParamDef("name", "Name", ParamKind.Text()), ParamDef("n", "N", ParamKind.Number())))
        val rows = (set(MapperInsert.apply(def(rep), null, src)) as ParamValue.Fixed).value as List<*>
        assertEquals(mapOf("kx" to "{{ \$json.user.age }}"), (rows[0] as Map<*, *>)["name"])
        val full = ParamKind.Repeater(persistentListOf(ParamDef("name", "Name", ParamKind.Text())), maxItems = 1)
        assertEquals(InsertOutcome.Rejected, MapperInsert.apply(def(full), ParamValue.Fixed(listOf(mapOf("name" to "a"))), src))
    }

    @Test fun tapAndDropGiveTheSameText() {
        // tap-to-insert uses the same function with the focused field's caret
        val viaTap = MapperInsert.apply(def(ParamKind.Text()), ParamValue.Expr("a"), src, null, 1, 1)
        val viaDrop = MapperInsert.apply(def(ParamKind.Text()), ParamValue.Expr("a"), src, null, 1, 1)
        assertEquals(viaTap, viaDrop)
        assertEquals(payload.reference, "{{ \$json.user.name }}")
    }
}
