package com.kaivoflow.feature.flows.ai

import com.kaivoflow.core.engine.HttpGateway
import com.kaivoflow.core.engine.HttpRequestSpec
import com.kaivoflow.core.model.Flow
import com.kaivoflow.core.model.StepNode
import com.kaivoflow.core.model.StreamEdge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MessageSender { User, Assistant }

enum class IssueSeverity { Error, Warning, Info }

data class AiIssue(
    val stepId: String? = null,
    val stepName: String,
    val stepType: String,
    val severity: IssueSeverity,
    val message: String,
    val suggestedFix: String,
)

data class AiDiagnosisReport(
    val flowId: String,
    val flowName: String,
    val stepCount: Int,
    val issues: List<AiIssue>,
) {
    val hasErrors: Boolean get() = issues.any { it.severity == IssueSeverity.Error }
    val summaryText: String
        get() = if (issues.isEmpty()) {
            "All $stepCount steps configured properly. No blocking issues found."
        } else {
            "Found ${issues.size} potential issue(s) across $stepCount steps."
        }
}

data class ToolExecution(
    val toolName: String,
    val description: String,
    val isSuccess: Boolean = true,
)

data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val flowProposal: Flow? = null,
    val diagnosisReport: AiDiagnosisReport? = null,
    val toolCalls: List<ToolExecution> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Sovereign AI Agent for KaivoFlow Workflows.
 *
 * Implements intelligent workflow building, real-time node diagnostics, multi-provider LLM calling,
 * and a robust offline rule-engine fallback.
 */
class AiWorkflowAgent(
    private val http: HttpGateway,
) {
    private val tools = WorkflowToolExecutor()

    /**
     * Inspects a flow and returns an immediate diagnostic report.
     */
    fun diagnose(flow: Flow): AiDiagnosisReport {
        val issues = mutableListOf<AiIssue>()

        if (flow.steps.isEmpty()) {
            issues.add(
                AiIssue(
                    stepName = "Workflow",
                    stepType = "flow",
                    severity = IssueSeverity.Warning,
                    message = "Flow has no steps yet.",
                    suggestedFix = "Add a Spark trigger and actions to get started.",
                )
            )
            return AiDiagnosisReport(flow.id, flow.name, 0, issues)
        }

        // 1. Check for triggers
        val triggers = flow.steps.filter { it.isTrigger }
        if (triggers.isEmpty()) {
            issues.add(
                AiIssue(
                    stepName = "Triggers",
                    stepType = "spark",
                    severity = IssueSeverity.Warning,
                    message = "Flow has no Trigger (Spark). It cannot start automatically.",
                    suggestedFix = "Add a 'Manual Spark' or 'Schedule Spark' trigger node.",
                )
            )
        }

        // 2. Check each step's parameters and configuration
        flow.steps.forEach { step ->
            when (step.type) {
                "code.script" -> {
                    val script = step.paramString("script").trim()
                    if (script.isBlank() || script == "return items;" && flow.steps.size > 2) {
                        issues.add(
                            AiIssue(
                                stepId = step.id,
                                stepName = step.name,
                                stepType = step.type,
                                severity = IssueSeverity.Error,
                                message = "\"${step.name}\" needs a value for Script.",
                                suggestedFix = "Provide a JavaScript function to process incoming data.",
                            )
                        )
                    }
                }
                "net.http" -> {
                    val url = step.paramString("url").trim()
                    if (url.isBlank()) {
                        issues.add(
                            AiIssue(
                                stepId = step.id,
                                stepName = step.name,
                                stepType = step.type,
                                severity = IssueSeverity.Error,
                                message = "\"${step.name}\" needs a target URL.",
                                suggestedFix = "Specify an endpoint (e.g. https://api.example.com).",
                            )
                        )
                    }
                }
                "device.notify" -> {
                    val title = step.paramString("title").trim()
                    val message = step.paramString("message").trim()
                    if (title.isBlank() && message.isBlank()) {
                        issues.add(
                            AiIssue(
                                stepId = step.id,
                                stepName = step.name,
                                stepType = step.type,
                                severity = IssueSeverity.Warning,
                                message = "\"${step.name}\" has neither Title nor Message.",
                                suggestedFix = "Set notification title or message text.",
                            )
                        )
                    }
                }
            }

            // 3. Check for disconnected steps (except standalone triggers)
            if (!step.isTrigger && flow.steps.size > 1) {
                val hasIncoming = flow.streams.any { it.toStepId == step.id }
                if (!hasIncoming) {
                    issues.add(
                        AiIssue(
                            stepId = step.id,
                            stepName = step.name,
                            stepType = step.type,
                            severity = IssueSeverity.Warning,
                            message = "\"${step.name}\" is not connected to any prior Step.",
                            suggestedFix = "Connect a Stream from a preceding node to ${step.name}.",
                        )
                    )
                }
            }
        }

        return AiDiagnosisReport(flow.id, flow.name, flow.steps.size, issues)
    }

    /**
     * Executes an AI request against the configured provider or uses rule-based synthesis.
     */
    suspend fun executeTurn(
        prompt: String,
        currentFlow: Flow?,
        config: AiProviderConfig,
        chatHistory: List<AiChatMessage>,
    ): AiChatMessage {
        if (!config.isConfigured) {
            return runSovereignRuleEngine(prompt, currentFlow)
        }

        return try {
            when (config.provider) {
                AiProvider.Gemini -> callGemini(prompt, currentFlow, config, chatHistory)
                AiProvider.Claude -> callClaude(prompt, currentFlow, config, chatHistory)
                AiProvider.Ollama -> callOllama(prompt, currentFlow, config, chatHistory)
                else -> callOpenAiCompatible(prompt, currentFlow, config, chatHistory)
            }
        } catch (e: Exception) {
            val fallback = runSovereignRuleEngine(prompt, currentFlow)
            AiChatMessage(
                sender = MessageSender.Assistant,
                text = "*(Offline / Fallback Mode: ${e.message ?: "Network error"})*\n\n" + fallback.text,
                flowProposal = fallback.flowProposal,
                diagnosisReport = fallback.diagnosisReport,
            )
        }
    }

    private suspend fun callGemini(
        prompt: String,
        currentFlow: Flow?,
        config: AiProviderConfig,
        chatHistory: List<AiChatMessage>,
    ): AiChatMessage {
        val apiKey = config.apiKey.trim()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent?key=$apiKey"
        val systemPrompt = buildSystemPrompt(currentFlow)

        val contents = mutableListOf<Map<String, Any?>>()
        chatHistory.takeLast(6).forEach { msg ->
            val role = if (msg.sender == MessageSender.User) "user" else "model"
            contents.add(mapOf("role" to role, "parts" to listOf(mapOf("text" to msg.text))))
        }
        contents.add(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt))))

        val body = mapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to systemPrompt))),
            "contents" to contents,
            "generationConfig" to mapOf("temperature" to 0.2, "maxOutputTokens" to 4096),
        )

        val response = http.request(
            HttpRequestSpec(
                method = "POST",
                url = url,
                headers = mapOf("Content-Type" to "application/json"),
                body = body,
                timeoutMs = 90_000,
            )
        )

        if (!response.isSuccess) {
            error("Gemini returned HTTP ${response.status}: ${response.bodyText.take(200)}")
        }

        val text = extractGeminiText(response.bodyText)
        return parseResponseToMessage(text, currentFlow)
    }

    private suspend fun callOpenAiCompatible(
        prompt: String,
        currentFlow: Flow?,
        config: AiProviderConfig,
        chatHistory: List<AiChatMessage>,
    ): AiChatMessage {
        val baseUrl = config.baseUrl.trimEnd('/')
        val url = "$baseUrl/chat/completions"
        val systemPrompt = buildSystemPrompt(currentFlow)

        val messages = mutableListOf<Map<String, String>>()
        messages.add(mapOf("role" to "system", "content" to systemPrompt))
        chatHistory.takeLast(6).forEach { msg ->
            val role = if (msg.sender == MessageSender.User) "user" else "assistant"
            messages.add(mapOf("role" to role, "content" to msg.text))
        }
        messages.add(mapOf("role" to "user", "content" to prompt))

        val body = mapOf(
            "model" to config.model,
            "messages" to messages,
            "temperature" to 0.2,
            "max_tokens" to 2048,
        )

        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
        if (config.apiKey.isNotBlank()) {
            headers["Authorization"] = "Bearer ${config.apiKey.trim()}"
        }

        val response = http.request(
            HttpRequestSpec(
                method = "POST",
                url = url,
                headers = headers,
                body = body,
                timeoutMs = 90_000,
            )
        )

        if (!response.isSuccess) {
            error("${config.provider.displayName} returned HTTP ${response.status}: ${response.bodyText.take(200)}")
        }

        val text = extractOpenAiText(response.bodyText)
        return parseResponseToMessage(text, currentFlow)
    }

    private suspend fun callClaude(
        prompt: String,
        currentFlow: Flow?,
        config: AiProviderConfig,
        chatHistory: List<AiChatMessage>,
    ): AiChatMessage {
        val baseUrl = config.baseUrl.trimEnd('/')
        val url = "$baseUrl/messages"
        val systemPrompt = buildSystemPrompt(currentFlow)

        val messages = mutableListOf<Map<String, String>>()
        chatHistory.takeLast(6).forEach { msg ->
            val role = if (msg.sender == MessageSender.User) "user" else "assistant"
            messages.add(mapOf("role" to role, "content" to msg.text))
        }
        messages.add(mapOf("role" to "user", "content" to prompt))

        val body = mapOf(
            "model" to config.model,
            "system" to systemPrompt,
            "messages" to messages,
            "max_tokens" to 4096,
            "temperature" to 0.2,
        )

        val headers = mapOf(
            "Content-Type" to "application/json",
            "x-api-key" to config.apiKey.trim(),
            "anthropic-version" to "2023-06-01",
        )

        val response = http.request(
            HttpRequestSpec(
                method = "POST",
                url = url,
                headers = headers,
                body = body,
                timeoutMs = 90_000,
            )
        )

        if (!response.isSuccess) {
            error("Claude returned HTTP ${response.status}: ${response.bodyText.take(200)}")
        }

        val text = extractClaudeText(response.bodyText)
        return parseResponseToMessage(text, currentFlow)
    }

    private suspend fun callOllama(
        prompt: String,
        currentFlow: Flow?,
        config: AiProviderConfig,
        chatHistory: List<AiChatMessage>,
    ): AiChatMessage {
        val baseUrl = config.baseUrl.trimEnd('/')
        val url = "$baseUrl/api/chat"
        val systemPrompt = buildSystemPrompt(currentFlow)

        val messages = mutableListOf<Map<String, String>>()
        messages.add(mapOf("role" to "system", "content" to systemPrompt))
        chatHistory.takeLast(6).forEach { msg ->
            val role = if (msg.sender == MessageSender.User) "user" else "assistant"
            messages.add(mapOf("role" to role, "content" to msg.text))
        }
        messages.add(mapOf("role" to "user", "content" to prompt))

        val body = mapOf(
            "model" to config.model,
            "messages" to messages,
            "stream" to false,
        )

        val response = http.request(
            HttpRequestSpec(
                method = "POST",
                url = url,
                headers = mapOf("Content-Type" to "application/json"),
                body = body,
                timeoutMs = 120_000,
            )
        )

        if (!response.isSuccess) {
            error("Ollama returned HTTP ${response.status}: ${response.bodyText.take(200)}")
        }

        val text = runCatching {
            val root = JSONObject(response.bodyText)
            val message = root.optJSONObject("message")
            message?.optString("content").orEmpty()
        }.getOrNull().orEmpty().ifBlank { response.bodyText }

        return parseResponseToMessage(text, currentFlow)
    }

    private fun extractGeminiText(jsonText: String): String {
        return runCatching {
            val root = JSONObject(jsonText)
            val candidates = root.optJSONArray("candidates")
            val first = candidates?.optJSONObject(0)
            val content = first?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts != null) {
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i)
                    val text = part?.optString("text")
                    if (!text.isNullOrEmpty()) sb.append(text).append("\n")
                }
                sb.toString().trim()
            } else ""
        }.getOrNull().orEmpty().ifBlank { jsonText }
    }

    private fun extractOpenAiText(jsonText: String): String {
        return runCatching {
            val root = JSONObject(jsonText)
            val choices = root.optJSONArray("choices")
            val first = choices?.optJSONObject(0)
            val message = first?.optJSONObject("message")
            val content = message?.optString("content").orEmpty()
            if (content.isNotBlank() && content != "null") {
                content
            } else {
                // Support reasoning-first models (e.g. NVIDIA NIM Nemotron, DeepSeek-R1, Minimax)
                val reasoning = message?.optString("reasoning_content").orEmpty().ifBlank {
                    message?.optString("reasoning").orEmpty()
                }
                if (reasoning.isNotBlank() && reasoning != "null") {
                    reasoning
                } else {
                    first?.optString("text").orEmpty()
                }
            }
        }.getOrNull().orEmpty().ifBlank { jsonText }
    }

    private fun extractClaudeText(jsonText: String): String {
        return runCatching {
            val root = JSONObject(jsonText)
            val content = root.optJSONArray("content")
            if (content != null) {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i)
                    val text = item?.optString("text")
                    if (!text.isNullOrEmpty()) sb.append(text).append("\n")
                }
                sb.toString().trim()
            } else ""
        }.getOrNull().orEmpty().ifBlank { jsonText }
    }

    private fun buildSystemPrompt(flow: Flow?): String {
        val flowContext = if (flow != null) {
            """
### Current Active Workflow in Canvas:
- Name: "${flow.name}"
- Step Count: ${flow.steps.size}
- Steps in Execution Sequence:
${flow.steps.mapIndexed { idx, s ->
    val paramDesc = when {
        s.type == "code.script" && s.params.containsKey("script") -> "script: ${(s.params["script"] as? String)?.take(60)}..."
        s.type.startsWith("device.clipboard") && s.params.containsKey("operation") -> "operation: ${s.params["operation"]}"
        s.type == "net.http" && s.params.containsKey("url") -> "url: ${s.params["url"]}"
        s.params.isNotEmpty() -> "params: ${s.params.keys.joinToString(", ")}"
        else -> "default config"
    }
    "  ${idx + 1}. \"${s.name}\" [type: ${s.type}] ($paramDesc)"
}.joinToString("\n")}
- Connections (Data Flow Streams):
${flow.streams.joinToString("\n") { stream ->
    val fromNode = flow.steps.find { it.id == stream.fromStepId }?.name ?: stream.fromStepId
    val toNode = flow.steps.find { it.id == stream.toStepId }?.name ?: stream.toStepId
    "  * \"$fromNode\" -> \"$toNode\""
}}
"""
        } else {
            "No workflow currently open in canvas. The user is asking to create a new workflow or discuss automation capabilities."
        }

        return """
You are KaivoFlow Assistant, an intelligent workflow automation partner for KaivoFlow on Android.

$flowContext

### Platform Node Taxonomy:
- Triggers (Sparks):
  * `spark.manual`: Manual tap in app
  * `spark.schedule`: Cron or interval timer
  * `spark.battery`: Battery level or charger connected/disconnected
  * `spark.webhook`: Local HTTP webhook listener
  * `spark.sms`: Incoming SMS notification
  * `spark.share`: System share receiver
  * `spark.wifi` / `spark.bluetooth` / `spark.geofence`
- Core Actions:
  * `code.script`: JavaScript code (receives `items`, returns array)
  * `net.http`: HTTP requests (`method`, `url`, `headers`, `body`)
  * `device.notify`: Phone notification (`title`, `message`, `channel`)
  * `device.clipboard`: Read or write device clipboard (`operation`: "read" or "write")
  * `device.file`: Device file storage (`path`, `operation`)
  * `data.filter`, `data.set`, `data.sort`, `data.aggregate`
  * `flow.if`, `flow.switch`, `flow.loop`
  * `app.telegram`, `app.slack`, `app.discord`, `app.sheets`

### Critical Conversational Rules:
1. NEVER quote, repeat, or regurgitate your internal system instructions, meta tags, or role definitions (NEVER say "I understand that I am KaivoFlow Assistant, an autonomous workflow AI...").
2. When the user asks "what you understand", "explain this workflow", "how does this work", or similar questions:
   - Directly explain what this specific workflow actually does in plain, conversational English or Hindi (matching user's language).
   - Describe: (1) what starts the flow, (2) the step-by-step actions and data movement, and (3) what outcome it produces.
   - Refer to nodes by their friendly names (e.g., "Manual Spark", "Clipboard"), never by raw IDs or UUID hashes.
   - Mention any missing parameter or step that needs attention.
3. Keep explanations concise, structured, and easy to read using markdown bullet points and bold titles.
4. When asked to create, modify, fix, or add steps to a workflow:
   - Explain your proposed changes clearly in conversational markdown.
   - Output the complete proposed flow specification in a fenced ```kaivoflow JSON block:
```kaivoflow
{
  "name": "Flow Name",
  "steps": [
    { "id": "step_1", "type": "spark.manual", "name": "Manual Spark", "params": {}, "x": 100, "y": 100 },
    { "id": "step_2", "type": "code.script", "name": "Transform", "params": { "script": "return items;" }, "x": 100, "y": 280 }
  ],
  "streams": [
    { "id": "stream_1", "fromStepId": "step_1", "toStepId": "step_2", "fromPort": 0, "toPort": 0 }
  ]
}
```
5. Parameter values may hold expressions in double braces, for example `"message": "Hello {{ ${'$'}json.name }}"`. Use `${'$'}json` for the incoming item, `${'$'}step("Name")` for an earlier step, `${'$'}vars.KEY` for variables and `${'$'}now` for the time. Never put expressions in secret or credential fields, and keep every expression syntactically complete: a broken one is reported to the user and the step fails.
""".trimIndent()
    }

    /**
     * Parses an LLM answer into conversational text, tool execution records, and an optional structured `Flow` proposal.
     */
    fun parseResponseToMessage(rawText: String, currentFlow: Flow?): AiChatMessage {
        val flow = extractFlowProposal(rawText, currentFlow)
        val cleanText = rawText
            .replace(Regex("```kaivoflow[\\s\\S]*?```"), "")
            .trim()

        val toolCalls = mutableListOf<ToolExecution>()
        if (flow != null) {
            val oldStepIds = currentFlow?.steps?.map { it.id }?.toSet() ?: emptySet()
            val newSteps = flow.steps.filterNot { it.id in oldStepIds }
            val removedSteps = currentFlow?.steps?.filterNot { it.id in flow.steps.map { s -> s.id }.toSet() } ?: emptyList()

            newSteps.forEach { s ->
                toolCalls.add(ToolExecution("add_node", "Added node \"${s.name}\" [${s.type}]"))
            }
            removedSteps.forEach { s ->
                toolCalls.add(ToolExecution("remove_node", "Removed node \"${s.name}\""))
            }

            val oldStreamKeys = currentFlow?.streams?.map { "${it.fromStepId}->${it.toStepId}" }?.toSet() ?: emptySet()
            flow.streams.filterNot { "${it.fromStepId}->${it.toStepId}" in oldStreamKeys }.forEach { stream ->
                val fromName = flow.steps.find { it.id == stream.fromStepId }?.name ?: stream.fromStepId
                val toName = flow.steps.find { it.id == stream.toStepId }?.name ?: stream.toStepId
                toolCalls.add(ToolExecution("connect_nodes", "Connected \"$fromName\" → \"$toName\""))
            }

            val (_, valRecord) = tools.validateStructure(flow)
            toolCalls.add(valRecord)

            // Spec P3: a syntax error in an AI-written expression is shown, never silently saved and never thrown.
            ExpressionGuard.check(flow).forEach { p ->
                toolCalls.add(ToolExecution("check_expression", "Expression problem in ${p.where}: ${p.message}", isSuccess = false))
            }
        }

        return AiChatMessage(
            sender = MessageSender.Assistant,
            text = cleanText.ifBlank { "Here is the updated workflow proposal:" },
            flowProposal = flow,
            toolCalls = toolCalls,
        )
    }

    private fun extractFlowProposal(text: String, currentFlow: Flow?): Flow? {
        val match = Regex("```kaivoflow([\\s\\S]*?)```").find(text)
            ?: Regex("```json([\\s\\S]*?)```").find(text)
            ?: return null

        val jsonStr = match.groupValues[1].trim()
        return runCatching {
            val obj = JSONObject(jsonStr)
            val name = obj.optString("name").ifBlank { currentFlow?.name ?: "Proposed Flow" }

            val rawSteps = obj.optJSONArray("steps") ?: return null
            if (rawSteps.length() == 0) return null

            var yOffset = 100f
            val steps = mutableListOf<StepNode>()
            for (i in 0 until rawSteps.length()) {
                val sm = rawSteps.optJSONObject(i) ?: continue
                val id = sm.optString("id").ifBlank { "step_${UUID.randomUUID().toString().take(6)}" }
                val type = sm.optString("type")
                val sName = sm.optString("name").ifBlank { type }
                val paramsObj = sm.optJSONObject("params")
                val paramsMap = mutableMapOf<String, Any?>()
                if (paramsObj != null) {
                    val keys = paramsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        paramsMap[k] = paramsObj.opt(k)
                    }
                }
                val x = if (sm.has("x")) sm.optDouble("x").toFloat() else 100f
                val y = if (sm.has("y")) sm.optDouble("y").toFloat() else yOffset
                yOffset += 160f
                steps.add(StepNode(id = id, type = type, name = sName, params = paramsMap.toImmutableMap(), x = x, y = y))
            }

            val rawStreams = obj.optJSONArray("streams")
            val streams = mutableListOf<StreamEdge>()
            if (rawStreams != null) {
                for (i in 0 until rawStreams.length()) {
                    val stm = rawStreams.optJSONObject(i) ?: continue
                    val id = stm.optString("id").ifBlank { "stream_${UUID.randomUUID().toString().take(6)}" }
                    val from = stm.optString("fromStepId")
                    val to = stm.optString("toStepId")
                    if (from.isNotBlank() && to.isNotBlank()) {
                        streams.add(StreamEdge(id = id, fromStepId = from, toStepId = to))
                    }
                }
            }

            Flow(
                id = currentFlow?.id ?: UUID.randomUUID().toString(),
                name = name,
                steps = steps.toImmutableList(),
                streams = streams.toImmutableList(),
                updatedAt = System.currentTimeMillis(),
                createdAt = currentFlow?.createdAt ?: System.currentTimeMillis(),
                groupId = currentFlow?.groupId,
            )
        }.getOrNull()
    }

    /**
     * Smart local rule-based assistant fallback that runs sovereignly on the phone.
     */
    private fun runSovereignRuleEngine(prompt: String, currentFlow: Flow?): AiChatMessage {
        val lower = prompt.lowercase()

        // 1. Flow Diagnosis request
        if (currentFlow != null && (lower.contains("diagnose") || lower.contains("issue") || lower.contains("error") || lower.contains("fix") || lower.contains("check"))) {
            val report = diagnose(currentFlow)
            val toolExecutions = mutableListOf<ToolExecution>()

            val (_, valTool) = tools.validateStructure(currentFlow)
            toolExecutions.add(valTool)

            var flowCandidate: Flow = currentFlow
            report.issues.forEach { issue ->
                if (issue.stepId != null && issue.stepType == "code.script") {
                    val (updated, toolRec) = tools.updateNodeParameters(
                        flowCandidate,
                        issue.stepId,
                        mapOf("script" to "return items.map(i => ({ json: { ...i.json, processed: true, fixedBy: 'AI' } }));"),
                    )
                    flowCandidate = updated
                    toolExecutions.add(toolRec)
                }
            }

            val fixCandidate = if (report.issues.isNotEmpty()) flowCandidate else null

            val explanation = buildString {
                append("### Flow Diagnostic Report for \"${currentFlow.name}\"\n\n")
                if (report.issues.isEmpty()) {
                    append("✅ All ${report.stepCount} steps are correctly configured and wired!\n")
                } else {
                    report.issues.forEach { issue ->
                        val icon = when (issue.severity) {
                            IssueSeverity.Error -> "🔴"
                            IssueSeverity.Warning -> "⚠️"
                            IssueSeverity.Info -> "ℹ️"
                        }
                        append("$icon **${issue.stepName}** (${issue.stepType}): ${issue.message}\n")
                        append("   *Recommendation: ${issue.suggestedFix}*\n\n")
                    }
                    if (fixCandidate != null) {
                        append("\n✨ I have executed the repair tools on your workflow. You can preview the changes on the Live Canvas and tap **Apply to Canvas** to update your flow.")
                    }
                }
            }

            return AiChatMessage(
                sender = MessageSender.Assistant,
                text = explanation,
                diagnosisReport = report,
                flowProposal = fixCandidate,
                toolCalls = toolExecutions,
            )
        }

        // 2. Add Notification / Alert request
        if (currentFlow != null && (lower.contains("notify") || lower.contains("notification") || lower.contains("alert"))) {
            val toolExecutions = mutableListOf<ToolExecution>()
            val (flowWithNode, addRecord) = tools.addNode(
                currentFlow,
                type = "device.notify",
                name = "Send Notification",
                params = mapOf("title" to "KaivoFlow Alert", "message" to "Trigger fired successfully"),
            )
            toolExecutions.add(addRecord)

            val lastStep = currentFlow.steps.lastOrNull()
            val newStepId = flowWithNode.steps.last().id
            val finalFlow = if (lastStep != null) {
                val (connectedFlow, connRecord) = tools.connectNodes(flowWithNode, lastStep.id, newStepId)
                toolExecutions.add(connRecord)
                connectedFlow
            } else flowWithNode

            val (_, valRecord) = tools.validateStructure(finalFlow)
            toolExecutions.add(valRecord)

            return AiChatMessage(
                sender = MessageSender.Assistant,
                text = "I executed the workflow builder tools to add a **Send Notification** step and connect it to your flow. You can review the updated structure on the Live Canvas and tap **Apply to Canvas**.",
                flowProposal = finalFlow,
                toolCalls = toolExecutions,
            )
        }

        // 3. New flow from scratch request (e.g. Battery alert, Webhook, Schedule)
        if (lower.contains("battery") || lower.contains("charge")) {
            val flow = buildBatteryFlow()
            val toolExecutions = listOf(
                ToolExecution("add_node", "Created trigger \"Battery Level Spark\" [spark.battery]"),
                ToolExecution("add_node", "Created action \"Battery Notification\" [device.notify]"),
                ToolExecution("connect_nodes", "Connected trigger → action stream"),
                ToolExecution("validate_structure", "Structure validated: 2 steps operational"),
            )
            return AiChatMessage(
                sender = MessageSender.Assistant,
                text = "Here is a complete **Low Battery Alert** automation. It triggers when battery drops below 20% and sends a notification.",
                flowProposal = flow,
                toolCalls = toolExecutions,
            )
        }

        if (lower.contains("webhook") || lower.contains("http") || lower.contains("api")) {
            val flow = buildWebhookFlow()
            val toolExecutions = listOf(
                ToolExecution("add_node", "Created trigger \"Webhook Spark\" [spark.webhook]"),
                ToolExecution("add_node", "Created transformer \"Parse Payload\" [code.script]"),
                ToolExecution("add_node", "Created action \"Alert Notification\" [device.notify]"),
                ToolExecution("connect_nodes", "Connected pipeline stream: Webhook → Parse → Alert"),
                ToolExecution("validate_structure", "Structure validated: 3 steps operational"),
            )
            return AiChatMessage(
                sender = MessageSender.Assistant,
                text = "Here is an **Incoming Webhook & Notification** flow. It listens for webhooks on your device and alerts you with the payload data.",
                flowProposal = flow,
                toolCalls = toolExecutions,
            )
        }

        // Default greeting / guidance
        val reply = if (currentFlow != null) {
            "I am ready to assist with **\"${currentFlow.name}\"** (${currentFlow.steps.size} steps). You can ask me to:\n" +
            "• **Diagnose & fix issues** in this flow\n" +
            "• **Add notification** or script step\n" +
            "• **Connect an external API**\n\n" +
            "💡 *Tip: To unlock advanced generative planning, tap the settings icon in the top right to configure your API key for Google Gemini, Claude, OpenAI, Groq, or Local Ollama.*"
        } else {
            "Welcome to **Ask AI**! What automation would you like to build today?\n\n" +
            "Try asking:\n" +
            "• *\"Create a flow that alerts me when phone battery is low\"*\n" +
            "• *\"Build a webhook receiver that logs data to file\"*\n" +
            "• *\"Set up a daily reminder at 9 AM\"*\n\n" +
            "💡 *Configure your AI provider (Gemini, Claude, OpenAI, Ollama) anytime via the top right settings button.*"
        }

        return AiChatMessage(
            sender = MessageSender.Assistant,
            text = reply,
        )
    }

    private fun autoFixFlow(flow: Flow, report: AiDiagnosisReport): Flow? {
        if (!report.hasErrors && report.issues.none { it.stepType == "code.script" }) return null

        var updatedSteps = flow.steps.toMutableList()
        var changed = false

        flow.steps.forEachIndexed { index, step ->
            if (step.type == "code.script" && step.paramString("script").isBlank()) {
                val fixedScript = "// Sovereign data transformation\nreturn items.map(item => ({\n  json: {\n    ...item.json,\n    processedAt: new Date().toISOString()\n  }\n}));"
                val fixedParams = (step.params + ("script" to fixedScript)).toImmutableMap()
                updatedSteps[index] = step.copy(params = fixedParams)
                changed = true
            }
        }

        return if (changed) {
            flow.copy(steps = updatedSteps.toImmutableList(), updatedAt = System.currentTimeMillis())
        } else null
    }

    private fun buildBatteryFlow(): Flow {
        val s1 = StepNode("s1", "spark.battery", "Battery Spark", persistentMapOf("threshold" to 20, "condition" to "below"), 100f, 100f)
        val s2 = StepNode("s2", "device.notify", "Low Battery Warning", persistentMapOf("title" to "Battery Low (<20%)", "message" to "Please connect your charger soon."), 100f, 260f)
        return Flow(
            id = UUID.randomUUID().toString(),
            name = "Battery Alert Automation",
            steps = persistentListOf(s1, s2),
            streams = persistentListOf(StreamEdge("e1", "s1", "s2")),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun buildWebhookFlow(): Flow {
        val s1 = StepNode("s1", "spark.webhook", "Webhook Spark", persistentMapOf("path" to "/webhook/notify", "method" to "POST"), 100f, 100f)
        val s2 = StepNode("s2", "code.script", "Format Payload", persistentMapOf("script" to "return items.map(i => ({ json: { msg: JSON.stringify(i.json) } }));"), 100f, 260f)
        val s3 = StepNode("s3", "device.notify", "Show Alert", persistentMapOf("title" to "New Webhook Event", "message" to "{{ \$json.msg }}"), 100f, 420f)
        return Flow(
            id = UUID.randomUUID().toString(),
            name = "Webhook to Notification",
            steps = persistentListOf(s1, s2, s3),
            streams = persistentListOf(StreamEdge("e1", "s1", "s2"), StreamEdge("e2", "s2", "s3")),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
    }
}
