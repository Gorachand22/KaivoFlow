package com.kaivoflow.core.nodes.ai.shared

import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

internal fun aiSpec(
    id: String,
    title: String,
    subtitle: String,
    iconKey: String,
    extraParams: List<ParamSpec> = emptyList(),
    outputs: Int = 1,
    outputLabels: List<String> = emptyList(),
) = NodeSpec(
    id = id,
    title = title,
    category = StepCategory.Ai,
    subtitle = subtitle,
    iconKey = iconKey,
    outputs = outputs,
    outputLabels = outputLabels.toImmutableList(),
    params = (listOf(
            ParamSpec(
                key = "provider",
                label = "Provider",
                type = ParamType.Select,
                default = "openai",
                options = persistentListOf(
                    ParamOption("openai", "OpenAI-compatible"),
                    ParamOption("gemini", "Google Gemini"),
                    ParamOption("ollama", "Ollama (on your own machine)"),
                ),
            ),
            ParamSpec("credential", "API key credential", ParamType.Secret, showWhen = mapOf("provider" to listOf("openai", "gemini"))),
            ParamSpec("model", "Model", ParamType.Text, required = true, placeholder = "gpt-4o-mini"),
            ParamSpec("baseUrl", "Custom base URL", ParamType.Url, advanced = true, hint = "For Groq, OpenRouter, LM Studio or a LAN IP."),
            ParamSpec("temperature", "Creativity", ParamType.Number, default = 0.2, advanced = true),
            ParamSpec("maxTokens", "Max length", ParamType.Number, default = 800, advanced = true),
        ) + extraParams).toImmutableList(),
    keywords = listOf("ai", "llm", "gpt", "gemini", "ollama", "openai", "model").toImmutableList(),
    producesItems = true,
)
