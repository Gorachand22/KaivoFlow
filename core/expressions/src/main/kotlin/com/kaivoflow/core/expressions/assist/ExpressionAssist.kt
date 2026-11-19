package com.kaivoflow.core.expressions.assist

import com.kaivoflow.core.engine.ScopeBuilder
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.expressions.Arrow
import com.kaivoflow.core.expressions.ArrayLiteral
import com.kaivoflow.core.expressions.Binary
import com.kaivoflow.core.expressions.Call
import com.kaivoflow.core.expressions.Conditional
import com.kaivoflow.core.expressions.ErrorKind
import com.kaivoflow.core.expressions.Expr
import com.kaivoflow.core.expressions.ExpressionException
import com.kaivoflow.core.expressions.ExpressionLimits
import com.kaivoflow.core.expressions.ExpressionPreview
import com.kaivoflow.core.expressions.Identifier
import com.kaivoflow.core.expressions.Index
import com.kaivoflow.core.expressions.KaivoExpressionEvaluator
import com.kaivoflow.core.expressions.Library
import com.kaivoflow.core.expressions.Literal
import com.kaivoflow.core.expressions.Member
import com.kaivoflow.core.expressions.ObjectLiteral
import com.kaivoflow.core.expressions.ParseMode
import com.kaivoflow.core.expressions.Segment
import com.kaivoflow.core.expressions.Span
import com.kaivoflow.core.expressions.TemplateLiteral
import com.kaivoflow.core.expressions.Unary
import com.kaivoflow.core.expressions.parse
import com.kaivoflow.core.expressions.suggest
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.SchemaInference
import com.kaivoflow.core.model.SchemaNode
import com.kaivoflow.core.model.SchemaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import com.kaivoflow.core.expressions.analyze as analyzeAst

/** An upstream Step as the editor knows it. [name] is what `$step("...")` uses. */
data class UpstreamStep(
    val stepId: String,
    val name: String,
    val schema: SchemaNode?,
    val isImmediate: Boolean = false,
)

/**
 * What the expression under edit may refer to. [input] is the schema of `$json` (the selected
 * input), [upstream] the Steps whose data exists before this one, [otherSteps] names of Flow Steps
 * that are not upstream (referencing them is flagged), [varNames] the Flow variables.
 * When both [upstream] and [otherSteps] are empty, Step references are not checked.
 */
data class SchemaScope(
    val input: SchemaNode? = null,
    val upstream: List<UpstreamStep> = emptyList(),
    val otherSteps: Set<String> = emptySet(),
    val varNames: Set<String> = emptySet(),
) {
    internal val checksSteps: Boolean get() = upstream.isNotEmpty() || otherSteps.isNotEmpty()
    companion object { val Empty = SchemaScope() }
}

enum class DiagnosticSeverity { Error, Warning }

/** A problem in expression text. [span] is absolute in the checked text; [didYouMean] is a replacement for the flagged name. */
data class ExprDiagnostic(
    val span: Span,
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
    val didYouMean: String? = null,
)

enum class PendingReason { NoUpstreamData, NotUpstream }

sealed interface PreviewResult {
    data class Value(val value: Any?, val type: SchemaType, val rendered: String) : PreviewResult
    data class Pending(val reason: PendingReason, val stepName: String? = null) : PreviewResult
    data class Error(val error: ExpressionException, val span: Span?, val didYouMean: String?) : PreviewResult
}

/**
 * The data a live preview evaluates against; the same pieces a Run feeds to `ScopeBuilder`.
 * [stepOutputs] is keyed by Step id and [stepNames] maps id to name. [upstreamStepNames], when
 * set, names the Steps whose data may be read; others give [PendingReason.NotUpstream].
 */
data class PreviewContext(
    val stepName: String,
    val itemIndex: Int,
    val inputs: List<Item>,
    val stepOutputs: Map<String, List<Item>> = emptyMap(),
    val stepNames: Map<String, String> = emptyMap(),
    val vars: Map<String, Any?> = emptyMap(),
    val runId: String = "preview",
    val timezone: String = "UTC",
    val clock: () -> Long = { System.currentTimeMillis() },
    val upstreamStepNames: Set<String>? = null,
    /** Flow facts for `$run.flowId` and `$run.flowName`; screens outside a node (agents, variables) set them. */
    val flowId: String = "",
    val flowName: String = "",
)

enum class CompletionKind { Variable, Field, Function, Method, Step, Namespace, Keyword }

/**
 * One suggestion. Applying it replaces [replace] in the edited text with [insert]; the caret
 * then goes to `replace.start + caretOffset`. A higher [score] ranks first.
 */
data class Completion(
    val label: String,
    val insert: String,
    val replace: Span,
    val kind: CompletionKind,
    val type: SchemaType?,
    val sample: String?,
    val help: String?,
    val caretOffset: Int,
    val score: Int,
)

/** Static facts about expression text. */
data class ExprAnalysis(
    val steps: Set<String>,
    val variables: Set<String>,
    val paths: List<String>,
    val functions: Set<String>,
    val spans: List<ExprSpan>,
    val errors: List<ExprDiagnostic>,
)

interface ExpressionAssist {
    fun preview(text: String, context: PreviewContext, mode: ParseMode = ParseMode.Template): PreviewResult
    fun analyze(text: String): ExprAnalysis
    fun diagnostics(text: String, scope: SchemaScope): List<ExprDiagnostic>
    fun complete(text: String, cursor: Int, scope: SchemaScope, mode: ParseMode = ParseMode.Template): List<Completion>
}

/** The implementation over [KaivoExpressionEvaluator]: previews use the same evaluator and scope as a Run, with tighter budgets. */
class KaivoExpressionAssist(
    private val previewLimits: ExpressionLimits = ExpressionLimits.Preview,
    private val maxCompletions: Int = 60,
) : ExpressionAssist {

    private val evaluator = KaivoExpressionEvaluator(previewLimits)

    // ---- preview ----

    override fun preview(text: String, context: PreviewContext, mode: ParseMode): PreviewResult {
        val parsed = parse(text, mode, previewLimits)
        parsed.diagnostics.firstOrNull()?.let { return errorOf(it) }
        pendingOf(text, parsed.codeSegments.mapNotNull { it.ast }, context)?.let { return it }
        val scope = buildScope(context)
        return when (val r = evaluator.preview(text, scope, mode)) {
            is ExpressionPreview.Value -> PreviewResult.Value(r.value, schemaTypeOf(r.value), render(r.value))
            is ExpressionPreview.Failure -> errorOf(r.error)
        }
    }

    /** Off-thread preview; cancelling the caller discards the result (the evaluator is bounded by its own timeout). */
    suspend fun previewAsync(text: String, context: PreviewContext, mode: ParseMode = ParseMode.Template): PreviewResult =
        coroutineScope { async(Dispatchers.Default) { preview(text, context, mode) }.await() }

    /** The scope a Run would build for the same data. */
    fun buildScope(c: PreviewContext): Map<String, Any?> =
        ScopeBuilder(c.vars, c.runId, c.stepOutputs, c.clock, c.timezone, c.stepNames, flowId = c.flowId, flowName = c.flowName)
            .build(c.inputs, c.itemIndex, c.stepName)

    private fun errorOf(e: ExpressionException): PreviewResult.Error =
        PreviewResult.Error(e, e.position?.let { Span(it.offset, it.offset + maxOf(1, it.length)) }, e.suggestion)

    private fun pendingOf(text: String, asts: List<Expr>, c: PreviewContext): PreviewResult.Pending? {
        if (asts.isEmpty()) return null
        val ran = c.stepOutputs.keys.map { c.stepNames[it] ?: it }.toSet()
        for (ast in asts) {
            val refs = analyzeAst(ast)
            for (name in refs.steps) {
                if (c.upstreamStepNames != null && name !in c.upstreamStepNames) return PreviewResult.Pending(PendingReason.NotUpstream, name)
                if (name !in ran) return PreviewResult.Pending(PendingReason.NoUpstreamData, name)
            }
            if (c.inputs.isEmpty() && refs.variables.any { it in INPUT_VARS }) return PreviewResult.Pending(PendingReason.NoUpstreamData)
        }
        return null
    }

    // ---- analyze ----

    override fun analyze(text: String): ExprAnalysis {
        val parsed = parse(text)
        val steps = LinkedHashSet<String>(); val vars = LinkedHashSet<String>(); val paths = ArrayList<String>(); val fns = LinkedHashSet<String>()
        for (seg in parsed.codeSegments) {
            val ast = seg.ast ?: continue
            val r = analyzeAst(ast)
            steps += r.steps; vars += r.variables; paths += r.paths; fns += r.functions
        }
        return ExprAnalysis(steps, vars, paths, fns, ReferenceBuilder.parseSpans(text), syntaxDiagnostics(parsed.codeSegments))
    }

    private fun syntaxDiagnostics(segments: List<Segment.Code>): List<ExprDiagnostic> = segments.mapNotNull { seg ->
        val e = seg.error ?: return@mapNotNull null
        val pos = e.position
        val span = if (pos != null) Span(pos.offset, pos.offset + maxOf(1, pos.length)) else seg.span
        ExprDiagnostic(span, DiagnosticSeverity.Error, "syntax", e.detail, e.suggestion)
    }

    // ---- diagnostics ----

    override fun diagnostics(text: String, scope: SchemaScope): List<ExprDiagnostic> {
        val parsed = parse(text)
        val out = ArrayList<ExprDiagnostic>(syntaxDiagnostics(parsed.codeSegments))
        val checker = Checker(scope, out)
        for (seg in parsed.codeSegments) seg.ast?.let { checker.walk(it, emptySet(), true) }
        return out.distinctBy { Triple(it.span, it.code, it.message) }.sortedBy { it.span.start }
    }

    private inner class Checker(val scope: SchemaScope, val out: MutableList<ExprDiagnostic>) {
        val stepNames: List<String> = scope.upstream.map { it.name } + scope.otherSteps
        val resolver = Resolver(scope, out)

        fun walk(e: Expr, bound: Set<String>, chainTop: Boolean) {
            when (e) {
                is Literal -> {}
                is Identifier -> if (e.name.startsWith("$") && e.name !in bound && e.name !in KNOWN_VARS) {
                    out += ExprDiagnostic(e.span, DiagnosticSeverity.Error, "unknown.variable", "Unknown variable '${e.name}'", suggest(e.name, KNOWN_VARS))
                } else if (!e.name.startsWith("$") && e.name !in bound && e.name !in Library.namespaces && e.name !in Library.global && e.name !in Library.callableNamespaces && e.name !in KEYWORD_NAMES) {
                    out += ExprDiagnostic(e.span, DiagnosticSeverity.Error, "unknown.name", "Unknown name '${e.name}'", suggest(e.name, Library.namespaces.keys + bound))
                }
                is ArrayLiteral -> e.items.forEach { walk(it, bound, true) }
                is ObjectLiteral -> e.entries.forEach { en -> en.keyExpr?.let { walk(it, bound, true) }; walk(en.value, bound, true) }
                is Member -> { if (chainTop) resolver.resolve(e, true); walk(e.target, bound, false) }
                is Index -> { if (chainTop) resolver.resolve(e, true); walk(e.target, bound, false); walk(e.index, bound, true) }
                is Call -> call(e, bound)
                is Unary -> walk(e.operand, bound, true)
                is Binary -> { walk(e.left, bound, true); walk(e.right, bound, true) }
                is Conditional -> { walk(e.test, bound, true); walk(e.whenTrue, bound, true); walk(e.whenFalse, bound, true) }
                is Arrow -> walk(e.body, bound + e.params, true)
                is TemplateLiteral -> e.parts.forEach { if (it is Expr) walk(it, bound, true) }
            }
        }

        private fun call(e: Call, bound: Set<String>) {
            val c = e.callee
            when (c) {
                is Identifier -> {
                    if (c.name !in bound && c.name !in Library.global && c.name !in Library.callableNamespaces) {
                        out += ExprDiagnostic(c.span, DiagnosticSeverity.Error, "unknown.function", "Unknown function '${c.name}'", suggest(c.name, Library.global.keys + Library.callableNamespaces.keys + bound))
                    }
                    if (c.name == "\$step") stepArg(e)
                }
                is Member -> {
                    resolver.resolve(c.target, true)
                    val r = resolver.resolve(c.target, false)
                    val names = methodNamesFor(r)
                    if (names != null && c.name !in names) {
                        out += ExprDiagnostic(c.nameSpan, DiagnosticSeverity.Error, "unknown.function", "Unknown method '${c.name}'", suggest(c.name, names))
                    }
                    walk(c.target, bound, false)
                }
                else -> walk(c, bound, false)
            }
            e.args.forEach { walk(it, bound, true) }
        }

        private fun stepArg(e: Call) {
            val lit = e.args.firstOrNull() as? Literal ?: return
            val name = lit.value as? String ?: return
            resolver.checkStep(name, lit.span)
        }

        private fun methodNamesFor(r: Res): Set<String>? = when (r) {
            is Res.Namespace -> Library.namespaces[r.name]?.keys
            is Res.Value -> when (r.node?.type) {
                SchemaType.String -> TEXT_METHODS
                SchemaType.Date -> TEXT_METHODS + DATE_METHODS
                SchemaType.Number -> NUMBER_METHODS
                SchemaType.Array -> ARRAY_METHODS
                SchemaType.Object -> OBJECT_METHODS
                else -> null
            }
            is Res.Stream -> ARRAY_METHODS
            else -> null
        }
    }

    // ---- schema resolution ----

    private sealed interface Res {
        data class Value(val node: SchemaNode?) : Res
        data class Stream(val root: SchemaNode?) : Res
        data class Namespace(val name: String) : Res
        data object Unknown : Res
    }

    private inner class Resolver(val scope: SchemaScope, val out: MutableList<ExprDiagnostic>?) {
        fun resolve(e: Expr, emit: Boolean): Res = when (e) {
            is Identifier -> identifier(e)
            is Literal -> Res.Value(SchemaNode(schemaTypeOf(e.value)))
            is Call -> {
                val c = e.callee
                val name = (e.args.singleOrNull() as? Literal)?.value as? String
                if (c is Identifier && c.name == "\$step" && name != null) {
                    if (emit) checkStep(name, e.args[0].span)
                    val st = scope.upstream.firstOrNull { it.name == name }
                    if (st?.schema != null) Res.Stream(st.schema) else Res.Unknown
                } else Res.Unknown
            }
            is Member -> member(resolve(e.target, emit), e.name, e.nameSpan, emit)
            is Index -> {
                val t = resolve(e.target, emit)
                val key = (e.index as? Literal)?.value
                when {
                    key is String -> member(t, key, e.index.span, emit)
                    else -> elementOf(t)
                }
            }
            else -> Res.Unknown
        }

        private fun identifier(e: Identifier): Res = when (e.name) {
            "\$json", "\$item" -> Res.Value(scope.input)
            "\$items", "\$input", "\$all" -> Res.Stream(scope.input)
            "\$vars" -> Res.Value(if (scope.varNames.isEmpty()) null else SchemaNode(SchemaType.Object, fields = scope.varNames.associateWith { SchemaNode(SchemaType.Mixed) }.toImmutableMapCompat()))
            "\$run" -> Res.Value(RUN_NODE)
            "\$binary" -> Res.Value(scope.input?.fields?.get(SchemaInference.BINARY_KEY))
            else -> if (e.name in Library.namespaces) Res.Namespace(e.name) else Res.Unknown
        }

        private fun elementOf(t: Res): Res = when (t) {
            is Res.Stream -> Res.Value(t.root)
            is Res.Value -> if (t.node?.type == SchemaType.Array) Res.Value(t.node.element) else Res.Unknown
            else -> Res.Unknown
        }

        private fun member(t: Res, name: String, span: Span, emit: Boolean): Res = when (t) {
            is Res.Stream -> when (name) {
                "first", "last" -> Res.Value(t.root)
                "items" -> Res.Value(t.root?.let { SchemaNode(SchemaType.Array, element = it) })
                "count", "length" -> Res.Value(SchemaNode(SchemaType.Number))
                else -> field(t.root, name, span, emit)
            }
            is Res.Value -> {
                val n = t.node
                when {
                    n == null -> Res.Unknown
                    n.type == SchemaType.Array -> when (name) {
                        "first", "last" -> Res.Value(n.element)
                        "length", "count" -> Res.Value(SchemaNode(SchemaType.Number))
                        else -> Res.Unknown
                    }
                    n.type == SchemaType.String -> if (name == "length") Res.Value(SchemaNode(SchemaType.Number)) else Res.Unknown
                    else -> field(n, name, span, emit)
                }
            }
            else -> Res.Unknown
        }

        private fun field(n: SchemaNode?, name: String, span: Span, emit: Boolean): Res {
            if (n == null || (n.type != SchemaType.Object && n.type != SchemaType.Mixed)) return Res.Unknown
            val f = n.fields[name]
            if (f != null) return Res.Value(f)
            if (emit && out != null && n.fields.isNotEmpty() && !n.truncated) {
                val hint = suggest(name, n.fields.keys)
                out += ExprDiagnostic(
                    span, DiagnosticSeverity.Warning, "unknown.field",
                    "Field \"$name\" was not seen in the upstream data" + (hint?.let { ", did you mean \"$it\"?" } ?: ""),
                    hint,
                )
            }
            return Res.Unknown
        }

        fun checkStep(name: String, span: Span) {
            if (out == null || !scope.checksSteps) return
            if (scope.upstream.any { it.name == name }) return
            if (name in scope.otherSteps) {
                out += ExprDiagnostic(span, DiagnosticSeverity.Warning, "step.notUpstream", "Step \"$name\" is not upstream of this Step, so its data is not available here")
            } else {
                val hint = suggest(name, scope.upstream.map { it.name } + scope.otherSteps)
                out += ExprDiagnostic(span, DiagnosticSeverity.Error, "step.unknown", "No Step named \"$name\"" + (hint?.let { ", did you mean \"$it\"?" } ?: ""), hint)
            }
        }
    }

    // ---- completion ----

    override fun complete(text: String, cursor: Int, scope: SchemaScope, mode: ParseMode): List<Completion> {
        val at = cursor.coerceIn(0, text.length)
        val body = bodyAround(text, at, mode) ?: return emptyList()
        val prefix = text.substring(body.start, at)
        val matches = bracketMatches(prefix)
        if (inString(prefix) == null) {
            // fallthrough to normal contexts below
        } else {
            return stepNames(text, at, body, prefix, scope)
        }
        var wordStart = prefix.length
        while (wordStart > 0 && isIdent(prefix[wordStart - 1])) wordStart--
        val word = prefix.substring(wordStart)
        var rightEnd = at
        while (rightEnd < body.end && isIdent(text[rightEnd])) rightEnd++
        val wordSpan = Span(body.start + wordStart, rightEnd)
        val out = ArrayList<Completion>()
        val dot = wordStart - 1
        if (dot >= 0 && prefix[dot] == '.') {
            val optional = dot > 0 && prefix[dot - 1] == '?'
            val recvEnd = if (optional) dot - 1 else dot
            val recvStart = receiverStart(prefix, recvEnd, matches)
            if (recvStart < 0) return emptyList()
            val recv = resolveReceiver(prefix.substring(recvStart, recvEnd), scope)
            memberCompletions(recv, word, wordSpan, Span(body.start + dot, rightEnd), optional, out)
        } else {
            identifierCompletions(word, wordSpan, scope, out)
        }
        return out.sortedWith(compareByDescending<Completion> { it.score }.thenBy { it.label.length }.thenBy { it.label }).take(maxCompletions)
    }

    /** Body (inside braces) that contains [at], or null when the caret is in plain text. */
    private fun bodyAround(text: String, at: Int, mode: ParseMode): Span? {
        if (mode == ParseMode.Expression) return Span(0, text.length)
        for (seg in parse(text).codeSegments) if (at >= seg.bodySpan.start && at <= seg.bodySpan.end) return seg.bodySpan
        return null
    }

    private fun stepNames(text: String, at: Int, body: Span, prefix: String, scope: SchemaScope): List<Completion> {
        val m = STEP_ARG.find(prefix) ?: return emptyList()
        val q = m.groupValues[1][0]
        val partial = m.groupValues[2]
        var end = at
        while (end < body.end && text[end] != q) end++
        val closed = end < body.end && text[end] == q
        val span = Span(at - partial.length, end)
        val out = ArrayList<Completion>()
        fun add(name: String, upstream: Boolean, immediate: Boolean) {
            val s = matchScore(partial, name) ?: return
            val esc = name.replace("\\", "\\\\").replace(q.toString(), "\\$q")
            val insert = if (closed) esc else esc + q
            out += Completion(name, insert, span, CompletionKind.Step, SchemaType.Array, null,
                if (upstream) "Step output" else "Not upstream: its data is not available here", insert.length,
                s + (if (upstream) 200 else 0) + (if (immediate) 50 else 0))
        }
        for (u in scope.upstream) add(u.name, true, u.isImmediate)
        for (n in scope.otherSteps) add(n, false, false)
        return out.sortedWith(compareByDescending<Completion> { it.score }.thenBy { it.label }).take(maxCompletions)
    }

    private fun resolveReceiver(text: String, scope: SchemaScope): Res {
        val seg = parse(text, ParseMode.Expression).segments.singleOrNull() as? Segment.Code ?: return Res.Unknown
        val ast = seg.ast ?: return Res.Unknown
        if (seg.error != null) return Res.Unknown
        return Resolver(scope, null).resolve(ast, false)
    }

    private fun memberCompletions(r: Res, word: String, wordSpan: Span, dotSpan: Span, optional: Boolean, out: MutableList<Completion>) {
        fun field(name: String, node: SchemaNode?, rank: Int) {
            val s = matchScore(word, name) ?: return
            val simple = ReferenceBuilder.isSimpleKey(name)
            val insert = if (simple) name else (if (optional) "." else "") + "[${ReferenceBuilder.quote(name)}]"
            val replace = if (simple) wordSpan else Span(dotSpan.start, wordSpan.end)
            val presence = if (node != null && node.of > 0 && node.seenIn >= node.of) 20 else 0
            out += Completion(name, insert, replace, CompletionKind.Field, node?.type, node?.sample, node?.let { typeHelp(it) }, insert.length, s + 300 + presence + rank)
        }
        fun prop(name: String, type: SchemaType, help: String) {
            val s = matchScore(word, name) ?: return
            out += Completion(name, name, wordSpan, CompletionKind.Field, type, null, help, name.length, s + 250)
        }
        fun methods(receivers: Collection<String>) {
            val seen = HashSet<String>()
            for (f in KaivoExpressionEvaluator.library()) {
                if (f.kind != "method" || f.receiver !in receivers || !seen.add(f.name)) continue
                val s = matchScore(word, f.name) ?: continue
                out += callCompletion(f.name, f.receiver, f.minArgs, f.maxArgs, wordSpan, CompletionKind.Method, s + 100)
            }
        }
        when (r) {
            is Res.Stream -> {
                prop("first", SchemaType.Object, "First item"); prop("last", SchemaType.Object, "Last item")
                prop("items", SchemaType.Array, "All items"); prop("count", SchemaType.Number, "Number of items"); prop("length", SchemaType.Number, "Number of items")
                r.root?.fields?.forEach { (k, f) -> field(k, f, -10) }
                methods(listOf("array"))
            }
            is Res.Value -> {
                val n = r.node
                when (n?.type) {
                    SchemaType.Object -> { n.fields.forEach { (k, f) -> field(k, f, 0) }; methods(listOf("object")) }
                    SchemaType.Array -> { prop("first", n.element?.type ?: SchemaType.Mixed, "First element"); prop("last", n.element?.type ?: SchemaType.Mixed, "Last element"); prop("length", SchemaType.Number, "Number of elements"); methods(listOf("array")) }
                    SchemaType.String -> { prop("length", SchemaType.Number, "Number of characters"); methods(listOf("text")) }
                    SchemaType.Date -> { prop("length", SchemaType.Number, "Number of characters"); methods(listOf("text", "date")) }
                    SchemaType.Number -> methods(listOf("number"))
                    SchemaType.Bool, SchemaType.Null, SchemaType.Binary -> {}
                    SchemaType.Mixed -> { n.fields.forEach { (k, f) -> field(k, f, -20) }; methods(listOf("text", "number", "array", "object")) }
                    null -> methods(listOf("text", "number", "array", "object", "date"))
                }
            }
            is Res.Namespace -> for (f in KaivoExpressionEvaluator.library()) {
                if (f.kind != "namespace" || f.receiver != r.name) continue
                val s = matchScore(word, f.name) ?: continue
                out += callCompletion(f.name, f.receiver, f.minArgs, f.maxArgs, wordSpan, CompletionKind.Method, s + 200)
            }
            Res.Unknown -> methods(listOf("text", "number", "array", "object", "date"))
        }
    }

    private fun identifierCompletions(word: String, span: Span, scope: SchemaScope, out: MutableList<Completion>) {
        for ((name, help) in VARIABLE_HELP) {
            val s = matchScore(word, name) ?: continue
            val (insert, caret) = if (name == "\$step") "\$step(\"\")" to 7 else name to name.length
            out += Completion(name, insert, span, CompletionKind.Variable, variableType(name, scope), variableSample(name, scope), help, caret, s + 340)
        }
        val seen = HashSet<String>()
        for (f in KaivoExpressionEvaluator.library()) {
            if (f.kind != "global" || f.name == "\$step" || !seen.add(f.name)) continue
            val s = matchScore(word, f.name) ?: continue
            out += callCompletion(f.name, "", f.minArgs, f.maxArgs, span, CompletionKind.Function, s + (if (f.name.startsWith("$")) 330 else 100))
        }
        for (ns in Library.namespaces.keys) {
            val s = matchScore(word, ns) ?: continue
            out += Completion(ns, "$ns.", span, CompletionKind.Namespace, null, null, "Helper functions: $ns.…", ns.length + 1, s + 80)
        }
        if (word.isNotEmpty()) for (k in listOf("true", "false", "null")) {
            val s = matchScore(word, k) ?: continue
            out += Completion(k, k, span, CompletionKind.Keyword, null, null, null, k.length, s)
        }
    }

    private fun callCompletion(name: String, receiver: String, min: Int, max: Int, span: Span, kind: CompletionKind, score: Int): Completion {
        val sig = signature(name, receiver, min, max)
        val noArgs = max == 0
        val insert = "$name()"
        val caret = if (noArgs) insert.length else name.length + 1
        return Completion(name, insert, span, kind, null, null, (if (receiver.isNotEmpty() && kind == CompletionKind.Method) "$receiver." else "") + sig, caret, score)
    }

    private fun signature(name: String, receiver: String, min: Int, max: Int): String {
        val names = PARAM_NAMES["$receiver.$name"] ?: PARAM_NAMES[name]
        val count = if (max < 0) maxOf(min, 1) else max
        val params = (0 until count).joinToString(", ") { i ->
            val p = names?.getOrNull(i) ?: "arg${i + 1}"
            if (i >= min) "$p?" else p
        } + if (max < 0) ", …" else ""
        return "$name($params)"
    }

    private fun typeHelp(n: SchemaNode): String = buildString {
        append(n.type.name.lowercase())
        if (n.of > 0 && n.seenIn < n.of) append(", seen in ${n.seenIn} of ${n.of}")
    }

    private fun variableType(name: String, scope: SchemaScope): SchemaType? = when (name) {
        "\$json", "\$item", "\$vars", "\$run", "\$binary" -> SchemaType.Object
        "\$items", "\$input", "\$all" -> SchemaType.Array
        "\$index" -> SchemaType.Number
        "\$now", "\$today" -> SchemaType.Date
        else -> null
    }

    private fun variableSample(name: String, scope: SchemaScope): String? = null

    // ---- text helpers ----

    /** Ranks how well [name] fits what was typed; null when it does not. Exact prefix, then case-insensitive prefix, subsequence, contains. */
    private fun matchScore(typed: String, name: String): Int? {
        if (typed.isEmpty()) return 100
        if (name.startsWith(typed)) return if (name == typed) 1100 else 1000
        val t = typed.lowercase(); val n = name.lowercase()
        if (n.startsWith(t)) return 900
        if (n.contains(t)) return 600
        var i = 0
        for (c in n) if (i < t.length && c == t[i]) i++
        return if (i == t.length) 400 else null
    }

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    /** Quote character when [prefix] ends inside a string literal, else null. */
    private fun inString(prefix: String): Char? {
        var q: Char? = null
        var i = 0
        while (i < prefix.length) {
            val c = prefix[i]
            if (q != null) { if (c == '\\') i++ else if (c == q) q = null }
            else if (c == '"' || c == '\'' || c == '`') q = c
            i++
        }
        return q
    }

    /** For each closing bracket index in [prefix], the index of its opening bracket; strings are skipped. */
    private fun bracketMatches(prefix: String): Map<Int, Int> {
        val m = HashMap<Int, Int>(); val stack = ArrayList<Int>()
        var q: Char? = null; var i = 0
        while (i < prefix.length) {
            val c = prefix[i]
            if (q != null) { if (c == '\\') i++ else if (c == q) q = null }
            else when (c) {
                '"', '\'', '`' -> q = c
                '(', '[', '{' -> stack += i
                ')', ']', '}' -> if (stack.isNotEmpty()) m[i] = stack.removeAt(stack.size - 1)
            }
            i++
        }
        return m
    }

    /** Start of the member chain that ends just before [end] (a dot or the end of the receiver), or -1. */
    private fun receiverStart(prefix: String, end: Int, matches: Map<Int, Int>): Int {
        var i = end
        while (true) {
            if (i <= 0) return -1
            val c = prefix[i - 1]
            when {
                c == ')' || c == ']' -> {
                    i = matches[i - 1] ?: return -1
                    if (i > 0 && (prefix[i - 1] == ')' || prefix[i - 1] == ']' || isIdent(prefix[i - 1]))) continue
                }
                isIdent(c) -> while (i > 0 && isIdent(prefix[i - 1])) i--
                c == '"' || c == '\'' -> {
                    val open = prefix.lastIndexOf(c, i - 2)
                    if (open < 0) return -1
                    i = open
                }
                else -> return -1
            }
            if (i > 0 && prefix[i - 1] == '.') {
                i--
                if (i > 0 && prefix[i - 1] == '?') i--
                continue
            }
            return i
        }
    }

    companion object {
        private val STEP_ARG = Regex("""[$]step\(\s*(["'])([^"'\\]*)$""")
        private val INPUT_VARS = setOf("\$json", "\$item", "\$items", "\$input", "\$all", "\$first", "\$last", "\$count", "\$length")
        private val KEYWORD_NAMES = setOf("true", "false", "null", "undefined")
        private val KNOWN_VARS = setOf(
            "\$json", "\$item", "\$items", "\$index", "\$vars", "\$now", "\$today", "\$run", "\$step", "\$stepAll", "\$sources",
            "\$input", "\$runId", "\$count", "\$length", "\$first", "\$last", "\$all", "\$binary", "\$error", "\$flow", "\$env",
        )
        private val VARIABLE_HELP = listOf(
            "\$json" to "Data of the current item",
            "\$item" to "Current item (same as \$json)",
            "\$items" to "All items of the input",
            "\$index" to "Position of the current item",
            "\$vars" to "Flow variables",
            "\$run" to "Run info: id, timestamp, timezone, step",
            "\$step" to "Output of another Step: \$step(\"Name\")",
            "\$input" to "All input items",
            "\$binary" to "Attachments of the current item",
            "\$now" to "Current time",
            "\$today" to "Today's date",
        )
        private val RUN_NODE = SchemaNode(
            SchemaType.Object,
            fields = listOf("id", "timestamp", "timezone", "step").associateWith { SchemaNode(if (it == "timestamp") SchemaType.Number else SchemaType.String) }.toImmutableMapCompat(),
        )
        private val CATALOG by lazy { KaivoExpressionEvaluator.library() }
        private val TEXT_METHODS by lazy { CATALOG.filter { it.receiver == "text" }.map { it.name }.toSet() }
        private val NUMBER_METHODS by lazy { CATALOG.filter { it.receiver == "number" }.map { it.name }.toSet() }
        private val ARRAY_METHODS by lazy { CATALOG.filter { it.receiver == "array" }.map { it.name }.toSet() }
        private val OBJECT_METHODS by lazy { CATALOG.filter { it.receiver == "object" }.map { it.name }.toSet() }
        private val DATE_METHODS by lazy { CATALOG.filter { it.receiver == "date" }.map { it.name }.toSet() }

        private val PARAM_NAMES: Map<String, List<String>> = mapOf(
            "replace" to listOf("search", "replacement"), "replaceAll" to listOf("search", "replacement"),
            "split" to listOf("separator", "limit"), "includes" to listOf("value"), "startsWith" to listOf("prefix"),
            "endsWith" to listOf("suffix"), "indexOf" to listOf("value", "from"), "slice" to listOf("start", "end"),
            "substring" to listOf("start", "end"), "padStart" to listOf("length", "fill"), "padEnd" to listOf("length", "fill"),
            "repeat" to listOf("times"), "charAt" to listOf("index"), "at" to listOf("index"), "round" to listOf("decimals"),
            "pow" to listOf("exponent"), "clamp" to listOf("min", "max"), "toFixed" to listOf("decimals"),
            "map" to listOf("fn"), "filter" to listOf("fn"), "find" to listOf("fn"), "some" to listOf("fn"), "every" to listOf("fn"),
            "flatMap" to listOf("fn"), "findIndex" to listOf("fn"), "reduce" to listOf("fn", "initial"), "join" to listOf("separator"),
            "take" to listOf("count"), "pluck" to listOf("key"), "sort" to listOf("key", "order"), "hasKey" to listOf("key"),
            "get" to listOf("path", "default"), "pick" to listOf("key"), "omit" to listOf("key"), "merge" to listOf("other"),
            "format" to listOf("pattern", "zone"), "plus" to listOf("amount", "unit"), "minus" to listOf("amount", "unit"),
            "diff" to listOf("other", "unit"), "startOf" to listOf("unit"), "endOf" to listOf("unit"), "inZone" to listOf("zone"),
            "isBefore" to listOf("other"), "isAfter" to listOf("other"), "coalesce" to listOf("value"), "range" to listOf("start", "end", "step"),
            "\$step" to listOf("name", "field"), "typeOf" to listOf("value"), "length" to listOf("value"),
        )
    }
}

private fun <K, V> Map<K, V>.toImmutableMapCompat() = com.kaivoflow.core.expressions.assist.immutableMap(this)

private fun schemaTypeOf(v: Any?): SchemaType = when (v) {
    null -> SchemaType.Null
    is Boolean -> SchemaType.Bool
    is Number -> SchemaType.Number
    is String -> if (SchemaInference.looksLikeIsoDate(v)) SchemaType.Date else SchemaType.String
    is Map<*, *> -> SchemaType.Object
    is Iterable<*>, is Array<*> -> SchemaType.Array
    else -> SchemaType.String
}

private fun render(v: Any?, max: Int = 120): String {
    val s = when (v) {
        null -> "null"
        is String -> if (v.isEmpty()) "\"\"" else v
        is Map<*, *>, is Iterable<*> -> Values.stringify(v)
        is Number -> Values.asString(v)
        else -> v.toString()
    }
    return if (s.length <= max) s else s.take(max - 1) + "…"
}
