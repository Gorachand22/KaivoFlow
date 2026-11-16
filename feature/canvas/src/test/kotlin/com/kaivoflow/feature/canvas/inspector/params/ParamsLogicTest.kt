package com.kaivoflow.feature.canvas.inspector.params

import com.kaivoflow.core.expressions.assist.MapSource
import com.kaivoflow.core.expressions.assist.SourceKind
import com.kaivoflow.core.model.ExprMode
import com.kaivoflow.core.model.ParamDef
import com.kaivoflow.core.model.ParamIssue
import com.kaivoflow.core.model.ParamKind
import com.kaivoflow.core.model.ParamPath
import com.kaivoflow.core.model.ParamSchema
import com.kaivoflow.core.model.ParamSeverity
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.SchemaPath
import com.kaivoflow.core.model.SchemaType
import com.kaivoflow.core.model.ShowWhen
import com.kaivoflow.core.model.ValueType
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamsLogicTest {
    private fun src(vararg p: String) = MapSource.json(SchemaPath.of(*p))
    private fun schema(vararg d: ParamDef) = ParamSchema(1, d.toList().toImmutableList())

    // ---- modes ----

    @Test fun pearlOnlyWhereExprAllowedAndKindTakesIt() {
        assertTrue(ParamModes.showsPearl(ParamDef("a", "A", ParamKind.Text())))
        assertFalse(ParamModes.showsPearl(ParamDef("a", "A", ParamKind.Text(), expr = ExprMode.Never)))
        assertFalse(ParamModes.showsPearl(ParamDef("a", "A", ParamKind.Text(), expr = ExprMode.Only)))
        assertFalse(ParamModes.showsPearl(ParamDef("a", "A", ParamKind.Secret)))
        assertFalse(ParamModes.showsPearl(ParamDef("a", "A", ParamKind.Code())))
        assertTrue(ParamModes.isExprOnly(ParamDef("a", "A", ParamKind.Text(), expr = ExprMode.Only)))
    }

    @Test fun toggleRoundTripKeepsTheLiteral() {
        val text = ParamDef("t", "T", ParamKind.Text())
        assertEquals(ParamValue.Expr("hello"), ParamModes.toExpression(text, ParamValue.Fixed("hello")))
        val num = ParamDef("n", "N", ParamKind.Number())
        assertEquals(ParamValue.Expr("{{ 5 }}"), ParamModes.toExpression(num, ParamValue.Fixed(5L)))
        assertEquals(ParamValue.Expr("{{ true }}"), ParamModes.toExpression(ParamDef("b", "B", ParamKind.Bool), ParamValue.Fixed(true)))
        assertEquals(ParamValue.Fixed(5L), ParamModes.toFixed(5L))
        assertNull(ParamModes.toFixed(null))
    }

    @Test fun undoTextDescribesToggleAndRowRemoval() {
        assertEquals("Switched to expression", UndoText.describe(ParamValue.Fixed("a"), ParamValue.Expr("{{ x }}")))
        assertEquals("Switched to fixed value", UndoText.describe(ParamValue.Expr("x"), ParamValue.Fixed("a")))
        assertEquals("Removed row", UndoText.describe(ParamValue.Fixed(listOf(mapOf("a" to 1), mapOf("a" to 2))), ParamValue.Fixed(listOf(mapOf("a" to 1)))))
        assertNull(UndoText.describe(ParamValue.Fixed("a"), ParamValue.Fixed("ab")))
    }

    // ---- number ----

    @Test fun numberParsing() {
        assertEquals(NumberInput.Parsed.Empty, NumberInput.parse("  ", false))
        assertEquals(NumberInput.Parsed.Ok(0.0), NumberInput.parse("0", false))
        assertEquals(NumberInput.Parsed.Ok(-2.5), NumberInput.parse("-2.5", false))
        assertTrue(NumberInput.parse("2.5", true) is NumberInput.Parsed.Invalid)
        assertTrue(NumberInput.parse("abc", false) is NumberInput.Parsed.Invalid)
        assertTrue(NumberInput.parse("-", false) is NumberInput.Parsed.Invalid)
        assertTrue(NumberInput.parse("1,5", false) is NumberInput.Parsed.Invalid) // dot only, locale independent
    }

    @Test fun numberStoredFormatAndStepper() {
        assertEquals(5L, NumberInput.toStored(5.0, false))
        assertEquals(2.5, NumberInput.toStored(2.5, false))
        assertEquals("5", NumberInput.format(5.0))
        assertEquals("", NumberInput.format(null))
        assertEquals(0.3, NumberInput.step(0.2, 1, 0.1, null, null, false), 1e-12)
        assertEquals(10.0, NumberInput.step(null, 1, 1.0, 10.0, 20.0, true), 0.0)
        assertEquals(20.0, NumberInput.step(20.0, 1, 5.0, 10.0, 20.0, true), 0.0)
        assertEquals(10.0, NumberInput.step(12.0, -1, 5.0, 10.0, 20.0, true), 0.0)
    }

    @Test fun sliderOnlyForSmallBoundedRanges() {
        assertTrue(NumberInput.sliderEligible(0.0, 100.0, 1.0, true))
        assertFalse(NumberInput.sliderEligible(0.0, 5000.0, 1.0, true))
        assertFalse(NumberInput.sliderEligible(null, 10.0, 1.0, true))
        assertTrue(NumberInput.sliderEligible(0.0, 1.0, 0.01, false))
        assertTrue(NumberInput.repeatDelayMs(0) > NumberInput.repeatDelayMs(30))
    }

    // ---- form model ----

    @Test fun formHonoursShowRulesAdvancedAndKeepsHiddenValues() {
        val s = schema(
            ParamDef("mode", "Mode", ParamKind.Text()),
            ParamDef("secretUrl", "Url", ParamKind.Text(), show = ShowWhen.Eq("mode", "auth")),
            ParamDef("timeout", "Timeout", ParamKind.Number(), advanced = true),
            ParamDef("retries", "Retries", ParamKind.Number(), advanced = true, default = ParamValue.Fixed(3L)),
        )
        val values = mapOf<String, ParamValue?>(
            "mode" to ParamValue.Fixed("plain"), "secretUrl" to ParamValue.Fixed("kept"),
            "timeout" to ParamValue.Fixed(10L), "retries" to ParamValue.Fixed(3L),
        )
        val m = FormModelBuilder.build(s, values)
        assertEquals(listOf("mode"), m.primary.map { it.def.key })
        assertEquals(listOf("timeout", "retries"), m.advanced.map { it.def.key })
        assertEquals(1, m.advancedChanged) // retries equals its default
        val shown = FormModelBuilder.build(s, values + ("mode" to ParamValue.Fixed("auth")))
        assertEquals(listOf("mode", "secretUrl"), shown.primary.map { it.def.key })
        assertEquals(ParamValue.Fixed("kept"), shown.primary[1].value)
    }

    @Test fun expressionControlledShowRuleShowsFieldWithBadge() {
        val s = schema(
            ParamDef("mode", "Mode", ParamKind.Text()),
            ParamDef("x", "X", ParamKind.Text(), show = ShowWhen.Eq("mode", "a")),
        )
        val m = FormModelBuilder.build(s, mapOf("mode" to ParamValue.Expr("{{ \$json.m }}")))
        assertEquals(1, m.primary.count { it.def.key == "x" && it.dependsOnExpr })
    }

    @Test fun issuesAreSplitIntoOwnAndNested() {
        val s = schema(ParamDef("rows", "Rows", ParamKind.KeyValueList), ParamDef("u", "U", ParamKind.Text()))
        val issues = listOf(
            ParamIssue(ParamPath.of("u"), ParamSeverity.Error, "x", "bad"),
            ParamIssue(ParamPath.of("rows").index(1).child("name"), ParamSeverity.Error, "y", "name"),
        )
        val m = FormModelBuilder.build(s, emptyMap(), issues)
        assertEquals(1, m.primary.first { it.def.key == "u" }.issues.size)
        assertEquals(1, m.primary.first { it.def.key == "rows" }.nestedIssueCount)
        assertEquals(0, m.primary.first { it.def.key == "rows" }.issues.size)
    }

    @Test fun searchFiltersByLabelAndHint() {
        val s = schema(ParamDef("a", "Alpha", ParamKind.Text(), hint = "first letter"), ParamDef("b", "Beta", ParamKind.Text()))
        assertEquals(listOf("a"), FormModelBuilder.build(s, emptyMap(), search = "FIRST").primary.map { it.def.key })
        assertFalse(FormModelBuilder.offersSearch(s))
    }

    // ---- assignments ----

    @Test fun assignmentsEncodeDecodeRoundTrip() {
        val rows = listOf(AssignmentRow("a", ValueType.Number, ParamValue.Fixed(3L)), AssignmentRow("b.c", ValueType.Text, ParamValue.Expr("{{ \$json.x }}")))
        val back = AssignmentsModel.decode(AssignmentsModel.encode(rows))
        assertEquals(rows, back)
        assertNull(AssignmentsModel.encode(emptyList()))
    }

    @Test fun assignmentDropCreatesNamedTypedRow() {
        val rows = AssignmentsModel.fromDrop(emptyList(), src("user", "age"), "{{ \$json.user.age }}", SchemaType.Number)
        assertEquals(AssignmentRow("age", ValueType.Number, ParamValue.Expr("{{ \$json.user.age }}")), rows.single())
        val again = AssignmentsModel.fromDrop(rows, src("user", "age"), "{{ \$json.user.age }}", SchemaType.String)
        assertEquals(listOf("age", "age_2"), again.map { it.name })
        assertEquals(ValueType.Text, again[1].type)
        // a disallowed inferred type falls back
        val limited = AssignmentsModel.fromDrop(emptyList(), src("n"), "{{ \$json.n }}", SchemaType.Number, setOf(ValueType.Text))
        assertEquals(ValueType.Text, limited.single().type)
    }

    @Test fun assignmentEdits() {
        var rows = listOf(AssignmentRow("a"), AssignmentRow("b"), AssignmentRow("c"))
        rows = AssignmentsModel.move(rows, 0, 2)
        assertEquals(listOf("b", "c", "a"), rows.map { it.name })
        rows = AssignmentsModel.duplicate(rows, 0)
        assertEquals(listOf("b", "b_copy", "c", "a"), rows.map { it.name })
        rows = AssignmentsModel.remove(rows, 1)
        assertEquals(3, rows.size)
        assertEquals(setOf(1), AssignmentsModel.invalidNames(listOf(AssignmentRow("x"), AssignmentRow("x"))))
        assertEquals(setOf(0), AssignmentsModel.invalidNames(listOf(AssignmentRow(" "))))
    }

    @Test fun keyValueKeepsMapShapeWhenPossible() {
        val stored = ParamValue.Fixed(linkedMapOf<String, Any?>("A" to "1", "B" to mapOf("kx" to "{{ \$json.b }}")))
        val rows = KeyValueModel.decode(stored)
        assertEquals(ParamValue.Expr("{{ \$json.b }}"), rows[1].value)
        assertEquals(stored, KeyValueModel.encode(rows, KeyValueModel.wasMap(stored)))
        val dup = KeyValueModel.encode(listOf(KeyValueRow("A", ParamValue.Fixed("1")), KeyValueRow("A", ParamValue.Fixed("2"))), true)
        assertTrue((dup as ParamValue.Fixed).value is List<*>)
    }

    @Test fun rowEdits() {
        assertEquals(listOf(1, 2), RowEdits.remove(listOf(1, 2, 3), 2))
        assertEquals(listOf(1, 2, 3), RowEdits.remove(listOf(1, 2, 3), 1, min = 3))
        assertEquals(listOf(1, 2, 3, 4), RowEdits.insertAt(listOf(1, 2, 4), 2, 3))
        assertEquals(listOf(1, 2), RowEdits.add(listOf(1, 2), 3, max = 2))
        assertEquals(listOf(2, 1, 3), RowEdits.move(listOf(1, 2, 3), 1, 0))
        assertEquals(listOf(1, 1, 2), RowEdits.duplicate(listOf(1, 2), 0))
    }

    // ---- conditions ----

    @Test fun operatorsFollowTheLeftType() {
        assertTrue(ConditionOps.forType(OperandType.Text).any { it.key == "contains" })
        assertTrue(ConditionOps.forType(OperandType.Number).any { it.key == "between" && it.args == 2 })
        assertEquals(listOf("isTrue", "isFalse"), ConditionOps.forType(OperandType.Boolean).map { it.key })
        assertEquals(listOf("exists", "notExists"), ConditionOps.forType(OperandType.Any).map { it.key })
        assertEquals(OperandType.List, ConditionOps.typeOf(SchemaType.Array))
    }

    @Test fun changingLeftTypeSwitchesOperatorAndClearsIncompatibleRight() {
        val r = Rule("c1", ParamValue.Expr("{{ \$json.a }}"), OperandType.Text, "contains", ParamValue.Fixed("x"))
        val toNum = ConditionsModel.changeType(r, OperandType.Number)
        assertEquals("equals", toNum.rule.op)
        assertNull(toNum.rule.right)
        assertTrue(toNum.clearedRight)
        val toAny = ConditionsModel.changeType(r, OperandType.Any)
        assertEquals("exists", toAny.rule.op)
        assertFalse(toAny.clearedRight) // no right value is needed any more, but nothing incompatible was dropped
        val keep = ConditionsModel.changeType(Rule("c", op = "is", right = ParamValue.Fixed("2020-01-01")), OperandType.Date)
        assertEquals(ParamValue.Fixed("2020-01-01"), keep.rule.right)
        assertFalse(keep.clearedRight)
    }

    @Test fun treeEditsAndDepthLimit() {
        var t = ConditionsModel.empty()
        t = ConditionsModel.addRule(t)
        t = ConditionsModel.addRule(t)
        assertEquals(listOf("c1", "c2"), t.root.children.map { it.id })
        t = ConditionsModel.toggleCombinator(t, t.root.id)
        assertEquals(Combinator.Or, t.root.combinator)
        t = ConditionsModel.addGroup(t)
        val g1 = t.root.children.filterIsInstance<ConditionGroup>().single()
        t = ConditionsModel.addGroup(t, g1.id)
        val g2 = (ConditionsModel.find(t, g1.id) as ConditionGroup).children.filterIsInstance<ConditionGroup>().single()
        assertEquals(3, ConditionsModel.depthOf(t, g2.id))
        val deeper = ConditionsModel.addGroup(t, g2.id)
        assertEquals(t, deeper) // depth limit
        assertEquals(ConditionsModel.empty(), ConditionsModel.addGroup(ConditionsModel.empty(), allowNested = false))
        t = ConditionsModel.remove(t, "c1")
        assertNull(ConditionsModel.find(t, "c1"))
        assertEquals(t, ConditionsModel.remove(t, t.root.id)) // the root cannot be removed
    }

    @Test fun dropFillsLeftAndSetsOperatorGroupFromSchema() {
        var t = ConditionsModel.addRule(ConditionsModel.empty())
        t = ConditionsModel.dropOnLeft(t, "c1", "{{ \$json.age }}", SchemaType.Number)
        val r = ConditionsModel.find(t, "c1") as Rule
        assertEquals(OperandType.Number, r.leftType)
        assertEquals("equals", r.op)
        assertEquals(ParamValue.Expr("{{ \$json.age }}"), r.left)
        val t2 = ConditionsModel.dropAsNewRule(ConditionsModel.empty(), "g0", "{{ \$json.ok }}", SchemaType.Bool)
        assertEquals("isTrue", (t2.root.children.single() as Rule).op)
    }

    @Test fun conditionsRoundTripAndMatchValidatorShape() {
        var t = ConditionsModel.empty()
        t = ConditionsModel.addRule(t, rule = Rule("", ParamValue.Expr("{{ \$json.a }}"), OperandType.Number, "between", ParamValue.Fixed(1L), ParamValue.Fixed(5L)))
        t = ConditionsModel.addGroup(t)
        t = ConditionsModel.toggleCombinator(t, t.root.id)
        val enc = ConditionsModel.encode(t) as ParamValue.Fixed
        val m = enc.value as Map<*, *>
        assertEquals("or", m["combinator"])
        val first = (m["conditions"] as List<*>)[0] as Map<*, *>
        assertEquals("between", first["op"])
        assertEquals(5L, first["right2"])
        assertEquals(mapOf("kx" to "{{ \$json.a }}"), first["left"])
        val back = ConditionsModel.decode(enc)
        assertEquals(t.root.combinator, back.root.combinator)
        assertEquals(ConditionsModel.ruleCount(t), ConditionsModel.ruleCount(back))
        assertNull(ConditionsModel.encode(ConditionsModel.empty()))
        assertTrue(ConditionsModel.summary(back).contains("between"))
    }
}

class WidgetModelsTest {
    private fun opts(n: Int) = (1..n).map { com.kaivoflow.core.model.ParamOption("v$it", "Option $it") }

    @Test fun choiceLayoutBy4_6_20() {
        assertEquals(ChoiceLayout.Segmented, ChoiceModel.layout(4, false))
        assertEquals(ChoiceLayout.Sheet, ChoiceModel.layout(6, false))
        assertEquals(ChoiceLayout.SearchSheet, ChoiceModel.layout(20, false))
        assertEquals(ChoiceLayout.SearchSheet, ChoiceModel.layout(3, true))
    }

    @Test fun staleChoiceAndMultiSelect() {
        assertTrue(ChoiceModel.isStale(opts(3), "gone"))
        assertFalse(ChoiceModel.isStale(opts(3), "v2"))
        assertFalse(ChoiceModel.isStale(opts(3), ""))
        assertEquals(listOf("v1"), ChoiceModel.filter(opts(12), "option 1").filter { it.value == "v1" }.map { it.value })
        assertEquals(listOf("a", "b"), ChoiceModel.toggle(listOf("a"), "b", 2))
        assertEquals(listOf("a", "b"), ChoiceModel.toggle(listOf("a", "b"), "c", 2))
        assertEquals(listOf("v1", "v2"), ChoiceModel.selectAll(opts(5), 2))
        assertNull(ChoiceModel.stored(emptyList()))
    }

    @Test fun jsonStatusAcceptsExpressionsAndReportsPosition() {
        assertTrue(JsonModel.status("{\"id\": \"{{ \$json.id }}\"}") is JsonModel.Status.Valid)
        assertTrue(JsonModel.status("{\"id\": {{ \$json.id }} }") is JsonModel.Status.Valid)
        val bad = JsonModel.status("{\n  \"a\": }") as JsonModel.Status.Invalid
        assertEquals(2, bad.line)
        assertTrue(JsonModel.status("[1]", com.kaivoflow.core.model.JsonShape.Object) is JsonModel.Status.WrongShape)
        assertEquals(JsonModel.Status.Empty, JsonModel.status("  "))
    }

    @Test fun jsonFormat() {
        assertEquals("{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": {}\n}", JsonModel.format("{\"a\":[1,2],\"b\":{}}"))
        assertNull(JsonModel.format("{\"a\": {{ x }} }"))
    }

    @Test fun codeLinesAndSymbols() {
        assertEquals(1, CodeModel.lineCount(""))
        assertEquals(3, CodeModel.lineCount("a\nb\nc"))
        assertEquals("f()" to 2, CodeModel.insertSymbol("f", 1, 1, "("))
        assertEquals("(ab)" to 4, CodeModel.insertSymbol("ab", 0, 2, "("))
        assertEquals("a\t" to 2, CodeModel.insertSymbol("a", 1, 1, "\t"))
    }

    @Test fun colorHex() {
        assertEquals("#AABBCC", ColorHex.normalize("abc"))
        assertEquals("#112233", ColorHex.normalize(" #112233 "))
        assertEquals("#11223380", ColorHex.normalize("11223380"))
        assertNull(ColorHex.normalize("11223380", allowAlpha = false))
        assertNull(ColorHex.normalize("zzz"))
        assertEquals(0xFF112233.toInt(), ColorHex.toArgb("#112233"))
        assertEquals("#112233", ColorHex.fromArgb(0xFF112233.toInt()))
        assertEquals(listOf("#112233", "#AABBCC"), ColorHex.pushRecent(listOf("#AABBCC", "#112233"), "112233"))
    }

    @Test fun dateTextRoundTrip() {
        val p = DateTimeModel.parse("2026-11-16T09:30:00+05:30", com.kaivoflow.core.model.DateTimeMode.DateTime)!!
        assertEquals(9, p.hour); assertEquals(30, p.minute)
        assertEquals("2026-11-16T09:30:00+05:30", DateTimeModel.compose(p.epochDay, p.hour, p.minute, com.kaivoflow.core.model.DateTimeMode.DateTime, p.zone))
        assertEquals("2026-11-16", DateTimeModel.compose(p.epochDay, 0, 0, com.kaivoflow.core.model.DateTimeMode.Date, p.zone))
        assertEquals("07:05", DateTimeModel.compose(0, 7, 5, com.kaivoflow.core.model.DateTimeMode.Time, p.zone))
        assertNull(DateTimeModel.parse("nope", com.kaivoflow.core.model.DateTimeMode.Date))
        assertNull(DateTimeModel.parse("{{ \$now }}", com.kaivoflow.core.model.DateTimeMode.DateTime))
        assertEquals(p.epochDay, DateTimeModel.epochDayOf(DateTimeModel.pickerMillis(p.epochDay)))
        assertEquals("{{ \$now.plus(3, \"days\") }}", DateTimeModel.relativeExpression(3, "days"))
        assertEquals("{{ \$now.minus(2, \"hours\") }}", DateTimeModel.relativeExpression(-2, "hours"))
    }

    @Test fun noticeBold() {
        assertEquals(listOf(NoticeMarkup.Piece("a ", false), NoticeMarkup.Piece("b", true), NoticeMarkup.Piece(" c", false)), NoticeMarkup.pieces("a **b** c"))
        assertEquals(listOf(NoticeMarkup.Piece("a ** b", false)), NoticeMarkup.pieces("a ** b"))
    }
}
