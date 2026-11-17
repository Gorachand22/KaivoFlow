package com.kaivoflow.feature.flows.ai.agent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaivoflow.core.data.CredentialRepository
import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.feature.flows.ai.AiChatMessage
import com.kaivoflow.feature.flows.ai.AiProvider
import com.kaivoflow.feature.flows.ai.AiProviderConfig
import com.kaivoflow.feature.flows.ai.AiWorkflowAgent
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
)

@HiltViewModel
class AgentBuilderViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val credentialRepository: CredentialRepository,
    private val http: HttpGateway,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val agentId: String? = savedStateHandle["agentId"]
    private val workflowAgent = AiWorkflowAgent(http)

    private val _uiState = MutableStateFlow(AgentBuilderUiState())
    val uiState: StateFlow<AgentBuilderUiState> = _uiState.asStateFlow()

    init {
        loadProviderConfig()
        loadOrCreateAgent(agentId)
    }

    private fun loadProviderConfig() {
        viewModelScope.launch {
            val fields = credentialRepository.getFields("ai_provider_config")
            val config = AiProviderConfig.fromMap(fields)
            _uiState.update { it.copy(providerConfig = config) }
        }
    }

    private fun loadOrCreateAgent(id: String?) {
        viewModelScope.launch {
            if (!id.isNullOrBlank()) {
                val existing = agentRepository.agentById(id)
                if (existing != null) {
                    _uiState.update { it.copy(agent = existing) }
                    return@launch
                }
            }

            // Starter starter template draft inspired by sovereign news / assistant agent
            val starter = CustomAgent(
                name = "Morning News Brief",
                iconEmoji = "☀️",
                iconColorHex = 0xFFFF8A00,
                model = _uiState.value.providerConfig.model,
                provider = _uiState.value.providerConfig.provider,
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
                knowledge = listOf(
                    AgentKnowledgeItem(
                        title = "News Sources & Preferences",
                        content = "Focus on technology, open source, and science breakthroughs. Avoid clickbait and redundant articles.",
                    )
                ),
            )
            _uiState.update { it.copy(agent = starter) }
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
        _uiState.update { it.copy(agent = it.agent.copy(provider = provider, model = model)) }
    }

    fun updateInstructions(instructions: String) {
        _uiState.update { it.copy(agent = it.agent.copy(instructions = instructions)) }
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
            val config = _uiState.value.providerConfig.copy(
                provider = currentAgent.provider,
                model = currentAgent.model.ifBlank { _uiState.value.providerConfig.model },
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
            _uiState.update {
                it.copy(
                    isSaving = true,
                    agent = it.agent.copy(isPublished = true),
                )
            }
            agentRepository.saveAgent(_uiState.value.agent)
            _uiState.update { it.copy(isSaving = false, isSavedSuccess = true) }
            onSaved()
        }
    }
}
