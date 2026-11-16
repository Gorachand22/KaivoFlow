package com.kaivoflow.core.expressions

internal class ParseState(val limits: ExpressionLimits) {
    var nodes = 0
}

/** Recursive descent parser with precedence climbing. */
internal class Parser(
    private val src: SourceText,
    private val tokens: List<Token>,
    private val state: ParseState,
    private var depth: Int = 0,
) {
    private var pos = 0

    companion object {
        /** Parses `[start, end)` of [src] into a tree; returns null for blank input. */
        fun parseRange(src: SourceText, start: Int, end: Int, state: ParseState, depth: Int = 0): Expr? {
            val lexer = Lexer(src, start, end).run()
            lexer.error?.let { throw it }
            if (lexer.tokens.size == 1) return null
            return Parser(src, lexer.tokens, state, depth).parseAll()
        }

        private val BINARY_PRECEDENCE = mapOf(
            "??" to 1, "||" to 2, "&&" to 3,
            "==" to 4, "!=" to 4, "===" to 4, "!==" to 4,
            "<" to 5, ">" to 5, "<=" to 5, ">=" to 5,
            "+" to 6, "-" to 6,
            "*" to 7, "/" to 7, "%" to 7,
        )
    }

    private fun peek(k: Int = 0): Token = tokens[minOf(pos + k, tokens.size - 1)]
    private fun next(): Token = tokens[pos].also { if (pos < tokens.size - 1) pos++ }
    private fun isOp(text: String, k: Int = 0) = peek(k).let { (it.kind == TokenKind.Operator || it.kind == TokenKind.Punct) && it.text == text }

    private fun describe(t: Token) = if (t.kind == TokenKind.End) "end of expression" else "'${t.text}'"

    private fun fail(t: Token, message: String): Nothing = throw src.error(t.span, ErrorKind.Syntax, message)

    private fun expect(text: String, context: String): Token {
        val t = peek()
        if (!isOp(text)) fail(t, "Expected '$text' $context but found ${describe(t)}")
        return next()
    }

    fun parseAll(): Expr {
        val e = parseExpression()
        val t = peek()
        if (t.kind != TokenKind.End) {
            val hint = if (t.kind == TokenKind.String || t.kind == TokenKind.Identifier || t.kind == TokenKind.Variable || t.kind == TokenKind.Number)
                ". Is an operator missing before it?" else ""
            fail(t, "Unexpected ${describe(t)}$hint")
        }
        return e
    }

    private fun <T : Expr> T.sized(vararg children: Expr): T {
        var h = 0
        for (c in children) if (c.height > h) h = c.height
        height = h + 1 + 0
        if (height + depth > state.limits.maxAstDepth) {
            throw src.error(span, ErrorKind.Limit, "Expression is nested too deeply (limit ${state.limits.maxAstDepth})")
        }
        if (++state.nodes > state.limits.maxAstNodes) {
            throw src.error(span, ErrorKind.Limit, "Expression is too large (more than ${state.limits.maxAstNodes} syntax nodes)")
        }
        return this
    }

    private fun parseExpression(): Expr {
        depth++
        try {
            if (depth > state.limits.maxAstDepth) {
                throw src.error(peek().span, ErrorKind.Limit, "Expression is nested too deeply (limit ${state.limits.maxAstDepth})")
            }
            return parseTernary()
        } finally {
            depth--
        }
    }

    private fun parseTernary(): Expr {
        val test = parseBinary(1)
        if (isOp("?")) {
            next()
            val a = parseTernary()
            expect(":", "in the ? : conditional")
            val b = parseTernary()
            return Conditional(test.span.cover(b.span), test, a, b).sized(test, a, b)
        }
        return test
    }

    private fun parseBinary(minPrec: Int): Expr {
        var left = parseUnary()
        while (true) {
            val t = peek()
            if (t.kind != TokenKind.Operator) break
            val prec = BINARY_PRECEDENCE[t.text] ?: break
            if (prec < minPrec) break
            next()
            val right = parseBinary(prec + 1)
            left = Binary(left.span.cover(right.span), t.text, left, right).sized(left, right)
        }
        return left
    }

    private fun parseUnary(): Expr {
        val t = peek()
        if (t.kind == TokenKind.Operator && (t.text == "!" || t.text == "-" || t.text == "+")) {
            next()
            depth++
            try {
                if (depth > state.limits.maxAstDepth) throw src.error(t.span, ErrorKind.Limit, "Expression is nested too deeply (limit ${state.limits.maxAstDepth})")
                val operand = parseUnary()
                return Unary(t.span.cover(operand.span), t.text, operand).sized(operand)
            } finally {
                depth--
            }
        }
        return parsePower()
    }

    private fun parsePower(): Expr {
        val base = parsePostfix()
        if (isOp("**")) {
            next()
            val exp = parseUnary()
            return Binary(base.span.cover(exp.span), "**", base, exp).sized(base, exp)
        }
        return base
    }

    private fun parsePostfix(): Expr {
        var e = parsePrimary()
        while (true) {
            val t = peek()
            if (t.kind != TokenKind.Punct && t.kind != TokenKind.Operator) break
            when (t.text) {
                "." -> {
                    next()
                    val n = next()
                    if (n.kind != TokenKind.Identifier && n.kind != TokenKind.Keyword) fail(n, "Expected a property name after '.' but found ${describe(n)}")
                    e = Member(e.span.cover(n.span), e, n.text, false, n.span).sized(e)
                }
                "?." -> {
                    next()
                    val n = peek()
                    when {
                        n.kind == TokenKind.Punct && n.text == "[" -> {
                            next()
                            val idx = parseExpression()
                            val close = expect("]", "to close the index")
                            e = Index(e.span.cover(close.span), e, idx, true).sized(e, idx)
                        }
                        n.kind == TokenKind.Punct && n.text == "(" -> {
                            next()
                            val (args, close) = parseArgs()
                            e = Call(e.span.cover(close.span), e, args, true).sized(e, *args.toTypedArray())
                        }
                        n.kind == TokenKind.Identifier || n.kind == TokenKind.Keyword -> {
                            next()
                            e = Member(e.span.cover(n.span), e, n.text, true, n.span).sized(e)
                        }
                        else -> fail(n, "Expected a property name after '?.' but found ${describe(n)}")
                    }
                }
                "[" -> {
                    next()
                    val idx = parseExpression()
                    val close = expect("]", "to close the index")
                    e = Index(e.span.cover(close.span), e, idx, false).sized(e, idx)
                }
                "(" -> {
                    next()
                    val (args, close) = parseArgs()
                    e = Call(e.span.cover(close.span), e, args, false).sized(e, *args.toTypedArray())
                }
                else -> break
            }
        }
        return e
    }

    private fun parseArgs(): Pair<List<Expr>, Token> {
        val args = ArrayList<Expr>()
        if (isOp(")")) return args to next()
        while (true) {
            args += parseExpression()
            if (isOp(",")) {
                next()
                if (isOp(")")) return args to next()
                continue
            }
            return args to expect(")", "to close the argument list")
        }
    }

    private fun parsePrimary(): Expr {
        val t = peek()
        when (t.kind) {
            TokenKind.Number -> { next(); return Literal(t.span, t.literal).sized() }
            TokenKind.String -> { next(); return Literal(t.span, t.literal).sized() }
            TokenKind.Template -> { next(); return templateLiteral(t) }
            TokenKind.Keyword -> {
                next()
                val v: Any? = when (t.text) { "true" -> true; "false" -> false; else -> null }
                return Literal(t.span, v).sized()
            }
            TokenKind.Identifier, TokenKind.Variable -> {
                if (isOp("=>", 1)) return arrow(listOf(next()), t.span)
                next()
                return Identifier(t.span, t.text).sized()
            }
            TokenKind.Punct, TokenKind.Operator -> when (t.text) {
                "(" -> {
                    val arrowEnd = arrowParamsEnd()
                    if (arrowEnd >= 0) {
                        next()
                        val params = ArrayList<Token>()
                        while (!isOp(")")) {
                            val p = next()
                            params += p
                            if (isOp(",")) next()
                        }
                        next() // )
                        return arrow(params, t.span)
                    }
                    next()
                    val inner = parseExpression()
                    expect(")", "to close the parenthesis")
                    return inner
                }
                "[" -> {
                    next()
                    val items = ArrayList<Expr>()
                    while (!isOp("]")) {
                        items += parseExpression()
                        if (isOp(",")) next() else break
                    }
                    val close = expect("]", "to close the array")
                    return ArrayLiteral(t.span.cover(close.span), items).sized(*items.toTypedArray())
                }
                "{" -> return objectLiteral(t)
            }
            TokenKind.End -> fail(t, "Unexpected end of expression")
        }
        fail(t, "Unexpected ${describe(t)}")
    }

    /** If the tokens at `(` form `( ident, ident ) =>`, returns the index of `=>`; otherwise -1. */
    private fun arrowParamsEnd(): Int {
        var k = 1
        if (isOp(")", k)) return if (isOp("=>", k + 1)) pos + k + 1 else -1
        while (true) {
            val p = peek(k)
            if (p.kind != TokenKind.Identifier && p.kind != TokenKind.Variable) return -1
            k++
            if (isOp(",", k)) { k++; continue }
            if (isOp(")", k) && isOp("=>", k + 1)) return pos + k + 1
            return -1
        }
    }

    private fun arrow(paramTokens: List<Token>, startSpan: Span): Expr {
        val arrowTok = if (isOp("=>")) next() else fail(peek(), "Expected '=>'")
        val names = paramTokens.map { it.text }
        if (names.toSet().size != names.size) fail(arrowTok, "Duplicate parameter name in arrow function")
        val body = parseExpression()
        return Arrow(startSpan.cover(body.span), names, body).sized(body)
    }

    private fun objectLiteral(open: Token): Expr {
        next()
        val entries = ArrayList<ObjectEntry>()
        val children = ArrayList<Expr>()
        while (!isOp("}")) {
            val k = peek()
            var key: String? = null
            var keyExpr: Expr? = null
            when {
                k.kind == TokenKind.Identifier || k.kind == TokenKind.Keyword || k.kind == TokenKind.Variable -> { next(); key = k.text }
                k.kind == TokenKind.String -> { next(); key = k.literal as String }
                k.kind == TokenKind.Number -> { next(); key = k.text }
                isOp("[") -> {
                    next()
                    keyExpr = parseExpression()
                    expect("]", "to close the computed key")
                    children += keyExpr
                }
                else -> fail(k, "Expected an object key but found ${describe(k)}")
            }
            val colon = peek()
            if (!isOp(":")) fail(colon, "Expected ':' after the object key but found ${describe(colon)}")
            next()
            val value = parseExpression()
            children += value
            entries += ObjectEntry(key, keyExpr, value)
            if (isOp(",")) next() else break
        }
        val close = expect("}", "to close the object")
        return ObjectLiteral(open.span.cover(close.span), entries).sized(*children.toTypedArray())
    }

    private fun templateLiteral(t: Token): Expr {
        val parts = ArrayList<Any>()
        val children = ArrayList<Expr>()
        for (p in t.parts.orEmpty()) {
            if (p.text != null) {
                parts += p.text
            } else {
                val span = p.span!!
                val sub = parseRange(src, span.start, span.end, state, depth + 1)
                    ?: throw src.error(span, ErrorKind.Syntax, "Empty \${} in template string")
                parts += sub
                children += sub
            }
        }
        return TemplateLiteral(t.span, parts).sized(*children.toTypedArray())
    }
}
