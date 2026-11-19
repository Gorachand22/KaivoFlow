package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.ModelDiscovery
import com.kaivoflow.core.ai.ProviderEndpoints
import com.kaivoflow.core.engine.HttpGateway
import kotlinx.coroutines.CancellationException

/**
 * Lists the models of a provider for the settings screens. The work is done by `:core:ai`'s [ModelDiscovery]:
 * the address passes the host guard, the key goes in a header (never the URL), redirects are refused and error
 * text is redacted. This class only keeps the old call shape for the screens.
 */
class ModelDiscoveryService(private val http: HttpGateway) {
    suspend fun discoverModels(provider: AiProvider, baseUrl: String, apiKey: String): Result<List<String>> =
        try {
            Result.success(ModelDiscovery(http).list(ProviderEndpoints.config(provider.toKind(), "", baseUrl, apiKey)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
