package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.ParamResolver
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import kotlinx.collections.immutable.persistentListOf

/**
 * Tiny vocabulary shared by the extended Step executors. Every executor still receives a plain
 * [StepContext]; these helpers only remove the repetitive "resolve this template against item i"
 * boilerplate, so the code reads like the automation it performs.
 */
internal fun StepContext.resolve(raw: Any?, index: Int = 0): Any? =
    ParamResolver(evaluator).resolveValue(raw, scope(index))

internal fun StepContext.resolveText(raw: Any?, index: Int = 0, fallback: String = ""): String =
    Values.asString(resolve(raw, index), fallback)

internal fun StepContext.resolveNumber(raw: Any?, index: Int = 0, fallback: Double = 0.0): Double =
    Values.asDouble(resolve(raw, index)) ?: fallback

internal fun StepContext.resolveBoolean(raw: Any?, index: Int = 0, fallback: Boolean = false): Boolean =
    when (val resolved = resolve(raw, index)) {
        null -> fallback
        else -> Values.asBoolean(resolved)
    }

/** Secret params are never expression-expanded; read them straight from the raw Step params. */
internal fun StepContext.secret(key: String): String = Values.asString(step.params[key])

/** Connected-account credentials expose `accessToken`; classic vault entries expose `apiKey`. */
internal fun Map<String, String>.bearerToken(): String = com.kaivoflow.core.model.CredentialFields.bearer(this)

/** Output field names are resolved once against the first item; they are configuration, not data. */
internal fun StepContext.outputName(default: String): String = Values.asString(
    ParamResolver(evaluator).resolveValue(step.params["outputField"], scope()), default,
)

/** Kept for Step executors that already hold a resolved parameter map. */
internal fun outputField(params: Map<String, Any?>, default: String): String =
    Values.asString(params["outputField"], default)

internal fun selectOptions(vararg pairs: Pair<String, String>) =
    persistentListOf(*pairs.map { ParamOption(it.first, it.second) }.toTypedArray())

internal fun textParam(
    key: String,
    label: String,
    default: String? = null,
    placeholder: String = "",
    required: Boolean = false,
    hint: String = "",
    advanced: Boolean = false,
    showWhen: Map<String, List<Any?>> = emptyMap(),
) = ParamSpec(
    key = key,
    label = label,
    type = ParamType.Text,
    required = required,
    default = default,
    placeholder = placeholder,
    hint = hint,
    advanced = advanced,
    showWhen = showWhen,
)
