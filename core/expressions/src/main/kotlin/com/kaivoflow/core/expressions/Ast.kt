package com.kaivoflow.core.expressions

/** Syntax tree. Every node knows its [span] in the original text so UIs can map results back to text. */
sealed class Expr {
    abstract val span: Span

    /** Height of the subtree rooted here (set by the parser). */
    var height: Int = 1
        internal set
}

data class Literal(override val span: Span, val value: Any?) : Expr()
data class ArrayLiteral(override val span: Span, val items: List<Expr>) : Expr()

/** One `key: value` pair; the key is either the constant [key] or the computed [keyExpr]. */
data class ObjectEntry(val key: String?, val keyExpr: Expr?, val value: Expr)
data class ObjectLiteral(override val span: Span, val entries: List<ObjectEntry>) : Expr()

/** A bare name: `$json`, `now`, or an arrow function parameter. */
data class Identifier(override val span: Span, val name: String) : Expr()
data class Member(override val span: Span, val target: Expr, val name: String, val optional: Boolean, val nameSpan: Span) : Expr()
data class Index(override val span: Span, val target: Expr, val index: Expr, val optional: Boolean) : Expr()
data class Call(override val span: Span, val callee: Expr, val args: List<Expr>, val optional: Boolean) : Expr()
data class Unary(override val span: Span, val op: String, val operand: Expr) : Expr()
data class Binary(override val span: Span, val op: String, val left: Expr, val right: Expr) : Expr()
data class Conditional(override val span: Span, val test: Expr, val whenTrue: Expr, val whenFalse: Expr) : Expr()
data class Arrow(override val span: Span, val params: List<String>, val body: Expr) : Expr()

/** Backtick string; [parts] hold `String` literals and [Expr] interpolations. */
data class TemplateLiteral(override val span: Span, val parts: List<Any>) : Expr()
