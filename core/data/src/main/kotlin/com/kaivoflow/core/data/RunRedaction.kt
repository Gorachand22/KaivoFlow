package com.kaivoflow.core.data

import com.kaivoflow.core.engine.stepdata.CapturedExecution
import com.kaivoflow.core.engine.stepdata.CapturedPort
import com.kaivoflow.core.engine.stepdata.StepDataRedactor
import com.kaivoflow.core.engine.stepdata.StepDataStore
import com.kaivoflow.core.engine.stepdata.StepErrorInfo
import com.kaivoflow.core.model.RunRecord
import kotlinx.collections.immutable.toImmutableList

/**
 * Masks text that is about to be stored or shown. Layers: the vault's exact secret values, the step-data
 * redactor's shapes (bearer, JWT, sk-, URL passwords, secret query parameters), and extra shapes the audit
 * found missing (Telegram bot tokens, Stripe keys, Google OAuth tokens, Slack webhook URLs, key-like headers).
 */
class SecretMasker(secretValues: Collection<String>) {
    private val base = StepDataRedactor(secretValues)

    fun mask(text: String): String {
        if (text.isEmpty()) return text
        var s = base.redactString(text)
        for (p in EXTRA) s = p.replace(s) { m -> if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty() && m.groupValues[1] != m.value) m.groupValues[1] + MASK else MASK }
        return s
    }

    fun maskOrNull(text: String?): String? = text?.let(::mask)

    fun maskValue(v: Any?): Any? = base.redact(v)

    companion object {
        const val MASK = StepDataRedactor.MASK
        private val EXTRA = listOf(
            Regex("""(?<![0-9])\d{6,12}:[A-Za-z0-9_-]{30,}"""),
            Regex("""\b(?:sk|rk|pk)_(?:live|test)_[A-Za-z0-9]{16,}"""),
            Regex("""\bya29\.[A-Za-z0-9._-]{20,}"""),
            Regex("""https://hooks\.slack\.com/services/[A-Za-z0-9/_-]+"""),
            Regex("""(?i)((?:x-api-key|x-goog-api-key|api-key|set-cookie|cookie|x-auth-token)["']?\s*[:=]\s*["']?)[^\s"',;}]{4,}"""),
            Regex("""\bAIza[0-9A-Za-z_-]{20,}"""),
        )
    }
}

/** Builds a [SecretMasker] from the vault's current values. */
suspend fun SecretValueSource.masker(): SecretMasker = SecretMasker(values())

/**
 * Wraps the Run repository so the error text of a Run and of every Step in it is masked before it is
 * saved (audit I-05). Reads are untouched. Fails closed: if the vault values cannot be read, shape-based
 * masking still runs.
 */
class RedactingRunRepository(
    private val delegate: RunRepository,
    private val secrets: SecretValueSource,
) : RunRepository by delegate {
    override suspend fun save(run: RunRecord) {
        val masker = runCatching { secrets.masker() }.getOrDefault(SecretMasker(emptyList()))
        val clean = run.copy(
            error = masker.maskOrNull(run.error),
            stepRuns = run.stepRuns.map { it.copy(error = masker.maskOrNull(it.error)) }.toImmutableList(),
        )
        delegate.save(clean)
    }
}

/**
 * Wraps the Step data store so each captured execution is masked with the real credential values before
 * it is stored (audit DB-11, I-06): error message, details and HTTP body, and every stored item. The engine's
 * own capture already redacts by shape; this adds the exact secret values it never had.
 */
class RedactingStepDataStore(
    private val delegate: StepDataStore,
    private val secrets: SecretValueSource,
) : StepDataStore by delegate {
    override suspend fun write(execution: CapturedExecution) {
        val values = runCatching { secrets.values() }.getOrDefault(emptyList())
        val masker = SecretMasker(values)
        val items = StepDataRedactor(values)
        fun port(p: CapturedPort) = p.copy(items = p.items.map { items.redactItem(it) })
        val error = execution.record.error?.let { e ->
            e.copy(
                message = masker.mask(e.message),
                details = masker.maskOrNull(e.details),
                httpBody = masker.maskOrNull(e.httpBody),
            )
        }
        delegate.write(
            execution.copy(
                record = execution.record.copy(error = error),
                inputs = execution.inputs.map(::port),
                outputs = execution.outputs.map(::port),
            )
        )
    }
}
