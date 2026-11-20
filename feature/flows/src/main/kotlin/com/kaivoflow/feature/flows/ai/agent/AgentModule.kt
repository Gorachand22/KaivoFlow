package com.kaivoflow.feature.flows.ai.agent

import com.kaivoflow.feature.flows.ai.AiSessionRepository
import com.kaivoflow.feature.flows.ai.FileAiSessionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {
    @Binds
    abstract fun bindAgentFlowPort(impl: RoomAgentFlowPort): AgentFlowPort

    @Binds
    abstract fun bindAgentActivationPort(impl: EngineAgentActivationPort): AgentActivationPort

    @Binds
    abstract fun bindAgentCredentialPort(impl: VaultAgentCredentialPort): AgentCredentialPort

    @Binds
    @Singleton
    abstract fun bindAgentRepository(impl: FileAgentRepository): AgentRepository

    @Binds
    @Singleton
    abstract fun bindAiSessionRepository(impl: FileAiSessionRepository): AiSessionRepository

    companion object {
        @Provides
        @Singleton
        fun provideAgentPublicationService(
            agents: AgentRepository,
            flows: AgentFlowPort,
            activation: AgentActivationPort,
            credentials: AgentCredentialPort,
        ): AgentPublicationService = AgentPublicationService(agents, flows, activation, credentials)
    }
}
