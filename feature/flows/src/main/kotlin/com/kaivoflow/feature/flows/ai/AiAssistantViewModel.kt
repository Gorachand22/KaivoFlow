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
)

@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val flowRepository: FlowRepository,
    private val credentialRepository: CredentialRepository,
    private val http: HttpGateway,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val agent = AiWorkflowAgent(http)
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

    fun saveProviderConfig(config: AiProviderConfig) {
        viewModelScope.launch {
            credentialRepository.save(
                id = "ai_provider_config",
                type = "ai_assistant",
                label = "AI Assistant Config",
                fields = config.toMap(),
            )
            _uiState.update { it.copy(providerConfig = config, showSettingsSheet = false) }
        }
    }

    fun toggleSettingsSheet(show: Boolean) {
        _uiState.update { it.copy(showSettingsSheet = show) }
    }

    fun detachFlow() {
        _uiState.update {
            it.copy(
                currentFlow = null,
                activeProposedFlow = null,
            )
        }
        val msg = AiChatMessage(
            sender = MessageSender.Assistant,
            text = "Detached from workflow context. You are now in general automation mode. What would you like to build or discuss?",
        )
        _uiState.update { it.copy(messages = it.messages + msg) }
    }

    private fun initSession(targetFlowId: String?) {
        viewModelScope.launch {
            val flow = if (!targetFlowId.isNullOrBlank()) flowRepository.flow(targetFlowId) else null
            val greeting = if (flow != null) {
                AiChatMessage(
                    sender = MessageSender.Assistant,
                    text = "I see you're working on the **\"${flow.name}\"** workflow. What would you like to do with it — make changes, debug an issue, set up steps, or something else?",
                )
            } else {
                AiChatMessage(
                    sender = MessageSender.Assistant,
                    text = "What do you want to automate? Tell me what to build or ask a question.",
                )
            }
            _uiState.update {
                it.copy(
                    currentFlow = flow,
                    messages = listOf(greeting),
                )
            }
        }
    }

    fun sendMessage(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank() || _uiState.value.isGenerating) return

        val userMsg = AiChatMessage(
            sender = MessageSender.User,
            text = trimmed,
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMsg,
                isGenerating = true,
            )
        }

        viewModelScope.launch {
            val state = _uiState.value
            val response = agent.executeTurn(
                prompt = trimmed,
                currentFlow = state.currentFlow,
                config = state.providerConfig,
                chatHistory = state.messages,
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

    fun applyProposedFlow(flow: Flow, onApplied: (flowId: String) -> Unit) {
        viewModelScope.launch {
            flowRepository.save(flow)
            _uiState.update {
                it.copy(
                    currentFlow = flow,
                    activeProposedFlow = null,
                    messages = it.messages + AiChatMessage(
                        sender = MessageSender.Assistant,
                        text = "✅ Successfully applied changes to **\"${flow.name}\"**! Opening canvas...",
                    ),
                )
            }
            onApplied(flow.id)
        }
    }

    fun clearChat() {
        initSession(_uiState.value.currentFlow?.id)
    }
}
