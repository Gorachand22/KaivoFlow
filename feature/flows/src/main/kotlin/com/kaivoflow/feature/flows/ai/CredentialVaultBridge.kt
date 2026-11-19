package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.data.CredentialRepository
import com.kaivoflow.core.data.WorkflowVariableRepository
import com.kaivoflow.core.engine.CredentialExpr
import javax.inject.Inject
import javax.inject.Singleton

/** The parts of a vault credential an AI screen needs. [apiKey] is a plain string only until it is handed to `:core:ai`. */
data class AiCredential(val id: String, val name: String, val type: String, val provider: AiProvider, val apiKey: String, val baseUrl: String) {
    override fun toString(): String = "AiCredential(id=$id, type=$type, apiKey=${if (apiKey.isBlank()) "<none>" else "***"})"
}

/**
 * The one place the AI screens read a credential from the vault. It replaces the copies in the assistant and
 * Agent Builder view models: it resolves non-secret fields (a base URL may be an expression, spec P3), never
 * evaluates the key, and reports a bad expression as text instead of throwing.
 */
@Singleton
class CredentialVaultBridge @Inject constructor(
    private val credentials: CredentialRepository,
    variables: WorkflowVariableRepository,
) {
    private val fields = CredentialFieldResolver(variables)

    sealed interface Loaded {
        data class Ok(val credential: AiCredential) : Loaded
        data class Failed(val message: String) : Loaded
    }

    /**
     * Reads credential [id]. Expressions in non-secret fields are evaluated in the workspace-variable scope, or by
     * [evaluate] when the caller has its own scope (an agent's global scope).
     */
    suspend fun load(id: String, type: String?, name: String = "", evaluate: ((String?, Map<String, String>) -> CredentialExpr.Result)? = null): Loaded {
        val stored = credentials.getFields(id) ?: return Loaded.Failed("That credential could not be found.")
        val resolved = when {
            evaluate != null -> evaluate(type, stored)
            else -> fields.resolve(type, stored)
        }
        resolved.errors.values.firstOrNull()?.let { return Loaded.Failed(it) }
        val f = resolved.fields
        val kind = type?.let { AiProvider.fromKey(it) } ?: AiProvider.OpenAI
        return Loaded.Ok(AiCredential(id, name, type.orEmpty(), kind, f["apiKey"] ?: f["token"] ?: "", f["baseUrl"].orEmpty()))
    }
}
