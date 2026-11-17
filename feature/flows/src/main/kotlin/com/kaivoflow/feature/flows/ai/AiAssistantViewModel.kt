package com.kaivoflow.feature.flows.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaivoflow.core.data.CredentialRepository
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.model.Flow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiAssistantUiState(
    val messages: List<AiChatMessage> = emptyList(),
    val currentFlow: Flow? = null,
    val isGenerating: Boolean = false,
    val providerConfig: AiProviderConfig = AiProviderConfig(),
    val showSettingsSheet: Boolean = false,
    val activeProposedFlow: Flow? = null,
    val isDiscoveringModels: Boolean = false,
    val modelDiscoveryMessage: String? = null,
    val modelDiscoveryIsError: Boolean = false,
)

@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val flowRepository: FlowRepository,
    private val credentialRepository: CredentialRepository,
    private val http: HttpGateway,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val agent = AiWorkflowAgent(http)
    private val modelDiscovery = ModelDiscoveryService(http)
    private val flowId: String? = savedStateHandle["flowId"]

    private val _uiState = MutableStateFlow(AiAssistantUiState())
    val uiState: StateFlow<AiAssistantUiState> = _uiState.asStateFlow()

    init {
        loadProviderConfig()
        initSession(flowId)
    }

    private fun loadProviderConfig() {
        viewModelScope.launch {
            val fields = credentialRepository.getFields("ai_provider_config")
            val config = AiProviderConfig.fromMap(fields)
            _uiState.update { it.copy(providerConfig = config) }
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

    fun saveProviderConfig(config: AiProviderConfig) {
        viewModelScope.launch {
            credentialRepository.save(
                id = "ai_provider_config",
                type = "ai_assistant",
                label = "AI Assistant Config",
                fields = config.toMap(),
            )
            _uiState.update {
                it.copy(
                    providerConfig = config,
                    showSettingsSheet = false,
                    modelDiscoveryMessage = null,
                )
            }
        }
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
    }

    fun clearChat() {
        initSession(flowId)
    }

    private fun initSession(targetFlowId: String?) {
        viewModelScope.launch {
            if (targetFlowId.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        messages = listOf(
                            AiChatMessage(
                                sender = MessageSender.Assistant,
                                text = "Hello! I am your sovereign workflow assistant.\n\n" +
                                    "Describe any automated workflow you want to build (e.g. \"Fetch weather API every morning and send SMS\" or \"Webhook that writes JSON to Data Table\").",
                            )
                        )
                    )
                }
            } else {
                val flow = flowRepository.flow(targetFlowId)
                if (flow != null) {
                    val report = agent.diagnose(flow)
                    val introText = "I see you're working on the \"${flow.name}\" workflow.\n\n" +
                        "I've analyzed its structure (${flow.steps.size} steps). What would you like to do with it — make changes, fix issues, or add new steps?"
                    _uiState.update {
                        it.copy(
                            currentFlow = flow,
                            messages = listOf(
                                AiChatMessage(
                                    sender = MessageSender.Assistant,
                                    text = introText,
                                    diagnosisReport = report,
                                )
                            )
                        )
                    }
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

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isGenerating = true,
            )
        }

        viewModelScope.launch {
            val response = agent.executeTurn(
                prompt = trimmed,
                currentFlow = _uiState.value.currentFlow,
                config = _uiState.value.providerConfig,
                chatHistory = _uiState.value.messages,
            )

            _uiState.update {
                it.copy(
                    messages = it.messages + response,
                    isGenerating = false,
                    activeProposedFlow = response.flowProposal ?: it.activeProposedFlow,
                )
            }
        }
    }

    fun applyProposedFlow(flow: Flow, onApplied: (String) -> Unit) {
        viewModelScope.launch {
            flowRepository.save(flow)
            _uiState.update {
                it.copy(
                    currentFlow = flow,
                    activeProposedFlow = null,
                    messages = it.messages + AiChatMessage(
                        sender = MessageSender.Assistant,
                        text = "Applied \"${flow.name}\" to your local workflows! You can inspect and run it directly on the canvas.",
                    )
                )
            }
            onApplied(flow.id)
        }
    }
}
