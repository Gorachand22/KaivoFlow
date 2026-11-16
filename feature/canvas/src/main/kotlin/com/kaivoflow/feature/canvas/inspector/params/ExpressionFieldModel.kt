package com.kaivoflow.feature.canvas.inspector.params

import com.kaivoflow.core.expressions.assist.Completion
import com.kaivoflow.core.expressions.assist.DiagnosticSeverity
import com.kaivoflow.core.expressions.assist.ExprDiagnostic
import com.kaivoflow.core.expressions.assist.ExprSpan
import com.kaivoflow.core.expressions.assist.ReferenceBuilder
import com.kaivoflow.core.expressions.assist.SourceKind
import com.kaivoflow.core.expressions.assist.TextEdit
import com.kaivoflow.core.model.SchemaPath
import com.kaivoflow.feature.canvas.inspector.state.PreviewState

// Pure logic of the expression field (spec P2 3.6 to 3.8). The text stays the single source of truth;
// chips are drawn over it, never stored apart from it.

enum class ChipKind { Field, Step, Helper }

/** Gold when the reference resolves, amber when the field is absent in upstream data, rose on an error. */
enum class ChipState { Resolved, Absent, Error }

data class ChipModel(
    /** Absolute range of the whole `{{ ... }}`. */
    val start: Int,
    val end: Int,
    /** Range of the text between the braces, for selecting it to edit the path. */
    val bodyStart: Int,
    val bodyEnd: Int,
    val label: String,
    val kind: ChipKind,
    val state: ChipState,
    /** Full text for TalkBack. */
    val description: String,
)

/** What the line under an expression field says. */
sealed interface PreviewLine {
    data object None : PreviewLine
    data class Value(val rendered: String, val type: String) : PreviewLine
    data class Pending(val message: String) : PreviewLine
    data class Error(val message: String, val start: Int?, val end: Int?) : PreviewLine
}

object ExprFieldModel {
    const val OPEN = "{{ "
    const val CLOSE = " }}"

    fun chips(text: String, diagnostics: List<ExprDiagnostic> = emptyList()): List<ChipModel> =
        ReferenceBuilder.parseSpans(text).mapNotNull { s ->
            when (s) {
                is ExprSpan.Literal -> null
                is ExprSpan.Reference -> {
                    val kind = if (s.source.isImmediateParent) ChipKind.Field else ChipKind.Step
                    val path = pathLabel(s.source.kind, s.source.path)
                    val label = if (kind == ChipKind.Step) "${s.source.stepName} · $path" else path
                    ChipModel(s.span.start, s.span.end, s.bodySpan.start, s.bodySpan.end, label, kind, stateOf(s.span.start, s.span.end, null, diagnostics), "Reference ${s.raw}")
                }
                is ExprSpan.Code -> {
                    val label = if (s.raw.length > 24) s.raw.take(23) + "…" else s.raw.ifEmpty { "empty" }
                    ChipModel(s.span.start, s.span.end, s.bodySpan.start, s.bodySpan.end, label, ChipKind.Helper, stateOf(s.span.start, s.span.end, s.error, diagnostics), "Expression ${s.raw}" + (s.error?.let { ", error: $it" } ?: ""))
                }
            }
        }

    private fun pathLabel(kind: SourceKind, path: SchemaPath): String {
        val head = when (kind) {
            SourceKind.Json, SourceKind.Item -> ""
            SourceKind.Binary -> "binary"
            SourceKind.Run -> "run"
            SourceKind.Vars -> "vars"
        }
        val p = path.toString()
        return when {
            head.isEmpty() && p.isEmpty() -> "item"
            head.isEmpty() -> p
            p.isEmpty() -> head
            else -> "$head.$p"
        }
    }

    private fun stateOf(start: Int, end: Int, parseError: String?, diagnostics: List<ExprDiagnostic>): ChipState {
        if (parseError != null) return ChipState.Error
        val hits = diagnostics.filter { it.span.start < end && it.span.end > start }
        return when {
            hits.any { it.severity == DiagnosticSeverity.Error } -> ChipState.Error
            hits.any { it.severity == DiagnosticSeverity.Warning } -> ChipState.Absent
            else -> ChipState.Resolved
        }
    }

    // ---- accessory bar ----

    private fun clamp(text: String, a: Int, b: Int): Pair<Int, Int> =
        minOf(a, b).coerceIn(0, text.length) to maxOf(a, b).coerceIn(0, text.length)

    /** `{{ }}` with the caret inside; a selection is wrapped. */
    fun insertBraces(text: String, selStart: Int, selEnd: Int): TextEdit {
        val (a, b) = clamp(text, selStart, selEnd)
        val inner = text.substring(a, b)
        val ins = OPEN + inner + CLOSE
        val caret = if (inner.isEmpty()) a + OPEN.length else a + ins.length
        return TextEdit(text.substring(0, a) + ins + text.substring(b), caret)
    }

    /** A matching pair such as `""`, `()` or `[]`; a selection is wrapped, otherwise the caret lands between. */
    fun insertPair(text: String, selStart: Int, selEnd: Int, open: Char, close: Char): TextEdit {
        val (a, b) = clamp(text, selStart, selEnd)
        val inner = text.substring(a, b)
        val ins = "$open$inner$close"
        val caret = if (inner.isEmpty()) a + 1 else a + ins.length
        return TextEdit(text.substring(0, a) + ins + text.substring(b), caret)
    }

    fun insertText(text: String, selStart: Int, selEnd: Int, insert: String): TextEdit {
        val (a, b) = clamp(text, selStart, selEnd)
        return TextEdit(text.substring(0, a) + insert + text.substring(b), a + insert.length)
    }

    /** Applies a suggestion: replaces its range; the caret goes to `replace.start + caretOffset`. */
    fun applyCompletion(text: String, c: Completion): TextEdit {
        val a = c.replace.start.coerceIn(0, text.length)
        val b = c.replace.end.coerceIn(a, text.length)
        val caret = (a + c.caretOffset).coerceIn(0, a + c.insert.length)
        return TextEdit(text.substring(0, a) + c.insert + text.substring(b), caret)
    }

    /** Backspace right after a chip deletes the whole chip; returns null when the caret is not right after one. */
    fun deleteChipBefore(text: String, caret: Int, selEnd: Int = caret): TextEdit? {
        if (caret != selEnd) return null
        val chip = ReferenceBuilder.parseSpans(text).firstOrNull { it !is ExprSpan.Literal && it.span.end == caret } ?: return null
        return TextEdit(text.removeRange(chip.span.start, chip.span.end), chip.span.start)
    }

    /** True when the caret is inside `{{ }}` or right after a lone `{{`: the suggestion strip is worth showing. */
    fun suggestionsApply(text: String, caret: Int): Boolean {
        val c = caret.coerceIn(0, text.length)
        val before = text.substring(0, c)
        val lastOpen = before.lastIndexOf("{{")
        if (lastOpen < 0) return false
        val closedBetween = before.indexOf("}}", lastOpen + 2) >= 0
        return !closedBetween
    }

    /** Selects the path text of a chip so it can be edited in place (tap on a chip). */
    fun selectionForChip(chip: ChipModel): Pair<Int, Int> = chip.bodyStart to chip.bodyEnd

    // ---- preview line ----

    fun previewLine(state: PreviewState?, diagnostics: List<ExprDiagnostic> = emptyList()): PreviewLine {
        if (state is PreviewState.Error) return PreviewLine.Error(state.message, state.start, state.end)
        val firstError = diagnostics.firstOrNull { it.severity == DiagnosticSeverity.Error }
        if (firstError != null) {
            val msg = firstError.message + (firstError.didYouMean?.let { " Did you mean \"$it\"?" } ?: "")
            return PreviewLine.Error(msg, firstError.span.start, firstError.span.end)
        }
        return when (state) {
            is PreviewState.Value -> PreviewLine.Value(state.rendered, state.type)
            is PreviewState.Pending -> PreviewLine.Pending(state.message)
            else -> PreviewLine.None
        }
    }

    /** Ranges to draw in rose inside the field. */
    fun errorRanges(text: String, line: PreviewLine, diagnostics: List<ExprDiagnostic>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (d in diagnostics) if (d.severity == DiagnosticSeverity.Error && d.span.end > d.span.start) out += d.span.start until d.span.end
        if (out.isEmpty() && line is PreviewLine.Error && line.start != null && line.end != null && line.end > line.start) out += line.start until line.end
        return out.map { it.first.coerceIn(0, text.length) until it.last.plus(1).coerceIn(0, text.length) }.filter { !it.isEmpty() }
    }
}

/** Undo and redo for one expression field. Typing within [coalesceMs] merges into one step; chip edits and insertions are their own steps. */
class EditHistory(private val limit: Int = 100, private val coalesceMs: Long = 800) {
    data class Snapshot(val text: String, val caret: Int)

    private val undoStack = ArrayList<Snapshot>()
    private val redoStack = ArrayList<Snapshot>()
    private var lastAt = Long.MIN_VALUE
    private var lastAtomic = true

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Call before a change is applied. [atomic] edits (chip delete, insert, completion) never merge with typing. */
    fun record(before: Snapshot, nowMs: Long, atomic: Boolean) {
        val merge = !atomic && !lastAtomic && nowMs - lastAt <= coalesceMs && undoStack.isNotEmpty()
        if (!merge) {
            undoStack += before
            if (undoStack.size > limit) undoStack.removeAt(0)
        }
        redoStack.clear()
        lastAt = nowMs
        lastAtomic = atomic
    }

    fun undo(current: Snapshot): Snapshot? {
        val s = undoStack.removeLastOrNull() ?: return null
        redoStack += current
        lastAtomic = true
        return s
    }

    fun redo(current: Snapshot): Snapshot? {
        val s = redoStack.removeLastOrNull() ?: return null
        undoStack += current
        lastAtomic = true
        return s
    }
}
