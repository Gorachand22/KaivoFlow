package com.kaivoflow.core.engine

import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.StepNode

/**
 * Expression support.
 *
 * Android implementations live in `:core:expressions` (QuickJS). Keeping the interface here
 * means the engine can be fully unit-tested on a plain JVM with a stub evaluator, and any
 * future sandbox (V8, Wasm, remote) can be dropped in without touching the runner.
 */
interface ExpressionEvaluator {

    /**
     * Evaluates a bare expression body (without the surrounding `{{ }}`).
     * The result keeps its JSON type (number stays a number, object stays a map).
     */
    fun evaluate(code: String, scope: Map<String, Any?>): Any?

    /**
     * Resolves a template string.
     *
     * - `"{{ ${'$'}json.total }}"` → typed value (Double)
     * - `"Total: {{ ${'$'}json.total }} INR"` → String with the value interpolated
     * - `"plain text"` → returned unchanged, never evaluated (fast path used by the engine)
     */
    fun interpolate(template: String, scope: Map<String, Any?>): Any? {
        val trimmed = template.trim()
        if (!trimmed.contains("{{")) return template
        val single = SINGLE_EXPRESSION.matchEntire(trimmed)
        if (single != null) return evaluate(single.groupValues[1].trim(), scope)
        return interpolateAll(template, scope)
    }

    fun interpolateAll(template: String, scope: Map<String, Any?>): String {
        val sb = StringBuilder()
        var cursor = 0
        for (m in MULTI_EXPRESSION.findAll(template)) {
            sb.append(template, cursor, m.range.first)
            val value = evaluate(m.groupValues[1].trim(), scope)
            sb.append(Values.asString(value))
            cursor = m.range.last + 1
        }
        sb.append(template, cursor, template.length)
        return sb.toString()
    }

    companion object {
        private val SINGLE_EXPRESSION = Regex("""^\{{2,3}([\s\S]*?)\}{2,3}$""")
        private val MULTI_EXPRESSION = Regex("""\{{2,3}([\s\S]*?)\}{2,3}""")
    }
}

/**
 * Resolves Step parameters into concrete values, applying expressions where needed and
 * stripping the "expression" wrapping for single-value fields.
 */
class ParamResolver(private val evaluator: ExpressionEvaluator) {

    fun resolveValue(value: Any?, scope: Map<String, Any?>, supportsExpression: Boolean = true): Any? {
        if (!supportsExpression) return value
        return when (value) {
            is String -> if (value.contains("{{")) evaluator.interpolate(value, scope) else value
            is Map<*, *> -> value.entries.associate { it.key.toString() to resolveValue(it.value, scope) }
            is List<*> -> value.map { resolveValue(it, scope) }
            else -> value
        }
    }

    fun resolveParams(step: StepNode, scope: Map<String, Any?>, spec: NodeSpec? = null): Map<String, Any?> {
        val literal = spec?.params?.filter { !it.supportsExpression }?.map { it.key }?.toSet().orEmpty()
        return step.params.entries.associate { (key, raw) ->
            key to resolveValue(raw, scope, supportsExpression = key !in literal)
        }
    }

    fun resolveString(value: Any?, scope: Map<String, Any?>, field: String): String {
        val resolved = resolveValue(value, scope) ?: return ""
        return Values.asString(resolved)
    }
}

/**
 * Builds the `$…` scope a Step sees. Mirrors the documented variable surface:
 * `${'$'}json`, `${'$'}items`, `${'$'}vars`, `${'$'}now`, `${'$'}today`, `$run`, `${'$'}step()`, `$index`.
 */
class ScopeBuilder(
    private val flowVars: Map<String, Any?>,
    private val runId: String,
    private val stepOutputs: Map<String, List<FlowData>>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timezone: String = "UTC",
) {
    fun build(input: List<FlowData>, index: Int = 0, stepName: String = ""): Map<String, Any?> {
        val first = input.getOrNull(index)?.json ?: emptyMap()
        val nowMillis = clock()
        val now = java.time.Instant.ofEpochMilli(nowMillis)
        val zone = runCatching { java.time.ZoneId.of(timezone) }.getOrDefault(java.time.ZoneId.of("UTC"))
        val atZone = now.atZone(zone)

        val previous = stepOutputs.mapValues { (_, items) -> items.mapIndexed { i, d -> d.json + ("index" to i) } }

        return mapOf(
            "json" to first,
            "items" to input.map { it.json },
            "item" to first,
            "index" to index,
            "vars" to flowVars.toMap(),
            "now" to now.toString(),
            "today" to atZone.toLocalDate().toString(),
            "run" to mapOf(
                "id" to runId,
                "timestamp" to nowMillis,
                "timezone" to timezone,
                "step" to stepName,
            ),
            "step" to previous,
        )
    }
}
