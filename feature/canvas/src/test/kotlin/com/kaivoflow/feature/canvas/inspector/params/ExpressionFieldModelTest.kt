package com.kaivoflow.feature.canvas.inspector.params

import com.kaivoflow.core.expressions.Span
import com.kaivoflow.core.expressions.assist.Completion
import com.kaivoflow.core.expressions.assist.CompletionKind
import com.kaivoflow.core.expressions.assist.DiagnosticSeverity
import com.kaivoflow.core.expressions.assist.ExprDiagnostic
import com.kaivoflow.feature.canvas.inspector.state.PreviewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionFieldModelTest {
    @Test fun chipsComeFromTheSpanParserWithStates() {
        val text = "Hi {{ \$json.name }} and {{ \$step(\"A\").x }} {{ upper(1) }} {{ 1 + }}"
        val chips = ExprFieldModel.chips(text)
        assertEquals(4, chips.size)
        assertEquals(ChipKind.Field, chips[0].kind)
        assertEquals("name", chips[0].label)
        assertEquals(ChipKind.Step, chips[1].kind)
        assertTrue(chips[1].label.startsWith("A"))
        assertEquals(ChipKind.Helper, chips[2].kind)
        assertEquals(ChipState.Error, chips[3].state)
        assertEquals(text.substring(chips[0].start, chips[0].end), "{{ \$json.name }}")
    }

    @Test fun diagnosticsColourChips() {
        val text = "{{ \$json.nme }}"
        val warn = ExprDiagnostic(Span(3, 13), DiagnosticSeverity.Warning, "field.absent", "absent")
        assertEquals(ChipState.Absent, ExprFieldModel.chips(text, listOf(warn)).single().state)
        val err = warn.copy(severity = DiagnosticSeverity.Error)
        assertEquals(ChipState.Error, ExprFieldModel.chips(text, listOf(err)).single().state)
    }

    @Test fun accessoryBarEdits() {
        assertEquals("ab{{  }}cd" to 5, ExprFieldModel.insertBraces("abcd", 2, 2).let { it.text to it.caret })
        val wrapped = ExprFieldModel.insertBraces("abcd", 1, 3)
        assertEquals("a{{ bc }}d", wrapped.text)
        assertEquals(wrapped.text.indexOf("}}") + 2, wrapped.caret)
        val q = ExprFieldModel.insertPair("x", 1, 1, '"', '"')
        assertEquals("x\"\"", q.text); assertEquals(2, q.caret)
        val br = ExprFieldModel.insertPair("x", 0, 1, '[', ']')
        assertEquals("[x]", br.text)
        // reversed selection and out of range are tolerated
        assertEquals("abXcd", ExprFieldModel.insertText("abcd", 2, 2, "X").text)
        assertEquals("X", ExprFieldModel.insertText("", 5, -3, "X").text)
    }

    @Test fun completionReplacesRangeAndPlacesCaret() {
        val c = Completion("name", "name", Span(9, 11), CompletionKind.Field, null, null, null, 4, 1)
        val e = ExprFieldModel.applyCompletion("{{ \$json.na }}", c)
        assertEquals("{{ \$json.name }}", e.text)
        val call = Completion("upper", "upper()", Span(3, 3), CompletionKind.Function, null, null, null, 6, 1)
        assertEquals(9, ExprFieldModel.applyCompletion("{{  }}", call).caret)
    }

    @Test fun backspaceAfterChipRemovesItWhole() {
        val text = "a {{ \$json.x }}"
        val e = ExprFieldModel.deleteChipBefore(text, text.length)
        assertNotNull(e)
        assertEquals("a ", e!!.text)
        assertEquals(2, e.caret)
        assertNull(ExprFieldModel.deleteChipBefore(text, text.length - 1))
        assertNull(ExprFieldModel.deleteChipBefore(text, 3, 5)) // a selection is a normal delete
    }

    @Test fun suggestionsApplyInsideBraces() {
        assertTrue(ExprFieldModel.suggestionsApply("{{ \$json.", 9))
        assertTrue(ExprFieldModel.suggestionsApply("hi {{", 5))
        assertFalse(ExprFieldModel.suggestionsApply("{{ a }} tail", 12))
        assertFalse(ExprFieldModel.suggestionsApply("plain", 3))
    }

    @Test fun previewLinePrefersErrors() {
        val err = ExprDiagnostic(Span(3, 6), DiagnosticSeverity.Error, "unknown", "Unknown field \"nme\"", didYouMean = "name")
        val line = ExprFieldModel.previewLine(PreviewState.Value("1", "number"), listOf(err))
        assertTrue(line is PreviewLine.Error)
        assertTrue((line as PreviewLine.Error).message.contains("name"))
        assertEquals(listOf(3 until 6), ExprFieldModel.errorRanges("{{ nme }}", line, listOf(err)))
        assertEquals(PreviewLine.Value("1", "number"), ExprFieldModel.previewLine(PreviewState.Value("1", "number")))
        assertTrue(ExprFieldModel.previewLine(PreviewState.Pending("no data yet")) is PreviewLine.Pending)
        assertEquals(PreviewLine.None, ExprFieldModel.previewLine(null))
    }

    @Test fun historyCoalescesTypingButNotAtomicEdits() {
        val h = EditHistory(coalesceMs = 500)
        h.record(EditHistory.Snapshot("", 0), 0, atomic = false)
        h.record(EditHistory.Snapshot("a", 1), 100, atomic = false)   // merged with the first
        h.record(EditHistory.Snapshot("ab", 2), 200, atomic = true)  // chip insert: own step
        val u1 = h.undo(EditHistory.Snapshot("ab{{ }}", 7))!!
        assertEquals("ab", u1.text)
        val u2 = h.undo(EditHistory.Snapshot("ab", 2))!!
        assertEquals("", u2.text)
        assertFalse(h.canUndo)
        assertEquals("ab", h.redo(EditHistory.Snapshot("", 0))!!.text)
    }
}
