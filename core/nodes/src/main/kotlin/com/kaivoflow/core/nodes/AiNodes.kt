package com.kaivoflow.core.nodes

import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.engine.ParamResolver
import com.kaivoflow.core.engine.StepContext
import com.kaivoflow.core.engine.StepExecutionException
import com.kaivoflow.core.engine.StepExecutor
import com.kaivoflow.core.engine.StepResult
import com.kaivoflow.core.engine.Values
import com.kaivoflow.core.model.FlowData
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.ParamOption
import com.kaivoflow.core.model.ParamSpec
import com.kaivoflow.core.model.ParamType
import com.kaivoflow.core.model.StepCategory
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * AI Steps.
 *
 * Three providers are supported through one code path:
 *  • `openai`   — any OpenAI-compatible endpoint (OpenAI, Groq, Together, OpenRouter, LM Studio)
 *  • `gemini`   — Google Generative Language API
 *  • `ollama`   — a model running on the user's own machine or phone-side LAN IP
 *
 * KaivoFlow never proxies AI traffic: the phone calls the provider directly with the user's own
 * key, which is what the Privacy Centre promises.
 */
internal object AiClient {

    data class Result(val text: String, val json: Any?, val usage: Map<String, Any?>)

    suspend fun chat(
        ctx: StepContext,
        params: Map<String, Any?>,
        prompt: String,
        system: String,
        jsonMode: Boolean,
    ): Result {
        val provider = Values.asString(params["provider"], "openai")
        val model = Values.asString(params["model"])
        val temperature = Values.asDouble(params["temperature"]) ?: 0.2
        val maxTokens = (Values.asDouble(params["maxTokens"]) ?: 800.0).toInt()

        if (model.isBlank()) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "choose a model (or type a custom one)")
        }

        val credential = runCatching { ctx.credential() }.getOrNull().orEmpty()
        val apiKey = credential["apiKey"].orEmpty().ifBlank { Values.asString(params["apiKey"]) }

        return when (provider) {
            "gemini" -> gemini(ctx, params, model, prompt, system, temperature, maxTokens, apiKey, jsonMode)
            "ollama" -> ollama(ctx, params, model, prompt, system, temperature, jsonMode)
            else -> openAiCompatible(ctx, params, model, prompt, system, temperature, maxTokens, apiKey, jsonMode)
        }
    }

    private suspend fun openAiCompatible(
        ctx: StepContext,
        params: Map<String, Any?>,
        model: String,
        prompt: String,
        system: String,
        temperature: Double,
        maxTokens: Int,
        apiKey: String,
        jsonMode: Boolean,
    ): Result {
        val baseUrl = Values.asString(params["baseUrl"]).ifBlank { "https://api.openai.com/v1" }.trimEnd('/')
        if (apiKey.isBlank() && !baseUrl.contains("localhost") && !baseUrl.contains("127.0.0.1")) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "add an API key credential for $model")
        }
        val messages = buildList {
            if (system.isNotBlank()) add(mapOf("role" to "system", "content" to system))
            add(mapOf("role" to "user", "content" to prompt))
        }
        val body = buildMap<String, Any?> {
            put("model", model)
            put("messages", messages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            if (jsonMode) put("response_format", mapOf("type" to "json_object"))
        }
        val response = ctx.http.request(
            HttpRequestSpec(
                method = "POST",
                url = "$baseUrl/chat/completions",
                headers = buildMap {
                    put("Content-Type", "application/json")
                    if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
                },
                body = body,
                timeoutMs = 120_000,
            )
        )
        if (!response.isSuccess) {
            throw StepExecutionException(
                ctx.step.id, ctx.step.name,
                "$model returned HTTP ${response.status}: ${response.bodyText.take(240)}"
            )
        }
        val parsed = Values.asMap(response.body)
        val content = Values.asList(parsed["choices"]).firstOrNull()?.let { choice ->
            Values.asString(Values.deepGet(choice, "message.content"))
        }.orEmpty()
        return Result(content, JsonLite.parse(content), Values.asMap(parsed["usage"]))
    }

    private suspend fun gemini(
        ctx: StepContext,
        params: Map<String, Any?>,
        model: String,
        prompt: String,
        system: String,
        temperature: Double,
        maxTokens: Int,
        apiKey: String,
        jsonMode: Boolean,
    ): Result {
        if (apiKey.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add a Google AI API key")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val body = buildMap<String, Any?> {
            put("contents", listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt)))))
            if (system.isNotBlank()) put("systemInstruction", mapOf("parts" to listOf(mapOf("text" to system))))
            put(
                "generationConfig",
                buildMap<String, Any?> {
                    put("temperature", temperature)
                    put("maxOutputTokens", maxTokens)
                    if (jsonMode) put("responseMimeType", "application/json")
                },
            )
        }
        val response = ctx.http.request(
            HttpRequestSpec(method = "POST", url = url, headers = mapOf("Content-Type" to "application/json"), body = body, timeoutMs = 120_000)
        )
        if (!response.isSuccess) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "$model returned HTTP ${response.status}: ${response.bodyText.take(240)}")
        }
        val parsed = Values.asMap(response.body)
        val content = Values.asList(parsed["candidates"]).firstOrNull()?.let { candidate ->
            Values.asList(Values.deepGet(candidate, "content.parts")).joinToString("") { part ->
                Values.asString(Values.deepGet(part, "text"))
            }
        }.orEmpty()
        return Result(content, JsonLite.parse(content), Values.asMap(parsed["usageMetadata"]))
    }

    private suspend fun ollama(
        ctx: StepContext,
        params: Map<String, Any?>,
        model: String,
        prompt: String,
        system: String,
        temperature: Double,
        jsonMode: Boolean,
    ): Result {
        val baseUrl = Values.asString(params["baseUrl"]).ifBlank { "http://127.0.0.1:11434" }.trimEnd('/')
        val messages = buildList {
            if (system.isNotBlank()) add(mapOf("role" to "system", "content" to system))
            add(mapOf("role" to "user", "content" to prompt))
        }
        val response = ctx.http.request(
            HttpRequestSpec(
                method = "POST",
                url = "$baseUrl/api/chat",
                headers = mapOf("Content-Type" to "application/json"),
                body = mapOf(
                    "model" to model,
                    "messages" to messages,
                    "stream" to false,
                    "format" to if (jsonMode) "json" else null,
                    "options" to mapOf("temperature" to temperature),
                ),
                timeoutMs = 120_000,
            )
        )
        if (!response.isSuccess) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, "Could not reach $baseUrl (HTTP ${response.status}). Is Ollama running?")
        }
        val parsed = Values.asMap(response.body)
        val content = Values.asString(Values.deepGet(parsed, "message.content"))
        return Result(content, JsonLite.parse(content), emptyMap())
    }
}

private fun aiSpec(
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

/** Ask the model anything and store the answer on the item. */
class AiChatNode : StepExecutor {
    override val spec = aiSpec(
        id = "ai.chat",
        title = "Ask AI",
        subtitle = "Send a prompt and keep the answer",
        iconKey = "sparkles",
        extraParams = listOf(
            ParamSpec(
                key = "prompt",
                label = "Prompt",
                type = ParamType.Multiline,
                required = true,
                placeholder = "Summarise this in one line: {{ ${'$'}json.text }}",
            ),
            ParamSpec("system", "System instructions", ParamType.Multiline, advanced = true),
            ParamSpec("outputField", "Put the answer in", ParamType.Text, default = "answer"),
        ),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val resolver = ParamResolver(ctx.evaluator)
        val outputs = ctx.input.mapIndexed { index, item ->
            val scope = ctx.scope(index)
            val prompt = Values.asString(resolver.resolveValue(params["prompt"], scope))
            if (prompt.isBlank()) throw StepExecutionException(ctx.step.id, ctx.step.name, "the prompt is empty")
            val system = Values.asString(resolver.resolveValue(params["system"], scope))
            val result = AiClient.chat(ctx, params, prompt, system, jsonMode = false)
            val field = Values.asString(params["outputField"], "answer")
            item.copy(json = item.json + (field to result.text))
        }
        return ctx.output(outputs)
    }
}

/** Extract fields using a shallow type contract, rejecting malformed or unexpected output. */
class AiExtractNode : StepExecutor {
    override val spec = aiSpec(
        id = "ai.extract",
        title = "Extract fields with AI",
        subtitle = "Turn messy text into clean fields",
        iconKey = "extract",
        extraParams = listOf(
            ParamSpec("text", "Text to read", ParamType.Multiline, required = true, placeholder = "{{ ${'$'}json.text }}"),
            ParamSpec(
                key = "schema",
                label = "Fields to extract",
                type = ParamType.Json,
                required = true,
                default = """{"merchant":"string","amount":"number","date":"string","category":"string"}""",
            ),
            ParamSpec("instructions", "Extra instructions", ParamType.Multiline, advanced = true),
            ParamSpec("mergeIntoItem", "Add fields to the item", ParamType.Boolean, default = true),
        ),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val schema = try { AiExtractionSchema.parse(params["schema"]) }
        catch (e: IllegalArgumentException) {
            throw StepExecutionException(ctx.step.id, ctx.step.name, e.message ?: "the field types are invalid")
        }
        val resolver = ParamResolver(ctx.evaluator)
        val outputs = ctx.input.mapIndexed { index, item ->
            val scope = ctx.scope(index)
            val text = Values.asString(resolver.resolveValue(params["text"], scope))
            val extra = Values.asString(params["instructions"])
            val prompt = buildString {
                appendLine("Extract data from the text below.")
                appendLine("Return ONLY a JSON object with exactly these keys and types: ${Values.stringify(schema)}")
                appendLine("Use null when a value is not present. Do not invent values.")
                if (extra.isNotBlank()) appendLine(extra)
                appendLine()
                appendLine("TEXT:")
                append(text.take(12_000))
            }
            val result = AiClient.chat(ctx, params, prompt, "You are a precise data extraction engine. Reply with JSON only.", jsonMode = true)
            val extracted = result.json as? Map<*, *>
                ?: throw StepExecutionException(
                    ctx.step.id, ctx.step.name,
                    "the model did not return valid JSON. Try a larger model, or lower the creativity setting."
                )
            AiExtractionSchema.validate(schema, extracted)?.let { problem ->
                throw StepExecutionException(ctx.step.id, ctx.step.name, problem)
            }
            if (Values.asBoolean(params["mergeIntoItem"] ?: true)) {
                item.copy(json = Values.deepMerge(item.json, extracted) as Map<String, Any?>)
            } else {
                FlowData(Values.asMap(extracted))
            }
        }
        return ctx.output(outputs)
    }
}

/** Route items into branches by an AI-chosen label — classification without code. */
class AiClassifyNode : StepExecutor {
    override val spec = aiSpec(
        id = "ai.classify",
        title = "Sort with AI",
        subtitle = "Send items into branches by meaning",
        iconKey = "sort",
        outputs = 4,
        outputLabels = listOf("label 1", "label 2", "label 3", "anything else"),
        extraParams = listOf(
            ParamSpec("text", "Text to classify", ParamType.Multiline, required = true, placeholder = "{{ ${'$'}json.text }}"),
            ParamSpec("labels", "Labels (comma separated)", ParamType.Text, required = true, placeholder = "urgent, invoice, personal"),
            ParamSpec("instructions", "How to decide", ParamType.Multiline, advanced = true),
            ParamSpec("outputField", "Put the label in", ParamType.Text, default = "label"),
        ),
    )

    override val requiresNetwork: Boolean get() = true

    override suspend fun execute(ctx: StepContext): StepResult {
        val params = ctx.params()
        val resolver = ParamResolver(ctx.evaluator)
        val labels = Values.asString(params["labels"]).split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (labels.isEmpty()) throw StepExecutionException(ctx.step.id, ctx.step.name, "add at least one label")
        val outputs = MutableList(spec.outputs) { mutableListOf<FlowData>() }
        val field = Values.asString(params["outputField"], "label")

        ctx.input.forEachIndexed { index, item ->
            val scope = ctx.scope(index)
            val text = Values.asString(resolver.resolveValue(params["text"], scope))
            val instructions = Values.asString(resolver.resolveValue(params["instructions"], scope))
            val prompt = buildString {
                appendLine("Classify the text into exactly one of these labels: ${labels.joinToString(", ")}")
                appendLine("If none fits, answer \"other\".")
                if (instructions.isNotBlank()) appendLine(instructions)
                appendLine()
                appendLine("TEXT:")
                append(text.take(8_000))
            }
            val result = AiClient.chat(
                ctx, params, prompt,
                "Answer with a single label and nothing else.", jsonMode = false
            )
            val answer = result.text.trim().trim('"', '.', '\n').lowercase()
            val index2 = labels.indexOfFirst { answer.contains(it.lowercase()) }
            val target = if (index2 in 0..2) index2 else 3
            outputs[target] += item.copy(json = item.json + (field to (if (index2 >= 0) labels[index2] else "other")))
        }
        return StepResult(outputs.mapIndexed { i, items -> i to items }.toMap())
    }
}
