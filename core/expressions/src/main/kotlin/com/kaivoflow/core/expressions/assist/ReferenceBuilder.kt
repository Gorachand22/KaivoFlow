package com.kaivoflow.core.expressions.assist

import com.kaivoflow.core.expressions.Call
import com.kaivoflow.core.expressions.Expr
import com.kaivoflow.core.expressions.Identifier
import com.kaivoflow.core.expressions.Index
import com.kaivoflow.core.expressions.Literal
import com.kaivoflow.core.expressions.Member
import com.kaivoflow.core.expressions.Segment
import com.kaivoflow.core.expressions.Span
import com.kaivoflow.core.expressions.parse
import com.kaivoflow.core.model.SchemaPath

/** What a mapping reference points at. */
enum class SourceKind {
    /** A field of the item's data: `$json.field`. */
    Json,

    /** An attachment of the item: `$binary.photo.fileName`. */
    Binary,

    /** The item as a whole (same data as [Json], other alias): `$item`. */
    Item,

    /** A Run value: `$run.id`. */
    Run,

    /** A Flow variable: `$vars.name`. */
    Vars,
}

/** Whether [ReferenceBuilder.build] wraps the reference in `{{ }}` or returns the bare body. */
enum class ReferenceTarget {
    /** `{{ $json.a }}`: for fixed fields, text fields, and JSON strings. */
    Template,

    /** `$json.a`: for the inside of an existing `{{ }}` or an expression-only field. */
    Expression,
}

/**
 * A place in data that the user dragged or tapped.
 *
 * [stepName] is what `$step("...")` uses; [stepId] is the stable id for the UI (empty when the
 * source was parsed back out of text, where only the name is known). [path] uses
 * [SchemaPath.ELEMENT] for "an element of the array" (written as `[0]`) and `[n]` segments for a
 * concrete index. [itemIndex] picks the item of another Step's output (`$step("A")[itemIndex]`).
 * Limitation shared with [SchemaPath]: a field literally named `[]` or `[3]` cannot be told apart
 * from an array segment.
 */
data class MapSource(
    val stepId: String,
    val isImmediateParent: Boolean,
    val path: SchemaPath,
    val kind: SourceKind,
    val stepName: String = stepId,
    val itemIndex: Int = 0,
) {
    companion object {
        fun json(path: SchemaPath = SchemaPath(emptyList())) =
            MapSource("", true, path, SourceKind.Json)
    }
}

/** A piece of stored expression text, for chip rendering. [span] is absolute in the text. */
sealed interface ExprSpan {
    val span: Span

    /** Plain text outside `{{ }}`. */
    data class Literal(override val span: Span, val text: String) : ExprSpan

    /** `{{ $json.a.b }}`: a pure reference that can be drawn as a chip and re-targeted. */
    data class Reference(
        override val span: Span,
        val bodySpan: Span,
        val source: MapSource,
        /** The body as written, trimmed; kept so unedited text is never rewritten. */
        val raw: String,
    ) : ExprSpan

    /** Anything else inside `{{ }}`: helper calls, arithmetic, errors. [error] is set when it cannot be parsed. */
    data class Code(
        override val span: Span,
        val bodySpan: Span,
        val raw: String,
        val error: String? = null,
    ) : ExprSpan
}

/** Result of inserting a reference into text. [caret] is the offset just after the inserted text. */
data class TextEdit(val text: String, val caret: Int)

/**
 * Canonical expression text for a dragged or tapped path, and the reverse: reading stored text
 * into spans. Everything the user sees as a chip is one `{{ }}` segment; the text stays the single
 * source of truth.
 *
 * Forms: the selected immediate parent gives `$json.field.sub[0]`; any other Step gives
 * `$step("Name")[0].field`, indexing the paired item (lineage already narrows `$step("Name")` to
 * the items the current item came from). Keys that are not simple identifiers use bracket
 * quoting: `$json["first name"]`.
 */
object ReferenceBuilder {

    fun build(source: MapSource, target: ReferenceTarget = ReferenceTarget.Template): String {
        val body = body(source)
        return if (target == ReferenceTarget.Template) "{{ $body }}" else body
    }

    /** Just the expression, no braces. */
    fun body(source: MapSource): String {
        val sb = StringBuilder()
        when (source.kind) {
            SourceKind.Run -> sb.append("\$run")
            SourceKind.Vars -> sb.append("\$vars")
            SourceKind.Json -> root(sb, source, "\$json")
            SourceKind.Item -> root(sb, source, "\$item")
            SourceKind.Binary -> {
                if (source.isImmediateParent) sb.append("\$binary")
                else {
                    stepRoot(sb, source)
                    sb.append("[").append(quote(BINARY_KEY)).append("]")
                }
            }
        }
        for (segment in source.path.segments) appendSegment(sb, segment)
        return sb.toString()
    }

    private fun root(sb: StringBuilder, source: MapSource, immediate: String) {
        if (source.isImmediateParent) sb.append(immediate) else stepRoot(sb, source)
    }

    private fun stepRoot(sb: StringBuilder, source: MapSource) {
        sb.append("\$step(").append(quote(source.stepName)).append(")[").append(source.itemIndex.coerceAtLeast(0)).append("]")
    }

    private fun appendSegment(sb: StringBuilder, segment: String) {
        when {
            segment == SchemaPath.ELEMENT -> sb.append("[0]")
            isIndexSegment(segment) -> sb.append(segment)
            isSimpleKey(segment) -> sb.append('.').append(segment)
            else -> sb.append('[').append(quote(segment)).append(']')
        }
    }

    /** Text to put after a receiver to read [key]: `.key` or `["odd key"]`. */
    fun keyAccess(key: String): String = if (isSimpleKey(key)) ".$key" else "[${quote(key)}]"

    /** True for names that can follow a dot: letters, digits and underscores, not starting with a digit, not a keyword. */
    fun isSimpleKey(key: String): Boolean =
        key.isNotEmpty() && SIMPLE_KEY.matches(key) && key !in KEYWORDS

    /** Double-quoted string literal with the escapes the expression lexer reads. */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ' || c == ' ' || c == ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ---- reading text ----

    /** Splits stored text into literal text, reference chips and raw code. Never throws. */
    fun parseSpans(text: String): List<ExprSpan> {
        val result = parse(text)
        val out = ArrayList<ExprSpan>(result.segments.size)
        for (seg in result.segments) {
            when (seg) {
                is Segment.Text -> out += ExprSpan.Literal(seg.span, seg.text)
                is Segment.Code -> {
                    val raw = text.substring(seg.bodySpan.start.coerceIn(0, text.length), seg.bodySpan.end.coerceIn(0, text.length)).trim()
                    val ast = seg.ast
                    val source = if (seg.error == null && ast != null) sourceOf(ast) else null
                    out += when {
                        source != null -> ExprSpan.Reference(seg.span, seg.bodySpan, source, raw)
                        else -> ExprSpan.Code(seg.span, seg.bodySpan, raw, seg.error?.detail)
                    }
                }
            }
        }
        return out
    }

    /** Every pure reference in [text], in order. */
    fun referencesIn(text: String): List<MapSource> =
        parseSpans(text).filterIsInstance<ExprSpan.Reference>().map { it.source }

    /** Parses a bare expression body (not wrapped in braces) as a reference, or null when it is anything else. */
    fun parseBody(body: String): MapSource? {
        val seg = parse("{{ $body }}").segments.singleOrNull() as? Segment.Code ?: return null
        val ast = seg.ast ?: return null
        return if (seg.error == null) sourceOf(ast) else null
    }

    /**
     * Inserts [source] into [text] replacing the selection [selStart, selEnd). Inside an existing
     * `{{ }}` the bare expression goes in; elsewhere a new `{{ }}` chip is added.
     */
    fun insertInto(text: String, selStart: Int, selEnd: Int, source: MapSource): TextEdit {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val inside = parse(text).segments.filterIsInstance<Segment.Code>().any {
            a >= it.bodySpan.start && b <= it.bodySpan.end
        }
        val insert = build(source, if (inside) ReferenceTarget.Expression else ReferenceTarget.Template)
        return TextEdit(text.substring(0, a) + insert + text.substring(b), a + insert.length)
    }

    // ---- AST to source ----

    private fun sourceOf(ast: Expr): MapSource? {
        val ops = ArrayList<Expr>()
        var cur = ast
        while (true) {
            cur = when (cur) {
                is Member -> { if (cur.optional) return null; ops += cur; cur.target }
                is Index -> { if (cur.optional) return null; ops += cur; cur.target }
                else -> break
            }
        }
        ops.reverse()
        var pos = 0
        var kind: SourceKind
        var immediate = true
        var stepName = ""
        var itemIndex = 0
        when {
            cur is Identifier && cur.name == "\$json" -> kind = SourceKind.Json
            cur is Identifier && cur.name == "\$item" -> kind = SourceKind.Item
            cur is Identifier && cur.name == "\$binary" -> kind = SourceKind.Binary
            cur is Identifier && cur.name == "\$vars" -> kind = SourceKind.Vars
            cur is Identifier && cur.name == "\$run" -> kind = SourceKind.Run
            cur is Call && !cur.optional && cur.callee is Identifier && (cur.callee as Identifier).name == "\$step" &&
                cur.args.size == 1 && (cur.args[0] as? Literal)?.value is String -> {
                immediate = false
                kind = SourceKind.Json
                stepName = (cur.args[0] as Literal).value as String
                // optional item selector: [n] or .first
                val sel = ops.getOrNull(pos)
                if (sel is Index) {
                    val n = ((sel.index as? Literal)?.value as? Number)?.toDouble()
                    if (n != null && n >= 0 && n == Math.floor(n) && n < Int.MAX_VALUE) { itemIndex = n.toInt(); pos++ }
                } else if (sel is Member && sel.name == "first") pos++
                val bin = ops.getOrNull(pos)
                if (bin is Index && (bin.index as? Literal)?.value == BINARY_KEY) { kind = SourceKind.Binary; pos++ }
            }
            else -> return null
        }
        val segments = ArrayList<String>()
        while (pos < ops.size) {
            when (val op = ops[pos++]) {
                is Member -> segments += op.name
                is Index -> {
                    val v = (op.index as? Literal)?.value
                    when {
                        v is String -> segments += v
                        v is Number && v.toDouble() >= 0 && v.toDouble() == Math.floor(v.toDouble()) && v.toDouble() < Int.MAX_VALUE ->
                            segments += "[${v.toInt()}]"
                        else -> return null
                    }
                }
                else -> return null
            }
        }
        if (kind == SourceKind.Vars || kind == SourceKind.Run) immediate = true
        return MapSource(
            stepId = "",
            isImmediateParent = immediate,
            path = SchemaPath(segments),
            kind = kind,
            stepName = stepName,
            itemIndex = itemIndex,
        )
    }

    private fun isIndexSegment(s: String): Boolean =
        s.length >= 3 && s.first() == '[' && s.last() == ']' && s.substring(1, s.length - 1).all { it.isDigit() }

    /** Key of the attachments object in a Step item (matches `SchemaInference.BINARY_KEY`). */
    const val BINARY_KEY = "\$binary"

    private val SIMPLE_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val KEYWORDS = setOf("true", "false", "null", "undefined")

}
