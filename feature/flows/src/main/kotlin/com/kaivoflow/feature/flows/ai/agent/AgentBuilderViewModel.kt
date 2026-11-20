package com.kaivoflow.feature.flows.ai.agent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaivoflow.core.data.CredentialRepository
import com.kaivoflow.core.data.WorkflowVariableRepository
import com.kaivoflow.core.engine.CredentialExpr
import com.kaivoflow.core.engine.GlobalScope
import com.kaivoflow.core.ai.ApprovalRelay
import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.engine.UniversalResolver
import com.kaivoflow.core.engine.VariableResolution
import com.kaivoflow.core.expressions.KaivoExpressionEvaluator
import com.kaivoflow.core.model.CredentialItem
import com.kaivoflow.core.model.StepRunStatus
import com.kaivoflow.core.work.FlowEngine
import com.kaivoflow.feature.flows.ai.AiChatMessage
import com.kaivoflow.feature.flows.ai.AiProvider
import com.kaivoflow.feature.flows.ai.AiProviderConfig
import com.kaivoflow.feature.flows.ai.CredentialVaultBridge
import com.kaivoflow.feature.flows.ai.ModelDiscoveryService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class AgentBuilderTab {
    Build, Knowledge, Sessions, Settings
}

data class AgentTemplate(
    val id: String,
    val name: String,
    val emoji: String,
    val colorHex: Long,
    val description: String,
    val instructions: String,
    val triggers: List<AgentTrigger> = emptyList(),
    val tools: List<AgentTool> = emptyList(),
    val isWebSearchEnabled: Boolean = false,
)

object StarterTemplates {
    val list: List<AgentTemplate> = listOf(
        AgentTemplate(
            id = "blank",
            name = "New Custom Agent",
            emoji = "🤖",
            colorHex = 0xFF6366F1,
            description = "Blank canvas to build any agent from scratch",
            instructions = "You are an autonomous workflow assistant for KaivoFlow. Follow instructions carefully and use available tools to automate tasks.",
            triggers = listOf(
                AgentTrigger(
                    type = "manual",
                    name = "Manual Spark",
                    description = "Triggered on demand by tapping Run",
                    isEnabled = true,
                )
            ),
        ),
        AgentTemplate(
            id = "morning_news",
            name = "Morning News Brief",
            emoji = "☀️",
            colorHex = 0xFFFF8A00,
            description = "A short brief delivered every morning at 9am",
            instructions = "You are a morning brief agent. Compile a short morning brief for the person: a greeting, today's date, and up to five points worth their attention. You have no web access, so say plainly when you do not know something instead of guessing.",
            triggers = listOf(
                AgentTrigger(
                    type = "schedule",
                    name = "Daily 9:00 AM Spark",
                    description = "Runs automatically every morning at 9am",
                    config = AgentTriggerConfig.dailyAt("09:00"),
                    isEnabled = true,
                )
            ),
            tools = emptyList(),
        ),
        AgentTemplate(
            id = "email_triage",
            name = "Message & Email Triage",
            emoji = "✉️",
            colorHex = 0xFF10B981,
            description = "Filters incoming messages and summarizes action items",
            instructions = "You are an intelligent triage agent. Monitor incoming emails and messages, summarize urgent action items, and trigger priority notifications.",
            triggers = listOf(
                AgentTrigger(
                    type = "webhook",
                    name = "Incoming Message Trigger",
                    description = "Triggered when new message or email payload arrives",
                    isEnabled = true,
                )
            ),
            tools = listOf(
                AgentTool(
                    type = "device",
                    name = "Priority Notification",
                    description = "Sends alerts for high-priority items",
                    isEnabled = true,
                ),
            ),
        ),
        AgentTemplate(
            id = "api_monitor",
            name = "API Health Monitor",
            emoji = "🔔",
            colorHex = 0xFFEC4899,
            description = "Periodically checks service endpoints and alerts on error",
            instructions = "You are an autonomous monitoring agent. Periodically check target endpoints and health checks. If error rates exceed thresholds or services are down, send high-priority alerts.",
            triggers = listOf(
                AgentTrigger(
                    type = "schedule",
                    name = "Hourly Health Check",
                    description = "Runs every 60 minutes",
                    config = mapOf("frequency" to "hourly"),
                    isEnabled = true,
                )
            ),
            tools = listOf(
                AgentTool(
                    type = "device",
                    name = "Critical Alert",
                    description = "Notifies on failed health check",
                    isEnabled = true,
                )
            ),
        ),
    )
}

data class AgentBuilderUiState(
    val agent: CustomAgent = CustomAgent(),
    val currentTab: AgentBuilderTab = AgentBuilderTab.Build,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val showTestSheet: Boolean = false,
    val testPrompt: String = "",
    val testSessionOutput: String? = null,
    val providerConfig: AiProviderConfig = AiProviderConfig(),
    val isSavedSuccess: Boolean = false,
    val templates: List<AgentTemplate> = StarterTemplates.list,
    val selectedTemplateId: String? = null,
    val credentials: List<CredentialItem> = emptyList(),
    val discoveredModels: List<String> = emptyList(),
    val isDiscoveringModels: Boolean = false,
    /** Workspace variables, the `$vars` the expression fields preview against. */
    val workspaceVars: Map<String, Any?> = emptyMap(),
    /** A credential field expression that could not be evaluated, shown under the header. */
    val credentialError: String? = null,
    /** What the Always-On engine says about this agent (never a stored flag). */
    val liveState: AgentLiveState = AgentLiveState.NotPublished,
    /** Production Runs of the published agent, newest first. */
    val runSessions: List<AgentSession> = emptyList(),
    val publishNotice: AgentNotice? = null,
    val isPublishing: Boolean = false,
) {
    /** Test sessions and production Runs together, newest first. */
    val allSessions: List<AgentSession>
        get() = (runSessions + agent.sessions).sortedByDescending { it.timestamp }

    val effectiveModels: List<String>
        get() = if (discoveredModels.isNotEmpty()) discoveredModels else providerConfig.effectiveModels
}

@HiltViewModel
class AgentBuilderViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val credentialRepository: CredentialRepository,
    private val http: HttpGateway,
    private val variableRepository: WorkflowVariableRepository,
    private val vault: CredentialVaultBridge,
    private val flowEngine: FlowEngine,
    private val publication: AgentPublicationService,
    private val runHistory: AgentRunHistorySource,
    /** The Test Run sheet attaches to this to show approve/deny cards before a tool acts on the phone. */
    val approvalRelay: ApprovalRelay,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val agentId: String? = savedStateHandle["agentId"]
    private val modelDiscovery = ModelDiscoveryService(http)
    private val evaluator = KaivoExpressionEvaluator()
    private val resolver = UniversalResolver(evaluator)

    private val _uiState = MutableStateFlow(AgentBuilderUiState())
    val uiState: StateFlow<AgentBuilderUiState> = _uiState.asStateFlow()

    init {
        observeCredentials()
        loadWorkspaceVars()
        loadProviderConfig()
        loadOrCreateAgent(agentId)
    }

    /** Live badge and Run history come from the engine and the Run store, for the agent this screen edits. */
    private var watchJob: kotlinx.coroutines.Job? = null
    private fun watch(agentId: String) {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            launch { publication.observe().collect { all -> _uiState.update { it.copy(liveState = all[agentId] ?: AgentLiveState.NotPublished) } } }
            launch { runHistory.observe(agentId).collect { runs -> _uiState.update { it.copy(runSessions = runs) } } }
        }
    }

    private fun observeCredentials() {
        viewModelScope.launch {
            credentialRepository.observeCredentials().collect { allCreds ->
                val aiTypes = setOf("openAiApi", "geminiApi", "anthropicApi", "ollamaApi", "credential_type_openai", "credential_type_gemini", "credential_type_claude", "credential_type_ollama")
                val creds = allCreds.filter { c ->
                    c.id != "ai_provider_config" && aiTypes.any { t -> c.type.contains(t, ignoreCase = true) }
                }
                _uiState.update { it.copy(credentials = creds) }
                val currentAgent = _uiState.value.agent
                if (currentAgent.credentialId == null && creds.isNotEmpty()) {
                    val matching = creds.firstOrNull { it.id == _uiState.value.providerConfig.credentialId }
                        ?: creds.firstOrNull { it.type.contains("openai", ignoreCase = true) }
                        ?: creds.first()
                    selectCredential(matching.id)
                }
            }
        }
    }

    /** Variable values may themselves be expressions; the editor previews against their evaluated form. */
    private fun loadWorkspaceVars() {
        viewModelScope.launch {
            val raw = runCatching { variableRepository.resolveVariables(null) }.getOrNull().orEmpty()
            val vars = VariableResolution.resolve(raw, evaluator).vars
            _uiState.update { it.copy(workspaceVars = vars) }
        }
    }

    /** Global scope of one agent call: `$vars`, `$now`, `$today` and the agent as flow info. No item data, no environment. */
    private fun agentScope(agent: CustomAgent): Map<String, Any?> =
        GlobalScope.build(_uiState.value.workspaceVars, flowId = agent.id, flowName = agent.name)

    private fun loadProviderConfig() {
        viewModelScope.launch {
            val fields = credentialRepository.getFields("ai_provider_config")
            val config = AiProviderConfig.fromMap(fields)
            _uiState.update { it.copy(providerConfig = config) }
        }
    }

    fun selectCredential(credentialId: String) {
        viewModelScope.launch {
            val cred = _uiState.value.credentials.find { it.id == credentialId } ?: return@launch
            val loaded = vault.load(cred.id, cred.type, cred.name) { type, stored ->
                CredentialExpr.resolveFields(type, stored, evaluator, GlobalScope.build(_uiState.value.workspaceVars))
            }
            if (loaded is CredentialVaultBridge.Loaded.Failed) {
                _uiState.update { it.copy(credentialError = loaded.message) }
                return@launch
            }
            val c = (loaded as CredentialVaultBridge.Loaded.Ok).credential
            val apiKey = c.apiKey
            val baseUrl = c.baseUrl
            val provider = c.provider

            _uiState.update { state ->
                state.copy(
                    credentialError = null,
                    agent = state.agent.copy(
                        credentialId = cred.id,
                        provider = provider,
                    ),
                    isDiscoveringModels = true,
                )
            }

            val result = modelDiscovery.discoverModels(provider, baseUrl, apiKey)
            result.fold(
                onSuccess = { models ->
                    _uiState.update { state ->
                        val chosenModel = if (models.contains(state.agent.model) && state.agent.model.isNotBlank()) {
                            state.agent.model
                        } else {
                            models.firstOrNull() ?: state.agent.model
                        }
                        state.copy(
                            discoveredModels = models,
                            isDiscoveringModels = false,
                            agent = state.agent.copy(
                                model = chosenModel,
                                provider = provider,
                            ),
                        )
                    }
                },
                onFailure = {
                    _uiState.update { state ->
                        state.copy(isDiscoveringModels = false)
                    }
                }
            )
        }
    }

    private fun loadOrCreateAgent(id: String?) {
        viewModelScope.launch {
            if (!id.isNullOrBlank()) {
                val existing = agentRepository.agentById(id)
                if (existing != null) {
                    _uiState.update { it.copy(agent = existing) }
                    watch(existing.id)
                    existing.credentialId?.let { selectCredential(it) }
                    return@launch
                }
            }

            // Create a fresh customizable agent draft with a new unique ID
            val defaultTemplate = StarterTemplates.list.first()
            val newAgent = CustomAgent(
                id = UUID.randomUUID().toString(),
                name = defaultTemplate.name,
                iconEmoji = defaultTemplate.emoji,
                iconColorHex = defaultTemplate.colorHex,
                model = _uiState.value.providerConfig.model.ifBlank { AiProvider.OpenAI.defaultModel },
                provider = _uiState.value.providerConfig.provider,
                instructions = defaultTemplate.instructions,
                triggers = defaultTemplate.triggers,
                tools = defaultTemplate.tools,
            )
            _uiState.update { it.copy(agent = newAgent, selectedTemplateId = defaultTemplate.id) }
            watch(newAgent.id)
        }
    }

    fun applyTemplate(template: AgentTemplate) {
        _uiState.update { current ->
            current.copy(
                selectedTemplateId = template.id,
                agent = current.agent.copy(
                    name = template.name,
                    iconEmoji = template.emoji,
                    iconColorHex = template.colorHex,
                    instructions = template.instructions,
                    triggers = template.triggers,
                    tools = template.tools,
                )
            )
        }
    }

    fun setTab(tab: AgentBuilderTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(agent = it.agent.copy(name = name)) }
    }

    fun updateIcon(emoji: String, colorHex: Long) {
        _uiState.update { it.copy(agent = it.agent.copy(iconEmoji = emoji, iconColorHex = colorHex)) }
    }

    fun updateModel(provider: AiProvider, model: String) {
        val resolvedProvider = if (model.contains("/") && provider != AiProvider.OpenAI) {
            AiProvider.OpenAI
        } else {
            provider
        }
        _uiState.update { it.copy(agent = it.agent.copy(provider = resolvedProvider, model = model)) }
    }

    fun updateInstructions(instructions: String) {
        _uiState.update { it.copy(agent = it.agent.copy(instructions = instructions)) }
    }

    fun addSkill(name: String, description: String = "") {
        val skill = AgentSkill(name = name, description = description)
        _uiState.update { it.copy(agent = it.agent.copy(skills = it.agent.skills + skill)) }
    }

    fun removeSkill(skillId: String) {
        _uiState.update { it.copy(agent = it.agent.copy(skills = it.agent.skills.filterNot { s -> s.id == skillId })) }
    }

    fun toggleWebSearch(enabled: Boolean) {
        _uiState.update { it.copy(agent = it.agent.copy(webSearchEnabled = enabled)) }
    }

    fun toggleCrossSessionMemory(enabled: Boolean) {
        _uiState.update { it.copy(agent = it.agent.copy(crossSessionMemoryEnabled = enabled)) }
    }

    fun addTrigger(trigger: AgentTrigger) {
        _uiState.update { it.copy(agent = it.agent.copy(triggers = it.agent.triggers + trigger)) }
    }

    fun removeTrigger(triggerId: String) {
        _uiState.update { it.copy(agent = it.agent.copy(triggers = it.agent.triggers.filterNot { t -> t.id == triggerId })) }
    }

    fun toggleTrigger(triggerId: String) {
        _uiState.update {
            val updated = it.agent.triggers.map { t ->
                if (t.id == triggerId) t.copy(isEnabled = !t.isEnabled) else t
            }
            it.copy(agent = it.agent.copy(triggers = updated))
        }
    }

    fun addTool(tool: AgentTool) {
        _uiState.update { it.copy(agent = it.agent.copy(tools = it.agent.tools + tool)) }
    }

    fun removeTool(toolId: String) {
        _uiState.update { it.copy(agent = it.agent.copy(tools = it.agent.tools.filterNot { t -> t.id == toolId })) }
    }

    fun toggleTool(toolId: String) {
        _uiState.update {
            val updated = it.agent.tools.map { t ->
                if (t.id == toolId) t.copy(isEnabled = !t.isEnabled) else t
            }
            it.copy(agent = it.agent.copy(tools = updated))
        }
    }

    fun addKnowledge(title: String, content: String) {
        val item = AgentKnowledgeItem(title = title, content = content)
        _uiState.update { it.copy(agent = it.agent.copy(knowledge = it.agent.knowledge + item)) }
    }

    fun removeKnowledge(id: String) {
        _uiState.update { it.copy(agent = it.agent.copy(knowledge = it.agent.knowledge.filterNot { k -> k.id == id })) }
    }

    fun updateSettings(settings: AgentSettings) {
        _uiState.update { it.copy(agent = it.agent.copy(settings = settings)) }
    }

    fun toggleTestSheet(show: Boolean) {
        _uiState.update { it.copy(showTestSheet = show) }
    }

    fun setTestPrompt(prompt: String) {
        _uiState.update { it.copy(testPrompt = prompt) }
    }

    /**
     * Test Run follows the Test Step rule (audit 8.3): the agent is compiled to a one-Step in-memory Flow and only that
     * Step runs, in test mode, with the person's test message as its own input. It is never a production Run, no
     * Spark exists, nothing is saved or armed, and a tool that acts on the phone asks first (approve/deny card).
     */
    fun runTestAgent(userPrompt: String) {
        val prompt = userPrompt.trim()
        if (prompt.isBlank() || _uiState.value.isTesting) return

        _uiState.update { it.copy(isTesting = true, testSessionOutput = null) }

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val currentAgent = _uiState.value.agent

            val targetCred = currentAgent.credentialId?.let { id -> _uiState.value.credentials.find { it.id == id } }
                ?: _uiState.value.credentials.firstOrNull { it.id == _uiState.value.providerConfig.credentialId }
                ?: _uiState.value.credentials.firstOrNull { it.type.contains("openai", ignoreCase = true) }
                ?: _uiState.value.credentials.firstOrNull()
            if (targetCred == null) { failTest(prompt, "Add an AI credential first.", startTime); return@launch }
            if (currentAgent.model.isBlank()) { failTest(prompt, "Choose a model first.", startTime); return@launch }

            // Non-secret credential fields may be expressions (spec P3); a bad one ends the test with its message.
            val loaded = vault.load(targetCred.id, targetCred.type, targetCred.name) { type, stored ->
                CredentialExpr.resolveFields(type, stored, evaluator, agentScope(currentAgent))
            }
            val cred = when (loaded) {
                is CredentialVaultBridge.Loaded.Failed -> { failTest(prompt, loaded.message, startTime); return@launch }
                is CredentialVaultBridge.Loaded.Ok -> loaded.credential
            }

            // Instructions and knowledge may hold expressions; they are resolved here, on every call (spec P3).
            val system = when (val built = AgentPrompt.buildSystem(currentAgent, resolver, agentScope(currentAgent))) {
                is AgentPrompt.Built.Ok -> built.text
                is AgentPrompt.Built.Failed -> { failTest(prompt, built.message, startTime); return@launch }
            }

            val compiled = AgentFlowCompiler.compile(
                agent = currentAgent,
                systemText = system,
                task = prompt,
                provider = cred.provider,
                model = currentAgent.model,
                credentialId = targetCred.id,
                baseUrl = cred.baseUrl,
            )
            val run = try {
                flowEngine.testStep(compiled.flow, compiled.stepId, compiled.input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failTest(prompt, e.message ?: "The model call failed.", startTime)
                return@launch
            }

            val ok = run.status == StepRunStatus.Succeeded
            val item = run.output.firstOrNull()?.json.orEmpty()
            val answer = (item["answer"] as? String)?.takeIf { it.isNotBlank() }
            val meta = item["_ai"] as? Map<*, *>
            val text = buildString {
                if (ok) append(answer ?: "The agent finished without an answer.") else append("Test failed.\n").append(run.error ?: "The agent could not finish.")
                if (meta != null) append("\n\n").append("Steps: ${meta["iterations"]} · tool calls: ${meta["toolCalls"]} · tokens: ${meta["totalTokens"]}")
                if (compiled.unavailableTools.isNotEmpty()) append("\n\nNot offered to the model: ").append(compiled.unavailableTools.joinToString("; "))
            }.trim()

            val session = AgentSession(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                status = if (ok) "Success" else "Error",
                prompt = prompt,
                response = text,
                durationMs = System.currentTimeMillis() - startTime,
            )

            _uiState.update {
                it.copy(
                    isTesting = false,
                    testSessionOutput = text,
                    agent = it.agent.copy(sessions = listOf(session) + it.agent.sessions),
                )
            }
        }
    }

    /** A bad expression ends the test run with a readable message; nothing is sent to the model and nothing crashes. */
    private fun failTest(prompt: String, message: String, startTime: Long) {
        val text = "Could not run this agent.\n$message"
        val session = AgentSession(
            status = "Error",
            prompt = prompt,
            response = text,
            durationMs = System.currentTimeMillis() - startTime,
        )
        _uiState.update {
            it.copy(isTesting = false, testSessionOutput = text, agent = it.agent.copy(sessions = listOf(session) + it.agent.sessions))
        }
    }

    fun saveAgent(onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            agentRepository.saveAgent(_uiState.value.agent)
            _uiState.update { it.copy(isSaving = false, isSavedSuccess = true) }
            onSaved()
        }
    }

    /**
     * Publish (Run 24/7): the draft is saved first (a refused publish must never lose edits), then compiled and handed to the
     * Always-On engine through [AgentPublicationService]. The agent counts as live only when the engine says so; until then the
     * screen shows why not ([AgentBuilderUiState.publishNotice]). A live agent is republished (publish changes).
     */
    fun publishAgent(acknowledge: Boolean = false, onPublished: () -> Unit = {}) {
        if (_uiState.value.isPublishing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true, isSaving = true, publishNotice = null) }
            val agent = _uiState.value.agent
            val outcome = try {
                agentRepository.saveAgent(agent)
                publication.publish(agent.id, acknowledge)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AgentPublishOutcome.Failed(e.message ?: "Publishing failed.")
            }
            _uiState.update { it.copy(isPublishing = false, isSaving = false, isSavedSuccess = true, publishNotice = outcome.toNotice()) }
            if (outcome is AgentPublishOutcome.Done) onPublished()
        }
    }

    fun dismissPublishNotice() { _uiState.update { it.copy(publishNotice = null) } }

    /** The author's consent for one tool to act in a published run. Off unless the person turns it on. */
    fun setToolUnattended(toolId: String, allowed: Boolean) {
        _uiState.update { st -> st.copy(agent = st.agent.copy(tools = st.agent.tools.map { if (it.id == toolId) it.copy(allowUnattended = allowed) else it })) }
    }
}
