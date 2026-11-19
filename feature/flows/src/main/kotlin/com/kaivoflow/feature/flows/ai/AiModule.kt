package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.ApprovalRelay
import com.kaivoflow.core.ai.ChatModels
import com.kaivoflow.core.ai.flowedit.AssistantCatalog
import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.nodes.CatalogAliases
import com.kaivoflow.core.nodes.NodeCatalog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wiring for the AI backend on `:core:ai`: the provider clients (over the app's one HTTP gateway), the catalog the
 * assistant reads, and the relay between the agent loop and the screens that show approve/deny cards.
 * [CredentialVaultBridge] is `@Singleton @Inject` and needs no entry here.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    @Provides
    @Singleton
    fun approvalRelay(): ApprovalRelay = ApprovalRelay()

    @Provides
    @Singleton
    fun assistantCatalog(): AssistantCatalog = AssistantCatalog(NodeCatalog.registry(), CatalogAliases.renames)

    @Provides
    @Singleton
    fun chatClients(http: HttpGateway): ChatClientProvider = ChatClientProvider { config -> ChatModels.create(http, config) }
}
