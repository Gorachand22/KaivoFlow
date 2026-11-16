package com.kaivoflow.feature.canvas.inspector.logic

import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.feature.canvas.inspector.state.ParentPolicy
import com.kaivoflow.feature.canvas.inspector.state.PreviewState

/** One upstream connection of a Step: the parent Step and the output port that feeds it. */
data class ParentEdge(val stepId: String, val port: Int = 0)

/** What the planner needs to know about the data of a Step: stored from an earlier Run or Test, or pinned. */
fun interface DataAvailability {
    fun hasData(stepId: String): Boolean
}

sealed interface TestPlan {
    /** Test only this Step. Parents with data feed it; a Step without parents gets one empty item. */
    data object RunNow : TestPlan
    /** Test these Steps in order (missing ancestors first), then this one. */
    data class RunInOrder(val steps: List<String>) : TestPlan
    /** The input is missing: ask the user. [stepIds] are the parents without data. */
    data class NeedsParents(val stepIds: List<String>) : TestPlan
}

/** Decides what Test Step does when upstream data may be missing (spec P2 5.8). Pure and JVM-tested. */
object TestPlanner {
    const val MAX_ANCESTORS = 12

    /**
     * [parentsOf] returns the connections that feed a Step. A Step with no connections is always runnable.
     * Policy [ParentPolicy.Ask] stops with [TestPlan.NeedsParents]; [ParentPolicy.UsePinnedOrLast] runs with whatever exists
     * (the engine falls back to one empty item); [ParentPolicy.RunThem] tests the missing ancestors first.
     */
    fun plan(stepId: String, parentsOf: (String) -> List<ParentEdge>, data: DataAvailability, policy: ParentPolicy): TestPlan {
        val direct = parentsOf(stepId).map { it.stepId }.distinct()
        val missing = direct.filterNot(data::hasData)
        if (direct.isEmpty() || missing.isEmpty()) return TestPlan.RunNow
        return when (policy) {
            ParentPolicy.Ask -> TestPlan.NeedsParents(missing)
            ParentPolicy.UsePinnedOrLast -> TestPlan.RunNow
            ParentPolicy.RunThem -> {
                val order = LinkedHashSet<String>()
                val visiting = HashSet<String>()
                fun visit(id: String) {
                    if (id in order || !visiting.add(id) || order.size >= MAX_ANCESTORS) return
                    for (p in parentsOf(id).map { it.stepId }.distinct()) if (!data.hasData(p)) visit(p)
                    order += id
                }
                missing.forEach(::visit)
                TestPlan.RunInOrder(order.toList() + stepId)
            }
        }
    }
}

/** Where a failure message points inside an expression field. */
data class ErrorSpot(val key: String, val start: Int, val end: Int, val detail: String)

/**
 * Turns an engine failure message such as `Parameter "url" ... (line 1, column 9)` into a highlight
 * range in one expression field. The engine reports only text, so the field is found by the quoted
 * parameter name or, failing that, when exactly one parameter holds an expression.
 */
object ErrorSurface {
    private val position = Regex("""\(line (\d+), column (\d+)\)""")
    private val quotedKey = Regex("""[Pp]arameter "([^"]+)"""")

    fun locate(message: String, values: Map<String, ParamValue?>): ErrorSpot? {
        val pos = position.find(message) ?: return null
        val exprs = values.filter { it.value is ParamValue.Expr }
        if (exprs.isEmpty()) return null
        val named = quotedKey.find(message)?.groupValues?.get(1)
        val key = when {
            named != null && named in exprs -> named
            exprs.size == 1 -> exprs.keys.first()
            else -> return null
        }
        val text = (exprs.getValue(key) as ParamValue.Expr).text
        val line = pos.groupValues[1].toInt()
        val col = pos.groupValues[2].toInt()
        val at = offsetOf(text, line, col) ?: return null
        return ErrorSpot(key, at, tokenEnd(text, at), message.substringBefore(" (line").trim())
    }

    /** 0-based offset of a 1-based line and column, or null when outside the text. */
    fun offsetOf(text: String, line: Int, column: Int): Int? {
        if (line < 1 || column < 1) return null
        var start = 0
        repeat(line - 1) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) return null
            start = nl + 1
        }
        val at = start + column - 1
        return if (at <= text.length) at else null
    }

    private fun tokenEnd(text: String, at: Int): Int {
        if (at >= text.length) return text.length
        var e = at + 1
        if (text[at].isLetterOrDigit() || text[at] == '_' || text[at] == '$') {
            while (e < text.length && (text[e].isLetterOrDigit() || text[e] == '_')) e++
        }
        return e
    }

    /** The preview the located field shows instead of its normal one. */
    fun asPreview(spot: ErrorSpot): PreviewState.Error = PreviewState.Error(spot.detail, spot.start, spot.end)
}
