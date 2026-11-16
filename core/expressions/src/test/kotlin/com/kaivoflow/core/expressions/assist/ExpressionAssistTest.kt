package com.kaivoflow.core.expressions.assist

import com.kaivoflow.core.expressions.ExpressionException
import com.kaivoflow.core.expressions.KaivoExpressionEvaluator
import com.kaivoflow.core.expressions.ParseMode
import com.kaivoflow.core.model.Item
import com.kaivoflow.core.model.SchemaInference
import com.kaivoflow.core.model.SchemaType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionAssistTest {
    private val assist = KaivoExpressionAssist()
    private val inputs = listOf(
        Item.of("name" to "  Ada ", "qty" to 3, "price" to 19.5, "tags" to listOf("x", "y"), "customer" to mapOf("email" to "a@b.c", "age" to 36), "first name" to "Ada", "when" to "2026-01-02T03:04:05Z"),
        Item.of("name" to "Bob", "qty" to 5, "price" to 2.0, "tags" to listOf("z"), "customer" to mapOf("email" to "b@b.c", "age" to 41), "first name" to "Bob", "when" to "2026-02-02T03:04:05Z"),
    )
    private val fetched = listOf(Item.of("id" to 7, "title" to "Hello"), Item.of("id" to 8, "title" to "World"))
    private fun ctx(index: Int = 0, upstream: Set<String>? = setOf("Fetch")) = PreviewContext(
        stepName = "Me", itemIndex = index, inputs = inputs,
        stepOutputs = mapOf("s1" to fetched), stepNames = mapOf("s1" to "Fetch"),
        vars = mapOf("sheet" to "abc", "n" to 10.0), runId = "run-1", clock = { 1_700_000_000_000L }, upstreamStepNames = upstream,
    )
    private val scope = SchemaScope(
        input = SchemaInference.infer(inputs.asSequence()),
        upstream = listOf(UpstreamStep("s1", "Fetch", SchemaInference.infer(fetched.asSequence()), isImmediate = true)),
        otherSteps = setOf("Later"),
        varNames = setOf("sheet", "n"),
    )

    // ---- preview equals Run ----

    private val corpus: List<String> = buildList {
        addAll(listOf("plain text", "{{ \$json.name }}", "{{ \$json.name.trim() }}", "Hi {{ \$json.name.trim() }}!", "{{ \$json.qty * \$json.price }}",
            "{{ \$json.qty + 1 }}", "{{ \$json.qty > 3 }}", "{{ \$json.tags }}", "{{ \$json.tags.length }}", "{{ \$json.tags.join(\", \") }}",
            "{{ \$json.customer.email }}", "{{ \$json.customer }}", "{{ \$json[\"first name\"] }}", "{{ \$json.missing }}", "{{ \$json.missing.deeper }}",
            "{{ \$index }}", "{{ \$items.length }}", "{{ \$vars.sheet }}", "{{ \$vars.n * 2 }}", "{{ \$run.id }}", "{{ \$step(\"Fetch\").first.title }}",
            "{{ \$step(\"Fetch\").count }}", "{{ \$step(\"Fetch\")[1].id }}", "{{ \$step(\"Fetch\").map(i => i.id) }}", "{{ \$step(\"Fetch\", \"title\") }}",
            "{{ \$json.qty > 3 ? \"big\" : \"small\" }}", "{{ \$json.name ?? \"none\" }}", "{{ Math.round(\$json.price) }}", "{{ Math.max(1, 2, 3) }}",
            "{{ \$json.name.upper() }}", "{{ \$json.name.trim().length }}", "{{ [1,2,3].sum() }}", "{{ \$items.map(i => i.qty).sum() }}",
            "{{ \$json.when.format(\"yyyy-MM-dd\") }}", "{{ \$now }}", "{{ \$today }}", "{{ 1 / 0 }}", "{{ nope }}", "{{ \$jsno.name }}", "{{ \$json.name. }}",
            "{{ foo(1) }}", "{{ \$json.name.trimm() }}", "a {{ 1 + }} b", "pre {{ \$json.a", "{{ typeOf(\$json.qty) }}", "{{ isEmpty(\$json.tags) }}",
            "{{ { a: 1, b: [2, 3] } }}", "{{ \"x\".repeat(3) }}", "{{ range(0, 5) }}", "{{ \$json.customer.keys() }}", "{{ \$json.customer.age >= 40 && \$json.qty < 4 }}"))
        for (i in 0..1) {
            for (f in listOf("name", "qty", "price", "customer.age", "tags[0]", "customer.email")) add("{{ \$json.$f }}#$i")
        }
        for (f in listOf("id", "title", "nothing")) { add("{{ \$step(\"Fetch\").first.$f }}#0"); add("{{ \$step(\"Fetch\").last.$f }}#1") }
        for (n in listOf(1, 2, 7, 13, 50)) add("{{ \$json.qty * $n + \$index }}#1")
        for (a in 1..6) for (op in listOf("+", "*", "-")) add("{{ \$json.qty $op $a }}#${a % 2}")
        for (m in listOf("trim()", "upper()", "lower()", "length", "capitalize()", "split(\"a\")", "padStart(8, \"*\")", "slice(0, 2)")) add("{{ \$json.name.$m }}#0")
        for (w in listOf("\$json.name", "\$json.qty", "\$json.tags")) add("Value: {{ $w }} end")
    }

    @Test fun previewEqualsRunResultForCorpus() {
        assertTrue("corpus has ${corpus.size}", corpus.size >= 100)
        val evaluator = KaivoExpressionEvaluator()
        var errors = 0
        for (entry in corpus) {
            val text = entry.substringBefore('#')
            val idx = entry.substringAfter('#', "0").toInt()
            val c = ctx(idx)
            val scopeMap = assist.buildScope(c)
            val run = try { Result.success(evaluator.interpolate(text, scopeMap)) } catch (e: ExpressionException) { Result.failure(e) }
            val prev = assist.preview(text, c)
            when {
                run.isSuccess -> {
                    assertTrue("$text -> $prev", prev is PreviewResult.Value)
                    assertEquals(text, run.getOrNull(), (prev as PreviewResult.Value).value)
                }
                else -> { errors++; assertTrue("$text -> $prev", prev is PreviewResult.Error); assertEquals(text, run.exceptionOrNull()!!.message, (prev as PreviewResult.Error).error.message) }
            }
        }
        assertTrue(errors >= 5)
    }

    @Test fun previewTypesAndRendering() {
        val v = assist.preview("{{ \$json.qty * 2 }}", ctx()) as PreviewResult.Value
        assertEquals(SchemaType.Number, v.type); assertEquals("6", v.rendered)
        assertEquals(SchemaType.String, (assist.preview("{{ \$json.name }}", ctx()) as PreviewResult.Value).type)
        assertEquals(SchemaType.Array, (assist.preview("{{ \$json.tags }}", ctx()) as PreviewResult.Value).type)
        assertEquals(SchemaType.Date, (assist.preview("{{ \$json.when }}", ctx()) as PreviewResult.Value).type)
        assertEquals(SchemaType.Null, (assist.preview("{{ \$json.zip }}", ctx()) as PreviewResult.Value).type)
        assertEquals("[\"x\",\"y\"]", (assist.preview("{{ \$json.tags }}", ctx()) as PreviewResult.Value).rendered)
        assertEquals(SchemaType.Bool, (assist.preview("\$json.qty > 1", ctx(), ParseMode.Expression) as PreviewResult.Value).type)
    }

    @Test fun previewUsesSelectedItem() {
        assertEquals("Bob", (assist.preview("{{ \$json.name }}", ctx(1)) as PreviewResult.Value).value)
    }

    @Test fun previewPendingStates() {
        val none = ctx().copy(stepOutputs = emptyMap(), stepNames = emptyMap())
        assertEquals(PreviewResult.Pending(PendingReason.NoUpstreamData, "Fetch"), assist.preview("{{ \$step(\"Fetch\").first.id }}", none))
        assertEquals(PreviewResult.Pending(PendingReason.NotUpstream, "Fetch"), assist.preview("{{ \$step(\"Fetch\").first.id }}", ctx(upstream = emptySet())))
        assertEquals(PreviewResult.Pending(PendingReason.NoUpstreamData), assist.preview("{{ \$json.a }}", ctx().copy(inputs = emptyList())))
        assertTrue(assist.preview("plain", ctx().copy(inputs = emptyList())) is PreviewResult.Value)
    }

    @Test fun previewErrorCarriesSpanAndSuggestion() {
        val e = assist.preview("x {{ \$jsno.name }}", ctx()) as PreviewResult.Error
        assertEquals("\$json", e.didYouMean)
        assertNotNull(e.span)
        val syn = assist.preview("{{ 1 + }}", ctx()) as PreviewResult.Error
        assertNotNull(syn.span)
    }

    @Test fun previewBudgetStopsHeavyExpressionQuickly() {
        val t0 = System.nanoTime()
        val r = assist.preview("{{ range(0, 100000000).map(i => i * 2).sum() }}", ctx())
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 400)
        assertTrue(r is PreviewResult.Error)
    }

    @Test fun cancelledAsyncPreviewDoesNotBreakAnything() = runBlocking {
        val job = async(start = CoroutineStart.UNDISPATCHED) { assist.previewAsync("{{ range(0, 100000000).map(i => i).sum() }}", ctx()) }
        job.cancel()
        val ok = assist.previewAsync("{{ 1 + 1 }}", ctx())
        assertEquals(2.0, (ok as PreviewResult.Value).value)
    }

    // ---- diagnostics ----

    @Test fun unknownFieldGivesDidYouMean() {
        val text = "Hi {{ \$json.nme }}"
        val d = assist.diagnostics(text, scope).single()
        assertEquals("unknown.field", d.code); assertEquals("name", d.didYouMean)
        assertEquals("nme", text.substring(d.span.start, d.span.end))
        assertTrue(d.message.contains("did you mean \"name\""))
        val nested = assist.diagnostics("{{ \$json.customer.emial }}", scope).single()
        assertEquals("email", nested.didYouMean)
        assertTrue(assist.diagnostics("{{ \$json.name }} {{ \$json.customer.age }}", scope).isEmpty())
        assertEquals("unknown.field", assist.diagnostics("{{ \$step(\"Fetch\").first.titel }}", scope).single().code)
        assertEquals("title", assist.diagnostics("{{ \$step(\"Fetch\").titel }}", scope).single().didYouMean)
    }

    @Test fun nonUpstreamStepIsFlagged() {
        val d = assist.diagnostics("{{ \$step(\"Later\").first.x }}", scope).single()
        assertEquals("step.notUpstream", d.code); assertEquals(DiagnosticSeverity.Warning, d.severity)
        val unknown = assist.diagnostics("{{ \$step(\"Fetsh\").first.id }}", scope).single()
        assertEquals("step.unknown", unknown.code); assertEquals("Fetch", unknown.didYouMean)
        assertTrue(assist.diagnostics("{{ \$step(\"Fetch\").first.id }}", scope).isEmpty())
        assertTrue(assist.diagnostics("{{ \$step(\"Whatever\").first.id }}", SchemaScope.Empty).isEmpty())
    }

    @Test fun otherDiagnostics() {
        assertEquals("unknown.variable", assist.diagnostics("{{ \$jsno }}", scope).single().code)
        assertEquals("\$json", assist.diagnostics("{{ \$jsno }}", scope).single().didYouMean)
        assertEquals("unknown.function", assist.diagnostics("{{ foo(1) }}", scope).single().code)
        val m = assist.diagnostics("{{ \$json.name.trimm() }}", scope).single()
        assertEquals("trim", m.didYouMean)
        assertEquals("syntax", assist.diagnostics("{{ 1 + }}", scope).single().code)
        assertEquals("syntax", assist.diagnostics("x {{ \$json.name", scope).single().code)
        assertTrue(assist.diagnostics("{{ \$items.map(i => i.qty).sum() }}", scope).isEmpty())
        assertTrue(assist.diagnostics("{{ Math.round(\$json.price) }}", scope).isEmpty())
        assertEquals("Math", assist.diagnostics("{{ Mth.round(1) }}", scope).first().didYouMean)
    }

    @Test fun analyzeReportsReferences() {
        val a = assist.analyze("{{ \$step(\"Fetch\").first.id + \$json.qty }} {{ now() }}")
        assertEquals(setOf("Fetch"), a.steps); assertTrue("\$json" in a.variables); assertTrue("now" in a.functions)
        assertEquals(3, a.spans.size); assertTrue(a.errors.isEmpty())
        assertEquals(1, assist.analyze("{{ 1 + }}").errors.size)
    }

    // ---- completion ----

    private fun complete(text: String, cursorMarker: String = "|"): List<Completion> {
        val at = text.indexOf(cursorMarker)
        return assist.complete(text.replace(cursorMarker, ""), at, scope)
    }

    private fun apply(text: String, c: Completion): Pair<String, Int> =
        (text.substring(0, c.replace.start) + c.insert + text.substring(c.replace.end)) to (c.replace.start + c.caretOffset)

    @Test fun afterOpeningBracesOffersVariablesFirst() {
        val r = complete("{{|")
        assertTrue(r.take(6).all { it.kind == CompletionKind.Variable })
        assertTrue(r.any { it.label == "\$json" } && r.any { it.label == "\$step" })
        assertTrue(complete("plain|").isEmpty())
        assertTrue(complete("{{ \"abc|\" }}").isEmpty())
    }

    @Test fun afterJsonDotOffersSchemaFields() {
        val r = complete("{{ \$json.|")
        val fields = r.filter { it.kind == CompletionKind.Field }.map { it.label }
        assertTrue(fields.containsAll(listOf("name", "qty", "price", "tags", "customer", "first name")))
        val q = r.first { it.label == "qty" }
        assertEquals(SchemaType.Number, q.type); assertEquals("3", q.sample)
        val prefixed = complete("{{ \$json.na|")
        assertEquals("name", prefixed.first().label)
        assertEquals("{{ \$json.name", apply("{{ \$json.na", prefixed.first()).first)
    }

    @Test fun oddKeyFieldUsesBracketsAndCoversTheDot() {
        val text = "{{ \$json.first| }}"
        val c = complete(text).first { it.label == "first name" }
        assertEquals("[\"first name\"]", c.insert)
        assertEquals("{{ \$json[\"first name\"] }}", apply("{{ \$json.first }}", c).first)
    }

    @Test fun nestedAndStepFields() {
        assertEquals(setOf("email", "age"), complete("{{ \$json.customer.|").filter { it.kind == CompletionKind.Field }.map { it.label }.toSet())
        val s = complete("{{ \$step(\"Fetch\").first.|").filter { it.kind == CompletionKind.Field }.map { it.label }
        assertTrue(s.containsAll(listOf("id", "title")))
        val stream = complete("{{ \$step(\"Fetch\").|").map { it.label }
        assertTrue(stream.containsAll(listOf("first", "last", "count", "items", "id", "title")))
    }

    @Test fun stepNameCompletion() {
        val r = complete("{{ \$step(\"|")
        assertEquals(listOf("Fetch", "Later"), r.map { it.label })
        assertTrue(r[1].help!!.contains("Not upstream"))
        val typed = complete("{{ \$step('Fe|")
        assertEquals("Fetch", typed.first().label)
        assertEquals("{{ \$step('Fetch'", apply("{{ \$step('Fe", typed.first()).first)
        val closed = complete("{{ \$step(\"F|\") }}").first()
        assertEquals("{{ \$step(\"Fetch\") }}", apply("{{ \$step(\"F\") }}", closed).first)
    }

    @Test fun methodsByReceiverType() {
        fun labels(t: String) = complete(t).filter { it.kind == CompletionKind.Method }.map { it.label }
        assertTrue(labels("{{ \$json.name.|").containsAll(listOf("trim", "upper", "replace", "split")))
        assertTrue("map" !in labels("{{ \$json.name.|"))
        assertTrue(labels("{{ \$json.tags.|").containsAll(listOf("map", "filter", "join", "sum")))
        assertTrue(labels("{{ \$json.qty.|").containsAll(listOf("round", "toFixed", "pow")))
        assertTrue("trim" !in labels("{{ \$json.qty.|"))
        assertTrue(labels("{{ \$json.customer.|").containsAll(listOf("keys", "hasKey")))
        assertTrue(labels("{{ \$json.name.trim().|").isEmpty().not())
        assertTrue(labels("{{ Math.|").containsAll(listOf("round", "max")))
        assertTrue(labels("{{ \$json.tags[0].|").contains("trim"))
        assertTrue(labels("{{ \"abc\".|").contains("upper"))
    }

    @Test fun methodCompletionHasSignatureAndCaret() {
        val r = complete("{{ \$json.name.rep|").first { it.label == "replace" }
        assertEquals("replace(search, replacement)", r.help!!.substringAfter("text."))
        assertEquals("replace()", r.insert); assertEquals("replace(".length, r.caretOffset)
        val t = complete("{{ \$json.name.tri|").first { it.label == "trim" }
        assertEquals("trim()".length, t.caretOffset)
        val pad = complete("{{ \$json.name.padSt|").first { it.label == "padStart" }
        assertTrue(pad.help!!.contains("padStart(length, fill?)"))
    }

    @Test fun rankingPrefersPrefixThenFuzzy() {
        val r = complete("{{ \$json.pr|").map { it.label }
        assertEquals("price", r.first())
        val fuzzy = complete("{{ \$json.cstmr|").map { it.label }
        assertEquals("customer", fuzzy.first())
        val tie = complete("{{ \$json.|").filter { it.kind == CompletionKind.Field }
        assertTrue(tie.indices.all { i -> i == 0 || tie[i - 1].score >= tie[i].score })
        val v = complete("{{ \$j|").first()
        assertEquals("\$json", v.label)
    }

    @Test fun fieldsSeenInAllItemsRankFirst() {
        val partial = listOf(Item.of("a" to 1, "b" to 2), Item.of("a" to 3))
        val sc = SchemaScope(input = SchemaInference.infer(partial.asSequence()))
        val r = assist.complete("{{ \$json. }}", 9, sc).filter { it.kind == CompletionKind.Field }.map { it.label }
        assertEquals(listOf("a", "b"), r)
    }

    @Test fun globalFunctionAndNamespaceCompletion() {
        val r = complete("{{ coal|")
        assertEquals("coalesce", r.first().label)
        assertEquals(CompletionKind.Function, r.first().kind)
        val ns = complete("{{ Mat|").first()
        assertEquals("Math.", ns.insert); assertEquals(CompletionKind.Namespace, ns.kind)
        assertEquals("\$step(\"\")", complete("{{ \$ste|").first().insert)
        assertEquals(7, complete("{{ \$ste|").first().caretOffset)
    }

    @Test fun completionInExpressionModeAndMidText() {
        val r = assist.complete("\$json.na", 8, scope, ParseMode.Expression)
        assertEquals("name", r.first().label)
        val text = "a {{ \$json.na }} b {{ \$json.qty }}"
        val c = assist.complete(text, text.indexOf("na }}") + 2, scope).first()
        assertEquals("name", c.label)
        assertEquals("a {{ \$json.name }} b {{ \$json.qty }}", apply(text, c).first)
    }

    @Test fun unknownReceiverStillOffersMethods() {
        val r = assist.complete("{{ \$json.nothing. }}", 17, scope)
        assertTrue(r.isNotEmpty() && r.all { it.kind == CompletionKind.Method })
        assertNull(r.firstOrNull { it.kind == CompletionKind.Field })
    }
}
