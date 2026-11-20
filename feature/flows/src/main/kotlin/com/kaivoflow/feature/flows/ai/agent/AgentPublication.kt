package com.kaivoflow.feature.flows.ai.agent

import com.kaivoflow.core.engine.ActivationResult
import com.kaivoflow.core.engine.ActivationStatus
import com.kaivoflow.core.engine.ChangeDetector
import com.kaivoflow.core.engine.LifecycleSnapshot
import com.kaivoflow.core.engine.LifecycleState
import com.kaivoflow.core.engine.PublishPreview
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.RunRecord
import com.kaivoflow.core.model.RunStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow as KFlow

/**
 * What the engine says about an agent's published Flow. There is no stored flag behind it: every value comes from the lifecycle
 * state the Always-On engine keeps for the hidden Flow `agent:<id>`, so it cannot claim "Live" when nothing is armed.
 */
sealed interface AgentLiveState {
    /** No publication, or it was unpublished. Test runs and edits never change this. */
    data object NotPublished : AgentLiveState

    /** Armed and running on its own. [changesNotLive] is true when the saved agent differs from what is armed. */
    data class Live(val revision: Int?, val since: Long, val changesNotLive: Boolean = false) : AgentLiveState
    data class Paused(val until: Long?, val since: Long) : AgentLiveState

    /** Published, but a trigger could not be armed or keeps failing. It may still run partly; it is not healthy. */
    data class Failing(val code: String?, val nextRetryAt: Long?, val since: Long) : AgentLiveState

    val isLive: Boolean get() = this is Live || this is Failing || this is Paused

    companion object {
        fun from(state: LifecycleState, revision: Int?, since: Long, pausedUntil: Long?, errorCode: String?, nextRetryAt: Long?): AgentLiveState = when (state) {
            LifecycleState.Draft -> NotPublished
            LifecycleState.Published -> Live(revision, since)
            LifecycleState.Paused -> Paused(pausedUntil, since)
            LifecycleState.Error -> Failing(errorCode, nextRetryAt, since)
        }

        fun from(s: LifecycleSnapshot?): AgentLiveState =
            if (s == null) NotPublished else from(s.state, s.publishedRevision, s.since, s.pausedUntil, s.errorCode, s.nextRetryAt)
    }
}

sealed interface AgentPublishOutcome {
    /** Agent-specific checks failed. Nothing was saved or armed. */
    data class Refused(val issues: List<AgentIssue>) : AgentPublishOutcome

    /** Only warnings. Call again with `acknowledge = true` to publish anyway. */
    data class NeedsAcknowledgement(val warnings: List<String>) : AgentPublishOutcome

    /** The engine's own publish checks (permissions, webhook token, schedule) blocked it. */
    data class Blocked(val problems: List<String>) : AgentPublishOutcome

    /** A trigger could not be armed; the previous revision (if any) keeps running. */
    data class ArmFailed(val details: List<String>) : AgentPublishOutcome
    data class Done(val revision: Int?, val state: AgentLiveState, val warnings: List<String>) : AgentPublishOutcome
    data object Unchanged : AgentPublishOutcome
    data class Failed(val message: String) : AgentPublishOutcome
}

/** Storage of the hidden Flow. */
interface AgentFlowPort {
    suspend fun flow(id: String): Flow?
    suspend fun save(flow: Flow)
    suspend fun delete(id: String)

    /** The engine's lifecycle state of every agent Flow, keyed by agent id. Agents that were never published are absent. */
    fun observeAgentStates(): KFlow<Map<String, AgentLiveState>>
}

/** The Always-On engine. The Android adapter delegates to ActivationManager; nothing else may arm an agent. */
interface AgentActivationPort {
    suspend fun snapshot(flowId: String): LifecycleSnapshot?
    suspend fun publish(flowId: String, acknowledgeWarnings: Boolean): ActivationResult
    suspend fun publishChanges(flowId: String, acknowledgeWarnings: Boolean): ActivationResult
    suspend fun pause(flowId: String, untilMs: Long?): ActivationResult
    suspend fun resume(flowId: String): ActivationResult
    suspend fun unpublish(flowId: String): ActivationResult
    suspend fun retryNow(flowId: String): ActivationResult
    suspend fun delete(flowId: String)
    suspend fun preview(flowId: String): PublishPreview?
}

interface AgentCredentialPort {
    suspend fun info(credentialId: String): AgentCredentialInfo?

    /** One live call that proves the key works (lists models). Never returns or logs the key. */
    suspend fun probe(info: AgentCredentialInfo, model: String): CredentialProbe
}

/**
 * Publish (Run 24/7) for an agent: compile, check, save the hidden Flow, hand it to the Always-On engine.
 * Pause, resume, unpublish and delete go to the same engine. All decisions about the lifecycle are the engine's
 * (ActivationCoordinator); this class only adds the agent-specific checks and the compile.
 */
class AgentPublicationService(
    private val agents: AgentRepository,
    private val flows: AgentFlowPort,
    private val activation: AgentActivationPort,
    private val credentials: AgentCredentialPort,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun publish(agentId: String, acknowledge: Boolean = false): AgentPublishOutcome {
        val agent = agents.agentById(agentId) ?: return AgentPublishOutcome.Failed("This agent no longer exists.")
        val flowId = AgentIds.flowId(agent.id)
        val info = agent.credentialId?.let { credentials.info(it) }
        // The live call only runs when the cheap checks already pass, so a typo does not cost a request.
        val pre = AgentProductionCompiler.compile(agent, info)
        if (pre.blocking.isNotEmpty()) return AgentPublishOutcome.Refused(pre.issues)
        val probe = credentials.probe(info!!, agent.model)
        val compiled = AgentProductionCompiler.compile(agent, info, probe)
        val flow = compiled.flow ?: return AgentPublishOutcome.Refused(compiled.issues)
        val warnings = compiled.warnings.map { it.message }
        if (warnings.isNotEmpty() && !acknowledge) return AgentPublishOutcome.NeedsAcknowledgement(warnings)

        val snap = activation.snapshot(flowId)
        val state = snap?.state ?: LifecycleState.Draft
        val existing = flows.flow(flowId)
        val now = clock()
        flows.save(flow.copy(createdAt = existing?.createdAt ?: now, updatedAt = now))

        val result = when (state) {
            LifecycleState.Draft -> activation.publish(flowId, acknowledge)
            LifecycleState.Published -> activation.publishChanges(flowId, acknowledge)
            LifecycleState.Paused -> {
                // Edits to a paused agent go live with the resume, so the person gets what they just saved, not the old revision.
                val r = activation.resume(flowId)
                if (!r.ok) r else activation.publishChanges(flowId, acknowledge)
            }
            LifecycleState.Error -> return AgentPublishOutcome.Failed("The agent is in an error state (${snap?.errorCode ?: "unknown"}). Fix the cause or retry, then publish the changes.")
        }
        val outcome = map(result, warnings)
        if (state == LifecycleState.Draft && outcome !is AgentPublishOutcome.Done) flows.delete(flowId) // no orphan hidden Flow
        return outcome
    }

    suspend fun pause(agentId: String, untilMs: Long? = null): AgentPublishOutcome = simple(activation.pause(AgentIds.flowId(agentId), untilMs))
    suspend fun resume(agentId: String): AgentPublishOutcome = simple(activation.resume(AgentIds.flowId(agentId)))
    suspend fun unpublish(agentId: String): AgentPublishOutcome = simple(activation.unpublish(AgentIds.flowId(agentId)))
    suspend fun retry(agentId: String): AgentPublishOutcome = simple(activation.retryNow(AgentIds.flowId(agentId)))

    /** Deletes the agent. A live agent is disarmed first (and its runs purged), so nothing keeps running without an owner. */
    suspend fun delete(agentId: String) {
        activation.delete(AgentIds.flowId(agentId))
        agents.deleteAgent(agentId)
    }

    /** Live state of every agent, from the engine's lifecycle plus "changes not live". Never from a stored flag. */
    fun observe(): KFlow<Map<String, AgentLiveState>> =
        combine(flows.observeAgentStates(), agents.observeAgents()) { states, list ->
            list.associate { a -> a.id to displayState(a, states[a.id] ?: AgentLiveState.NotPublished) }
        }

    suspend fun stateOf(agentId: String): AgentLiveState = AgentLiveState.from(activation.snapshot(AgentIds.flowId(agentId)))

    /**
     * The state to show, including "changes not live": the agent as it would compile now against the Flow that was handed to the
     * engine, compared with the engine's own change detector. A compile that would now be refused counts as changed.
     */
    suspend fun displayState(agent: CustomAgent, state: AgentLiveState): AgentLiveState {
        if (state !is AgentLiveState.Live) return state
        val stored = flows.flow(AgentIds.flowId(agent.id)) ?: return state
        val info = agent.credentialId?.let { credentials.info(it) }
        val now = AgentProductionCompiler.compile(agent, info).flow
        return state.copy(changesNotLive = now == null || ChangeDetector.differs(now, stored))
    }

    private fun simple(r: ActivationResult): AgentPublishOutcome = when (r.status) {
        ActivationStatus.Done -> AgentPublishOutcome.Done(r.snapshot?.publishedRevision, AgentLiveState.from(r.snapshot), emptyList())
        ActivationStatus.Unchanged -> AgentPublishOutcome.Unchanged
        ActivationStatus.Blocked -> AgentPublishOutcome.Blocked(r.report?.errors?.map { it.message }.orEmpty())
        ActivationStatus.NotFound -> AgentPublishOutcome.Failed("This agent is not published.")
        ActivationStatus.Refused -> AgentPublishOutcome.Failed("The engine refused: ${r.refusal?.name ?: "not allowed now"}.")
        else -> AgentPublishOutcome.Failed("That is not possible in the agent's current state.")
    }

    private fun map(r: ActivationResult, agentWarnings: List<String>): AgentPublishOutcome = when (r.status) {
        ActivationStatus.Done -> AgentPublishOutcome.Done(
            r.snapshot?.publishedRevision, AgentLiveState.from(r.snapshot),
            agentWarnings + r.report?.warnings?.map { it.message }.orEmpty(),
        )
        ActivationStatus.Unchanged -> AgentPublishOutcome.Unchanged
        ActivationStatus.Blocked -> AgentPublishOutcome.Blocked(r.report?.errors?.map { it.message }.orEmpty())
        ActivationStatus.NeedsAcknowledgement -> AgentPublishOutcome.NeedsAcknowledgement(agentWarnings + r.report?.warnings?.map { it.message }.orEmpty())
        ActivationStatus.ArmFailed -> AgentPublishOutcome.ArmFailed(r.sparks.filterValues { it.isFailure }.map { (id, st) -> "$id: ${st.detail}" })
        else -> AgentPublishOutcome.Failed("The engine could not publish this agent (${r.status.name}).")
    }
}

/** Production Runs of an agent, shown in the Sessions tab next to the test sessions. */
object AgentRunHistory {
    private const val PREVIEW = 600

    fun toSession(run: RunRecord): AgentSession {
        val answer = run.stepRuns.firstOrNull { it.stepId == AgentProductionCompiler.AGENT_STEP }
            ?.output?.firstOrNull()?.json?.get("answer")?.toString()?.trim().orEmpty()
        val ok = run.status == RunStatus.Succeeded || run.status == RunStatus.PartialSuccess
        val text = when {
            ok && answer.isNotEmpty() -> answer.take(PREVIEW)
            ok -> "Finished without an answer."
            else -> (run.error ?: run.stepRuns.firstOrNull { it.error != null }?.error ?: "The run ${run.status.name.lowercase()}.").take(PREVIEW)
        }
        return AgentSession(
            id = run.id,
            timestamp = run.startedAt,
            status = if (ok) "Success" else "Error",
            prompt = "Run by ${run.trigger}",
            response = text,
            durationMs = run.durationMs,
        )
    }
}

/** What a person is told after a publish, pause or unpublish that did not simply work. Null when nothing needs saying. */
data class AgentNotice(val title: String, val lines: List<String>, val canAcknowledge: Boolean = false)

fun AgentPublishOutcome.toNotice(): AgentNotice? = when (this) {
    is AgentPublishOutcome.Done -> null
    AgentPublishOutcome.Unchanged -> AgentNotice("Nothing to change", listOf("The live agent already matches what you saved."))
    is AgentPublishOutcome.Refused -> AgentNotice(
        "This agent cannot go live yet",
        issues.filter { it.severity == AgentIssueSeverity.Blocking }.map { it.message },
    )
    is AgentPublishOutcome.NeedsAcknowledgement -> AgentNotice("Check before going live", warnings, canAcknowledge = true)
    is AgentPublishOutcome.Blocked -> AgentNotice("The engine cannot start this agent", problems.ifEmpty { listOf("A check failed.") })
    is AgentPublishOutcome.ArmFailed -> AgentNotice("A trigger could not be started", details.ifEmpty { listOf("The previous version keeps running, if there was one.") })
    is AgentPublishOutcome.Failed -> AgentNotice("Could not do that", listOf(message))
}
