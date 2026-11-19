package com.kaivoflow.core.engine

import com.kaivoflow.core.model.CredentialFieldSpec
import com.kaivoflow.core.model.CredentialRef
import com.kaivoflow.core.model.CredentialTypeInfo
import com.kaivoflow.core.model.ExprFieldCodec
import com.kaivoflow.core.model.ParamValue
import com.kaivoflow.core.model.ParamValueCodec
import kotlinx.coroutines.CancellationException

// Expressions outside nodes (spec P3). Scope here is global: `$vars`, `$now`, `$today`, `$run` flow info.
// There is no item data and never an environment. Every entry point returns a result instead of throwing,
// so a bad expression becomes a message, not a crash.

object GlobalScope {
    fun build(
        vars: Map<String, Any?> = emptyMap(),
        flowId: String = "",
        flowName: String = "",
        timezone: String = "UTC",
        runId: String = "global",
        clock: () -> Long = { System.currentTimeMillis() },
    ): Map<String, Any?> =
        ScopeBuilder(flowVars = vars, runId = runId, stepOutputs = emptyMap(), clock = clock, timezone = timezone, flowId = flowId, flowName = flowName)
            .build(emptyList(), 0, "")
}

sealed interface Resolved {
    data class Ok(val value: Any?) : Resolved
    /** [message] names the field and says what is wrong, ready to show. */
    data class Failed(val message: String) : Resolved

    val valueOrNull: Any? get() = (this as? Ok)?.value
}

class UniversalResolver(private val evaluator: ExpressionEvaluator) {

    /** A fixed value is returned as it is, an expression is evaluated against [scope]. */
    fun resolve(value: ParamValue?, scope: Map<String, Any?>, label: String): Resolved = when (value) {
        null -> Resolved.Ok(null)
        is ParamValue.Fixed -> Resolved.Ok(value.value)
        is ParamValue.Expr -> evaluate(value.text, scope, label)
    }

    /** Like [resolve] but the outcome is text. */
    fun resolveText(value: ParamValue?, scope: Map<String, Any?>, label: String): Resolved =
        when (val r = resolve(value, scope, label)) {
            is Resolved.Failed -> r
            is Resolved.Ok -> Resolved.Ok(Values.asString(r.value))
        }

    /** For a string field written by [ExprFieldCodec]: plain strings stay fixed. */
    fun resolveStored(stored: String?, scope: Map<String, Any?>, label: String): Resolved =
        resolveText(ExprFieldCodec.decode(stored), scope, label)

    private fun evaluate(text: String, scope: Map<String, Any?>, label: String): Resolved = try {
        Resolved.Ok(evaluator.interpolate(text, scope))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Resolved.Failed("$label: the expression could not be evaluated. ${e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName}")
    }
}

/** Workspace and flow variables: values may be expressions. */
object VariableResolution {
    data class Result(val vars: Map<String, Any?>, val errors: Map<String, String>)

    /**
     * [raw] holds fixed values and expressions in either storage form (strings from [ExprFieldCodec], values from
     * [ParamValueCodec]). Fixed values are read first and are what `$vars` shows inside an expression, so a
     * variable never depends on another expression variable and cycles cannot happen. A failed variable is
     * left out of the result, so reading it gives the usual "missing" value, and its message is in [Result.errors].
     */
    fun resolve(
        raw: Map<String, Any?>,
        evaluator: ExpressionEvaluator,
        flowId: String = "",
        flowName: String = "",
        timezone: String = "UTC",
        runId: String = "global",
        clock: () -> Long = { System.currentTimeMillis() },
    ): Result {
        val decoded = raw.entries.associate { (k, v) ->
            k to (if (v is String) ExprFieldCodec.decode(v) else ParamValueCodec.decode(v) ?: ParamValue.Fixed(null))
        }
        if (decoded.values.none { it is ParamValue.Expr }) return Result(raw, emptyMap())
        val out = LinkedHashMap<String, Any?>()
        decoded.forEach { (k, pv) -> if (pv is ParamValue.Fixed) out[k] = pv.value }
        val scope = GlobalScope.build(LinkedHashMap(out), flowId, flowName, timezone, runId, clock)
        val resolver = UniversalResolver(evaluator)
        val errors = LinkedHashMap<String, String>()
        decoded.forEach { (k, pv) ->
            if (pv is ParamValue.Expr) {
                when (val r = resolver.resolve(pv, scope, "Variable \"$k\"")) {
                    is Resolved.Ok -> out[k] = r.value
                    is Resolved.Failed -> errors[k] = r.message
                }
            }
        }
        return Result(out, errors)
    }
}

/** Non-secret credential fields may be expressions. Secret fields and unknown credential types are never evaluated. */
object CredentialExpr {
    data class Result(val fields: Map<String, String>, val errors: Map<String, String>)

    fun hasExpression(fields: Map<String, String>): Boolean = fields.values.any { ExprFieldCodec.isExpression(it) }

    fun resolveFields(type: String?, fields: Map<String, String>, evaluator: ExpressionEvaluator, scope: Map<String, Any?>): Result {
        if (!hasExpression(fields)) return Result(fields, emptyMap())
        val info = type?.let { CredentialTypeInfo.find(it) }
        val resolver = UniversalResolver(evaluator)
        val out = LinkedHashMap<String, String>()
        val errors = LinkedHashMap<String, String>()
        for ((key, stored) in fields) {
            val spec: CredentialFieldSpec? = info?.fields?.firstOrNull { it.key == key }
            if (spec == null || spec.isSecret || !ExprFieldCodec.isExpression(stored)) { out[key] = stored; continue }
            when (val r = resolver.resolveStored(stored, scope, "Credential field \"${spec.label}\"")) {
                is Resolved.Ok -> out[key] = r.value as? String ?: ""
                is Resolved.Failed -> errors[key] = r.message
            }
        }
        return Result(out, errors)
    }
}

/**
 * Wraps the vault so every node, however it reads a credential, gets non-secret fields already
 * evaluated. A bad expression throws a [KaivoException] with the field name; the Step fails like any
 * other Step error and the Run carries on by its error policy.
 */
class ExprCredentialProvider(
    private val delegate: CredentialProvider,
    private val evaluator: ExpressionEvaluator,
    private val vars: suspend () -> Map<String, Any?>,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : CredentialProvider {
    override suspend fun resolve(ref: String): Map<String, String> {
        val fields = delegate.resolve(ref)
        if (!CredentialExpr.hasExpression(fields)) return fields
        val type = delegate.list().firstOrNull { it.id == ref }?.type
        val scope = GlobalScope.build(runCatching { vars() }.getOrDefault(emptyMap()), clock = clock)
        val r = CredentialExpr.resolveFields(type, fields, evaluator, scope)
        r.errors.values.firstOrNull()?.let { throw KaivoException(it) }
        return r.fields
    }

    override suspend fun list(): List<CredentialRef> = delegate.list()
}
