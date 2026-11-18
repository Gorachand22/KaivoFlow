package com.kaivoflow.feature.flows.ai.agent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaivoflow.core.data.CredentialRepository
import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.model.CredentialItem
import com.kaivoflow.feature.flows.ai.AiChatMessage
import com.kaivoflow.feature.flows.ai.AiProvider
import com.kaivoflow.feature.flows.ai.AiProviderConfig
import com.kaivoflow.feature.flows.ai.AiWorkflowAgent
import com.kaivoflow.feature.flows.ai.ModelDiscoveryService
import dagger.hilt.android.lifecycle.HiltViewModel
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
            description = "Searches web daily at 9am for top headlines",
            instructions = "You are a news brief agent. Every morning at 9am, search the web for today's top headlines and compile a concise summary. For each story include a headline, a one-sentence summary, and a source link. Limit the brief to five stories.",
            triggers = listOf(
                AgentTrigger(
                    type = "schedule",
                    name = "Daily 9:00 AM Spark",
                    description = "Runs automatically every morning at 9am",
                    config = mapOf("cron" to "0 9 * * *"),
                    isEnabled = true,
                )
            ),
            tools = listOf(
                AgentTool(
                    type = "web_search",
                    name = "Web Search",
                    description = "Searches the web for latest headlines and live articles",
                    isEnabled = true,
                ),
                AgentTool(
                    type = "device",
                    name = "Phone Notification",
                    description = "Sends the brief directly to phone notification bar",
                    isEnabled = true,
                )
            ),
            isWebSearchEnabled = true,
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
                    config = mapOf("cron" to "0 * * * *"),
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
) {
    val effectiveModels: List<String>
        get() = if (discoveredModels.isNotEmpty()) discoveredModels else providerConfig.effectiveModels
}

@HiltViewModel
class AgentBuilderViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val credentialRepository: CredentialRepository,
    private val http: HttpGateway,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val agentId: String? = savedStateHandle["agentId"]
    private val workflowAgent = AiWorkflowAgent(http)
    private val modelDiscovery = ModelDiscoveryService(http)

    private val _uiState = MutableStateFlow(AgentBuilderUiState())
    val uiState: StateFlow<AgentBuilderUiState> = _uiState.asStateFlow()

    init {
        observeCredentials()
        loadProviderConfig()
        loadOrCreateAgent(agentId)
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
            val fields = credentialRepository.getFields(cred.id).orEmpty()
            val apiKey = fields["apiKey"] ?: fields["token"] ?: ""
            val baseUrl = fields["baseUrl"] ?: ""
            val provider = AiProvider.fromKey(cred.type)

            _uiState.update { state ->
                state.copy(
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
                        val preferred = models.firstOrNull { it.contains("nemotron-3.5-bolt", ignoreCase = true) }
                            ?: models.firstOrNull { it.contains("nemotron", ignoreCase = true) }
                            ?: models.firstOrNull { it.contains("gpt-4o", ignoreCase = true) }
                        val chosenModel = if (models.contains(state.agent.model) && state.agent.model.isNotBlank()) {
                            state.agent.model
                        } else {
                            preferred ?: models.firstOrNull() ?: state.agent.model
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
                isPublished = false,
            )
            _uiState.update { it.copy(agent = newAgent, selectedTemplateId = defaultTemplate.id) }
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

            var activeApiKey = _uiState.value.providerConfig.apiKey
            var activeBaseUrl = _uiState.value.providerConfig.baseUrl
            var activeProvider = targetCred?.let { AiProvider.fromKey(it.type) } ?: currentAgent.provider

            if (targetCred != null) {
                val fields = credentialRepository.getFields(targetCred.id).orEmpty()
                fields["apiKey"]?.takeIf { it.isNotBlank() }?.let { activeApiKey = it }
                fields["baseUrl"]?.takeIf { it.isNotBlank() }?.let { activeBaseUrl = it }
                activeProvider = AiProvider.fromKey(targetCred.type)
            }

            if (currentAgent.model.contains("/") || currentAgent.model.contains("nemotron", ignoreCase = true) || currentAgent.model.contains("gpt", ignoreCase = true)) {
                activeProvider = AiProvider.OpenAI
            }

            val config = AiProviderConfig(
                provider = activeProvider,
                model = currentAgent.model.ifBlank { "nvidia/nemotron-3.5-bolt-e4b" },
                apiKey = activeApiKey,
                baseUrl = activeBaseUrl,
            )

            // Compose system prompt with Agent instructions + knowledge base + tools
            val fullPrompt = buildString {
                append("AGENT INSTRUCTIONS:\n")
                append(currentAgent.instructions)
                if (currentAgent.knowledge.isNotEmpty()) {
                    append("\n\nKNOWLEDGE BASE CONTEXT:\n")
                    currentAgent.knowledge.forEach {
                        append("- ${it.title}: ${it.content}\n")
                    }
                }
                if (currentAgent.tools.isNotEmpty()) {
                    append("\nAVAILABLE TOOLS:\n")
                    currentAgent.tools.filter { it.isEnabled }.forEach {
                        append("- ${it.name} (${it.type}): ${it.description}\n")
                    }
                }
                append("\n\nUSER PROMPT / TASK:\n")
                append(prompt)
            }

            val response = workflowAgent.executeTurn(
                prompt = fullPrompt,
                currentFlow = null,
                config = config,
                chatHistory = emptyList(),
            )

            val duration = System.currentTimeMillis() - startTime
            val session = AgentSession(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                status = "Success",
                prompt = prompt,
                response = response.text,
                durationMs = duration,
            )

            _uiState.update {
                it.copy(
                    isTesting = false,
                    testSessionOutput = response.text,
                    agent = it.agent.copy(sessions = listOf(session) + it.agent.sessions),
                )
            }
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

    fun publishAgent(onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            val published = _uiState.value.agent.copy(isPublished = true)
            _uiState.update {
                it.copy(
                    isSaving = true,
                    agent = published,
                )
            }
            agentRepository.saveAgent(published)
            _uiState.update { it.copy(isSaving = false, isSavedSuccess = true) }
            onSaved()
        }
    }
}
