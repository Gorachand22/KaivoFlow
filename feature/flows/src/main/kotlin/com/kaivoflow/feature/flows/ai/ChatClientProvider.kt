package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.ai.ChatModelClient
import com.kaivoflow.core.ai.ModelConfig

/** Builds a provider client from a checked config. The app binds the real adapters over the HTTP gateway; tests pass a fake. */
fun interface ChatClientProvider {
    /** Throws `ModelException` (kind Policy) when the base address is not allowed. */
    fun create(config: ModelConfig): ChatModelClient
}
