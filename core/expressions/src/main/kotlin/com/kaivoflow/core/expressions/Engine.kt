package com.kaivoflow.core.expressions

import com.kaivoflow.core.engine.ExpressionEvaluator
import com.kaivoflow.core.engine.Values

enum class ParseMode {
    /** Text mixing literal parts and `{{ expression }}` parts. */
    Template,

    /** One bare expression (the text inside `{{ }}`). */
    Expression,
}

/** A part of parsed text. Spans are absolute offsets in the parsed text. */
sealed interface Segment {
    val span: Span

    data class Text(override val span: Span, val text: String) : Segment

    /**
     * An expression. [span] includes the braces in template mode, [bodySpan] is only the expression.
     * [ast] is null for an empty body or when [error] is set; [tokens] are as far as lexing got.
     */
    data class Code(
        override val span: Span,
        val bodySpan: Span,
        val ast: Expr?,
        val tokens: List<Token>,
        val error: ExpressionException?,
    ) : Segment
}

/** Result of [parse]; never throws. Problems are reported in [diagnostics]. */
class ParseResult internal constructor(
    val source: String,
    val mode: ParseMode,
    val segments: List<Segment>,
    internal val sourceText: SourceText,
) {
    val diagnostics: List<ExpressionException> get() = segments.mapNotNull { (it as? Segment.Code)?.error }
    val ok: Boolean get() = diagnostics.isEmpty()
    val codeSegments: List<Segment.Code> get() = segments.filterIsInstance<Segment.Code>()
    val tokens: List<Token> get() = codeSegments.flatMap { it.tokens }

    /** The tree of the only expression, when the text is exactly one expression (typed result). */
    val singleAst: Expr?
        get() = codeSegments.singleOrNull()?.takeIf { c -> segments.all { it === c || (it is Segment.Text && it.text.isBlank()) } }?.ast
}

/** Parses [text] without evaluating it; tokens and spans are meant for chips and diagnostics. */
fun parse(text: String, mode: ParseMode = ParseMode.Template, limits: ExpressionLimits = ExpressionLimits.Default): ParseResult {
    val src = SourceText(text)
    val segments = ArrayList<Segment>()
    if (mode == ParseMode.Expression) {
        segments += parseBody(src, Span(0, text.length), Span(0, text.length), limits)
    } else {
        for (raw in TemplateScanner.scan(text)) {
            when (raw) {
                is RawSegment.Text -> segments += Segment.Text(Span(raw.start, raw.end), text.substring(raw.start, raw.end))
                is RawSegment.Code -> segments += parseBody(src, Span(raw.start, raw.end), Span(raw.bodyStart, raw.bodyEnd), limits)
                is RawSegment.Unterminated -> {
                    val body = Span(raw.start + 2, text.length)
                    val lexed = Lexer(src, body.start, body.end).run()
                    segments += Segment.Code(
                        Span(raw.start, text.length), body, null, lexed.tokens.dropLast(1),
                        src.error(Span(raw.start, raw.start + 2), ErrorKind.Syntax, "Unterminated '{{': the closing '}}' is missing"),
                    )
                }
            }
        }
    }
    return ParseResult(text, mode, segments, src)
}

private fun parseBody(src: SourceText, outer: Span, body: Span, limits: ExpressionLimits): Segment.Code {
    if (body.length > limits.maxExpressionLength) {
        return Segment.Code(outer, body, null, emptyList(),
            src.error(Span(body.start, body.start + 1), ErrorKind.Limit, "Expression is longer than ${limits.maxExpressionLength} characters"))
    }
    val lexer = Lexer(src, body.start, body.end).run()
    val tokens = lexer.tokens.dropLast(1)
    lexer.error?.let { return Segment.Code(outer, body, null, tokens, it) }
    if (tokens.isEmpty()) return Segment.Code(outer, body, null, tokens, null)
    return try {
        val ast = Parser(src, lexer.tokens, ParseState(limits)).parseAll()
        Segment.Code(outer, body, ast, tokens, null)
    } catch (e: ExpressionException) {
        Segment.Code(outer, body, null, tokens, e)
    }
}

// ---- analysis ----

/** Variables referenced by [ast] (names like `$json`, `$step`), excluding arrow function parameters. */
fun referencedVariables(ast: Expr): Set<String> = analyze(ast).variables

fun referencedVariables(parsed: ParseResult): Set<String> =
    parsed.codeSegments.mapNotNull { it.ast }.flatMap { referencedVariables(it) }.toSet()

/**
 * What an expression reads.
 * [variables] such as `$json`; [steps] literal names in `$step("Name")`; [paths] maximal property
 * chains such as `$json.customer.name` or `$step("Fetch").id` (only constant keys); [functions]
 * names of called functions and methods.
 */
data class ExpressionReferences(
    val variables: Set<String>,
    val steps: Set<String>,
    val paths: List<String>,
    val functions: Set<String>,
)

fun analyze(ast: Expr): ExpressionReferences {
    val vars = LinkedHashSet<String>()
    val steps = LinkedHashSet<String>()
    val paths = ArrayList<String>()
    val funcs = LinkedHashSet<String>()

    fun pathOf(e: Expr): String? = when (e) {
        is Identifier -> if (e.name.startsWith("$")) e.name else null
        is Member -> pathOf(e.target)?.let { "$it.${e.name}" }
        is Index -> {
            val i = e.index
            val key = (i as? Literal)?.value
            pathOf(e.target)?.let { base ->
                when (key) {
                    is String -> "$base[\"$key\"]"
                    is Number -> "$base[${Rt.fmtNum(key.toDouble())}]"
                    else -> null
                }
            }
        }
        is Call -> {
            val c = e.callee
            val first = (e.args.firstOrNull() as? Literal)?.value
            if (c is Identifier && c.name == "\$step" && first is String && e.args.size == 1) "\$step(\"$first\")" else null
        }
        else -> null
    }

    fun walk(e: Expr, bound: Set<String>, chainTop: Boolean) {
        if ((e is Member || e is Index || e is Call) && chainTop) {
            pathOf(e)?.let { if (it.contains('.') || it.contains('[')) paths += it }
        }
        when (e) {
            is Literal -> {}
            is Identifier -> if (e.name.startsWith("$") && e.name !in bound) vars += e.name
            is ArrayLiteral -> e.items.forEach { walk(it, bound, true) }
            is ObjectLiteral -> e.entries.forEach { en -> en.keyExpr?.let { walk(it, bound, true) }; walk(en.value, bound, true) }
            is Member -> walk(e.target, bound, false)
            is Index -> { walk(e.target, bound, false); walk(e.index, bound, true) }
            is Call -> {
                val c = e.callee
                when (c) {
                    is Identifier -> { funcs += c.name; if (c.name.startsWith("$") && c.name !in bound) vars += c.name }
                    is Member -> { funcs += c.name; walk(c.target, bound, false) }
                    else -> walk(c, bound, false)
                }
                val first = (e.args.firstOrNull() as? Literal)?.value
                if (c is Identifier && c.name == "\$step" && first is String) steps += first
                e.args.forEach { walk(it, bound, true) }
            }
            is Unary -> walk(e.operand, bound, true)
            is Binary -> { walk(e.left, bound, true); walk(e.right, bound, true) }
            is Conditional -> { walk(e.test, bound, true); walk(e.whenTrue, bound, true); walk(e.whenFalse, bound, true) }
            is Arrow -> walk(e.body, bound + e.params, true)
            is TemplateLiteral -> e.parts.forEach { if (it is Expr) walk(it, bound, true) }
        }
    }
    walk(ast, emptySet(), true)
    return ExpressionReferences(vars, steps, paths, funcs)
}

// ---- evaluation ----

private class ParseCache(private val capacity: Int = 512) {
    private val map = object : LinkedHashMap<String, ParseResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParseResult>?) = size > capacity
    }

    @Synchronized fun get(key: String): ParseResult? = map[key]
    @Synchronized fun put(key: String, value: ParseResult) { map[key] = value }
}

/**
 * KaivoFlow's own expression engine behind the [ExpressionEvaluator] interface.
 *
 * Safe navigation is the default (missing paths give null); pass `strict = true` to make missing
 * properties, null receivers and out-of-range indexes raise [ExpressionException] (use `?.` to opt
 * out per access). Limits come from [limits]; instances are thread safe.
 */
class KaivoExpressionEvaluator(
    val limits: ExpressionLimits = ExpressionLimits.Default,
    val strict: Boolean = false,
) : ExpressionEvaluator {

    private val cache = ParseCache()

    private fun compile(text: String, mode: ParseMode): ParseResult {
        val key = (if (mode == ParseMode.Expression) "E:" else "T:") + text
        cache.get(key)?.let { return it }
        return parse(text, mode, limits).also { if (text.length <= 20_000) cache.put(key, it) }
    }

    override fun evaluate(code: String, scope: Map<String, Any?>): Any? {
        if (code.isBlank()) return null
        return run(compile(code, ParseMode.Expression), scope, typed = true) as Any?
    }

    override fun interpolate(template: String, scope: Map<String, Any?>): Any? {
        if (!template.contains("{{")) return template
        val parsed = compile(template, ParseMode.Template)
        val typed = parsed.singleAst != null || (parsed.codeSegments.size == 1 && parsed.segments.all { it is Segment.Code || (it as Segment.Text).text.isBlank() })
        return run(parsed, scope, typed)
    }

    override fun interpolateAll(template: String, scope: Map<String, Any?>): String {
        if (!template.contains("{{")) return template
        return Values.asString(run(compile(template, ParseMode.Template), scope, typed = false))
    }

    /** Evaluates text like the Run does but returns the outcome instead of throwing. */
    fun preview(text: String, scope: Map<String, Any?>, mode: ParseMode = ParseMode.Template): ExpressionPreview =
        safely {
            val parsed = compile(text, mode)
            val typed = mode == ParseMode.Expression || parsed.codeSegments.size == 1 && parsed.segments.all { it is Segment.Code || (it as Segment.Text).text.isBlank() }
            run(parsed, scope, typed)
        }

    private inline fun safely(block: () -> Any?): ExpressionPreview = try {
        val v = block()
        ExpressionPreview.Value(v, Rt.typeName(v))
    } catch (e: ExpressionException) {
        ExpressionPreview.Failure(e)
    } catch (e: Exception) {
        ExpressionPreview.Failure(ExpressionException("Unexpected failure: ${e.javaClass.simpleName}", ErrorKind.Runtime))
    } catch (e: StackOverflowError) {
        ExpressionPreview.Failure(ExpressionException("Expression is too deeply nested to evaluate", ErrorKind.Limit))
    }

    private fun run(parsed: ParseResult, scope: Map<String, Any?>, typed: Boolean): Any? {
        parsed.diagnostics.firstOrNull()?.let { throw it }
        val src = parsed.sourceText
        val st = EvalState(limits, strict, scope)
        val ip = Interp(src, st)
        try {
            fun value(seg: Segment.Code): Any? {
                val ast = seg.ast ?: return null
                val v = ip.eval(ast, null)
                return if (st.sawSpecial) Rt.plain(v) {
                    throw src.error(seg.span, ErrorKind.Type, "A function cannot be used as a result; call it or use it inside map() or filter()")
                } else v
            }
            if (parsed.mode == ParseMode.Expression) return value(parsed.codeSegments.single())
            if (typed) return value(parsed.codeSegments.single())
            val sb = StringBuilder()
            for (seg in parsed.segments) {
                when (seg) {
                    is Segment.Text -> sb.append(seg.text)
                    is Segment.Code -> sb.append(Rt.str(value(seg)))
                }
                if (sb.length > limits.maxStringLength) {
                    throw src.error(seg.span, ErrorKind.Limit, "Result longer than ${limits.maxStringLength} characters")
                }
            }
            return sb.toString()
        } catch (e: StackOverflowError) {
            throw ExpressionException("Expression is too deeply nested to evaluate", ErrorKind.Limit)
        } catch (e: ExpressionException) {
            throw e
        } catch (e: RuntimeException) {
            throw ExpressionException("Unexpected failure: ${e.javaClass.simpleName}: ${e.message}", ErrorKind.Runtime, cause = e)
        }
    }

    companion object {
        /** Catalogue of every whitelisted function and method, for autocomplete. */
        fun library(): List<FunctionInfo> = Library.catalog()
    }
}

/** Outcome of [evaluatePreview]. */
sealed interface ExpressionPreview {
    /** [type] is one of `null`, `boolean`, `number`, `string`, `array`, `object`. */
    data class Value(val value: Any?, val type: String) : ExpressionPreview
    data class Failure(val error: ExpressionException) : ExpressionPreview
}

/** Evaluates [text] against [scope] with tight preview limits and never throws. */
fun evaluatePreview(
    text: String,
    scope: Map<String, Any?>,
    mode: ParseMode = ParseMode.Template,
    limits: ExpressionLimits = ExpressionLimits.Preview,
    strict: Boolean = false,
): ExpressionPreview = KaivoExpressionEvaluator(limits, strict).preview(text, scope, mode)
