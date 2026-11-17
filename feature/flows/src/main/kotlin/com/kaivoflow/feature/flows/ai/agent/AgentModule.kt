package com.kaivoflow.feature.flows.ai.agent

import com.kaivoflow.feature.flows.ai.AiSessionRepository
import com.kaivoflow.feature.flows.ai.FileAiSessionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {
    @Binds
    @Singleton
    abstract fun bindAgentRepository(impl: FileAgentRepository): AgentRepository

    @Binds
    @Singleton
    abstract fun bindAiSessionRepository(impl: FileAiSessionRepository): AiSessionRepository
}
