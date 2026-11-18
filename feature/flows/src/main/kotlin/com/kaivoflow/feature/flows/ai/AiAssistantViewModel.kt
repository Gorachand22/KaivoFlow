package com.kaivoflow.feature.flows.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaivoflow.core.data.CredentialRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.model.CredentialItem
import com.kaivoflow.core.model.Flow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class AssistantViewMode {
    Chat,
    CanvasPreview,
}

data class AiAssistantUiState(
    val messages: List<AiChatMessage> = emptyList(),
    val currentFlow: Flow? = null,
    val isGenerating: Boolean = false,
    val providerConfig: AiProviderConfig = AiProviderConfig(),
    val showSettingsSheet: Boolean = false,
    val showHistorySheet: Boolean = false,
    val activeProposedFlow: Flow? = null,
    val isDiscoveringModels: Boolean = false,
    val modelDiscoveryMessage: String? = null,
    val modelDiscoveryIsError: Boolean = false,
    val viewMode: AssistantViewMode = AssistantViewMode.Chat,
    val savedSessions: List<AiSession> = emptyList(),
    val savedCredentials: List<CredentialItem> = emptyList(),
    val activeSessionId: String = UUID.randomUUID().toString(),
)

@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val flowRepository: FlowRepository,
    private val credentialRepository: CredentialRepository,
    private val aiSessionRepository: AiSessionRepository,
    private val http: HttpGateway,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val agent = AiWorkflowAgent(http)
    private val modelDiscovery = ModelDiscoveryService(http)
    private val flowId: String? = savedStateHandle["flowId"]

    private val _uiState = MutableStateFlow(AiAssistantUiState())
    val uiState: StateFlow<AiAssistantUiState> = _uiState.asStateFlow()

    init {
        observeCredentials()
        loadProviderConfig()
        observeSavedSessions()
        initSession(flowId)
    }

    private fun observeCredentials() {
        viewModelScope.launch {
            credentialRepository.observeCredentials().collect { allCreds ->
                val aiTypes = setOf("openAiApi", "geminiApi", "anthropicApi", "ollamaApi", "credential_type_openai", "credential_type_gemini", "credential_type_claude", "credential_type_ollama")
                val filtered = allCreds.filter { cred ->
                    cred.id != "ai_provider_config" && aiTypes.any { type -> cred.type.contains(type, ignoreCase = true) }
                }
                _uiState.update { it.copy(savedCredentials = filtered) }

                val currentCredId = _uiState.value.providerConfig.credentialId
                if (currentCredId != null) {
                    val matching = filtered.find { it.id == currentCredId }
                    if (matching != null && matching.name != _uiState.value.providerConfig.credentialName) {
                        _uiState.update {
                            it.copy(providerConfig = it.providerConfig.copy(credentialName = matching.name))
                        }
                    }
                }
            }
        }
    }

    private fun observeSavedSessions() {
        viewModelScope.launch {
            aiSessionRepository.observeSessions().collect { list ->
                _uiState.update { it.copy(savedSessions = list) }
            }
        }
    }

    private fun loadProviderConfig() {
        viewModelScope.launch {
            val fields = credentialRepository.getFields("ai_provider_config")
            var config = AiProviderConfig.fromMap(fields)

            val credId = config.credentialId
            if (!credId.isNullOrBlank()) {
                val credFields = credentialRepository.getFields(credId)
                if (credFields != null) {
                    val apiKey = credFields["apiKey"] ?: credFields["token"] ?: ""
                    val baseUrl = credFields["baseUrl"] ?: ""
                    config = config.copy(
                        apiKey = if (apiKey.isNotBlank()) apiKey else config.apiKey,
                        baseUrl = if (baseUrl.isNotBlank()) baseUrl else config.baseUrl,
                    )
                }
            }
            _uiState.update { it.copy(providerConfig = config) }
            if (config.isConfigured) {
                discoverModels(config.provider, config.baseUrl, config.apiKey)
            }
        }
    }

    fun setViewMode(mode: AssistantViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun toggleHistorySheet(show: Boolean) {
        _uiState.update { it.copy(showHistorySheet = show) }
    }

    fun loadSession(session: AiSession) {
        viewModelScope.launch {
            val flow = session.flowId?.let { flowRepository.flow(it) }
            _uiState.update {
                it.copy(
                    activeSessionId = session.id,
                    messages = session.messages,
                    currentFlow = flow,
                    activeProposedFlow = session.messages.lastOrNull { m -> m.flowProposal != null }?.flowProposal,
                    viewMode = AssistantViewMode.Chat,
                    showHistorySheet = false,
                )
            }
        }
    }

    fun newSession() {
        val newId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                activeSessionId = newId,
                messages = emptyList(),
                currentFlow = null,
                activeProposedFlow = null,
                viewMode = AssistantViewMode.Chat,
                showHistorySheet = false,
            )
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            aiSessionRepository.deleteSession(sessionId)
            if (_uiState.value.activeSessionId == sessionId) {
                newSession()
            }
        }
    }

    fun discoverModels(provider: AiProvider, baseUrl: String, apiKey: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDiscoveringModels = true,
                    modelDiscoveryMessage = null,
                    modelDiscoveryIsError = false,
                )
            }
            val result = modelDiscovery.discoverModels(provider, baseUrl, apiKey)
            result.fold(
                onSuccess = { discovered ->
                    _uiState.update { state ->
                        val updatedConfig = state.providerConfig.copy(
                            provider = provider,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            discoveredModels = discovered,
                            model = if (discovered.contains(state.providerConfig.model)) {
                                state.providerConfig.model
                            } else {
                                discovered.firstOrNull() ?: state.providerConfig.model
                            },
                        )
                        state.copy(
                            isDiscoveringModels = false,
                            providerConfig = updatedConfig,
                            modelDiscoveryMessage = "Discovered ${discovered.size} model(s) successfully!",
                            modelDiscoveryIsError = false,
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isDiscoveringModels = false,
                            modelDiscoveryMessage = err.message ?: "Failed to connect to provider",
                            modelDiscoveryIsError = true,
                        )
                    }
                }
            )
        }
    }

    fun saveProviderConfig(config: AiProviderConfig, credentialName: String? = null) {
        viewModelScope.launch {
            val credId = config.credentialId ?: UUID.randomUUID().toString().take(16)
            val name = credentialName?.ifBlank { null }
                ?: config.credentialName?.ifBlank { null }
                ?: "${config.provider.displayName} account"

            val credFields = mapOf(
                "apiKey" to config.apiKey,
                "baseUrl" to config.baseUrl,
                "model" to config.model,
            )

            // Save or update versioned credential in sovereign repository
            credentialRepository.save(
                id = credId,
                type = config.provider.key,
                label = name,
                fields = credFields,
            )

            val updatedConfig = config.copy(
                credentialId = credId,
                credentialName = name,
            )

            // Save active AI provider configuration pointer
            credentialRepository.save(
                id = "ai_provider_config",
                type = "ai_assistant",
                label = "AI Assistant Config",
                fields = updatedConfig.toMap(),
            )

            _uiState.update {
                it.copy(
                    providerConfig = updatedConfig,
                    showSettingsSheet = false,
                    modelDiscoveryMessage = null,
                )
            }
        }
    }

    fun selectCredential(credentialId: String) {
        viewModelScope.launch {
            val cred = _uiState.value.savedCredentials.find { it.id == credentialId } ?: return@launch
            val fields = credentialRepository.getFields(credentialId).orEmpty()
            val apiKey = fields["apiKey"] ?: fields["token"] ?: ""
            val baseUrl = fields["baseUrl"] ?: ""
            val provider = AiProvider.fromKey(cred.type)

            val updatedConfig = _uiState.value.providerConfig.copy(
                provider = provider,
                apiKey = apiKey,
                baseUrl = baseUrl,
                credentialId = credentialId,
                credentialName = cred.name,
            )

            credentialRepository.save(
                id = "ai_provider_config",
                type = "ai_assistant",
                label = "AI Assistant Config",
                fields = updatedConfig.toMap(),
            )

            _uiState.update { state ->
                state.copy(providerConfig = updatedConfig)
            }
            discoverModels(provider, baseUrl, apiKey)
        }
    }

    fun selectModel(model: String) {
        viewModelScope.launch {
            val updated = _uiState.value.providerConfig.copy(model = model)
            credentialRepository.save(
                id = "ai_provider_config",
                type = "ai_assistant",
                label = "AI Assistant Config",
                fields = updated.toMap(),
            )
            _uiState.update { it.copy(providerConfig = updated) }
        }
    }

    suspend fun getCredentialFields(credentialId: String): Map<String, String> {
        return credentialRepository.getFields(credentialId).orEmpty()
    }

    fun toggleSettingsSheet(show: Boolean) {
        _uiState.update {
            it.copy(
                showSettingsSheet = show,
                modelDiscoveryMessage = if (!show) null else it.modelDiscoveryMessage,
            )
        }
    }

    fun detachFlow() {
        _uiState.update { it.copy(currentFlow = null, activeProposedFlow = null) }
        persistCurrentSession()
    }

    fun clearChat() {
        initSession(flowId)
    }

    private fun initSession(targetFlowId: String?) {
        viewModelScope.launch {
            if (targetFlowId.isNullOrBlank()) {
                _uiState.update { it.copy(messages = emptyList(), currentFlow = null) }
            } else {
                val flow = flowRepository.flow(targetFlowId)
                _uiState.update {
                    it.copy(
                        currentFlow = flow,
                        messages = emptyList(),
                    )
                }
            }
        }
    }

    fun sendMessage(userText: String) {
        val trimmed = userText.trim()
        if (trimmed.isBlank() || _uiState.value.isGenerating) return

        val userMessage = AiChatMessage(
            sender = MessageSender.User,
            text = trimmed,
        )

        val updatedMessages = _uiState.value.messages + userMessage
        _uiState.update {
            it.copy(
                messages = updatedMessages,
                isGenerating = true,
            )
        }

        viewModelScope.launch {
            val response = agent.executeTurn(
                prompt = trimmed,
                currentFlow = _uiState.value.currentFlow,
                config = _uiState.value.providerConfig,
                chatHistory = updatedMessages,
            )

            val proposed = response.flowProposal
            if (proposed != null) {
                flowRepository.save(proposed)
            }

            _uiState.update {
                it.copy(
                    messages = it.messages + response,
                    isGenerating = false,
                    currentFlow = proposed ?: it.currentFlow,
                    activeProposedFlow = null,
                )
            }
            persistCurrentSession()
        }
    }

    private fun persistCurrentSession() {
        val state = _uiState.value
        if (state.messages.isEmpty()) return

        val title = state.messages.firstOrNull { it.sender == MessageSender.User }?.text?.take(36)
            ?: state.currentFlow?.name
            ?: "Conversation"

        val session = AiSession(
            id = state.activeSessionId,
            title = title,
            flowId = state.currentFlow?.id,
            flowName = state.currentFlow?.name,
            messages = state.messages,
            updatedAt = System.currentTimeMillis(),
        )

        viewModelScope.launch {
            aiSessionRepository.saveSession(session)
        }
    }

    fun applyProposedFlow(flow: Flow, onApplied: (String) -> Unit) {
        viewModelScope.launch {
            flowRepository.save(flow)
            val ackMessage = AiChatMessage(
                sender = MessageSender.Assistant,
                text = "Applied \"${flow.name}\" to your local workflows! You can inspect and run it directly on the canvas.",
            )
            _uiState.update {
                it.copy(
                    currentFlow = flow,
                    activeProposedFlow = null,
                    messages = it.messages + ackMessage,
                    viewMode = AssistantViewMode.Chat,
                )
            }
            persistCurrentSession()
            onApplied(flow.id)
        }
    }
}
