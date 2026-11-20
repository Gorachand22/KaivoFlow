package com.kaivoflow.feature.flows.ai.agent

import com.kaivoflow.core.ai.Redactor
import com.kaivoflow.core.data.CredentialRepository
import com.kaivoflow.core.data.FlowLifecycleRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.RunRepository
import com.kaivoflow.core.engine.ActivationResult
import com.kaivoflow.core.engine.LifecycleSnapshot
import com.kaivoflow.core.engine.PublishPreview
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.work.alwayson.ActivationManager
import com.kaivoflow.feature.flows.ai.CredentialVaultBridge
import com.kaivoflow.feature.flows.ai.ModelDiscoveryService
import com.kaivoflow.core.engine.HttpGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow as KFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The hidden Flow lives in the normal Flow store (so the engine, Reconciler and boot restore see it like any Flow). */
@Singleton
class RoomAgentFlowPort @Inject constructor(private val flows: FlowRepository) : AgentFlowPort {
    override suspend fun flow(id: String): Flow? = flows.flow(id)
    override suspend fun save(flow: Flow) = flows.save(flow)
    override suspend fun delete(id: String) = flows.delete(id)

    override fun observeAgentStates(): KFlow<Map<String, AgentLiveState>> = flows.observeSummaries().map { list ->
        list.filter { AgentIds.isAgentFlow(it.id) }.associate {
            AgentIds.agentId(it.id)!! to AgentLiveState.from(it.lifecycle, it.publishedRevision, it.lifecycleSince, it.pausedUntil, it.errorCode, it.nextRetryAt)
        }
    }
}

/** Delegates every lifecycle change to ActivationManager, the single owner of the publish lifecycle. */
@Singleton
class EngineAgentActivationPort @Inject constructor(
    private val manager: ActivationManager,
    private val lifecycle: FlowLifecycleRepository,
) : AgentActivationPort {
    override suspend fun snapshot(flowId: String): LifecycleSnapshot? = lifecycle.snapshot(flowId)
    override suspend fun publish(flowId: String, acknowledgeWarnings: Boolean): ActivationResult = manager.publish(flowId, acknowledgeWarnings)
    override suspend fun publishChanges(flowId: String, acknowledgeWarnings: Boolean): ActivationResult = manager.publishChanges(flowId, acknowledgeWarnings)
    override suspend fun pause(flowId: String, untilMs: Long?): ActivationResult = manager.pause(flowId, untilMs)
    override suspend fun resume(flowId: String): ActivationResult = manager.resume(flowId)
    override suspend fun unpublish(flowId: String): ActivationResult = manager.unpublish(flowId, confirmed = true)
    override suspend fun retryNow(flowId: String): ActivationResult = manager.retryNow(flowId)
    override suspend fun delete(flowId: String) = manager.delete(flowId)
    override suspend fun preview(flowId: String): PublishPreview? = manager.preview(flowId)
}

@Singleton
class VaultAgentCredentialPort @Inject constructor(
    private val credentials: CredentialRepository,
    private val vault: CredentialVaultBridge,
    http: HttpGateway,
) : AgentCredentialPort {
    private val discovery = ModelDiscoveryService(http)

    override suspend fun info(credentialId: String): AgentCredentialInfo? {
        val item = credentials.observeCredentials().first().firstOrNull { it.id == credentialId } ?: return null
        val loaded = vault.load(item.id, item.type, item.name) as? CredentialVaultBridge.Loaded.Ok ?: return null
        return AgentCredentialInfo(item.id, loaded.credential.provider, loaded.credential.baseUrl)
    }

    override suspend fun probe(info: AgentCredentialInfo, model: String): CredentialProbe {
        val item = credentials.observeCredentials().first().firstOrNull { it.id == info.id }
            ?: return CredentialProbe.Failed("the credential is no longer in the vault")
        val loaded = vault.load(item.id, item.type, item.name)
        val cred = (loaded as? CredentialVaultBridge.Loaded.Ok)?.credential
            ?: return CredentialProbe.Failed((loaded as CredentialVaultBridge.Loaded.Failed).message)
        return try {
            discovery.discoverModels(cred.provider, cred.baseUrl, cred.apiKey).fold(
                onSuccess = { models -> CredentialProbe.Working(models.isEmpty() || models.contains(model)) },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    CredentialProbe.Failed(Redactor(listOf(cred.apiKey)).redact(e.message ?: e.javaClass.simpleName).take(200))
                },
            )
        } catch (e: CancellationException) {
            throw e
        }
    }
}

/** Run history of an agent: the normal Run records of its hidden Flow, as sessions. */
@Singleton
class AgentRunHistorySource @Inject constructor(private val runs: RunRepository) {
    fun observe(agentId: String, limit: Int = 50): KFlow<List<AgentSession>> =
        runs.observeForFlow(AgentIds.flowId(agentId), limit).map { list -> list.map(AgentRunHistory::toSession) }
}
